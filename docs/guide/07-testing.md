# 07 · 测试与故障练习

R01—R07、R10 的前置断言已在历史 H3 放行时执行，证据见 [放行记录](../reviews/02-release-h3.md)。R03 进程强杀、R10 整库堆峰值，以及 R08、R09、R11、R12，仍按本表的后续阶段执行，不能用那次放行代替。关闭状态只维护在 [实施清单](01-checklist.md)。缺陷定义见 [01-defects.md](../reviews/01-defects.md)。阶段列写 **0.2.x 正式号**；括号内为施工别名 / 更旧号。

## 0. 审查问题到后续阶段的回归矩阵

| 编号 | 来源 / 首次验收阶段 | 可控场景 | 必须断言，不只检查返回类型 |
|---|---|---|---|
| R01 | T1/T5；P-A，**0.2.1**/0.2.4 复验（K01/K04；旧 K03/K06） | A/B 会话同 key 同正文；同会话合法/异文重试；输入缩进/换行；双连接同 key 起跑 | B 不拿到 A 的回放；合法重试同一 Turn；原文逐字符相同；最多一份请求事实；新增 ID 不影响重试 |
| R02 | T2；P-B，**0.2.1**/0.2.5 复验（K01/K05；旧 K03/K07） | FixedClock 使 claim 到期；错误 owner 但正确 revision；取消与 beginCommit 在屏障后竞争 | 无效 claim 不落库；迟到者无正式写入；取消/进入提交仅一个胜者，拒绝方不改变三表 |
| R03 | T2；P-B，**0.2.1**/0.2.7 复验（K01/K07；旧 K03/K09） | 保存 COMMITTING 计划后丢弃对象、重新加载；最终 commit 后丢弃响应再重试 | 从同一冻结计划提交，不重跑模型；lease 推进不换 owner/卡死；一份助手消息和完成事件；**0.2.7** 再做真实进程强杀 |
| R04 | T3；P-C，**0.2.4**/0.2.7 复验（K04/K07；旧 K06/K09） | 空/无关附加事件；A/B 执行逆序完成；读取已提交 cursor 后再提交另一结果 | 每个成功完成都有匹配完成事件；晚提交事件能增量读取；回滚三表不变；序号不复用，不要求连续 |
| R05 | T7；P-B，**0.2.3**/0.2.5 复验（K03/K05；旧 K05/K07） | 公开 reconstitute/save 构造终态→RUNNING 或绕过 committer 写 COMPLETED | 持久化拒绝，status/revision/output/completedAt 等均不变；正常单步转换不受影响 |
| R06 | T4；P-D，**0.2.6**/0.2.7 复验（K06/K07；旧 K08/K09） | 手工目录混同名 DB/JSON；残缺快照；校验/备份/迁移失败；空 history 后重启 | 无关/残缺目录不删、不淘汰有效恢复点；备份失败不迁移；路径链接不能越界；完整恢复链可读 |
| R07 | T6；P-E，各阶段新增依赖时复验 | 隔离 fixture 引入 JDBC、Spring Context、SDK、反向依赖；合法依赖对照 | 违例确实触发门禁，合法生产依赖与测试 scope 通过；记录实际负例而非只贴规则 |
| R08 | T1/T2 类推；**0.2.2**（K02；旧 K04） | operationId 不变但工具/参数/来源改变；外部响应丢失；旧尝试迟到 | 冲突不调用 Adapter；合法重放沿用绑定；UNKNOWN 不盲重试；外部观察可留审计但不越权写正式结果 |
| R09 | T1/T2/T7 类推；**0.2.3**/0.2.5（K03/K05；旧 K05/K07） | sourceTurn 存在但身份/作用域错配；旧 revision change；旧 attempt 携带新 revision 回报 | 错配拒绝；合法跨会话记忆不误拒；全部事实原子回滚；LOST 不复活，重复结果不重复交付 |
| R10 | T8；P-D，**0.2.7** 复验（K07；旧 K09） | 含控制字符的 build-id；大文件摘要与受限堆 | metadata 可解析且值往返一致；固定缓冲内存有上限；摘要与可信基准一致，**0.2.7** 再测整个备份峰值 |
| R11 | 审查 §6.5/§6.6；**0.2.4**/0.2.6（K04/K06；旧 K06/K08） | 未认证/失效凭据读取 HTTP/SSE/internal；越界 cursor；两个 cwd 同绝对数据路径 | 不扩大可见范围；internal 按既定公开/受控策略响应；仍打开同库，未落制品目录；真实密钥不入输出 |
| R12 | T1—T8；**0.2.7**（K07；旧 K09） | 真实进程终止/重启、并发及 2 核 2 GB 等价资源限制 | 无双有效 owner、无重复副作用/回答、事件可补发；环境配置与 RSS/线程/队列/备份峰值有实际记录 |

矩阵中的并发使用屏障、可控执行器或明确故障点，时间使用 FixedClock，不靠 sleep 碰运气。所有场景只用临时数据库/目录，文件清理负例不得放进真实 backups。前置 R03 是存储/重加载测试，不冒充现行 **0.2.7**（K07；旧 K09）进程级恢复证据。

## 1. 测试分层

```text
kernel unit tests       快、无 Spring、无网络
adapter integration     SQLite、模型协议、HTTP/SSE
application scenario    从 HTTP 到数据库的完整场景
failure injection       崩溃、超时、重复请求、断线
resource verification   2 核 2 GB 等价环境
```

优先通过 Module Interface 测试行为，不为私有方法逐个写脆弱测试。

### 1.1 真人实机 / 产品路径验收（强制）

与「隔离单测里注入 Fake / 假 HostCapability」分开：凡宣称**真人实机、用户路径、收口证据**的验证，必须最接近真实用户环境。

| 要求 | 说明 |
|------|------|
| 默认 `wannian.model.mode=live` | **禁止**为「方便跑通」把实机验收改成 `fake`。缺密钥/外网 → 标明未就绪并停测，不得静默退 Fake 冒充通过。 |
| 本机能力由 harness 自证 | OS / PowerShell 等是否可用，以进程内 `LocalHostCapabilityDetector` → `HostCapabilitySet` 及管理 API（如 `GET /api/manage/agent/tools` 的 `hostCapabilities` / 工具三态）为准。**禁止**用 Agent shell 自行 `Get-Command pwsh` 等外挂探测当验收证据，也不得用外挂结论覆盖 harness。 |
| 配置与数据路径贴近默认 | 优先默认 `data-dir`、默认端口与真实 `wannian.json`；临时目录仅用于不污染用户数据的隔离实机冒烟，仍须 `mode=live`。 |
| Fake 仅限单测替身 | `FakeModelAdapter`、测试注入的假能力集可留在仓库，**不得**当作真人实机或 Loop/工具产品路径的通过证据。 |

Agent 执行「实际测试 / 实机验收」时：启动真实装配进程 → 调产品 API / 对话路径 → 以响应与落库为准；不得先用 shell 预判环境再改产品行为。

## 2. 测试工具

准备：

```text
FakeModel
FakeToolRuntime
FixedClock
SequenceIdGenerator
ManualTaskExecutor（供 TaskRuntime 内部 seam 使用）
FailureInjector
TemporarySQLiteDatabase
RecordedOutboxSubscriber
```

Fake 应能配置输出序列并保留调用历史，便于断言次数、参数和顺序。

测试以深 Module 的 Interface 为主要表面：`ToolRuntime`、`TurnCommitter`、`MemoryRuntime/RelationshipRuntime` 和 `TaskRuntime`。内部 Validator、Policy 链节点只在确有独立复杂算法时测试；不要为每个内部类复制一套行为测试。

## 3. Turn 领域单测与事务集成测试

状态转换可用纯领域单测；下列涉及接收、幂等、CAS、消息/事件写入的断言必须通过真实持久化适配器验证，不能只用 fake repository 证明数据库原子性。

至少覆盖：

1. 首次提交创建 Turn。
2. 相同幂等请求返回原 Turn。
3. 相同 key 不同正文返回冲突。
4. 无 owner 不能进入 RUNNING。
5. revision 不匹配拒绝保存。
6. 完成时 Message 与 outbox 同时产生。
7. 提交失败时全部回滚。
8. COMPLETED 不能重新运行。

在正常转换之外运行 R01—R05：跨会话误回放、错误 owner、缺完成事件、快照绕过都不会被“正常对象按顺序调用领域方法”的单测自动覆盖。

测试名描述行为：

```text
reuses_existing_turn_when_same_client_request_is_retried
rolls_back_message_when_outbox_append_fails
rejects_claim_when_revision_changed
```

## 4. Agent Loop 测试

覆盖直接回答、一次工具调用、非法工具、非法参数、预算耗尽、模型超时、工具 UNKNOWN、取消、超长 observation 和敏感 trace 清理。

## 5. Tool 幂等测试

通过 `ToolRuntime.execute` 测试完整行为，不绕过它直接调用内部 Adapter：

```text
相同 operationId + 相同参数 → 不重复副作用
相同 operationId + 不同参数 → 冲突
执行成功但响应丢失 → 可查询原结果
无法确认结果 → UNKNOWN，不自动再执行
```

首版工具无副作用，也要测试参数、超时和输出大小限制。

同时执行 R08，检查幂等身份的完整绑定，不重复历史 H2 只比较正文而漏掉归属的错误。

## 6. Task 测试

通过 `TaskRuntime` 测试 prepare、dispatch、acceptResult 与 cancel；`ManualTaskExecutor` 只替换内部执行 seam：

- Task 创建和确认回复同事务；
- Task READY 后创建 attempt 1；
- attempt 1 FAILED 后创建 attempt 2；
- attempt 1 永远保持 FAILED；
- lease 到期变 LOST；
- 取消请求阻止新 Run；
- SubAgentRun 无权直接创建用户 Message；
- TaskResult 验证失败不能标 SUCCEEDED。
- 同 executor 的旧 attempt/旧 lease token 携带正确 revision 仍被拒绝；
- 取消与完成竞争、重复结果回报、taskId/runId 错配见 R09，不仅测试正常 lease。

## 7. Memory 与 Relationship 测试

直接复用 31 号工作簿的决策表。使用 FixedClock。每项同时断言 decision 和正式存储是否变化。

## 8. SQLite 集成测试

每个测试使用独立临时目录：

1. 从空库执行全部 migration。
2. 验证 foreign key 与 WAL。
3. 写入完整 Conversation→Turn→Message→Outbox 链。
4. 关闭连接并重新打开。
5. 验证 revision compare-and-set。
6. 注入事务中途异常并验证回滚。
7. 执行一致性备份并恢复。

另对 `TurnCommitter.commit` 验证：任何 expected revision 冲突时，Message、Memory、Relationship、Task 与 Outbox 均不发生部分写入。

测试绝不能连接开发者真实数据目录。

备份/迁移还必须执行 R06/R10：现有 `keep-me/readme.txt` 不误删不足以证明安全，需要混入同名 DB/JSON 的手工目录。完整恢复包含 schema 版本、digest、integrity_check 与 Conversation→Turn→Message→Outbox 链。不得用“备份文件存在”替代恢复验收。

## 9. HTTP 与 SSE 场景

普通对话：

```text
POST Turn
→ 收到 turnId
→ SSE 收到 accepted/progress/completed
→ 查询 Conversation 得到正式消息
```

断线重连：

```text
接收 event 1、2
→ 断开
→ Turn 完成并产生 event 3、4
→ 使用 lastEventId=2 重连
→ 只收到 3、4
```

模型和工具调用次数不得增加。

增加 R04 的逆序完成游标用例以及 R11 的鉴权/可见范围用例。唯一序号不自动保证按提交顺序可重放；认证也必须覆盖 SSE 和补发，不能仅保护 POST。

## 10. 故障注入

使用测试专用 `FailureInjector`，生产默认 no-op。故障点：

```text
AFTER_TURN_CREATED
AFTER_MODEL_DECISION
AFTER_TOOL_RETURNED
BEFORE_FINAL_COMMIT
AFTER_COMMIT_PLAN_PERSISTED
AFTER_FINAL_COMMIT
BEFORE_OUTBOX_DELIVERY
AFTER_RUN_LEASED
```

每个故障点执行：触发异常或终止、重启、运行恢复、检查数据库和调用记录。

`AFTER_COMMIT_PLAN_PERSISTED` 必须已经处于 COMMITTING 且计划落盘，恢复只加载冻结计划；不要将它和模型返回但结果未持久化混成一例。进程退出与普通抛异常分别记录，后者不等于前者。

## 11. 恢复断言

- 不存在两个有效 execution claim；
- 已提交回答不会重复生成；
- 未提交回答不会出现在 Conversation；
- outbox 可补发；
- operationId 不变化；
- UNKNOWN 副作用不自动重试；
- lease 过期后新建下一 Run；
- 数据库完整性检查通过。
- 已冻结 COMMITTING 不因 lease 到期重新 claim，也不因无内存 Outcome 永久等待；
- 备份/迁移失败后仍保留有效恢复点，手工目录没有被误删。

## 12. 资源测试

记录：启动空闲 RSS、普通 Turn 峰值、工具 Turn 峰值、后台 Task 峰值、SQLite/WAL 增长、线程数和模型并发数。

设置模型并发、后台 Run、SSE 连接、工具输出、上下文和队列长度上限。超过上限时排队或拒绝，不无限创建线程。

另记录大库备份与摘要阶段峰值，不只测空库。执行环境不满足 2 核 2 GB 等价限制时只能报告本机观察，按实施清单 **0.2.7-A/B** 分开交付，不勾选资源验收。

## 13. 窄验证原则

```text
目标 Module 单测
→ 受影响 Adapter 集成测试
→ 关键应用场景
→ 最后运行更宽构建
```

不要只跑编译就声称状态机正确，也不要为值类型小改每次启动完整应用。

## 14. 测试证据格式

```text
测试目标：
关联审查 T 编号 / 回归 R 编号 / 阶段：
参与档 A / B 与环境限制：
源码基线（提交或文件 hash）：
命令：
退出码：
通过数量：
失败数量：
关键断言：
返回结果及数据库/外部调用次数的前后对照：
日志/报告路径：
未覆盖风险：
```

验收必须保存实际输出，不接受只写“测试通过”。

负例拒绝时同时断言事实未部分改变；fixture 没触发预期错误不能算负例通过。若报告根计数与 testcase 节点不一致，说明统计口径，不拿测试数量替代覆盖范围。计划中的新增用例不得提前填已通过。
