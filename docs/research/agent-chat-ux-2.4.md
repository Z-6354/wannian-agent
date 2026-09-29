# 2.4 Agent 聊天交互对标

研究日期：2026-09-24。以下区分源代码可验证的行为与产品建议；本地路径均指当前机器上的只读对标项目。

## 流式消息与工具详情

- DeepSeek Harness 把一个回合的过程做成可展开节点，展开状态由 `aria-expanded` 表达；等待、工具执行和流式阶段共享持续的活动标签，避免状态文案闪烁。来源：[TurnProcessNodeView.tsx](D:/0HAN/Work/deepseek-harness/packages/client/ui-chat/src/client/chat/TurnProcessNodeView.tsx#L50)、[ChatView.tsx](D:/0HAN/Work/deepseek-harness/packages/client/ui-chat/src/client/chat/ChatView.tsx#L167)。
- DeepSeek Harness 的工具调用和上下文注入共用折叠外观；摘要和正文分开呈现。流式内容会改变行高，因此视图专门处理自动跟随和滚动位置。来源：[ContextInjectionRow.tsx](D:/0HAN/Work/deepseek-harness/packages/client/ui-chat/src/client/chat/ContextInjectionRow.tsx#L21)、[ChatView.tsx](D:/0HAN/Work/deepseek-harness/packages/client/ui-chat/src/client/chat/ChatView.tsx#L632)。
- OpenClaw 工具卡片把折叠摘要、参数预览、详细正文分层；逐张卡片有手动展开状态和 `aria-expanded`。来源：[chat-tool-cards.ts](D:/0HAN/openclaw/ui/src/pages/chat/components/chat-tool-cards.ts#L242)、[同文件](D:/0HAN/openclaw/ui/src/pages/chat/components/chat-tool-cards.ts#L442)。
- OpenClaw 对文本增量按动画帧请求刷新，终态立即刷新；终态缺消息时回拉历史。来源：[chat-state-events.ts](D:/0HAN/openclaw/ui/src/pages/chat/chat-state-events.ts#L566)、[同文件](D:/0HAN/openclaw/ui/src/pages/chat/chat-state-events.ts#L606)。
- Codex 开源 app-server 明确把 MCP 工具 UI 描述放入工具调用事件和保存的历史，历史回放不必等待工具目录。来源：[Codex app-server README](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md#MCP-App-UI)。

**对 2.4 的建议：** 后端输出有序、可重放的结构化事件，包括正文增量、工具开始、参数/安全摘要、结果增量或阶段、成功/失败、回合终态。前端以回合为外层折叠，以工具调用为内层折叠；两层都在流式过程中可打开，展开状态不要随增量重置。保存足以重建详情的安全元数据，且断线补发和历史回读走同一个投影逻辑。大结果需要截断或按需读取，不能直接把原始密钥、内部提示词和任意工具输出全部推给浏览器。

## 会话恢复、列表与标题

- DeepSeek Harness 的工作区列表以会话摘要为行，打开行会按稳定 `sessionId` 选中会话；新会话流程另有入口。列表支持折叠、展开、搜索。来源：[navigation.ts](D:/0HAN/Work/deepseek-harness/packages/client/ui-workspace/src/client/navigation.ts#L139)、[WorkspaceBrowser.tsx](D:/0HAN/Work/deepseek-harness/packages/client/ui-workspace/src/client/rows/WorkspaceBrowser.tsx#L44)、[同文件](D:/0HAN/Work/deepseek-harness/packages/client/ui-workspace/src/client/rows/WorkspaceBrowser.tsx#L1046)。
- DeepSeek Harness 已有“首次提示词”自动命名策略：仅新建、非 fork、无已有标题的会话触发一次辅助模型调用；失败保留回退标题，手工重命名后不再自动覆盖。来源：[first-prompt README](D:/0HAN/Work/deepseek-harness/packages/session/session-title-first-prompt-llm/README.md#L30)、[session-title types](D:/0HAN/Work/deepseek-harness/packages/session/session-title/src/types.ts#L36)。生成策略限制输入字节、输出 token 和超时，并拒绝空白或非文本结果。来源：[session-title-llm README](D:/0HAN/Work/deepseek-harness/packages/session/session-title-llm/README.md#L12)。
- Cursor 官方文档确认历史面板可打开完整旧对话、重命名和删除；本地对话历史存 SQLite。来源：[Cursor History](https://docs.cursor.com/en/agent/chat/history)。
- OpenClaw 侧栏拥有独立会话列表投影与刷新；聊天历史加载具有请求版本和会话归属判定，避免切换会话后旧请求污染新视图。来源：[app-sidebar-session-list-render.ts](D:/0HAN/openclaw/ui/src/components/app-sidebar-session-list-render.ts#L18)、[chat-history-state.ts](D:/0HAN/openclaw/ui/src/pages/chat/chat-history-state.ts#L96)。

**对 2.4 的建议：** 网页入口优先取当前浏览器记住的最近会话 ID，验证它仍可访问后加载其历史；失效时从服务器会话列表选择最近一次有效会话。侧栏按最近活动排序，并使选中行与 URL/浏览器状态同步。第一次用户消息提交后异步生成短标题，先显示临时标题；标题失败不影响聊天。标题结果只覆盖仍为自动标题的同一会话。历史恢复是读取过程，不重发用户消息，也不重新调用模型或工具。

## 范围与风险

1. “所有内容流式输出”需要产品边界：用户可见的正文、允许展示的过程说明、工具摘要和安全详情可流式呈现；模型内部推理、密钥和未经筛选的原始工具结果不可直接透出。
2. 重连补发须以持久事件序号去重，尤其是工具调用开始、结束与终态。仅追加前端字符串会在重连时重复显示。
3. 自动标题与首轮主调用并行；生成慢、失败或晚到时不能阻塞回复，也不能覆盖用户手工改名。
4. 上述来源只说明可借鉴机制，不代表 Codex、Cursor 和 DeepSeek Harness 有完全一致的 UI。应在本产品定义统一的事件语义，再按现有页面组件实现。
