# 归档 · 0.2.1–0.2.3 施工单

`status`: **已交付归档**（2026-09-26 迁入）  
`live`: 现行活计划见 [plans/README](../../README.md)；排期真源 [roadmap](../../roadmap.md)。  
`scope`: **仅** v0.2 已交付的 **0.2.1 / 0.2.2 / 0.2.3** 施工正文。不包含 **0.2.4+**。

验收勾选仍以 [实施清单](../../../guide/01-checklist.md) 为准；本目录只保留历史施工指令，不再作为开工入口。

---

## 总览

| 正式号 | 别名 | 主题 | 状态 | 正文 |
|--------|------|------|------|------|
| **0.2.1** | K01 | Agent Loop / 错误码 / Turn 接线 | 已交付 | [k01-agent-loop.md](./k01-agent-loop.md) |
| **0.2.2** | K02 | ToolRuntime + Role×Facet×Host | 已交付 · 2026-09-22 | [k02-tools.md](./k02-tools.md) |
| **0.2.2-R** | — | OS/PS family 同构求交 | 已实施 | [k02-tool-impl-binding.md](./k02-tool-impl-binding.md) |
| **0.2.3** | K03 | Memory + Relationship（R1） | 已交付 · 2026-09-24 | [k03-memory.md](./k03-memory.md) |
| **0.2.3-B** | — | 热路径：工具锁死 / byName / Mem0 锚 / Freeze | 已交付 | [k03-b-hotpath-impl.md](./k03-b-hotpath-impl.md) |
| **0.2.3-D** | — | 召回 A / 弱 B / HTTP | 已交付 | [k03-d-recall-tombstone-http.md](./k03-d-recall-tombstone-http.md) |
| **0.2.3-D+** | — | `search_memory` | 已交付 | [k03-d-search-tool.md](./k03-d-search-tool.md) |
| **0.2.3-R** | — | `SqliteFrozenPlanStore` 抽取 | 已交付 | [k03-turn-committer-split.md](./k03-turn-committer-split.md) |
| **0.2.3-L** | — | 运行日志 / turn_step 窄版 | 已交付 | [k03-l-run-journal.md](./k03-l-run-journal.md) |

设计稿（仍在 research）：[memory-system-2.3.md](../../../research/memory-system-2.3.md)。  
关闭复核：`0.2.1` → [04-reverify](../../../reviews/04-reverify-0.2.1.md)。

---

## 批次关系（简）

```text
0.2.1 Loop
   ↓
0.2.2 Tools  (+ 0.2.2-R 能力标签求交)
   ↓
0.2.3 Memory
   ├─ B  热路径
   ├─ D / D+  召回 · HTTP · search_memory
   ├─ R  FrozenPlanStore 拆分
   └─ L  turn_step 运行日志（供 0.2.4-A 加厚）
```

下一交付线 **0.2.4** 已交付并归档 → [../0.2.4/](../0.2.4/README.md)。
