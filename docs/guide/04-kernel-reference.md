# 04 · 内核参考

前半是包、类型与 Interface。后半是状态机与持久化契约。进度以 [01-checklist.md](./01-checklist.md) 为准。

## 包、类型与 Interface

## 1. 命名约定

- ID 使用不可变值类型，不在业务代码到处传裸 `String`。
- 命令以动词命名，例如 `SubmitTurnCommand`。
- 已发生的事实以过去式命名，例如 `TurnCompleted`。
- Seam 使用角色名，例如 `ModelPort`；Adapter 使用技术名，例如 `OpenAiCompatibleModelAdapter`。
- 数据库行对象不得冒充领域对象。
- Java package 全小写；类型使用 PascalCase。

## 2. api module

建议目录：

```text
api/src/main/java/com/wannian/server/api/
├─ common/
├─ conversation/
├─ turn/
├─ task/
├─ tool/
└─ event/
```

### 标识类型

```java
public record ConversationId(UUID value) {}
public record MessageId(UUID value) {}
public record TurnId(UUID value) {}
public record BackgroundTaskId(UUID value) {}
public record SubAgentRunId(UUID value) {}
public record OperationId(String value) {}
```

要求：

- 构造时拒绝 `null`；
- `OperationId` 有长度与字符集限制；
- HTTP 层负责从字符串解析；
- Kernel 不重复解析裸字符串。

### SubmitTurnRequest

字段建议：

```text
clientRequestId  客户端重试使用的幂等键
conversationId  会话
userText         用户原文
requestedAt      客户端时间，仅供展示/诊断
```

Interface 规则：

- `userText` 空白时返回明确校验错误；非空正文保留原始缩进/换行，不 trim 后保存或判重；
- 同一个 `clientRequestId` 与相同请求重复提交，返回同一 Turn；
- 同一个全局 ID 携带不同 conversationId 或不同正文，返回冲突错误；新生成的 Turn/Message ID 不改变重试身份。
- 消息 sequenceNo 由服务端事务分配，HTTP 客户端不提供权威序号。

### TurnReceipt

```text
turnId
conversationId
status
acceptedAt
replayed  是否返回已有结果
```

不要直接返回数据库实体或 JPA/SQL 行对象。

## 3. kernel module 包结构

```text
kernel/src/main/java/com/wannian/server/kernel/
├─ conversation/
├─ turn/
├─ agentloop/
├─ task/
├─ tool/
├─ memory/
├─ relationship/
├─ model/
├─ outbox/
└─ shared/
```

## 4. Turn Module

### TurnEngine

外部 Interface 建议保持很小：

```java
public interface TurnEngine {
    TurnReceipt submit(SubmitTurnCommand command);
    TurnResult resume(TurnId turnId);
    CancelTurnResult cancel(TurnId turnId, CancelReason reason);
}
```

调用者需要知道：

- `submit` 可以返回已存在的 Turn；
- `resume` 只恢复可安全恢复的步骤；
- `cancel` 是请求取消，不保证已经发生的外部副作用可撤销；
- 同一 Turn 不能有两个有效 execution owner。

Implementation 内部隐藏：

- 状态转换；
- execution claim；
- Agent Loop；
- 最终提交；
- outbox。

### TurnRepository

不要暴露任意 SQL 式 CRUD。建议：

```java
interface TurnRepository {
    Optional<Turn> find(TurnId id);
    Optional<Turn> findByClientRequestId(String id);
    boolean claim(TurnId id, ExecutionClaim claim, long expectedRevision);
    void save(Turn turn, long expectedRevision);
}
```

上面的接口只示意职责，不要求把当前封闭结果改成 boolean/void。`claim` 和 `save` 必须有 compare-and-set 语义；revision 与 owner 分别校验，更新数量为零不得默默继续。加载快照不赋予任意修改状态的权力，`reconstitute/save` 不得绕过终态规则或完成事务。

### TurnCommitter

最终业务提交不得由 TurnEngine 顺序调用多个 Repository。使用领域化 Interface：

```java
public interface TurnCommitter {
    CommitTurnResult commit(CommitTurnPlan plan);
}
```

`CommitTurnPlan` 是不可变数据，包含 turnId、expectedExecutionId、expected revisions、助手 Message、批准的 Memory/Relationship 变化、可选 TaskDraft 与附加事件。SQLite Implementation 在单一事务内验证并提交，并保证生成匹配当前 Turn 的必需完成事件、分配权威消息/Outbox 序号。不能把空附加事件列表解释为允许无完成事件。它不是允许调用者任意拼装 SQL 的通用 `UnitOfWork`。

执行身份、COMMITTING 冻结计划与恢复协议统一见 [29 号 §2/§8](04-kernel-reference.md)。K03-P 已放行；接真实模型属于 **0.2.1**，尚未开始。本节之后的接口是后续阶段的目标，不表示已经实现。

## 5. AgentLoop Module

### Interface

```java
public interface AgentLoop {
    AgentOutcome run(AgentInput input, AgentBudget budget);
}
```

`AgentInput` 是已经准备好的不可变快照，不允许 Loop 自己打开数据库随意查询。

`AgentOutcome` 是封闭类型：

```text
FinalResponse
BackgroundAccepted
ControlledFailure
Cancelled
```

核心 Implementation 文件建议：

```text
DefaultAgentLoop.java          OWNER: USER
ContextAssembler.java         框架可代建，策略部分 OWNER: USER
ModelDecisionValidator.java   可代建
AgentBudget.java              可代建
```

### 注释模板

```java
// OWNER: USER
// 目的：执行一次有上限的模型—工具—观察循环。
// 输入保证：上下文已经冻结；工具目录不可变；budget 为正数。
// 输出保证：不会返回 null；不会直接提交数据库；不会直接发送 SSE。
// 禁止：创建具体模型客户端、调用 Controller、执行任意 Shell。
// 参见：05-agent-loop.md。
```

## 6. Model Module

```java
public interface ModelPort {
    ModelDecision decide(ModelRequest request, ModelCallContext context);
}
```

`ModelDecision`：

```text
FinalAnswer(text, usage)
ToolCalls(list, usage)
ModelRefusal(reason, usage)
```

厂商超时等技术失败通过受控 `ModelFailure` 表达，不把 SDK 异常传入 Kernel。

`ModelCallContext` 至少包含：

```text
turnId
stepNumber
deadline
cancelToken
traceId
```

## 7. ToolRuntime Module

Agent Loop 只调用一个深 Interface：

```java
public interface ToolRuntime {
    ToolExecutionOutcome execute(ToolInvocation invocation,
                                 ToolExecutionContext context);
}
```

`ToolExecutionOutcome` 是封闭结果：`Succeeded`、`Rejected`、`Failed`、`Unknown`。

`DefaultToolRuntime` 内部隐藏完整处理链：

```text
ToolCatalog
→ ToolCallValidator
→ ToolPolicy
→ ToolBudget
→ ToolOperationStore.begin
→ ToolAdapterRegistry
→ ToolResultSanitizer
→ ToolOperationStore.complete
```

内部 ToolAdapter Interface 规则：每次调用携带 operationId；输入已经校验；结果可序列化且有大小上限；不返回 Stream、线程、文件句柄或 SDK 对象。该内部 seam 在 v0.2 有 Local/Fake Adapter；远程 Worker Adapter 更后，不暴露给 Agent Loop。

## 8. Task Module

### TaskRuntime

```java
public interface TaskRuntime {
    TaskDraft prepare(TaskProposal proposal, OriginTurn origin);
    DispatchOutcome dispatchNext(DispatchBudget budget);
    CompletionOutcome acceptResult(SubAgentRunResult result);
    CancelTaskResult requestCancel(BackgroundTaskId id);
}
```

`TaskRuntime` 隐藏 Task/Run 状态转换、lease、attempt、retry 与取消顺序。`TaskDraft` 不直接落库，而是进入 `CommitTurnPlan`，与确认回复原子提交。

### TaskExecutor 内部 seam

```java
public interface TaskExecutor {
    DispatchResult dispatch(SubAgentRunSpec spec);
    CancelDispatchResult cancel(SubAgentRunId runId, LeaseToken lease);
}
```

0.1 Adapter：`LocalTaskExecutor`。  
0.2 Adapter：`RemoteTaskExecutor`。

`SubAgentRunSpec` 禁止包含不可序列化闭包或 Bean 引用。

## 9. Memory Module

建议外部 Interface：

```java
public interface MemoryRuntime {
    MemoryContext recall(RecallRequest request);
    MemoryEvaluation evaluate(List<MemoryCandidate> candidates,
                              MemoryPolicyContext context);
}
```

核心文件：

```text
DefaultMemoryRuntime.java      OWNER: USER
MemoryPolicy.java              OWNER: USER
MemoryRepository.java          seam
SQLiteMemoryRepository.java    Adapter
```

`recall` 返回受预算限制的结构化上下文，不返回整个数据库。

`evaluate` 返回批准、拒绝、需确认和并存等决策，以及零个或多个 `ApprovedMemoryChange`。MemoryRuntime 不自行 commit；批准变化进入 `CommitTurnPlan`。

## 10. Relationship Module

```java
public interface RelationshipRuntime {
    RelationshipContext beforeTurn(BeforeTurnRequest request);
    RelationshipEvaluation afterTurn(AfterTurnFacts facts,
                                     RelationshipSnapshot current);
}
```

`afterTurn` 只返回可解释的 `ApprovedRelationshipChange`，不自行写库。正式提交交给 `TurnCommitter`，防止 Relationship 已变化但 Turn 失败。

## 11. Outbox Module

```java
interface Outbox {
    void append(OutboxEvent event);
    List<OutboxEvent> loadAfter(EventCursor cursor, int limit);
}
```

`append` 由 `TurnCommitter` 的 SQLite Implementation 在当前事务中调用。其他业务 Module 不直接控制提交顺序。Publisher 不属于 Kernel 的业务决策，可放在 app。

## 12. Clock 与 ID

Kernel 不直接调用 `Instant.now()` 或散落 `UUID.randomUUID()`：

```java
interface ClockPort { Instant now(); }
interface IdGenerator { UUID next(); }
```

测试使用 `FixedClock` 和确定性 ID，避免时间等待和随机失败。

## 13. app module Adapter

```text
http/
  TurnController
  TaskController
  SseController
persistence/sqlite/
  SQLiteTurnRepository
  SQLiteConversationRepository
  SQLiteOutbox
model/
  OpenAiCompatibleModelAdapter
tool/
  CurrentTimeTool
  CalculateTool
task/
  LocalTaskExecutor
platform/
  LiveController
  ReadyController
  VersionController
  DrainController
```

Adapter 负责技术转换，不重新实现业务策略。

## 14. 错误模型

分类如下。实施排期见 [`33`](01-checklist.md) **0.2.1**：在该阶段收成全进程唯一的错误 code 与日志约定，之后各模块只引用，不各自另建。

```text
ValidationError       用户输入不合法
ConflictError         revision、幂等键或状态冲突
PolicyDenied          权限或预算不允许
DependencyUnavailable 模型、数据库或工具暂不可用
ExecutionFailed       已受控的执行失败
InternalDefect        不应发生的程序缺陷
```

错误必须有稳定 code，登记与日志约定只有一套；业务接口仍保留具名封闭结果，不合并成 `Result<Object, String>`。预期冲突使用结果，内部缺陷保留异常通道。

日志记录 code、操作类别、关联 ID、耗时与脱敏原因，区分数据库忙、约束冲突与内部缺陷；不直接输出异常对象、SQL、堆栈、密钥或敏感正文。用户返回只含稳定 code 与安全文案，不能一边吞原因一边将所有失败标为可重试。


## 状态机与持久化


K03-P 已放行，见 [放行记录](../reviews/02-release-h3.md)。本文仍是持久化契约：T1—T7 对应的实现已经在代码里，后续阶段不能绕过。排期与关闭状态以 [实施清单](01-checklist.md) 为准。缺陷定义见 [01-defects.md](../reviews/01-defects.md)。

## 1. 总原则

- 数据库保存已经发生的事实和当前可恢复状态。
- 状态转换必须由领域方法完成，不允许任意字符串更新。
- 每个可并发修改的聚合带 `revision`。
- revision 防止旧版本覆盖，不能替代会话归属、执行 owner 或合法源/目标状态校验。
- 外部调用不放在长数据库事务内。
- 任何“已向用户承诺”的结果都必须有已提交记录。

## 2. Turn 状态机

| 当前状态 | 事件 | 下一状态 | 条件 |
|---|---|---|---|
| RECEIVED | claim | CLAIMED | revision 匹配、无有效 owner，新 executionId 独立且 expiresAt > now |
| CLAIMED | start | RUNNING | executionId 匹配且 claim 未过期 |
| RUNNING | beginCommit | COMMITTING | owner/revision/lease 校验成功，最终提交计划与本次转换同事务持久化 |
| COMMITTING | commit | COMPLETED | 冻结 executionId、提交计划与 revision 匹配；Message 与必需完成事件同事务成功 |
| RECEIVED/CLAIMED/RUNNING | cancel | CANCELLED | 尚未进入不可取消提交 |
| CLAIMED/RUNNING | fail | FAILED | 错误已分类并记录 |

禁止：

- `COMPLETED → RUNNING`；
- `FAILED → COMPLETED`；
- 无 claim 的 `RECEIVED → RUNNING`；
- 通过修改数据库字符串强行跳状态。
- 经公开 `reconstitute` 拼新快照再 `save` 绕过状态转换；通用 save 不得写 COMPLETED 或从终态复活。

恢复中断的非终态 Turn 时，按持久化 step 判断是否可重试，再以明确恢复迁移和 CAS 创建独立 execution claim 或终止。FAILED/CANCELLED/COMPLETED 不原地复活；已保存历史 step 不改写。恢复命令不能把旧 owner 身份替换成当前 owner 后原样提交迟到结果。

### 执行身份与提交区（T2/T7）

- `executionId` 标识一次尝试，不能对所有尝试固定为 `local-primary`；节点身份与尝试身份分开。
- P-B 先建立可恢复提交协议：短事务内校验 owner/revision/lease，保存版本化最终计划并迁移 COMMITTING。可利用 FINALIZE step 保存计划；它必须包含重建提交所需事实，不能只是调试 trace 或“已有结果”的布尔值。
- 保存的计划绑定进入 COMMITTING 后的 revision 和冻结 executionId；恢复提交加载此计划，不接受旧执行者传来的另一份 Outcome，不重新调用模型/工具。
- 合法进入 COMMITTING 后不普通取消、不重新 claim。lease 时间推进不自动废弃冻结计划；瞬时数据库忙在有界重试后留下可恢复事实。计划损坏或永久约束失败必须显式报错并保留诊断/恢复入口，不能无限后台重试或偷偷改回 RUNNING。
- 取消与 beginCommit 在持久化 CAS 边界决胜；不能仅在内存检查取消标记后无条件提交。
- 当前 `TurnRepository.save` 已检查合法源/目标和 owner，并用调用方传入的 `now` 对照库中 `claim_expires_at`，不再只看 revision，也不再信任对象自己的 `updatedAt`。`RUNNING → COMMITTING` 不能经通用 `save`；必须经 `TurnCommitter.freezeCommit` 与可恢复完成计划同事务写入。

## 3. BackgroundTask 状态机

| 当前状态 | 事件 | 下一状态 |
|---|---|---|
| CREATED | enqueue | READY |
| READY | dispatch | RUNNING |
| RUNNING | waitExternal | WAITING |
| WAITING | conditionMet | READY |
| RUNNING | resultAccepted | SUCCEEDED |
| RUNNING | retryableRunFailed | READY |
| READY/RUNNING/WAITING | cancelRequested | CANCEL_REQUESTED |
| CANCEL_REQUESTED | allRunsStopped | CANCELLED |
| RUNNING | retryExhausted | FAILED |

Task 保存目标和最终状态，Run 保存每次尝试。不能为了重试把同一 Run 的 FAILED 改回 RUNNING。

## 4. SubAgentRun 状态机

```text
CREATED → LEASED → RUNNING → SUCCEEDED
                         ├→ FAILED
                         ├→ CANCELLED
                         └→ LOST
```

lease 至少保存：

```text
executorId
leaseTokenHash
leaseExpiresAt
attemptNumber
```

v0.2 单核节点的 `executorId` 可以固定为 `local-primary`，但状态含义不得省略。

结果必须绑定 taskId、runId、attempt 与该次 lease token；revision 正确或 executorId 相同不等于该尝试仍有效。旧/LOST/已取消尝试的迟到结果不得推进新尝试，重复回报不重复交付。

## 5. ToolOperation 状态机

```text
PROPOSED → VALIDATED → STARTED → SUCCEEDED
                           ├──→ FAILED
                           └──→ UNKNOWN
```

`UNKNOWN` 表示外部可能已经执行，但本系统无法确认。只有工具 Adapter 能通过相同 `operationId` 查询确定结果时，才允许自动恢复。

## 6. 表结构建议

以下是字段语义，不是可直接复制的最终 SQL；实施时应通过 migration 明确 SQLite 类型、约束和索引。

### conversation

```text
id                 TEXT PK
title              TEXT NULL
status             TEXT NOT NULL
revision           INTEGER NOT NULL
created_at         TEXT NOT NULL
updated_at         TEXT NOT NULL
```

### message

```text
id                 TEXT PK
conversation_id    TEXT NOT NULL FK
turn_id            TEXT NULL FK
role               TEXT NOT NULL
content_json       TEXT NOT NULL
sequence_no        INTEGER NOT NULL
created_at         TEXT NOT NULL
UNIQUE(conversation_id, sequence_no)
```

内容使用版本化 JSON envelope，避免把未来 tool message 强塞成纯文本。

### turn

```text
id                    TEXT PK
conversation_id       TEXT NOT NULL FK
client_request_id     TEXT NOT NULL UNIQUE
status                TEXT NOT NULL
input_message_id      TEXT NOT NULL FK
output_message_id     TEXT NULL FK
execution_id          TEXT NULL
claim_expires_at      TEXT NULL
revision              INTEGER NOT NULL
error_code            TEXT NULL
created_at            TEXT NOT NULL
updated_at            TEXT NOT NULL
completed_at          TEXT NULL
```

### turn_step

```text
id                    TEXT PK
turn_id               TEXT NOT NULL FK
step_no               INTEGER NOT NULL
kind                  TEXT NOT NULL
request_json          TEXT NULL
result_json           TEXT NULL
status                TEXT NOT NULL
started_at            TEXT NOT NULL
finished_at           TEXT NULL
UNIQUE(turn_id, step_no)
```

`kind` 示例：MODEL_CALL、TOOL_CALL、BACKGROUND_HANDOFF、FINALIZE。

### tool_operation

```text
operation_id          TEXT PK
turn_id               TEXT NULL FK
run_id                TEXT NULL FK
tool_name             TEXT NOT NULL
arguments_digest      TEXT NOT NULL
status                TEXT NOT NULL
result_json           TEXT NULL
error_code            TEXT NULL
started_at            TEXT NULL
finished_at           TEXT NULL
```

相同 operationId 携带不同工具名、arguments digest 或来源 Turn/Run 必须拒绝。合法恢复沿用原绑定；不能把另一回合的同参数调用当成自己的结果。

### background_task

```text
id                    TEXT PK
origin_turn_id        TEXT NOT NULL FK
status                TEXT NOT NULL
task_type             TEXT NOT NULL
input_json            TEXT NOT NULL
retry_policy_json     TEXT NOT NULL
revision              INTEGER NOT NULL
created_at            TEXT NOT NULL
updated_at            TEXT NOT NULL
completed_at          TEXT NULL
```

### sub_agent_run

```text
id                    TEXT PK
task_id               TEXT NOT NULL FK
attempt_no            INTEGER NOT NULL
status                TEXT NOT NULL
executor_id           TEXT NOT NULL
lease_token_hash      TEXT NULL
lease_expires_at      TEXT NULL
result_json           TEXT NULL
evidence_json         TEXT NULL
error_code            TEXT NULL
created_at            TEXT NOT NULL
updated_at            TEXT NOT NULL
UNIQUE(task_id, attempt_no)
```

### memory_record

```text
id                    TEXT PK
subject_key           TEXT NOT NULL
kind                  TEXT NOT NULL
content_json          TEXT NOT NULL
sensitivity           TEXT NOT NULL
source_turn_id        TEXT NOT NULL FK
status                TEXT NOT NULL
valid_from            TEXT NULL
valid_until           TEXT NULL
supersedes_id         TEXT NULL FK
revision              INTEGER NOT NULL
created_at            TEXT NOT NULL
```

### relationship_state

```text
companion_identity_id TEXT PK
state_json            TEXT NOT NULL
reason_json           TEXT NOT NULL
source_turn_id        TEXT NULL FK
revision              INTEGER NOT NULL
updated_at            TEXT NOT NULL
```

### outbox_event

```text
id                    TEXT PK
aggregate_type        TEXT NOT NULL
aggregate_id          TEXT NOT NULL
event_type            TEXT NOT NULL
payload_json          TEXT NOT NULL
sequence_no           INTEGER NOT NULL UNIQUE
created_at            TEXT NOT NULL
```

不要把“是否已经被某个浏览器看到”写成全局布尔值。不同连接通过各自 cursor 判断。

## 7. 索引

至少检查：

```text
turn(client_request_id)
turn(conversation_id, created_at)
turn(status, claim_expires_at)
message(conversation_id, sequence_no)
background_task(status, updated_at)
sub_agent_run(task_id, attempt_no)
sub_agent_run(status, lease_expires_at)
memory_record(subject_key, status)
outbox_event(sequence_no)
```

索引不是越多越好；每个索引都要能说出对应查询。先检查 UNIQUE 自动建立的索引，避免再建同列同顺序索引；已发布版本通过后续 migration 调整并提供查询计划依据。

## 8. 事务边界

最终提交统一经过：

```java
TurnCommitter.commit(CommitTurnPlan plan)
```

`CommitTurnPlan` 携带 expectedExecutionId、所有 expected revision 和待提交事实。调用者不能取得通用事务对象后自行决定 Repository 顺序，也不能自行分配权威数据库序号。

### 接收 Turn

同一事务：

```text
检查全局 clientRequestId 与 conversationId、原始正文的绑定
同会话同正文回放；异会话或异正文冲突
在写事务内分配会话消息序号并创建用户 Message
创建 Turn(RECEIVED)
提交
```

原始正文只做空白/格式/长度校验，不 trim 后覆盖。新生成的 turnId/messageId 不属于重试身份；不能因重试生成新 ID 就再创建 Turn。并发唯一键竞争回滚后，在新事务重读原绑定；BUSY 重试有总上限，不把所有 SQL 异常当作成功回放。

### 完成 Turn

同一事务：

```text
校验冻结 execution owner、最终计划与 revision
在写事务内分配会话消息序号并创建助手 Message
提交 MemoryRuntime/RelationshipRuntime 返回的 ApprovedChange
Turn → COMPLETED
构造匹配本 Turn/助手消息的必需完成事件，事务内分配全局序号并追加
提交
```

必需完成事件由 TurnCommitter 保证；调用方提供的附加事件不能替代它。允许附加列表为空不等于允许 COMPLETED 没有完成事件。若仍由计划携带必需事件，必须校验类型、aggregateId 与消息引用，不能仅检查列表非空。

### 后台化

同一事务：

```text
校验冻结 execution owner、最终计划与全部 expected revision
提交 TaskRuntime.prepare 返回的 TaskDraft 为 BackgroundTask(CREATED)
创建“任务已接受” Message
Turn → COMPLETED
追加匹配确认消息的必需完成事件（序号仍由事务分配）
提交
```

Task 从 CREATED 变为 READY、Run 创建、lease、retry 和 cancel 由 `TaskRuntime` 管理；事务后的调度器只调用 `TaskRuntime.dispatchNext`，不自行跳状态。

### CommitTurnPlan 最低内容

```text
turnId
expectedExecutionId
expectedTurnRevision
expectedMemoryRevisions（有记忆更新时，或具体 change 自带）
expectedRelationshipRevision（有关系变化时）
assistantMessage
approvedMemoryChanges
approvedRelationshipChange（可选）
taskDraft（可选）
additionalOutboxEvents（可空；不免除 committer 生成必需完成事件的责任）
```

SQLite `TurnCommitter` Implementation 必须先校验全部前置条件，再执行写入；任一校验或写入失败则整体回滚。

Memory/Relationship 在 **0.2.3**、Task 在 **0.2.5** 接入具体不可变类型；在各自阶段以前继续拒绝非空占位。正式 change 需验证来源/身份/作用域和全部 revision。冲突不能通过替换成最新 revision 强行覆盖。

### 消息顺序与 Outbox cursor（T3）

序号分配与事实写入在同一数据库写事务中完成，不能由 HTTP 客户端、Loop 或进程内计数器决定；禁止事务外 `MAX+1`。允许跳号，但不允许游标已经看到更大序号后，又提交更小序号。Outbox 清理后不复用历史序号；UUID 去重身份与有序 cursor 分工不同。

v0.2 按 [实施清单](01-checklist.md) 同会话串行执行、跨会话有界并行；不要靠限制为一个线程代替数据库约束。逆序完成、重复提交、事务回滚必须单独验证。

## 9. 不应放进事务的操作

- 云模型请求；
- HTTP 工具调用；
- 文件处理；
- 等待后台任务；
- SSE 网络发送。

这些操作前后用持久 step 和 operation 状态衔接。

## 10. 崩溃恢复矩阵

| 崩溃位置 | 数据状态 | 重启动作 |
|---|---|---|
| Turn 创建前 | 无记录 | 客户端可重试 |
| Turn 已创建、未 claim | RECEIVED | 调度器重新 claim |
| 模型调用中 | RUNNING step 未完成 | 根据预算创建新 step 或失败，不假装已有结果 |
| 工具返回后、结果未落库 | ToolOperation 可能 STARTED | 查询 operationId；无法确认则 UNKNOWN |
| 最终计划持久化前 | 无正式回答，可能有未完成外部调用 | 根据 step/operation/owner 判断是否能重试，不默认重跑 |
| 已进入 COMMITTING、完成事务提交前 | 冻结 owner + 可恢复最终计划，无正式回答 | 加载计划提交；不重跑模型/工具，不随意重新 claim |
| 完成事务提交后、SSE 前 | COMPLETED + outbox | 只补发事件，不重新执行 |
| Run lease 中进程退出 | lease 未续期 | 到期后标 LOST，新建下一 attempt |

## 11. SQLite 配置

启动时验证：

```text
PRAGMA foreign_keys = ON
PRAGMA journal_mode = WAL
PRAGMA busy_timeout = 有限毫秒数
```

不要永久无限重试 `SQLITE_BUSY`。使用短暂、带抖动且有总上限的重试，超过后返回明确依赖忙错误。

## 12. Migration

规则：

- 文件按 `V001__initial.sql` 递增；
- 已发布 migration 不修改，只新增下一号；
- 每次启动先校验 checksum；
- migration 前执行一致性备份；
- 校验/备份/迁移失败不淘汰旧有效备份；成功后才按保留策略清理已认领的成功快照；
- history 表存在不等于已有成功版本：空 history、首次迁移失败后重启必须有测试，不吞掉错误继续；
- 破坏性字段删除不进入仍需回退的版本窗口；
- 测试必须从空库完整运行全部 migration。

## 13. 备份与恢复

使用 SQLite 一致性备份能力或短暂停写后的受控备份，不直接复制活跃主文件。备份至少带：

```text
createdAt
schemaVersion
applicationBuildId
databaseDigest
```

恢复演练必须在独立临时目录进行，不能覆盖当前数据。验证 migration 版本、完整性检查、Conversation 数量和一条完整 Turn 链。

自动清理只认领受控命名、可解析自有 manifest、实际数据库与成功标记共同成立的目录。仅含 `wannian.db` 或 JSON 不足以认领；手工目录、残缺快照、未知来源目录必须保留。删除前验证实际路径仍位于备份根内，拒绝链接/junction 绕出边界。

摘要使用固定缓冲流式计算，不将整个库读进堆；metadata 用 JSON 序列化器，不手拼不完整转义。正常重启是否备份与保留淘汰规则须明确，不因反复启动失败丢失最后可用恢复点。

打包运行的数据目录采用固定绝对路径，独立于 cwd 与制品目录；只拦 `target` 路径组件不能证明已满足此要求。现行 **0.2.6**（别名 K06）验证从两个 cwd 打开同库，现行 **0.2.7**（别名 K07）再做真正进程重启和故障恢复。
