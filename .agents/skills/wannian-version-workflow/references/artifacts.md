# 产物命名与落盘

路径相对 wannian-agent 工作区根。流程不在此重复：层步骤 → [layers.md](./layers.md)；草稿/审阅卡 → [implement-draft.md](./implement-draft.md)。

---

## 1. 研究稿

| 项 | 约定 |
|----|------|
| 路径 | `docs/research/<slug>.md` |
| 文首 | status、purpose、链到计划（若有） |
| 必备 | 需求摘要；外部对照（可吸收/勿抄）；本仓不变量（若有）；**已定决议**；未决 |
| 索引 | 更新 `docs/research/README.md` |
| 不是 | 施工单、排期、写码授权 |

`docs/research/history/` ≠ 现行产品档施工。  
立项检索覆盖：通用搜索、GitHub、论文、官方 docs、同业——结论写入本文，对话同步用户后再改需求。

---

## 2. 整体项目计划

| 项 | 约定 |
|----|------|
| 路径 | `docs/plans/` 总计划，或 `roadmap.md` + `product/01-overview.md` |
| 必备 | 目标、不做、**实现周期切分**、依赖、索引义务 |
| 门 | 用户对话审过 |

---

## 3. 实现周期 / 小版本计划

| 项 | 约定 |
|----|------|
| 路径 | `docs/plans/k0x-<topic>.md`（活） |
| 文首 | 正式号（`2.5` / `2.5.1`）；链 research/contract/workflow |
| status | `初版·待审` → `已审` → 归档 |

交付后：`docs/plans/archive/…`，更新 archive README。

---

## 4. 阶段实施单

| 项 | 约定 |
|----|------|
| 路径 | `docs/plans/k0x-<stage>-implementation.md` |
| 必备 | 代码事实、不变量、MANIFEST、禁区、完成检查、F 测预告、开工门 |
| MANIFEST | 见 [implement-draft.md](./implement-draft.md) §2 |

范本：`docs/plans/archive/0.2.4/k04-a-behavior-journal-implementation.md`。

---

## 5. 草稿目录

```text
docs/plans/<k0x-*-draft>/
  MANIFEST.md / REVIEW.md（可选）
  wn-server/... 或 wannian-ui/...
```

规则权威 → [implement-draft.md](./implement-draft.md)。阶段收口后可删该号草稿（或留至清理版统一清）。

---

## 6. 进度与审查

| 产物 | 路径 |
|------|------|
| 勾选 | `docs/guide/01-checklist.md` |
| 审查 | `docs/reviews/`（另建；不改写旧关闭结论） |
| 冲突优先级 | 产品概览 > roadmap > checklist > 现行计划/规格 > research |
