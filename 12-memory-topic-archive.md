# 12 · 记忆专题阶段性存档

`archived_at`: 2026-09-17  
`status`: **topic-archived** — 暂停继续展开记忆问题，保留所有资料、已确认决策与未决事项；不是删除或最终冻结。

## 1. 已保存资料

- 外部、本地 Agent 记忆实现调研：[`docs/architecture/research-2026-09-17-agent-memory-patterns.md`](../../docs/architecture/research-2026-09-17-agent-memory-patterns.md)
- 累计产品与架构决策：[`06-discussion-decisions.md`](./06-discussion-decisions.md)
- 自动提升审核：[`07-memory-auto-promotion-review.md`](./07-memory-auto-promotion-review.md)
- 敏感记忆边界：[`08-sensitive-memory-review.md`](./08-sensitive-memory-review.md)
- 冲突和时间语义：[`09-memory-conflict-review.md`](./09-memory-conflict-review.md)
- RelationshipState：[`10-relationship-state-review.md`](./10-relationship-state-review.md)
- 后台任务可靠性：[`11-memory-job-reliability-review.md`](./11-memory-job-reliability-review.md)

## 2. 已确认到的边界

- 单用户、唯一烟火、多 Facet；共享核心身份和关系，专业记忆可按 Facet 隔离。
- 长期记忆区分 scope、kind、lifecycle、sensitivity 和来源证据。
- 普通明确陈述可用单条证据；INFERRED 至少需要两个不同 Turn 的一致证据。
- S0 可按规则自动提升；S1 需明确要求、可见提示和加密；S2 永不进入长期记忆体系。
- 冲突采用 SUPERSEDE、COEXIST、TEMPORAL、HOLD，不用简单追加或覆盖。
- RelationshipState 是互动契约和共同历史索引；用户侧显示阶段和客观数据，由服务端持久化并通过统一 API 提供。
- 流式输出完成、完整消息持久化后才进行后台记忆抽取。

## 3. 暂未确认

- Memory Job 的数据库任务、lease、有限重试和崩溃恢复方案仍是审核草案。
- S1 加密密钥、备份、导出和彻底删除实现未设计。
- Reflection 是否建立独立非事实存储尚未确认。
- 关系阶段的具体迁移条件、API schema 和 SQLite 表结构未设计。

## 4. 恢复点

再次讨论记忆专题时，从 [`11-memory-job-reliability-review.md`](./11-memory-job-reliability-review.md) 的建议确认项继续；不要重新调研或覆盖本轮文档，除非外部依赖或需求已经变化。
