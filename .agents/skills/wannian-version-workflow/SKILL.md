---
name: wannian-version-workflow
description: >-
  wannian-agent 从零到交付：讨论→检索→整体计划→按实现周期(2.x)推进；周期内规划/实现/清理小版本(2.x.n)。
  用户提新想法时须先问是否本周期实现，否则选定周期写入计划。问从0怎么做、开周期、验收、
  「按工作流下一步」时使用。小改与其它产品跳过。
---

# wannian-agent · 从零到交付工作流

工作区：本产品根。本文件是**路由与硬门**；细则按下方「按需加载」打开对应 reference，**禁止**把未加载文件里的旧段落当现行规则。

| 产品权威（冲突时以此为准） | 路径 |
|------|------|
| 产品档边界 | [`docs/product/01-overview.md`](../../../docs/product/01-overview.md) §3 |
| 排期 | [`docs/plans/roadmap.md`](../../../docs/plans/roadmap.md) |
| 勾选 | [`docs/guide/01-checklist.md`](../../../docs/guide/01-checklist.md) |
| 草稿入仓习惯 | [`docs/plans/version-stage-workflow.md`](../../../docs/plans/version-stage-workflow.md) |
| 编号对照 | [`docs/plans/version-numbering.md`](../../../docs/plans/version-numbering.md) |
| 角色 | [`AGENTS.md`](../../../../AGENTS.md) |

**编号一口径：** 产品档 `v2` · 实现周期 `2.x` · 小版本 `2.x.n`（首号规划、末号清理）。细则 → [numbering.md](references/numbering.md)。  
Skill / reference 里的「§」指**该文件内章节**，不是实现周期号。

参考索引：[references/README.md](references/README.md)。

---

## 接到任务时（先分流）

```text
1. 新想法/新需求 → diversions.md（先问是否本周期）
2. 审阅中且用户提问（非通过/驳回）→ review-protocol.md §答疑
3. 用户要求改计划/修规格 → review-protocol.md §改计划（须带上下文）
4. 否则判定层：层1 / 层2 / 层3(规划|实现|清理) / 旁线 / backlog / 小改
5. 只打开该层 reference；禁止跳层写生产码
6. 门未过 → 停并汇报；不假装完成
```

---

## 三层地图（摘要）

```text
层1 立项     讨论→检索→同步→多轮→整体计划【对话审过】
层2 实现周期 整理文档→告知目标→检索→敲定→2.x.n 草案表（含类型列；≠已审）
层3 小版本   规划(P1→P2) → 实现(草稿+审阅卡→拷生产→验收包) → 清理(审+测+归档)
```

已在中途：从当前层切入；缺门补门。步骤全文 → [layers.md](references/layers.md)。

---

## 硬门（跨层不变量）

| 门 | 规则 |
|----|------|
| 阶段表类型 | 仅三值：规划计划版 / 实现版 / 清理总结版；首=`2.x.1` 规划，末=清理，中=实现 |
| 审阅主场 | **对话**确认才算通过；文档是 AI 账本，不是「请用户自行打开 md」 |
| 规划双门 | P1 切分通过 → P2 各号细节对话通过 → 才开该实现版写码 |
| 实现草稿 | 未书面豁免：先 `*-draft/`，按**文件审阅卡**逐文件对话通过后再拷 `wn-server/` / `wannian-ui/` |
| 清理收口 | 无上下文子代理审 + 软件测 + 真人测 + 归档通过 → 才报周期完成 |
| 新想法 | 先问是否本周期；禁止偷塞 MANIFEST / 私自改阶段表 |

---

## 按需加载（解耦边界）

| 场景 | 打开 | 职责（只此文件为准） |
|------|------|----------------------|
| 编号 / 类型列 / 产品档 | [numbering.md](references/numbering.md) | 号段含义与阶段表类型硬规则 |
| 层1/2/3 步骤与清理清单 | [layers.md](references/layers.md) | 各层动作顺序与产出；不含字段模板 |
| 对话审 / 提问 / 改计划 | [review-protocol.md](references/review-protocol.md) | 审阅主场与子 Agent 旁路 |
| 规划 P1 / P2 怎么过门 | [planning-gates.md](references/planning-gates.md) | 切分审与逐项细节审流程 |
| 实现草稿 / 文件审阅卡 | [implement-draft.md](references/implement-draft.md) | 草稿门、MANIFEST、审阅卡、验收包 |
| 字段/方法/P1 详单模板 | [depth-templates.md](references/depth-templates.md) | 可审粒度的表头与骨架（无流程） |
| 新想法 / 旁线 / 小改 | [diversions.md](references/diversions.md) | 中途分流 |
| 路径与命名 | [artifacts.md](references/artifacts.md) | 落盘路径；不重复流程 |

**一责一文件：** 流程不写进 templates；模板不写进 router；审阅协议不掺实现草稿步骤。改规则只改 owning 文件，再改 README 索引（若职责变了）。

---

## 角色（摘要）

| 角色 | 做 | 不做 |
|------|----|------|
| 审阅（主） | 判层、搬入待审、守门；提问/改计划按 review-protocol 派子 Agent 并核对 | 默认不代写生产码；摘要代审；裸派改计划 |
| 答疑（子） | 只读查证 | 改计划 / status / 生产码 / 宣称已通过 |
| 改计划（子） | 按上下文改允许路径文档 | 生产码；偷标已审；扩大路径 |
| 执行 | 按已审 P2；草稿→审阅卡→拷生产；验收包；清理测归档 | 扩 MANIFEST；跳草稿门；无卡只丢 diff |
| 用户 | 对话放行 / 提问 / 改计划；真人测 | 不被要求以「通读长 md」为唯一审阅方式 |

闭环：`落盘 → 对话审（可穿插答疑/改计划）→ 对话通过 → 回写 status → 执行 → 验收包 → 下一 2.x.n`。

---

## 自检（出门前）

```text
[ ] 分流正确；新想法已先问本周期
[ ] 只加载了当前层需要的 reference；未用过时 § 号当权威
[ ] 阶段表类型首规划末清理；草案未冒充已审
[ ] 通过门在对话；全文级搬入（或文件审阅卡字段齐全）
[ ] 提问/改计划走子 Agent 规则；锚点未擅自前进
[ ] 实现：草稿 + 审阅卡后再拷生产（或已书面豁免）
[ ] 清理：三类测 + 归档后才报周期完成
```
