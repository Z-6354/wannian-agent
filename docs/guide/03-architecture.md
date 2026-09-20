# 03 · 架构与设计模式

前半是单节点架构说明，后半是设计模式审查。状态与持久化规则见 [04-kernel-reference.md](./04-kernel-reference.md)。进度以 [01-checklist.md](./01-checklist.md) 为准。

## 架构说明

## 1. 为什么先做单节点

0.1 要先证明烟火能够稳定完成普通 Agent 工作。如果 Turn、工具幂等、记忆提交和后台任务在一个进程里都不可靠，增加三台机器只会放大故障。

单节点不等于一次性原型。正确的单节点实现把“业务含义”和“执行位置”分开，因此 0.2 只需增加 Adapter 和控制面。

## 2. 三个交付单元

### api

保存跨层或对外稳定的值类型与协议。它应当很小，不包含业务编排。

### kernel

保存产品真正的行为：Turn、Agent Loop、Task、Memory、Relationship、Tool policy。它不认识 HTTP、SQLite、Spring Controller 或具体模型厂商。

### app

把外部世界接到 Kernel：HTTP、SSE、SQLite、模型 SDK、配置、工具实现和进程生命周期。

## 3. 深 Module

以 `TurnEngine` 为例。调用者只需要学会一个较小的 Interface：

```text
submit(command) → TurnReceipt
resume(turnId) → TurnResult
cancel(turnId) → CancelResult
```

其 Implementation 隐藏状态转换、幂等、Agent Loop 和错误映射；最终原子写入再委托给 `TurnCommitter`。这比让 Controller 或 TurnEngine 分别调用十个 Repository 更深，也让错误集中在一个地方修复。

## 4. 关键 seam

只有实际需要两种 Adapter 的地方才建立 seam：

| Seam | 0.1 Adapter | 测试 Adapter | 0.2 Adapter |
|---|---|---|---|
| ModelPort | CloudModelAdapter | FakeModel | 仍可复用 |
| ToolRuntime 内部 ToolAdapter seam | LocalToolAdapter | FakeToolAdapter | RemoteToolAdapter |
| TaskExecutor | LocalTaskExecutor | ManualTaskExecutor | RemoteTaskExecutor |
| ClockPort | SystemClock | FixedClock | 仍可复用 |
| Repositories | SQLite Adapter | InMemory/SQLite test | 可迁移其他存储 |

只有一个实现且没有测试替代需求的内部类，不要为了“架构漂亮”全部抽 Interface。

## 5. Turn、Task、Run 为什么不同

`Turn` 回答用户当前说的话；`BackgroundTask` 表示要完成的长期工作；`SubAgentRun` 表示一次执行尝试。

```text
Turn T1 创建 Task B1 后完成
Task B1 第一次执行 Run R1 失败
Task B1 第二次执行 Run R2 成功
Kernel 创建新的交付事件告诉用户结果
```

如果把三者合成一个对象，就无法同时表达“原聊天已结束”“任务仍存在”“第一次尝试失败但第二次成功”。

## 6. SSE 为什么不是业务状态

回答提交到数据库时，业务已经完成。SSE 只是交付渠道：

```text
COMPLETED Turn + 未投递 outbox
```

是完全合法的状态。客户端重连后继续读取即可。如果把断线解释为 Turn 失败，就可能重复模型调用和工具副作用。

## 7. 模型不是权威提交者

模型只能输出建议：

```text
FinalAnswer
ToolCallProposal
MemoryCandidate
RelationshipCandidate
BackgroundTaskProposal
```

Kernel 用确定性规则验证后才能提交。不能让模型生成 SQL、状态名或任意 Java 方法名后直接执行。

## 8. operationId 的意义

网络超时只能说明调用者没有得到结果，不能证明工具没有执行。稳定 `operationId` 让工具能够回答：

```text
这个操作从未执行
这个操作已经成功，结果是 X
这个操作正在执行
这个操作结果未知，禁止自动重试
```

0.1 就使用该语义，0.2 节点失联后才能安全重派任务。

## 9. 事务与 outbox

下列内容必须一起提交：

```text
Turn 最终状态
助手 Message
允许的 Memory/Relationship 变化
OutboxEvent
```

不能先提交消息再写事件，也不能先推 SSE 再提交数据库。事务内只写本地数据库，不在持锁期间调用模型或远程工具。

这里使用领域化 Unit of Work 思想：`TurnCommitter.commit(CommitTurnPlan)` 是唯一最终提交入口。不要暴露一个允许调用者任意拼接 Repository 的通用事务对象。

## 10. 数据目录与 artifact 分离

```text
application artifact：可删除、可替换
data directory：用户状态，必须备份
workspace：任务临时文件，可隔离清理
logs：诊断信息，有保留周期
```

把这些目录混在一起，会使未来 Guardian 回退程序时误删人生数据，或使任务清理误删稳定版本。

## 11. 0.2 如何扩展

0.1：

```text
TaskRuntime → TaskExecutor → LocalTaskExecutor
ToolRuntime → LocalToolAdapter
```

0.2：

```text
TaskRuntime → PlacementPolicy → LocalTaskExecutor | RemoteTaskExecutor
ToolRuntime → ToolAdapterRegistry → LocalToolAdapter | RemoteToolAdapter
```

TurnEngine 和 AgentLoop 不应该知道差异。Guardian 则完全位于进程外，只使用 `live/ready/version/drain`。

TaskExecutor 是 `TaskRuntime` 的内部 seam；AgentLoop 不直接调度 Run。ToolAdapter 也是 `ToolRuntime` 的内部 seam；AgentLoop 只处理 `ToolExecutionOutcome`。

## 12. SQLite 的边界

SQLite 足以支持 0.1 单用户、单写入节点。它不提供国内整机故障后的实时接管。0.2 的远程 Worker 不直接写 SQLite；只有国内 Kernel 提交权威状态。

若未来要求美国 Replica 接管最新状态，需另行设计复制与恢复，不能把 SQLite 文件放进共享网络目录假装多节点数据库。

## 13. 初学者判断代码放哪的方法

问四个问题：

1. 这是稳定协议或值类型吗？放 `api`。
2. 这是烟火的业务决定吗？放 `kernel`。
3. 这是数据库、HTTP、SDK 或操作系统细节吗？放 `app` Adapter。
4. 只是为了未来可能用到吗？现在先不要创建。

如果一个类同时导入 Controller、SQLite 和模型 SDK，又决定记忆规则，说明多个责任被揉在了一起。



## 设计模式审查


`date`: 2026-09-18  
`status`: **design-review** — 深 Module 与扩展能力；不授权实施。版本档次以 [产品概览](../product/01-overview.md) 为准。

2026-09-19：下面模式示例不是可绕过事务的完整 API。owner、冻结提交计划、必需完成事件与序号规则以 [内核参考](04-kernel-reference.md) 为准。历史 H3 已放行，进度见 [实施清单](01-checklist.md)。

## 1. 总体结论

当前方案的总体方向正确：`app → kernel → api`、Ports and Adapters、不可变命令/结果、outbox、幂等键和本地/远程执行 seam 都能支撑 0.1 向 0.2 演进。

但文档中仍有四处容易形成“Interface 很多、行为分散”的浅 Module：

1. Agent Loop 需要自己依次调用 ToolCatalog、Validator、Policy、OperationStore、Executor 和 Sanitizer；
2. Turn 完成事务横跨多个 Repository、Memory、Relationship、Task 和 Outbox；
3. Memory/Relationship 暴露 `evaluate` 与 `commit`，调用者可能漏步骤或错误排序；
4. BackgroundTask 创建、Run 调度、lease 与重试可能散落在 TurnEngine、dispatcher 和 executor。

实施前应通过四个深 Module 收口：

```text
ToolRuntime
TurnCommitter
MemoryRuntime / RelationshipRuntime
TaskRuntime
```

这样做不会增加功能，而是减少调用者必须知道的顺序与错误处理。

## 2. 已经适用且应保留的模式

### 2.1 Ports and Adapters

适用位置：

- `ModelPort`：真实云模型 Adapter + FakeModel；
- `TaskExecutor`：Local Adapter + 0.2 Remote Adapter；
- 持久化：SQLite Adapter + 测试 Adapter；
- `ClockPort`：SystemClock + FixedClock。

价值：Kernel 不依赖 SDK、网络、SQLite 和执行设备。0.2 增加远程节点时只增加 Adapter。

注意：并非每个类都需要 port。只有生产与测试确实需要替换，或 0.2 已明确存在第二 Adapter 时才建立 seam。

### 2.2 Strategy

适合表达真正会变化的决策：

```text
BackgroundPolicy
MemoryPolicy
RelationshipPolicy
ContextSelectionPolicy
ToolVisibilityPolicy
RetryPolicy
```

Strategy 应返回决策值，不直接写数据库。例如：

```java
MemoryDecision evaluate(MemoryCandidate candidate, MemoryPolicyContext context);
```

不适合把每个 if 都拆成 Strategy。首版只有一种固定规则的局部判断留在 Module 内部即可。

### 2.3 Command

适用：

```text
SubmitTurnCommand
CancelTurnCommand
CreateBackgroundTask
ToolInvocation
ApprovedMemoryChange
```

Command 必须是不可变数据，表达“想做什么”，而不是携带 Runnable、Bean 或打开的文件句柄。这样 0.2 才能序列化并跨节点传输。

### 2.4 Repository

适用于聚合持久化，但 Interface 应体现领域语义：

```text
findByClientRequestId
claim
save(expectedRevision)
```

不要创建通用 `BaseRepository<T>` 或把 `save/findAll/delete` 当成全部 Interface；这会泄漏数据库操作并绕过状态机。

### 2.5 Transactional Outbox

这是可靠交付的核心模式：业务状态与 OutboxEvent 同事务提交，SSE 只投递已提交事件。必须保留，不应换成进程内 Observer。

### 2.6 Decorator

非常适合横切能力：

```text
ModelPort
  → TimeoutModel
  → RetryModel
  → MeteredModel
  → CloudModelAdapter

ToolRuntime
  → TracedToolRuntime
  → MeteredToolRuntime
  → DefaultToolRuntime
```

Decorator 可以让重试、超时、指标和 trace 不污染 Agent Loop。顺序必须在装配处固定并有测试，避免重复重试。

### 2.7 Null Object

`FailureInjector.noop()`、无可用工具的空目录或未启用的可选观察器可以使用 Null Object，避免业务代码散落 null 判断。Null Object 不能用于隐藏必要依赖缺失；数据库或模型未配置应使 readiness 失败。

### 2.8 Bulkhead、Circuit Breaker 与有界 Retry

它们属于可靠性模式，放在外部 Adapter 周围：

- 模型并发隔离；
- HTTP 工具并发隔离；
- 短暂限流的有限重试；
- 连续失败时短期开路。

Agent Loop 不应自己维护厂商熔断状态。副作用工具默认不自动 Retry。

## 3. P0 · 将工具处理链深化为 ToolRuntime

### 当前问题

28 号文档要求调用者了解以下顺序：

```text
Catalog → Validator → Policy → Budget → OperationStore
→ Executor → Sanitizer → OperationStore.complete
```

这是 Chain of Responsibility 的形状，但若 Agent Loop 逐个调用，复杂度就泄漏到了调用者：任何新调用点都可能漏掉 policy、幂等或结果清理。

### 推荐 Interface

```java
public interface ToolRuntime {
    ToolExecutionOutcome execute(ToolInvocation invocation,
                                 ToolExecutionContext context);
}
```

`DefaultToolRuntime` 内部拥有：

```text
ToolCatalog
ToolCallValidator
ToolPolicy
ToolBudget
ToolOperationStore
ToolAdapterRegistry
ToolResultSanitizer
```

Agent Loop 只调用一次并处理封闭结果：

```text
Succeeded
Rejected
Failed
Unknown
```

### 模式判断

内部可以使用固定 Pipeline 或 Chain of Responsibility；外部不暴露链节点。这样 ToolRuntime 是深 Module，而不是八个浅 Module 的集合。

## 4. P0 · 使用 TurnCommitter 收口事务

### 当前问题

完成 Turn 时需要同时保存 Message、Turn、Memory、Relationship、Task 和 Outbox。若 TurnEngine 依次调用多个 Repository，事务顺序、expected revision 和失败回滚会散落。

### 推荐模式

使用 Unit of Work 思想，但不要设计通用 ORM 式 `UnitOfWork`。建立领域化的深 Module：

```java
public interface TurnCommitter {
    CommitTurnResult commit(CommitTurnPlan plan);
}
```

`CommitTurnPlan` 是纯数据：

```text
turnId
expectedExecutionId
expectedTurnRevision
expectedRelationshipRevision
assistantMessage
approvedMemoryChanges
approvedRelationshipChange
backgroundTaskDraft
additionalOutboxEvents（必需完成事件由 committer 保证）
```

SQLite Implementation 在一个事务内校验 owner、revision、计划与来源并提交。TurnEngine 不接触事务 runner，不决定各表写入顺序或分配数据库序号。完成事件不能省略；唯一序号不等于提交顺序，分配须与写入同事务。

### 收益

- 原子性集中；
- 更容易做崩溃测试；
- 未来切换存储不会重写 Agent Loop；
- 避免每个 Repository 自己提交事务。

## 5. P0 · Memory/Relationship 的 evaluate 与 commit 解耦修正

### 当前问题

现有 Interface 同时暴露 `evaluate` 和 `commit`。调用者必须知道先 evaluate、检查 decision、转换为 change、再 commit；这是一条容易误用的浅工作流。

### 推荐形状

把纯决策与事务提交明确分开：

```java
public interface MemoryRuntime {
    MemoryContext recall(RecallRequest request);
    MemoryEvaluation evaluate(List<MemoryCandidate> candidates,
                              MemoryPolicyContext context);
}
```

```java
public interface RelationshipRuntime {
    RelationshipContext beforeTurn(BeforeTurnRequest request);
    RelationshipEvaluation afterTurn(AfterTurnFacts facts,
                                     RelationshipSnapshot current);
}
```

二者只返回 `ApprovedChange`，正式持久化统一交给 `TurnCommitter`。这样不会发生 Memory 已提交但 Turn 最终失败的半提交状态。

这不是弱化 Memory Module；策略仍由用户实现，只是移除其自行开启事务的能力。

## 6. P0 · 使用 TaskRuntime 收口任务编排

### 当前问题

如果 `BackgroundTaskService`、dispatcher、lease scanner、retry policy 和 `TaskExecutor` 分别对外暴露，TurnEngine 或定时器必须理解过多状态。

### 推荐 Interface

```java
public interface TaskRuntime {
    TaskDraft prepare(TaskProposal proposal, OriginTurn origin);
    DispatchOutcome dispatchNext(DispatchBudget budget);
    CompletionOutcome acceptResult(SubAgentRunResult result);
    CancelTaskResult requestCancel(BackgroundTaskId id);
}
```

外部调度器只周期调用 `dispatchNext`；TaskRuntime 内部管理 lease、attempt、retry 和合法状态转换。`TaskExecutor` 保持内部 seam：0.1 Local，0.2 Remote。

TaskDraft 通过 TurnCommitter 与确认回复一起落库，避免“先回复接受、后创建任务”。

## 7. P1 · Agent Loop 使用 Orchestrator，不使用继承式 Template Method

Agent Loop 确实有固定步骤，但不建议：

```java
abstract class AbstractAgentLoop {
    protected abstract ...
}
```

继承会把用户核心逻辑绑定到框架生命周期，未来难以组合不同模型或策略。推荐一个显式 Orchestrator，注入 Strategy 和深 Module：

```text
DefaultAgentLoop
├─ ModelPort
├─ ToolRuntime
├─ BackgroundPolicy
└─ AgentBudget
```

用户实现 Loop 的流程；具体模型、工具和后台策略可替换。这是组合优于继承。

## 8. P1 · 状态机使用显式转换，不使用 class-per-state

Turn、Task、Run 状态有限且主要由持久化驱动。GoF State 模式为每个状态创建一个类会增加文件和跳转成本，不适合初学者首版。

推荐：

```java
Turn claim(ExecutionClaim claim)
Turn start()
Turn beginCommit()
Turn fail(Failure failure)
```

每个方法在聚合内检查合法状态并返回新对象或受控结果。数据库 Adapter 使用 expected revision 保存。

完成 Turn 只通过 TurnCommitter 原子提交，不提供供调用者自行完成再 save 的旁路。公开快照重建不能绕过合法源/目标状态校验；CAS 不替代 owner 与状态机规则。

当未来某个状态拥有大量独立行为时再局部引入 State pattern，不提前设计。

## 9. P1 · ModelPort 通过 Decorator 扩展

`ModelPort` 本身保持一个 `decide`。以下能力不得加入 Agent Loop：

```text
TimeoutModelPort
RateLimitedModelPort
RetryingModelPort
MeteredModelPort
FallbackModelPort（后续）
```

装配顺序示例：

```text
AgentLoop
→ Metered
→ RateLimited
→ Retrying（仅可安全请求）
→ Timeout
→ VendorAdapter
```

必须防止 SDK 自带重试与外层重试相乘。

## 10. P1 · 扩展使用 Adapter，不使用条件分支

（原「0.2 扩展」：指日后能力用 Adapter 挂上。现行产品 **v0.3 / 更后** 才加多节点与世界树；勿在 v0.2 单核 harness 里用 if 版本分支堆空实现。）

错误方向：

```java
if (nodeType == LOCAL) { ... } else if (nodeType == REMOTE) { ... }
```

推荐：

```text
TaskRuntime → TaskExecutor
                  ├─ LocalTaskExecutor
                  └─ RemoteTaskExecutor

ToolRuntime → ToolAdapterRegistry
                  ├─ LocalToolAdapter
                  └─ RemoteToolAdapter
```

PlacementPolicy 在 0.2 决定选哪个 executor/adapter；Agent Loop 不出现 nodeId、IP 或 mTLS。

## 11. P1 · 错误使用封闭结果，而不是异常控制正常流程

预期业务结果建议使用 sealed hierarchy：

```text
ToolExecutionOutcome
TaskDispatchOutcome
MemoryDecision
AgentOutcome
```

编程缺陷、数据库连接断开等异常仍使用异常通道。不要把所有错误都变成 `Result<Object, String>`，也不要让厂商异常穿过 seam。

## 12. 可以用，但暂不需要的模式

| 模式 | 判断 |
|---|---|
| Specification | Memory/Tool policy 规则多到需要组合时再引入；首版可用明确函数 |
| Factory | ID、operationId 和厂商 Adapter 构造可用；不建抽象工厂家族 |
| Composite | 未来工具组/策略树可能使用；首版没有必要 |
| Saga/Process Manager | 跨节点长期升级流程适合 0.2；普通 v0.1 TaskRuntime 足够 |
| Event Sourcing | 不采用；保留 outbox 和审计事件即可 |
| CQRS | 不采用完整框架；查询 DTO 与命令模型自然分开即可 |
| Plugin | 不在 0.1；工具注册表不等于开放插件系统 |
| Builder | ModelRequest 构造复杂后可使用；简单 record 优先 |

## 13. 明确禁止的过度设计

- 每个类都配一个 Java interface；
- 为每个状态创建一个状态类；
- 通用 `BaseRepository`、`BaseService`、`BaseController`；
- 反射扫描任意工具并自动开放给模型；
- 事件总线替代清晰的直接调用；
- 用 in-process Observer 承担可靠交付；
- 把所有业务流程包装成通用工作流引擎；
- 为尚不存在的第三种 Adapter 预建多层工厂；
- 在 0.1 引入微服务、消息中间件或分布式事务；
- 让 Spring Bean 生命周期成为领域状态机。

## 14. 推荐后的 Module 图

```text
HTTP/SSE Adapter
      │
      ▼
TurnEngine ────────────────┐
  │                        │
  ├─ ContextAssembler      │
  ├─ AgentLoop             │
  │    ├─ ModelPort        │
  │    ├─ ToolRuntime      │
  │    └─ BackgroundPolicy │
  ├─ MemoryRuntime         │
  ├─ RelationshipRuntime   │
  ├─ TaskRuntime           │
  └─ TurnCommitter ◄───────┘
          │
          └─ SQLite Adapter + Outbox

TaskRuntime
  └─ TaskExecutor
       ├─ Local Adapter（0.1）
       └─ Remote Adapter（0.2）
```

每个外部调用点只学习一个较小 Interface；复杂顺序留在深 Module 内部。

## 15. 对 26—33 号文档的修订清单

以下定向修订已于 2026-09-18 写回原文：

1. `[已修订]` 28 号：用 `ToolRuntime` 替代 Agent Loop 直接调用工具处理链。
2. `[已修订]` 28/29 号：新增 `TurnCommitter` 和 `CommitTurnPlan`，明确统一事务。
3. `[已修订]` 28/31 号：Memory/Relationship 不自行 commit，返回 ApprovedChange。
4. `[已修订]` 28/29/33 号：用 `TaskRuntime` 隐藏 lease、retry 与 dispatcher 顺序。
5. `[已修订]` 30 号：Agent Loop 依赖 `ToolRuntime`，不依赖 Validator/Executor/OperationIdFactory 细节。
6. `[已修订]` 32 号：测试以四个深 Module 的 Interface 为主，不测试内部链节点。
7. `[已修订]` 33 号：K02 先实现 TurnCommitter；K04 实现 ToolRuntime；K07 实现 TaskRuntime。

## 16. 最终判断

采用上述修订后，0.1 可以保持初学者可理解，同时具备明确扩展路径：

- 换模型：新增 Model Adapter 或 Decorator；
- 加工具：注册 Tool Adapter，不修改 Agent Loop；
- 加远程节点：新增 Remote Task/Tool Adapter，不修改 Turn；
- 换数据库：替换 TurnCommitter/Repository Adapter，不修改领域策略；
- 增加 Guardian：调用外部生命周期接口，不进入 Kernel；
- 改记忆或关系规则：替换 Strategy，不修改 HTTP、SQLite 和 Task。

设计模式的目标不是增加类，而是让变化发生在一个 seam 后面，并让调用者少学规则。
