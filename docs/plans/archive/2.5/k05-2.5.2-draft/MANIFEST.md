# 2.5.2 draft · MANIFEST

`status`: **文件审完 · 验收通过** — 2026-09-27
`spec`: ../k05-2.5.2-p2-spec.md  
`implementation`: ../k05-2.5.2-implementation.md

| 序 | 草稿相对路径 | 生产路径 | 对话审 |
|----|--------------|----------|--------|
| 1 | `.../BackgroundTaskId.java` | `wn-server/api/.../BackgroundTaskId.java` | **已通过 · 已入仓** |
| 2 | `.../SubAgentRunId.java` | `wn-server/api/.../SubAgentRunId.java` | **已通过 · 已入仓** |
| 3 | `.../task/TaskSource.java` | `wn-server/kernel/.../task/TaskSource.java` | **已通过 · 已入仓** |
| 4 | `.../task/TaskType.java` | `wn-server/kernel/.../task/TaskType.java` | **已通过 · 已入仓** |
| 5 | `.../task/NotifyPolicy.java` | `wn-server/kernel/.../task/NotifyPolicy.java` | **已通过 · 已入仓** |
| 6 | `.../task/BackgroundTaskStatus.java` | `wn-server/kernel/.../task/BackgroundTaskStatus.java` | **已通过 · 已入仓** |
| 7 | `.../task/SubAgentRunStatus.java` | `wn-server/kernel/.../task/SubAgentRunStatus.java` | **已通过 · 已入仓** |
| 8 | `.../task/ScheduleSpec.java` | `wn-server/kernel/.../task/ScheduleSpec.java` | **已通过 · 已入仓** |
| 9 | `.../task/ResolvedSchedule.java` | `wn-server/kernel/.../task/ResolvedSchedule.java` | **已通过 · 已入仓** |
| 10 | `.../task/OriginTurn.java` | `wn-server/kernel/.../task/OriginTurn.java` | **已通过 · 已入仓** |
| 11 | `.../task/TaskProposal.java` | `wn-server/kernel/.../task/TaskProposal.java` | **已通过 · 已入仓** |
| 12 | `.../task/TaskDraft.java` | `wn-server/kernel/.../task/TaskDraft.java` | **已通过 · 已入仓** |
| 13 | `.../task/ScheduleResolver.java` | `wn-server/kernel/.../task/ScheduleResolver.java` | **已通过 · 已入仓** |
| 14 | `.../task/DispatchBudget.java` | `wn-server/kernel/.../task/DispatchBudget.java` | **已通过 · 已入仓** |
| 15 | `.../task/DispatchOutcome.java` | `wn-server/kernel/.../task/DispatchOutcome.java` | **已通过 · 已入仓** |
| 16 | `.../task/CompletionOutcome.java` | `wn-server/kernel/.../task/CompletionOutcome.java` | **已通过 · 已入仓** |
| 17 | `.../task/CancelTaskResult.java` | `wn-server/kernel/.../task/CancelTaskResult.java` | **已通过 · 已入仓** |
| 18 | `.../task/SubAgentRunResult.java` | `wn-server/kernel/.../task/SubAgentRunResult.java` | **已通过 · 已入仓** |
| 19 | `.../task/TaskRuntime.java` | `wn-server/kernel/.../task/TaskRuntime.java` | **已通过 · 已入仓** |
| 20 | `.../task/BackgroundTaskRepository.java` | `wn-server/kernel/.../task/BackgroundTaskRepository.java` | **已通过 · 已入仓** |
| 21 | `.../task/SubAgentRunRepository.java` | `wn-server/kernel/.../task/SubAgentRunRepository.java` | **已通过 · 已入仓** |
| 22 | `.../V024__background_task.sql` | `wn-server/app/.../db/migration/V024__background_task.sql` | **已通过 · 已入仓** |
| 23 | `.../task/DefaultScheduleResolver.java` | `wn-server/app/.../task/DefaultScheduleResolver.java` | **已通过 · 已入仓** |
| 24 | `wn-server/app/pom.xml`（增 cron-utils） | 同左 | **已通过 · 已入仓** |
| 25 | `.../task/DefaultTaskRuntime.java` | `wn-server/app/.../task/DefaultTaskRuntime.java` | **已通过 · 已入仓** |
| 26 | `.../task/TaskDraft.java`（修正案） | `wn-server/kernel/.../task/TaskDraft.java` | **已通过 · 已入仓** |
| 27 | `.../persistence/SqliteBackgroundTaskRepository.java` | `wn-server/app/.../persistence/SqliteBackgroundTaskRepository.java` | **已通过 · 已入仓** |
| 28 | `.../persistence/SqliteSubAgentRunRepository.java` | `wn-server/app/.../persistence/SqliteSubAgentRunRepository.java` | **已通过 · 已入仓** |
| 29 | `.../task/TaskRuntimeConfig.java` | `wn-server/app/.../task/TaskRuntimeConfig.java` | **已通过 · 已入仓** |

`status`: **文件审完 · 待编译验收**


规则：一次一文件；通过后勾「已通过」再拷生产。
