# 0.2.4-D · REVIEW（代码已入仓）

`date`: 2026-09-24  
`status`: 网页会话系统代码入仓；无新测文件；未宣称 E；未改 Java。

## 已入仓文件清单

### 施工 / 文档
- `docs/plans/archive/0.2.4/k04-d-chat-ui-implementation.md`（status → 代码已入仓）
- `docs/plans/archive/0.2.4/k04-d-draft/REVIEW.md`（本文件）
- `docs/plans/README.md` / `docs/plans/roadmap.md`（状态行）

### 前端 `META-INF/resources/chat/`
- `api.js` — AbortSignal 透传；SSE options 形态；保留 C 的 async/stop/status
- `state.js` — selectionEpoch、committed 缓存、流式 reducer、expanded maps
- `history.js` — recent/bootstrap、分页历史、`refreshHistoryTail`
- `stream.js` — SSE 订阅/重连、epoch 门禁、终态 refetch
- `render.js` — 增量 transcript、双层 details、底部附近滚动、textContent
- `sidebar.js` — 列表/搜索/改名/归档/回收站/清空
- `app.js` — 薄入口：bootstrap、async send、挂接模块
- `index.html` — 会话侧栏、页眉标题、composer-toolbar / 生命周期控件

### CSS
- `wannian-ui/.../layouts/chat.css` — 双栏、侧栏列表、turn/tool details、窄屏抽屉
- `wannian-ui/.../layouts/app-shell.css` — `workspace.chat-workspace` 去 padding（最小）

## 行为摘要

1. 打开 `/chat/`：记住的会话或 `GET /recent` → 拉历史；零模型调用。
2. 侧栏 ACTIVE/归档/回收站 + 搜索；改名/归档/回收站/恢复/清空走 B PATCH/POST。
3. 发送走 `sendTurnAsync` + SSE；`selectionEpoch` 防串线；正式 Message 替换临时块。
4. 回合/工具可展开；上翻不强制滚底；`#composer-toolbar` 与 `.chat-actions[data-turn-id]` 预留给 E。

## 审阅修复（有条件通过 → 关闭必须项）

| # | 问题 | 处理 |
|---|------|------|
| 1 | `replayed` 仍 optimistic | `app.js`：`sent.replayed` 时不 append，只保 SSE |
| 2 | failed/cancelled 污染 committed | `state.js`：终态保持 `temporary+unfinished` 并 `refetch`；`history.js` prune 乐观块 |
| 3 | bootstrap 覆盖用户点选 | `app.js`：refreshList / open 后若 epoch 或 active 已变则跳过自动恢复 |

## 未做（明确 · 属 E/F）

- Stop / 撤队按钮与「已停止」「已排队」终态文案
- AI 自动标题消费 / `conversation.titleChanged`
- 新测试文件与真人验收

## 禁止项遵守

- 未改 `wn-server/**/*.java` / migration（D 范围）
- 页面无散落 `fetch`（仅 `api.js`）
- 未用 `innerHTML` 直插工具输出
