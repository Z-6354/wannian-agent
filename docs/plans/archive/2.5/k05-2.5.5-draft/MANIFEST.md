# 2.5.5 draft · MANIFEST

`status`: **全部已通过 · 已拷生产** — 2026-09-27（用户「全部审核通过」连续完成）  
`spec`: ../k05-2.5.5-p2-spec.md（**P2 已对话确认**）  
`implementation`: ../k05-2.5.5-implementation.md  
`locked`: G1–G6=A

| 序 | 路径 | 修改方式 | 对话审 |
|----|------|----------|--------|
| 1 | `kernel/.../task/BackgroundPolicyDecision.java` | 增加 | **已通过 · 已拷生产** |
| 2 | `kernel/.../task/BackgroundPolicy.java` | 增加 | **已通过 · 已拷生产** |
| 3 | `kernel/.../task/BackgroundPolicyContext.java` | 增加 | **已通过 · 已拷生产** |
| 4 | `api/.../common/TaskReviewId.java` | 增加 | **已通过 · 已拷生产** |
| 5 | `kernel/.../task/TaskReviewStatus.java` | 增加 | **已通过 · 已拷生产** |
| 6 | `kernel/.../task/TaskReviewPending.java` | 增加 | **已通过 · 已拷生产** |
| 7 | `kernel/.../task/TaskReviewPendingRepository.java` | 增加 | **已通过 · 已拷生产** |
| 8 | `kernel/.../agent/AgentOutcome.java` | 修改 | **已通过 · 已拷生产** |
| 9 | `kernel/.../error/ErrorCodes.java` | 修改 | **已通过 · 已拷生产** |
| 10 | `app/.../migration/V025__task_review_pending.sql` | 增加 | **已通过 · 已拷生产** |
| 11 | `app/.../persistence/SqliteTaskReviewPendingRepository.java` | 增加 | **已通过 · 已拷生产** |
| 12 | `app/.../task/DefaultBackgroundPolicy.java` | 增加 | **已通过 · 已拷生产** |
| 13 | `kernel/.../tool/builtin/ProposeBackgroundTaskToolAdapter.java`（+ Names/Pool/Policy） | 增加/修改 | **已通过 · 已拷生产** |
| 14 | `kernel/.../agent/DefaultAgentLoop.java` | 修改 | **已通过 · 已拷生产** |
| 15 | `kernel/.../turn/TurnEngine.java` | 修改 | **已通过 · 已拷生产** |
| 16 | `app/.../task/TaskReviewService.java` | 增加 | **已通过 · 已拷生产** |
| 17 | `app/.../http/TaskReviewController.java` | 增加 | **已通过 · 已拷生产** |
| 18 | `app/.../task/TaskRuntimeConfig.java` + `StreamDeliveryConfig.java` | 修改 | **已通过 · 已拷生产** |
| 19 | `app/.../application.yml` | 修改 | **已通过 · 已拷生产** |
| 20 | `app/.../chat/task-review.js` + `api.js` + `app.js` | 增加/修改 | **已通过 · 已拷生产** |

窄编译：`mvn -s D:\0HAN\HANAGENT\.mvn\settings.xml -pl app -am compile` **BUILD SUCCESS** · 2026-09-27T22:39:59+08:00  
代审修：`TaskReviewService.confirm` Held 回滚 CONFIRMED→PENDING · 再编译通过 · 验收通过（模型代审）
