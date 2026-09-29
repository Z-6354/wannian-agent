# 2.5.8 draft · MANIFEST

`status`: **已入仓 · 代审完成·书面降级放行** — 2026-09-28  
`compile`: `mvn -pl app -am compile` SUCCESS  
`spec`: ../k05-2.5.8-p2-spec.md  
`implementation`: ../k05-2.5.8-implementation.md  
`locked`: S1–S6=A  

| 序 | 路径 | 修改方式 | 目的 | 对话审 |
|----|------|----------|------|--------|
| 1 | `kernel/.../BackgroundTaskRepository.java` | 修改 | claimDue / reschedule / cancelScheduled | 整号放行 |
| 2 | `app/.../SqliteBackgroundTaskRepository.java` | 修改 | 同上 Sqlite | 整号放行 |
| 3 | `kernel/.../TaskRuntime.java` | 修改 | promoteDueScheduled | 整号放行 |
| 4 | `app/.../DefaultTaskRuntime.java` | 修改 | promote / 多次回 SCHEDULED / cancel CAS | 整号放行 |
| 5 | `app/.../SchedulePromoteTicker.java` | 增加 | 独立晋升拍 | 整号放行 |
| 6 | `app/.../TaskRuntimeConfig.java` | 修改 | Bean | 整号放行 |
| 7 | `app/.../application.yml` | 修改 | schedule.promote-* | 整号放行 |
| 8 | `app/.../V028__delivery_multi_fire.sql` | 增加 | 多次开火可重复入交付队 | 整号放行 |
| 9 | `app/.../TaskDeliveryService.java` | 修改 | 多次：仅活跃行幂等 | 整号放行 |
| 10 | `app/.../SqliteTaskDeliveryPendingRepository.java` | 修改 | findActiveQueued | 整号放行 |
| 11 | `kernel/.../TaskDeliveryPendingRepository.java` | 修改 | findActiveQueued | 整号放行 |
| 12 | `app/.../SqliteIdleDeliveryPendingRepository.java` | 修改 | findActiveQueued | 整号放行 |
| 13 | `kernel/.../IdleDeliveryPendingRepository.java` | 修改 | findActiveQueued | 整号放行 |

禁止：WORLD_TICK；Cron 表；V024–V027 原文；测文件。
