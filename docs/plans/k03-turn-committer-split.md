# 0.2.3-R · SqliteTurnCommitter 冻结计划 Store 抽取（有限实施）

`status`: **有限实施完成 · 全量合并测试通过；先前分组运行的 SQLite busy 未复现**（用户 2026-09-23 明确批准 sol 审查提出的必要重构）  
`version`: **0.2.3** 附属重构  
`parent`: [k03-memory.md](./k03-memory.md) 阶段 B  
`scope`: 仅抽取冻结计划存取与 JSON 编解码；保留 `TurnCommitter` 外部契约及原事务边界；未拆 message writer。

---

## 1. 问题（已验证）

- `SqliteTurnCommitter` ≈ **990+ 行**，同时承担：receive 幂等、freeze/commit 编排、消息/Turn/Outbox SQL、冻结计划 JSON、序号与冲突映射。
- 0.2.3 已抽出 `SqliteMemoryCommitWriter` / `SqliteRelationshipCommitWriter`（~130 行级），**行数几乎未降**（freeze JSON 编解码又加长）。
- 对开闭原则：再往 Committer 内堆新写缝会继续恶化；对可读性：单文件过厚。

## 2. 原计划目标与本轮实施范围

- Committer **只编排事务顺序**（深模块对外口仍是 `TurnCommitter`）。
- 冻结计划 SQL / plan_json 落在可单独审阅的内部 Store。
- 本轮只完成 `SqliteFrozenPlanStore` 抽取和 v1/v2 解码兼容；保留 message / Turn / Outbox SQL 在 Committer。
- 原先 200–350 行目标不作为本轮验收，后续若需扩大拆分须单独审阅和授权。

## 3. 建议落点（实施时）

```text
SqliteTurnCommitter              — receive / freeze / commit 编排 + 原事务边界 + message/Turn/Outbox SQL
  SqliteFrozenPlanStore          — insert / load / delete turn_commit_plan + plan_json 编解码（已抽取）
  SqliteMemoryCommitWriter       — 已有
  SqliteRelationshipCommitWriter — 已有
```

可选更后：`SqliteReceiveTurnWriter`（若 receive 仍占编排一半以上）。

## 4. 非目标

- 不改 `TurnCommitter` 对外 Port 语义。
- 不借拆分改业务规则（CAS / 幂等 / 原子性断言须保持）。
- 不并入 0.2.4 账本（turn_step）。

## 5. 本轮验收

- [x] `SqliteFrozenPlanStore` 在调用方传入的连接上操作，不创建或提交事务；事务边界仍由 `SqliteTurnCommitter` 持有。
- [x] v1 冻结计划恢复时，缺失 memory/relationship 作为空列表/null；v2 保留完整 memory/relationship 往返。
- [x] 非法或不支持的 plan_json 明确拒绝，不静默补默认值（v1 缺少新增字段除外）。
- [x] 单类窄测：`RecoverableCommitPlanTest` 10/10、`TurnTransitionPersistenceTest` 6/6、`ConsistencyBackupTest` 4/4、独立 `TurnCommitterAtomicityTest` 10/10。
- [x] 全量合并运行通过：V010 后 `mvn -s C:\Users\han\.m2\settings.xml -pl kernel,app -am test` BUILD SUCCESS，0 failures/errors/skipped；先前窄分组运行中 `TurnCommitterAtomicityTest.clearBusinessTables` 的间歇性 `SQLITE_BUSY` 在全量运行未复现，当前记录为非复现现象。
- [x] 本轮未抽取 `SqliteTurnMessageWriter`；不扩大至消息/outbox/sequence。

## 6. 与主施工单关系

主路径仍按 [k03-memory.md](./k03-memory.md) 阶段 B→E。本轮只解决冻结计划格式升级后 v1 COMMITTING Turn 无法恢复的问题，并将该职责隔离到内部 Store；不改变主路径业务规则，也不包含真人/live 验收。
