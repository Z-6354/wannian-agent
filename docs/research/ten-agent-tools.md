# 调研 · 十方 Agent 工具模块与去重目录

`status`: **reference** — 2026-09-22  
`purpose`: 对照 [agent-loop-survey](./agent-loop-survey.md) 十方的 **Tools 模块怎么挂**，以及 shipped / 内置工具的语义去重，供 **0.2.2 ToolRuntime + ToolProfile** 定边界。  
`not-authority`: 不改施工单；实施以日后 `k02-tools.md` 与清单为准。

---

## 0. 结论先讲（绑定，不是 Loop）

十方几乎都不是「工具挑 Agent」，也不是 Loop 运行时自由发现：

| 层 | 常见做法 |
|----|----------|
| **全局能力表** | Registry / Catalog / `registerTool` / 插件包：工具「存在」 |
| **Agent / Profile 可见集** | 每 Agent 的 `toolIds` / toolset / `tools=[]` / profile allow·deny：**多对多** |
| **本 Turn 再裁剪** | check_fn、凭据、沙箱、渠道、节点能力、tool_search 延迟加载 |

**多选多 = 全局 catalog × Agent/Profile 绑定表。**  
Loop 只消费「本 Turn 已注入」的 schemas，不负责注册。

对 wannian：`ToolCatalog`（全局）+ `ToolProfile` / 角色绑定（多对多）+ 可选节点能力求交 → `toolDescriptors`。

---

## 1. 十方 · 工具模块怎么实现

### L1 · HAN Agent（本机 `legacy/han-agent`）

| | |
|--|--|
| **注册** | `ToolRegistry`；`BuiltinToolsRegistrar` + `PlatformToolsBootstrap` 显式登记；MCP → `mcp__{server}__{tool}` |
| **绑定** | 每 Agent YAML **`tool-ids` 列表** → `schemasForIds` → `AgentRuntimeContext.allowedTools`（典型多对多） |
| **执行** | Loop → `ToolCallModule.invoke` → HITL 包装 → Registry handler |
| **证据** | `ToolRegistry` / `BuiltinTools.java` / `AgentManagerSettings` |

### L2 · OpenClaw

| | |
|--|--|
| **注册** | 内置 + `api.registerTool` 插件 + MCP + client-provided |
| **绑定** | **profile / allow·deny / 每 Agent 限制 / 渠道·沙箱·provider** 多层过滤后才进模型 |
| **执行** | 核内 `executeToolCalls`；大目录可走 Tool Search / Code Mode 桥，最终仍回原 pipeline |
| **证据** | [docs.openclaw.ai/tools](https://docs.openclaw.ai/tools) |

### L3 · Hermes

| | |
|--|--|
| **注册** | `tools/*.py` 自调用 `registry.register`；AST `discover_builtin_tools`；再 MCP / plugin |
| **绑定** | **toolset** enable/disable + 平台 preset（`hermes-cli` / telegram…）；`check_fn` 再裁 |
| **执行** | `handle_function_call` →（少数 agent 级工具截获）→ `registry.dispatch` |
| **证据** | [Tools Runtime](https://hermes-agent.nousresearch.com/docs/developer-guide/tools-runtime)、`toolsets.py` |

### L4 · DeepSeek Harness

| | |
|--|--|
| **注册** | Cordis 插件 `ctx.tools.register(defineTool(...))`；包 `packages/*/tool-*` |
| **绑定** | **Scope**：全局 vs per-agent scoped registration；`ToolRestriction` / visibility resolver；组合 profile 决定装哪些包 |
| **执行** | 守卫流水线：`pre-execute` → `execute` → `post-execute`；并行 / exclusive；PTC `run_code` 可嵌套回灌 |
| **证据** | [tool-catalog.md](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/tool-catalog.md)、[Tools 子系统](https://deepseek-harness.github.io/deepseek-harness/en/reference/subsystems/tools) |

### L5 · CowAgent

| | |
|--|--|
| **注册** | `ToolManager.load_tools` 扫内置类；MCP 热同步进 Agent |
| **绑定** | Agent 实例持有 `tools` dict/list；默认可「全内置」；Evolution 等路径再 allowlist |
| **执行** | `_execute_tool` 查表执行；可并行 |
| **证据** | [docs.cowagent.ai/tools](https://docs.cowagent.ai/tools)、`tool_manager.py` |

### G1 · OpenAI Agents SDK

| | |
|--|--|
| **注册** | 构造 `Agent(tools=[...])`：FunctionTool / Hosted / Computer / Shell / MCP |
| **绑定** | **每 Agent 一份 tools 列表**；Handoff 换 Agent = 换整包 tools（仍多对多配置） |
| **执行** | Runner turn 内执行后 `RunAgain` |
| **证据** | [Tools 文档](https://openai.github.io/openai-agents-python/tools/) |

### G2 · LangGraph ReAct

| | |
|--|--|
| **注册** | 无产品级内置目录；调用方传入 `tools` |
| **绑定** | 图状态 / ToolNode 绑定该图的工具列表 |
| **执行** | `ToolNode`（可 Send 并行） |
| **证据** | `chat_agent_executor` / ToolNode 文档 |

### G3 · smolagents

| | |
|--|--|
| **注册** | `Tool` 子类实例；`add_base_tools` 加默认箱；或 CodeAgent 用代码当动作 |
| **绑定** | 构造 `tools=[...]` 显式列表 |
| **执行** | JSON tool call 或 PythonExecutor |
| **证据** | [default_tools](https://huggingface.co/docs/smolagents/en/reference/default_tools) |

### G4 · Anthropic tool-use

| | |
|--|--|
| **注册** | 请求 `tools` 数组：用户自定义 + Anthropic-defined client/server tools |
| **绑定** | **每次 API 请求**声明可见集（应用层多对多） |
| **执行** | client：应用执行；server：Anthropic 执行；`stop_reason == tool_use` 循环在应用 |
| **证据** | [Tool reference](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-reference.md) |

### G5 · OpenHands SDK

| | |
|--|--|
| **注册** | `register_tool(name, factory)`；内置包 `openhands.tools.*` |
| **绑定** | `Agent(tools=[Tool(name=...)])` 按名引用 |
| **执行** | Workspace / executor；确认门闩在 Policy |
| **证据** | [Hello World](https://docs.openhands.dev/sdk/guides/hello-world)、`openhands-tools` |

---

## 2. 绑定模式对照（回答「多选多」）

| Agent | 全局能力 | Agent/角色可见 | 设备/环境再裁 |
|-------|----------|----------------|---------------|
| L1 | Registry | 每 Agent `toolIds` | MCP 会话合并 |
| L2 | 内置+插件+MCP | profile + allow/deny + per-agent | 沙箱/渠道/provider |
| L3 | Registry | toolset / 平台 preset | `check_fn`、后端 |
| L4 | Cordis 工具包 | Scope + 组合装包 | shell/fs 后端 |
| L5 | ToolManager | Agent.tools | MCP 热同步 / retrieval |
| G1 | SDK 类型 | Agent.tools | hosted vs local |
| G2 | 调用方 | 图绑定列表 | — |
| G3 | Tool 类 | 构造列表 | executor 环境 |
| G4 | API tools[] | 每请求 | server vs client |
| G5 | register_tool | Agent.tools 按名 | workspace |

**共性：** Catalog 全局注册一次；**多个 Agent/Profile 各选子集**；同一工具可进多个子集。

---

## 3. 语义去重汇总（跨十方）

名字不同、能力同类的合并为一条。MCP / 用户自定义无限集不枚举。

### 3.1 核心编码 / 工作区（几乎处处有）

| 语义能力 | 常见名字（去重代表） |
|----------|----------------------|
| Shell / 终端 | `bash`, `pwsh`, `exec`, `terminal`, `TerminalTool`, `ShellTool`, `process` / `process_manage` |
| 读文件 | `read`, `read_file`, `read_files`, `view` |
| 写文件 | `write`, `write_file` |
| 编辑 / 补丁 | `edit`, `str_replace`, `str_replace_editor`, `patch`, `apply_patch`, `ApplyPatchTool` |
| 列目录 | `ls`, `list_dir` |
| 搜索文件名 | `glob`, `search_files`(files) |
| 搜内容 | `grep`, `grep_code`, `search_files`(content) |
| 代码执行 / 解释器 | `execute_code`, `run_code`, `code_execution`, `PythonInterpreterTool`, `CodeInterpreterTool` |
| 持久终端 / PTY | `terminal_*`, persistent `bash`/`pwsh` |

### 3.2 Web / 浏览器 / 桌面

| 语义能力 | 常见名字 |
|----------|----------|
| 网页搜索 | `web_search`, `WebSearchTool`, DuckDuckGo/Google/Brave/Wikipedia 变体, `x_search` |
| 抓取页面 | `web_fetch`, `web_extract`, `VisitWebpageTool` |
| 浏览器自动化 | `browser` / `BrowserToolSet` / `browser_*` 成员 / Anthropic `browser_toolset` |
| 电脑键鼠 | `computer` / `ComputerTool` / `computer_use` / Anthropic computer toolset 成员 |
| 识图 / 视觉 | `view_image`, `vision`, `vision_analyze`, `read_image` |

### 3.3 人机 / 会话 / 编排

| 语义能力 | 常见名字 |
|----------|----------|
| 问用户 | `ask_user`, `ask_user_question`, `clarify`, `UserInputTool` |
| 待办 | `todo` / `todo_write` / `todo_list` / `TaskTrackerTool` |
| 记忆读写 | `memory`, `memory_search`, `memory_get`, `save_memory` |
| 会话检索 | `session_search`, `session_*`, `session_event_*` |
| 子代理 / 委派 | `subagent`, `delegate_task`, `agent_delegate`, agents-as-tools, `ralph` |
| 调度 / cron | `cron`, `scheduler`, `schedule_*`, `cronjob_manage`, reminders |
| 目标 / 进度 | `create_goal` / `get_goal` / `update_goal`, `progress_card` |
| 后台任务 | `job_*`, `agents_wait`, heartbeat |
| 技能包 | `skill` / `load_skill` / `skills_list` / `skill_view` |
| 结束回合 | `FinalAnswerTool`（smolagents） |
| 退出计划模式 | `exit_plan_mode`（DSH） |

### 3.4 检索 / 知识 / 媒体 / 通道

| 语义能力 | 常见名字 |
|----------|----------|
| 向量 / 文件库检索 | `FileSearchTool`, `rag_search` |
| 图片/音视频生成 | `image_generate`, `music_generate`, `video_generate`, `tts`, `text_to_speech` |
| 消息通道 | `message`, `send`, IM 专用工具（discord / feishu / …） |
| 密钥 / 配置 | `secrets`, `env_config` |
| 网关 / 节点 | `gateway`, `nodes`（OpenClaw） |
| 插件管理 | `plugins` |
| LSP | `lsp` |
| 工作流 | `workflow` |
| 团队协作 | DSH agent-team / OpenClaw swarm 族 |

### 3.5 元工具（大目录时）

| 语义能力 | 常见名字 |
|----------|----------|
| 工具搜索 | `tool_search`, `tool_describe`, `tool_call`, `tool_search_code`, Anthropic/OpenAI Tool Search |
| 程序化调工具 | PTC / `run_code` / `ProgrammaticToolCallingTool` / Code Mode |
| MCP 网关 | `list_mcp` / `call_mcp` / `HostedMCPTool` / 动态 `mcp__…` |

### 3.6 明确「无固定产品目录」

| Agent | 说明 |
|-------|------|
| **G2 LangGraph** | 框架不 ship 业务工具，只有 ToolNode |
| **G4** | 用户工具任意；官方另给 server/client 标准工具集 |
| **MCP 扩展** | L1/L2/L3/L5/G1 均可无限扩展，不进去重表 |

---

## 4. 分方 shipped 工具名（便于核对；未再展开参数）

### L1 HAN（`BuiltinTools` + 平台）

`get_time`, `whoami`, `get_version`, `list_users`, `load_skill`, `memory_search`, `save_memory`, `read_file`, `read_files`, `list_dir`, `grep_code`, `str_replace`, `write_file`, `web_search`, `web_fetch`, `get_weather`, `rag_search`, `hf_model_search`, `github_repo_search`, `list_mcp`, `open_mcp_category`, `list_mcp_names`, `call_mcp`, reminders / session_op / model_* / `subagent_invoke` / notify_* …

### L2 OpenClaw（代表类别）

`exec`, `process`, `terminal`, `code_execution`, `read`, `write`, `edit`, `apply_patch`, `ask_user`, `secrets`, `web_search`, `x_search`, `web_fetch`, `browser`, `screen`, `theme`, `progress_card`, `message`, `sessions_*`, `agents_*`, `subagents`, `cron`, `heartbeat_respond`, `gateway`, `nodes`, `plugins`, `view_image`, `image_generate`, `music_generate`, `video_generate`, `tts`, `tool_search*`, `wait`, …

### L3 Hermes（核心 + 扩展族）

核心常用：`web_search`, `web_extract`, `x_search`, `terminal`, `process_manage`, `read_file`, `write_file`, `patch`, `search_files`, `browser_*`, `execute_code`, `todo_list`, `memory`, `session_search`, `delegate_task`, `cronjob_manage`, `clarify`, `vision_analyze`, `image_generate`, …  
另有 HA / kanban / desktop / discord / feishu / yuanbao / spotify 等平台族。

### L4 DeepSeek Harness（生成 catalog 默认包）

`ask_user_question`, `run_code`, `exit_plan_mode`, `bash`, `pwsh`, `present`, `str_replace_editor`, `edit`, `read`, `read_image`, `write`, `glob`, `grep`, `terminal_*`, `create_goal`/`get_goal`/`update_goal`, `schedule_*`, `lsp`, `ralph`, `skill`, `session_*`, `subagent`, `list_subagent_models`, `interrupt_agent`/`list_agents`/`send_message`, `job_*`, `todo_write`, `workflow`, `web_fetch`, `web_search`, （opt-in）`cordis_*`, agent-team 族…

### L5 CowAgent

`read`, `write`, `edit`, `bash`, `ls`, `send`, `search_files`, `memory_search`, `memory_get`, `evolution_undo`, `subagent`, `agent_delegate`, `env_config`, `scheduler`, `web_search`, `web_fetch`, `vision`, `browser` + MCP 动态名。

### G1 OpenAI Agents SDK（类型，非业务名）

Hosted：`WebSearchTool`, `FileSearchTool`, `CodeInterpreterTool`, `ImageGenerationTool`, `HostedMCPTool`, `ToolSearchTool`, `ProgrammaticToolCallingTool`；本地/双模：`ComputerTool`, `ShellTool`, `ApplyPatchTool`, `FunctionTool`, agents-as-tools。

### G2 LangGraph

无固定 shipped 业务工具。

### G3 smolagents

`ApiWebSearchTool`, `DuckDuckGoSearchTool`, `GoogleSearchTool`, `WebSearchTool`, `WikipediaSearchTool`, `VisitWebpageTool`, `PythonInterpreterTool`, `UserInputTool`, `SpeechToTextTool`, `FinalAnswerTool`（+ CodeAgent 原生代码动作）。

### G4 Anthropic（官方）

Server：`web_search`, `web_fetch`, `code_execution`（含 bash/text_editor 子能力）, `tool_search`；Client toolsets：`computer_toolset`, `browser_toolset`；经典 client：`bash`, `text_editor`；用户自定义任意。

### G5 OpenHands

`TerminalTool`（原 Bash）, `FileEditorTool`, `TaskTrackerTool`, `GlobTool`, `GrepTool`, `BrowserToolSet`, `apply_patch`, `delegate`, …（见 `openhands-tools` 包目录）。

---

## 5. 对 wannian 0.2.2 的直接启示

1. **绑定用多对多表**，不要做成工具反选 Agent；对齐 L1 `toolIds` / L3 toolset / L2 profile。  
2. **Runtime 深模块**对齐 L4/G4：校验→策略→执行→消毒；Loop 只 `execute`。  
3. **首批工具**取语义交集的安全子集：`current_time` / `calculate` / 可选 `http_read`（≈ web_fetch 只读），**不要**首版上 shell/browser/computer。  
4. **Profile** 预留 `chat.default` / 日后 `chat.mcp` / `schedule`；MCP 名空间可后加。  
5. 大目录时的 tool_search / PTC 属增强，**不是** 0.2.2 必需。

---

## 6. 证据索引

| 方 | 一手入口 |
|----|----------|
| L1 | `legacy/han-agent/tool-call/.../BuiltinTools.java`, `ToolRegistry.java` |
| L2 | https://docs.openclaw.ai/tools |
| L3 | https://hermes-agent.nousresearch.com/docs/developer-guide/tools-runtime · `toolsets.py` |
| L4 | https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/tool-catalog.md |
| L5 | https://docs.cowagent.ai/tools · `agent/tools/` |
| G1 | https://openai.github.io/openai-agents-python/tools/ |
| G2 | LangGraph ToolNode / prebuilt ReAct |
| G3 | https://huggingface.co/docs/smolagents/en/reference/default_tools |
| G4 | https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-reference.md |
| G5 | https://docs.openhands.dev/sdk/guides/hello-world · `openhands-tools` |
