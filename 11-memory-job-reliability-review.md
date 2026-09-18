# 11 · Memory Candidate 后台任务可靠性审核草案

`date`: 2026-09-17  
`status`: **deferred-with-memory-topic** — 记忆专题于 2026-09-17 阶段性存档；本文方案尚未确认，恢复讨论时从本项继续，不授权实施。

## 1. 目标

流式 Turn 完成并持久化 Assistant Message 后，后台任务负责抽取、去重、冲突分析和自动提升。该任务失败不能改变已经完成的聊天结果，但必须最终可重试、可观测且不会重复写入。

## 2. 推荐状态机

```text
PENDING
→ RUNNING
→ SUCCEEDED
  或 RETRY_WAIT
  或 DEAD
  或 CANCELLED
```

- `PENDING`：Turn 已完成，任务已在数据库登记。
- `RUNNING`：某个 worker 持有带期限 lease。
- `RETRY_WAIT`：可恢复失败，等待下次执行。
- `SUCCEEDED`：抽取及所有领域写入已经提交。
- `DEAD`：超过最大尝试次数或遇到不可恢复错误，等待人工检查/重新触发。
- `CANCELLED`：来源 Turn 或 Conversation 被依法删除、遗忘，任务不得再处理。

## 3. 事务边界

不能采用“Turn 提交成功后只在内存中丢一个线程”的方式。推荐在持久化最终 Assistant Message 和 `turn.completed` 时，同一数据库事务插入 Memory Job：

```text
保存完整 Assistant Message
+ Turn → COMPLETED
+ 插入唯一 MemoryJob(PENDING)
COMMIT
```

这样服务即使在 commit 后立即崩溃，重启后仍能发现待处理任务。

后台抽取调用 LLM 不能占用上述事务。Worker 读取已提交的来源，调用模型后，再开启短事务提交候选、关系和 job 结果。

## 4. 幂等设计

建议唯一键：

```text
jobType + sourceTurnId + extractorVersion
```

- 同一 Turn、同一抽取版本最多有一个逻辑任务。
- 每个 Candidate 使用稳定 dedupe key，例如来源 Message 集合、规范化内容、scope、kind 和 extractorVersion 的哈希。
- 重试时先 upsert/核对既有结果，不能再次追加相同 Candidate、替代链或 RelationshipEvent。
- 升级抽取规则时允许创建新 `extractorVersion` 任务，但必须定义旧结果的合并/替代策略，不能默默双写。

## 5. lease 与崩溃恢复

- Worker 领取任务时写入 `leaseOwner`、`leaseUntil`、`attemptCount`。
- 进程崩溃或机器重启后，超过 `leaseUntil` 的 RUNNING 任务可被重新领取。
- 完成提交时必须验证 lease/version，过期 worker 不得覆盖新 worker 的结果。
- 单机 MVP 仍应使用数据库 lease，而不是依赖“当前只有一个进程”；这能覆盖线程崩溃和重启。

## 6. 重试分类

可重试：

- 上游 LLM 超时、限流、临时网络错误；
- SQLite busy/短暂锁冲突；
- 进程在最终提交前崩溃。

不可直接重试：

- 来源 Message 不存在或已被遗忘；
- 抽取输出持续不符合 schema；
- 领域校验失败，例如 S2 秘密试图进入候选；
- extractorVersion 已被禁用。

推荐指数退避并带抖动，设置最大尝试次数。达到上限进入 `DEAD`，不能无限消耗模型费用。

## 7. 与用户操作的竞态

- 用户在任务执行期间遗忘来源内容：提交前重新检查 tombstone；命中后丢弃结果并取消任务。
- 用户纠正事实：冲突裁决以提交时最新 ACTIVE 状态为准，不能只依赖任务启动时快照。
- 用户删除 Conversation：按产品删除策略取消未完成任务，并清理尚未提交的候选。
- 同一 Conversation 后续 Turn 已完成：任务可以按 Turn 独立执行，但冲突/提升提交必须串行化或使用乐观版本检查。

## 8. 流式 Turn 不变量

- Memory Job 只有在完整 Assistant Message 已持久化后才能创建/执行。
- 后台失败不把 Turn 从 `COMPLETED` 改为 `FAILED`。
- SSE `turn.completed` 不等待记忆抽取结束。
- 可以另有内部/管理事件表示 memory processing 状态，但不混入聊天文本流。
- 服务优雅关闭时停止领取新任务，并限时完成或释放现有 lease；不能假报 SUCCEEDED。

## 9. 最低可观测字段

```text
jobId
jobType
sourceTurnId
extractorVersion
status
attemptCount
nextAttemptAt
leaseOwner
leaseUntil
lastErrorCode
lastErrorSummary
createdAt
startedAt
finishedAt
```

错误摘要不得包含 S1 正文或任何 S2 内容。

## 10. 建议确认的决策

> MVP 使用数据库持久化 Memory Job；最终消息、Turn 完成和 PENDING Job 同事务提交。Worker 使用唯一键、lease、有限重试和幂等写入；崩溃后可重新领取。记忆失败不影响已完成 Turn，遗忘墓碑在最终提交前再次校验。
