# 活计划

版本三档见 [产品概览 §3](../product/01-overview.md)。进度勾选只认 [实施清单](../guide/01-checklist.md)。  
**排期只维护 [roadmap.md](./roadmap.md)。** 单批施工指令另开文件，不在施工单里再写一遍全版本表。
**怎么做（搜资料→计划→分阶段→草稿逐文件审→末段才测）：** [version-stage-workflow.md](./version-stage-workflow.md)（2026-09-23 锁定）。

v0.2 正式小版本：**0.2.1–0.2.7**（`K01`–`K07` 为施工别名）。

| 文档 | 档 | 状态 | 用途 |
|------|----|------|------|
| [version-stage-workflow.md](./version-stage-workflow.md) | 全档 | **现行** | 版本/阶段协作门（草稿审、末段才测） |
| [roadmap.md](./roadmap.md) | 全档 | 现行 | 唯一批次顺序 |
| [k01-agent-loop.md](./k01-agent-loop.md) | **0.2.1** | **已交付** | Loop / 错误码 / Turn 接线 |
| [k02-tools.md](./k02-tools.md) | **0.2.2** | **已交付** | ToolRuntime + Role×Facet×Host |
| [k02-tool-impl-binding.md](./k02-tool-impl-binding.md) | **0.2.2-R** | **已实施** | OS/PS family 同构求交 |
| [k03-memory.md](./k03-memory.md) | **0.2.3** | **已交付 · 2026-09-24** | Memory + Relationship（R1） |
| [k03-b-hotpath-impl.md](./k03-b-hotpath-impl.md) | **0.2.3-B** | **已交付** | 热路径：工具锁死 / byName / Mem0 锚 / Freeze |
| [k03-d-recall-tombstone-http.md](./k03-d-recall-tombstone-http.md) | **0.2.3-D** | **已交付** | 召回 A / 弱 B / HTTP |
| [k03-d-search-tool.md](./k03-d-search-tool.md) | **0.2.3-D+** | **已交付** | `search_memory` |
| [k03-turn-committer-split.md](./k03-turn-committer-split.md) | **0.2.3-R** | **已交付** | `SqliteFrozenPlanStore` 抽取 |
| [k03-l-run-journal.md](./k03-l-run-journal.md) | **0.2.3-L** | **已交付** | 运行日志 / turn_step 窄版 |
| [k04-behavior-journal.md](./k04-behavior-journal.md) | **0.2.4-A** | **已立项 · 下一默认工作** | 行为账本加厚 |

后续：`k04-outbox-sse.md`（**0.2.4-B**）… 开工时再建。未建**实施**计划 = 不授权写生产代码。  
**0.2.4** = A 账本加厚 + B 交付/历史恢复；清单见 [01-checklist · 0.2.4](../guide/01-checklist.md)。
