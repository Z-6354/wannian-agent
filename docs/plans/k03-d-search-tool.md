# 0.2.3-D 增补 · 注入预算策略 + search_memory

`status`: **已交付**（2026-09-24）；验收见 [实施清单 · 0.2.3](../guide/01-checklist.md)。  
`parent`: [k03-d-recall-tombstone-http.md](./k03-d-recall-tombstone-http.md)  
`research`: [memory-inject-budget-peers.md](../research/memory-inject-budget-peers.md)  
`workflow`: [version-stage-workflow.md](./version-stage-workflow.md)

---

## 1. 判定（相对他者手法）

| 候选 | 结论 |
|------|------|
| 纯 A 静默截断 | 保留为**底**，单独不够 |
| 纯 B 向量检索 | **否**（R1 禁向量） |
| 纯 D 身份强制破预算 | 可选增强；本仓 importance 已垫底身份，**不优先** |
| 纯 E continue | **要做**（小改） |
| 纯 G Letta core 块 | 更后 |
| **Open WebUI 式：推注入 + 拉搜索** | **最适** |

**选定包：A + E + 按需 `search_memory`（无向量）。**

理由：

1. 本仓已是「每轮 push Top-N + 字符顶」——与 WebUI 注入预算同构。  
2. R1 无向量 → 不能抄 Mem0/CrewAI 语义检索；用 **claim/subjectKey 子串匹配 + score 排序** 即可补「预算外仍可拉」。  
3. 陪伴场景：模型发现缺事实时主动搜，比盲目加大 TOP_N / CHAR_BUDGET 更省窗、更可控。  
4. E（超长条 skip 继续）避免一条长 claim 堵死后续短高分条——与 OpenClaw 非身份装填一致。  
5. 身份强制带（D）暂不做：高 importance 已在 score 中；再破预算易挤对话。

---

## 2. 行为写死

### 2.1 Assembler（改草稿 `09`）

- 仍：`recallTop(TOP_N)` → 按序拼行。  
- **改**：`out.length()+line.length() > CHAR_BUDGET` 时对该条 **`continue`**（跳过），不 `break`。  
- 仍：只 touch 实际拼入的 id。

### 2.2 工具 `search_memory`

| 项 | 决议 |
|----|------|
| 正式名 | `search_memory` |
| 可见 | 烟火默认 **locked**（与 `remember_fact` 同档） |
| 参数 | `query`（必填，非空）；`limit`（可选；默认/上限来自 `wannian.memory.search.*` → `MemorySearchLimits`） |
| 范围 | 仅 `CompanionIdentity.YANHUO` + `ACTIVE` |
| 匹配 | **无向量**：`claim` 或 `subjectKey` 含 query（大小写不敏感；空白 query → `TOOL_INVALID_ARGUMENTS`） |
| 排序 | 匹配集上 `MemoryDecay.score` desc，同分 `createdAt` desc |
| 返回 | JSON：`matches` 数组，每项 `id` / `claim` / `subjectKey` / `importance` / `score`（格式化小数）；可空数组 |
| 副作用 | **不写库**；可选 best-effort `touchRecalled` 命中 id（与 Assembler 同策略：失败只日志）— **本批选：touch** |
| 接线 | app 组合根创建 `SearchMemoryToolAdapter` 并注入 `MemoryStore` / `Clock` / 可选 `MemoryRecallTouch` / `MemorySearchLimits`；`ToolExecutionContext`、`ToolAdapterRequest` 与 `DefaultAgentLoop` 不携带这些专用 ports；缺接线 → `TOOL_UNAVAILABLE` |
| 禁止 | 假成功；直写 JDBC；向量 embed；扩大 limit 超 20 |

### 2.3 Store

- `MemoryStore` **可不增方法**：Adapter 内 `listActive` + 内存过滤即可（伴身 ACTIVE 量级本批可接受）。  
- 若后续变慢再加 `searchActive(companion, query, limit)` SQL `LIKE`——**本批不做**。

---

## 3. 文件清单（确认后插入 D 审序，接在 14 后）

| 审序 | 动作 | 路径 |
|------|------|------|
| 15 | NEW | `MemorySearchLimits` 类型与 application.yml 限额 |
| 16 | NEW | `SearchMemoryToolAdapter.java` |
| 17 | MOD | `ToolAdapterRequest` + `ToolExecutionContext`（只保留通用字段；search ports 在 adapter 构造时注入） |
| 18a | MOD | `BuiltinToolNames` + `SEARCH_MEMORY` |
| 18b | MOD | `BuiltinToolPool` 注册 / 参数 Schema |
| 18c | MOD | ToolCallValidator（query / limit） |
| 18d | MOD | ToolUsePolicy 锁定策略 + `YanhuoToolBindings` |
| 19a | MOD | `DefaultToolRuntime` 按通用执行上下文构造 Adapter 请求并校验参数 |
| 19b | MOD | `DefaultAgentLoop` 只依赖 ToolRuntime；不传 Store / Clock / touch / 限额 |
| 19c | MOD | app 组合根装配 search adapter，并通过 ToolSettings 注册；Recall limits bean 仍由 TurnEngineConfig 提供 |
| 20 | MOD | `ToolSettings` byName locked 三态与管理页锁定态 |
| 21 | DOC | 管理页工具说明与唯一真源约定 |

测文件仍归版本末段。

逐文件审阅与阶段代码审记录见 [实施清单 · 0.2.3](../guide/01-checklist.md)。

---

## 4. 明确不做（本增补）

- 加大 TOP_N/CHAR_BUDGET 替代搜索  
- 身份豁免破预算  
- 向量 / embedding  
- 分桶双 2000（WebUI user/context）— 可更后  
- HTTP 搜索 API（有 tool + GET list 即可）
