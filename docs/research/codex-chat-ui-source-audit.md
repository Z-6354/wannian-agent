# Codex 对话界面源码审查与 Web 转译

审查日期：2026-09-24。目标是为万年 Agent 对话页的细节和动态状态提供可追溯的参照，而不是照搬第三方界面。

## 源码边界

- 官方 [openai/codex](https://github.com/openai/codex) 公开 Rust TUI 和 app-server 协议代码。[维护者回复](https://github.com/openai/codex/discussions/16538)明确说明 Codex Desktop 前端未开源。
- 已安装 Codex Desktop 包含编译后的 CSS/JavaScript，可以核对实际尺寸、状态选择器和交互，但不是可读的原始组件源码。
- [friuns2/codex-mobile](https://github.com/friuns2/codex-mobile) 是社区 Vue Web UI，[dfones288/codex-desktop](https://github.com/dfones288/codex-desktop) 是社区 Electron/React 客户端。[pavel-voronin/codex-web-local](https://github.com/pavel-voronin/codex-web-local) 为已归档的早期项目。它们都不是 OpenAI 官方桌面前端。

## 官方 Rust TUI 的可转译规则

| 源码 | 原规则 | 本项目 Web 转译 |
| --- | --- | --- |
| [styles.md](https://github.com/openai/codex/blob/main/codex-rs/tui/styles.md)、[style.rs](https://github.com/openai/codex/blob/main/codex-rs/tui/src/style.rs) | 主文本保持清晰；次级文本按背景计算可读对比度；选中项统一强调色；成功、注意、失败分开 | 使用主题 token 控制正文、次级文案、激活状态和语义色；过程、工具、取消、失败不可只靠透明度或动画区分 |
| [history_cell/mod.rs](https://github.com/openai/codex/blob/main/codex-rs/tui/src/history_cell/mod.rs) | 对话单元区分简版与完整记录；活动内容更新时重新计算布局 | 流式文本、过程摘要、工具详情保持独立节点；新内容和展开后检查换行、滚动与焦点 |
| [history_cell/dynamic.rs](https://github.com/openai/codex/blob/main/codex-rs/tui/src/history_cell/dynamic.rs)、[mcp.rs](https://github.com/openai/codex/blob/main/codex-rs/tui/src/history_cell/mcp.rs) | 调用中、完成、失败、中断各有状态；摘要只预览少量内容，完整信息在详情中 | 工具卡显示明确状态及可展开箭头；长参数和输出限制卡内高度并允许滚动；加载动画尊重减少动态效果设置 |
| [exec_cell/render.rs](https://github.com/openai/codex/blob/main/codex-rs/tui/src/exec_cell/render.rs) | 工具输出预览限制长度，保留被省略的数量，失败汇总贴近组标题 | 可以增加短预览和省略计数；当前实现先保留完整内容于可滚动详情，避免截断丢失信息 |
| [bottom_pane/chat_composer.rs](https://github.com/openai/codex/blob/main/codex-rs/tui/src/bottom_pane/chat_composer.rs) | 输入、排队、停止、弹层和恢复草稿有明确状态转移 | Web 输入框的提交、排队、停止和会话切换须逐状态验证；终端格数不直接换算为 CSS 像素 |

## 社区 Web 源码中的可核对机制

- [codex-mobile 的对话组件](https://github.com/friuns2/codex-mobile/blob/main/src/components/content/ThreadConversation.vue)：流式内容与历史消息分层；按会话保存滚动状态；接近底部时自动跟随新内容；工具活动能展开，错误另有状态。
- [codex-mobile 的输入组件](https://github.com/friuns2/codex-mobile/blob/main/src/components/content/ThreadComposer.vue) 与 [侧栏组件](https://github.com/friuns2/codex-mobile/blob/main/src/components/sidebar/SidebarThreadTree.vue)：输入框按内容增长，发送/停止有独立状态；会话标题单行省略，菜单打开时调整层级，靠近视窗底部时菜单向上显示。
- [codex-desktop 的样式](https://github.com/dfones288/codex-desktop/blob/main/src/renderer/styles.css) 与 [React 主界面](https://github.com/dfones288/codex-desktop/blob/main/src/renderer/main.tsx)：用户消息约束最大宽度、助手正文不强制气泡；工具详情有独立滚动区域。该样式文件后半有多轮覆盖规则，具体尺寸需要看最终级联结果，不能把单条声明当成稳定设计规格。

## 本项目逐状态验收矩阵

| 场景 | 关键检查 |
| --- | --- |
| 空白、新建、打开会话 | 侧栏选中与主面板标题同步；焦点与草稿正确；窄屏抽屉不遮挡正文 |
| 搜索、进行中、归档、回收站 | 快速输入和切换时旧请求不覆写新列表；空态、长标题、菜单与滚动正常 |
| 提交、排队、停止 | 按钮可用性、提示、取消结果和重新提交正确；移动触控区足够大 |
| 流式、断线、重连、切换会话 | 增量文本不重复或丢失；重连提示及时清除；旧会话事件不进入新会话；重开会话能恢复活动态 |
| 工具调用 | 运行、成功、失败、拒绝、中断均有文案/颜色；摘要和详情可键盘展开；长输出可滚动 |
| 历史回放 | 已完成正文不被陈旧的运行事件或短预览覆盖；展开状态与 `aria-expanded` 一致 |
| 主题、缩放、减少动态效果 | 文本对比度、溢出、布局和可见焦点稳定；状态信息不依赖动画 |

这些条目需要以运行中的浏览器和 API/回放测试核对；静态源码检查本身不能证明动态行为正确。

## 2026-09-24 验证记录

- 新增的三个 Maven 聚焦测试通过：会话详情/最近会话游标、交错 Outbox 序号隔离、SSE 最近事件终态过滤。两个 Node 状态回放用例通过。随后离线 `-pl app -am -DskipTests package` 成功。
- 隔离 fake-model 服务中，1440×900、768×1024、375×812 三档无页面横向溢出；发起新会话、完成回复、刷新重开后，完整正文只出现一次，没有残留“生成中”。
- 浏览器操作归档、取消归档、移入回收站、恢复均成功。合成工具事件验证 RUNNING、SUCCEEDED、FAILED、CANCELLED、REJECTED 的 DOM 状态与语义色；过程/工具详情展开后 `aria-expanded` 同步，长输出在 260px 卡内滚动，空操作区不占间距。
- 合成工具事件只验证前端呈现；真实模型工具调用整条链路、网络断开/重连与多回合排队恢复仍需专项联调。不能将 fake-model 结果表述为真实模型链路验收。
- 追加浏览器拦截测试：搜索“甲”请求延迟、搜索“乙”先返回，再放行旧请求，侧栏只显示“乙”；归档列表请求延迟、切换回收站并先返回，再放行归档请求，侧栏只显示回收站且 tab 保持选中。网络断开/重连与多回合排队恢复仍未做端到端联调。
