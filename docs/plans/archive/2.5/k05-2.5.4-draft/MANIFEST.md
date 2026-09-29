# 2.5.4 draft · MANIFEST

`status`: **全部已通过 · 已拷生产** — 2026-09-27（用户授权连续完成至验收）  
`spec`: ../k05-2.5.4-p2-spec.md（**P2 已对话确认**）  
`implementation`: ../k05-2.5.4-implementation.md

| 序 | 路径 | 修改方式 | 对话审 |
|----|------|----------|--------|
| 1 | `kernel/.../task/TaskExecutor.java` | 增加 | **已通过 · 已拷生产** |
| 2 | `kernel/.../task/SubAgentRunSpec.java` | 增加 | **已通过 · 已拷生产** |
| 3 | `kernel/.../task/DispatchResult.java` | 增加 | **已通过 · 已拷生产** |
| 4 | `kernel/.../task/CancelDispatchResult.java` | 增加 | **已通过 · 已拷生产** |
| 5 | `kernel/.../task/BackgroundConcurrencyGate.java` | 增加 | **已通过 · 已拷生产** |
| 6 | `kernel/.../task/SubAgentRunSnapshot.java` | 增加 | **已通过 · 已拷生产** |
| 7 | `kernel/.../task/BackgroundTaskRepository.java` | 修改 | **已通过 · 已拷生产** |
| 8 | `kernel/.../task/SubAgentRunRepository.java` | 修改 | **已通过 · 已拷生产** |
| 9 | `kernel/.../error/ErrorCodes.java` | 修改 | **已通过 · 已拷生产** |
| 10 | `app/.../persistence/SqliteBackgroundTaskRepository.java` | 修改 | **已通过 · 已拷生产** |
| 11 | `app/.../persistence/SqliteSubAgentRunRepository.java` | 修改 | **已通过 · 已拷生产** |
| 12 | `app/.../task/DefaultBackgroundConcurrencyGate.java` | 增加 | **已通过 · 已拷生产** |
| 13 | `app/.../task/LocalTaskExecutor.java` | 增加 | **已通过 · 已拷生产** |
| 14 | `app/.../task/DefaultTaskRuntime.java` | 修改 | **已通过 · 已拷生产** |
| 15 | `app/.../task/BackgroundDispatchTicker.java` | 增加 | **已通过 · 已拷生产** |
| 16 | `app/.../task/TaskRuntimeConfig.java` | 修改 | **已通过 · 已拷生产** |
| 17 | `app/src/main/resources/application.yml` | 修改 | **已通过 · 已拷生产** |

禁止：`ModelSlotProbe`。窄编译：`mvn -pl app -am compile` BUILD SUCCESS · 2026-09-27T21:58:30+08:00。
