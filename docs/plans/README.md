# 活计划

版本三档见 [产品概览 §3](../product/01-overview.md)。进度勾选只认 [实施清单](../guide/01-checklist.md)。  
**排期只维护 [roadmap.md](./roadmap.md)。** 单批施工指令另开文件，不在施工单里再写一遍全版本表。

v0.2 正式小版本：**0.2.1–0.2.7**（`K01`–`K07` 为施工别名）。

| 文档 | 档 | 状态 | 用途 |
|------|----|------|------|
| [roadmap.md](./roadmap.md) | 全档 | 现行 | 唯一批次顺序 |
| [k01-agent-loop.md](./k01-agent-loop.md) | **0.2.1** | **已交付**（审计关闭见 [04](../reviews/04-reverify-0.2.1.md)） | Loop / 错误码 / Turn 接线（别名 K01） |
| [k02-tools.md](./k02-tools.md) | **0.2.2** | **已交付**（2026-09-22） | ToolRuntime + Role×Facet×Host 绑定（别名 K02） |
| [k02-tool-impl-binding.md](./k02-tool-impl-binding.md) | **0.2.2-R** | **已实施（R0–R2）** | OS/PS family 同构求交；`powershell_resolve_5`/`_7` |
| [k04-behavior-journal.md](./k04-behavior-journal.md) | **0.2.4-A** | **已立项 · 未开工** | 统一行为账本（turn_step；user/system/agent） |

后续：`k03-memory.md`（**0.2.3**）、`k04-outbox-sse.md`（**0.2.4-B**）… 开工时再建；v0.3 开工前再写 `n01-topology.md` 等。未建**实施**计划 = 不授权写生产代码。  
**0.2.4** 含 A 账本 + B 交付/历史恢复；清单见 [01-checklist · 0.2.4](../guide/01-checklist.md)。A 未完成不得勾选整批 0.2.4。
