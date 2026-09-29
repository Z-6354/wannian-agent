# 2.5.7 draft · MANIFEST

`status`: **已入仓 · 代审完成·书面降级放行** — 2026-09-28  
`spec`: ../k05-2.5.7-p2-spec.md  
`implementation`: ../k05-2.5.7-implementation.md  
`locked`: D1–D6=A  
`compile`: `mvn -pl app -am compile` SUCCESS

| 序 | 路径 | 修改方式 | 目的 | 对话审 |
|----|------|----------|------|--------|
| 1 | `api/.../IdleDeliveryId.java` | 增加 | Idle 交付单 ID | 整号放行 · 已入仓 |
| 2 | `kernel/.../IdleDeliveryStatus.java` | 增加 | 状态枚举 | 整号放行 · 已入仓 |
| 3 | `kernel/.../IdleDeliveryPending.java` | 增加 | 快照 | 整号放行 · 已入仓 |
| 4 | `kernel/.../IdleDeliveryPendingRepository.java` | 增加 | Repo | 整号放行 · 已入仓 |
| 5 | `app/.../V027__idle_delivery_pending.sql` | 增加 | 表 | 整号放行 · 已入仓 |
| 6 | `app/.../SqliteIdleDeliveryPendingRepository.java` | 增加 | Sqlite | 整号放行 · 已入仓 |
| 7 | `app/.../task/TaskDeliveryService.java` | 修改 | Idle 入队 | 整号放行 · 已入仓 |
| 8 | `app/.../task/IdleDeliveryWorker.java` | 增加 | receive+wake | 整号放行 · 已入仓 |
| 9 | `kernel/.../turn/TurnEngine.java` | 修改 | idle-task→SYSTEM | 整号放行 · 已入仓 |
| 10 | `app/.../stream/DurableTurnScheduler.java` | 修改 | 汇报指令+交付 CAS | 整号放行 · 已入仓 |
| 11 | `app/.../task/TaskRuntimeConfig.java` | 修改 | Bean | 整号放行 · 已入仓 |
| 12 | `app/.../application.yml` | 修改 | idle tick | 整号放行 · 已入仓 |
| 13 | `chat/state.js` + `render.js` | 修改 | 隐藏合成 USER | 整号放行 · 已入仓 |

禁止：Busy flush 改口；V024–V026；Executor/Gate；测文件。
