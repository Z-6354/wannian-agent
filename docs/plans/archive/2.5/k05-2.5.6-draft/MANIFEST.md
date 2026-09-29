# 2.5.6 draft · MANIFEST

`status`: **已入仓 · 已验收** — 2026-09-28  
`spec`: ../k05-2.5.6-p2-spec.md  
`implementation`: ../k05-2.5.6-implementation.md  
`locked`: H1–H6=A  
`note`: 用户整号 P2「2.5.6全部通过」后连续实施入仓；验收由新开无上下文子代理对照 §9。

| 序 | 路径 | 修改方式 | 目的 | 对话审 |
|----|------|----------|------|--------|
| 1 | `api/.../TaskDeliveryId.java` | 增加 | 交付单 ID | 整号放行 · 已入仓 |
| 2 | `kernel/.../task/TaskDeliveryStatus.java` | 增加 | QUEUED/DELIVERED/CANCELLED | 整号放行 · 已入仓 |
| 3 | `kernel/.../task/TaskDeliveryPending.java` | 增加 | 快照 | 整号放行 · 已入仓 |
| 4 | `kernel/.../task/TaskDeliveryPendingRepository.java` | 增加 | Repo | 整号放行 · 已入仓 |
| 5 | `app/.../V026__task_delivery_pending.sql` | 增加 | 表 | 整号放行 · 已入仓 |
| 6 | `app/.../SqliteTaskDeliveryPendingRepository.java` | 增加 | Sqlite | 整号放行 · 已入仓 |
| 7 | `app/.../task/TaskDeliveryService.java` | 增加 | 入队+flush | 整号放行 · 已入仓 |
| 8 | `app/.../task/DefaultTaskRuntime.java` | 修改 | 终态钩 | 整号放行 · 已入仓 |
| 9 | `app/.../stream/DurableTurnScheduler.java` | 修改 | onCompleted flush | 整号放行 · 已入仓 |
| 10 | `app/.../task/TaskRuntimeConfig.java` | 修改 | Bean | 整号放行 · 已入仓 |
| 11 | `app/.../application.yml` | 修改 | preview-chars | 整号放行 · 已入仓 |
| 12 | `app/.../persistence/SqliteTurnQueue.java` | 修改 | conversationBusyForDelivery | 整号放行 · 已入仓 |
| 13 | `chat/state.js` + `render.js` + 相关 cache-bust | 修改 | 附带不并键；虚线 class | 整号放行 · 已入仓 |
| 14 | `wannian-ui/.../chat.css` | 修改 | 虚线顶部分隔 | 整号放行 · 已入仓 |

禁止：Idle 唤模；V024/V025；Executor/Gate 行为改口；测文件。
