# 01 · 缺陷定义（T1–T8）

本文保留 2026-09-18 的缺陷定义，**不改写关闭结论**。T1—T7 证据在 [放行记录](./02-release-h3.md)。文内「暂缓接真实模型」是审查当时判断；现行版本以 [产品概览](../product/01-overview.md) 与 [实施清单](../guide/01-checklist.md) 为准（v0.1 已含直接回答；Loop 属 v0.2）。

审查日期：2026-09-18。对象：当前工作树中的 `wn-server`，不是某个已提交实现版本。性质：审阅与后续修复建议，**本次不修改业务代码，不启动修复，不授权提交、发布或扩大阶段范围**。

## 1. 结论

工程方向可以继续：三模块依赖、领域状态机、SQLite 事务、封闭结果、独立目录备份恢复均已有真实实现。没有理由推倒重写，也不需要为了未来多节点提前引入分布式框架。

但不能沿用旧报告“唯一必修是 RUNNING 直接 commit”的结论：这个问题在当前代码已经修好；新审查确认了更基础的会话身份、执行所有权、可靠交付及备份清理缺口。**建议保留 K01/K02 已交付的历史记录，但将“可直接接入真实模型”的判定暂缓，先完成本文 T1—T3；T4 的数据删除风险也应在继续使用自动备份前修正。** 这是补齐现有承诺，不是把 K03—K09 全部塞回 K02。

| 方面 | 本次判断 |
|---|---|
| K01 工程结构 | 当前源码分层成立；依赖门禁覆盖不完整，不能将测试通过理解为所有禁止依赖都能拦住 |
| K02 普通路径 | 接收、CAS、原子提交、备份恢复的已有定向测试通过 |
| K02 边界正确性 | 有实际复现的问题，需要补修；测试通过不足以证明整个协议可靠 |
| 进入 K03 | 先明确并固化执行所有权、完成事件、序号分配和 COMMITTING 恢复语义 |
| 后续整体路线 | 保留小内核、短事务和适配器；不提前实现 Guardian、远程节点或通用工作流 |

优先级：P1 为应在依赖它的后续功能接入前修复的正确性问题；P2 为边界、测试或可维护性问题。没有证据支持 P0 级全系统故障结论。

## 2. 范围与证据口径

- Git HEAD：`c4498988b255db96ee706f8adc54655881ccf504`，只有初始文档提交。`wn-server/` 当前未跟踪，不能用空的 `git diff HEAD` 声称“没有代码变化”。本次逐文件审查现有实现、测试、POM、SQL 和配置，排除 `target/` 作为源码。
- 主要依据：[内核合同](../decisions/01-contract.md)、[内核参考](../guide/04-kernel-reference.md)、[测试工作簿](../guide/07-testing.md)、[实施清单](../guide/01-checklist.md)、[架构](../guide/03-architecture.md)。（文内旧「25/28/29/32/33/34 号」均指这些文件。）
- Standards 与 Spec 分别独立只读审查；主审另做跨层检查及临时数据库复现。两轴结论分开保留，不用架构整洁掩盖行为错误，也不用功能通过掩盖依赖门禁缺口。
- **动态证实**：本轮真正运行的现有测试或临时探针。**静态确定**：由实际代码路径直接得出。**演进风险**：需后续组件接入才会产生的风险，不写成已经发生的生产事故。
- 本文代码定位以审查时源码行号为准；下文 `app/...`、`kernel/...` 均相对 `wn-server/`。新增报告保留旧 26 号审阅，不追改历史结论。

### 2.1 本轮测试

在产品目录执行，退出码 **0**：

```powershell
& D:/0HAN/HANAGENT/mvnw.cmd `
  -s D:/0HAN/HANAGENT/.mvn/settings.xml `
  -f wn-server/pom.xml `
  '-Dtest=TurnTransitionTest,ModuleDependencyRulesTest,TurnCommitterAtomicityTest,ReceiveTurnIdempotencyTest,TurnTransitionPersistenceTest,ConsistencyBackupTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test -q
```

| 测试类 | 实际 testcase 节点数 | 失败 / 错误 |
|---|---:|---:|
| TurnTransitionTest | 9 | 0 / 0 |
| ModuleDependencyRulesTest | 4 | 0 / 0 |
| TurnCommitterAtomicityTest | 5 | 0 / 0 |
| ReceiveTurnIdempotencyTest | 4 | 0 / 0 |
| TurnTransitionPersistenceTest | 4 | 0 / 0 |
| ConsistencyBackupTest | 4 | 0 / 0 |

共 30 个 testcase 节点。注意 ModuleDependencyRulesTest 的 Surefire XML 根属性写 `tests=2`，正文另含 2 条 ArchUnit testcase，共 4 条；不能不加说明地累加根属性后称为全部测试数。证据在各模块 `target/surefire-reports/`。

本轮没有重新执行全量 package、HTTP 全集、真实模型、进程强杀恢复、资源压测或远端测试；不借用历史产物宣称这些本轮通过。现有 HTTP 测试、启动配置及其他测试做了静态审查。

### 2.2 临时探针结果

使用本轮构建后的 classpath，Java 21 源文件启动模式，直接调用真实 `SqliteTurnCommitter`、`SqliteTurnRepository`、`TurnController` 和备份实现。数据库由 `Files.createTempDirectory` 创建，运行真实 V001 migration；没有操作开发数据目录，没有修改仓内测试或源码。

```text
CROSS_FIRST=Accepted[..., replayed=false]
CROSS_OTHER_CONVERSATION=Accepted[同一个 turnId, replayed=true]
CROSS_B_MESSAGES=0
RUNNING_COMMIT=Rejected[reasonCode=ILLEGAL_STATUS, ...]
EXPIRED_OWNER_COMMIT=Committed[..., newTurnRevision=5]
COMPLETED_WITHOUT_OUTBOX=0
TERMINAL_REOPEN=Saved[..., newRevision=6]
TERMINAL_REOPEN_STATUS=RUNNING
ALREADY_EXPIRED_CLAIM=CLAIMED
HTTP_STORED={"v":1,"text":"hello"}
UNRELATED_BACKUP_DIR_EXISTS=false
```

其中 HTTP 输入是 `"  hello\n"`；备份负例是本轮专门创建的 `backups/000-manual/`，内含伪造的 `wannian.db` 和 `notes.txt`，保留数设为 1 后被整体删除。

本机临时复现材料为 `%TEMP%/WannianReviewProbe.java`、`%TEMP%/wannian-review-classpath.txt`、`%TEMP%/wannian-review-probe-output.txt`。临时文件不作为长期交付依赖，本文 T1—T7 已记录重建场景所需步骤与断言。探针是在**复现当前错误**，退出码 0 不代表修复验收通过。

关键源码 SHA-256，供之后判断是否还是本次快照：

```text
SqliteTurnCommitter.java
958ABF0627D6513DEA549DAD968C75AF5A341F191164EBB91B8B7B4C79FF8D0D
Turn.java
C8E2BBA199CBDA48046EA37153AABA6579A2741D1BE1B25A6B04FE50EBEC44A0
BackupBeforeMigrate.java
754CA3B4C955A57C3578902F6AE0497D88EF94760E0149C67E4CFA8430618F13
```

## 3. Standards：规范与设计质量

实际 `api → JDK`、`kernel → api`、`app → kernel/api` 的生产依赖符合三层方向；ID 值类型、sealed 结果、TurnCommitter 集中事务值得保留。没有必要为了套设计模式拆出更多接口。

### S1 · P2：HTTP 改写用户原文

`app/src/main/java/com/wannian/server/app/http/TurnController.java:86–90` 使用 `text.trim()`。28 号定义 `userText` 是“用户原文”，并只要求空白输入拒绝。当前保存前删除边界空白，既破坏代码/格式化内容，又使同键的 `"hello"` 与 `" hello "` 被当作相同请求。已通过真实 Controller 探针确认。保留 `isBlank()` 校验，存储原字符串，详见 T5。

### S2 · P2：业务标题规则位于持久化适配层

`app/.../persistence/SequentialConversationTitlePolicy.java:15–19` 决定“会话 N”的业务规则；kernel/package-info 和 app README 将业务决策放在 kernel。建议将纯实现置于 kernel，Spring 装配留 app。它目前没有引发错误，是小范围分层纠偏，不应优先于事务问题。

### S3 · P2：门禁的保护范围小于约定

`app/src/test/java/com/wannian/server/app/ModuleDependencyRulesTest.java:47–52,80–86` 没有覆盖 kernel 的 Spring Context、模型 SDK、`java.sql` / `javax.sql`；API 的 ArchUnit 规则也未禁止 JDBC。当前代码没越界，但将来可以越界而测试仍绿。不能把“有规则”当作“故意违规时一定失败”。详见 T6。

### S4 · 设计判断：AI 命名不能仅替换 Bean

`kernel/.../conversation/ConversationTitlePolicy.java:6,17` 和 `SequentialConversationTitlePolicy.java:9` 承诺未来换 AI 策略即可，但接口只有会话数量，且 `SqliteConversationStore.java:48–52` 在建会话数据库事务内调用。它没有首轮内容，也没有首轮结束时机。纠正这项扩展承诺即可；未来 AI 命名应由完成后独立流程处理，不能把模型请求塞进创建会话事务。不要为这条建议提前实现 AI 命名。

### S5 · 设计判断：消息与 Outbox 的权威序号应归服务端事务

`CommitTurnPlan.java:78–79,98–104` 把消息/事件序号交给调用者，`TurnController.java:50–51,82` 甚至要求 HTTP 客户端提供消息序号。唯一约束只能保证“不重复”，不能保证提交后游标不会漏数据。SSE 尚未实现，**当前没有已发生漏投递的证据**；但这是 K03/K06 接线前应定案的接口问题，详见 T3 与第 6 节。

Standards 共 5 项：3 项明确规范/防护缺口、2 项设计建议；本轴最直接的行为问题是原文被改写，长期防护薄弱点是依赖门禁漏项。

## 4. Spec：规格符合性

### Q1 · P1：跨会话请求被错误地幂等回放

规格 33 要求 `clientRequestId` 幂等，29 要求接收事务创建所属会话的用户 Message 和 Turn。`SqliteTurnCommitter.java:121–125` 只比较正文，`:186–200` 查询未读 `conversation_id`。两个已存在会话 A/B 使用同键、同正文时，B 返回 A 的 Turn，B 没有消息。**动态证实**。保持当前全局 key 的规格，跨会话复用应冲突，不应无声改变为会话内唯一。详见 T1。

### Q2 · P2：自动清理误认手工目录为自有备份

规格 33 要求可配置旧备份清理；实现 `BackupBeforeMigrate.java:102` 还承诺“不删无关目录”，但 `:122–124` 只要包含 `wannian.db` **或** `wannian-backup.json` 就认作快照，然后 `:118,127–135` 删除整个目录。已在临时目录复现。需要明确自有快照的命名、manifest 和成功标记，不能通过改注释来合理化误删。详见 T4。

### Q3 · P2：K01 负例验收覆盖不足

规格 33 的“kernel 只依赖 api”“依赖反向测试能够在故意违规时失败”，不能由当前少量黑名单完全证明。与 S3 指向同一证据，本轴不再重复计为另一修复任务。本轮正常门禁测试通过，但未改仓内源码注入违规；后续应以独立 fixture 验证违例被拒绝。详见 T6。

### 主审补充 Q4 · P1：完成提交缺少执行所有权校验

29 号“完成 Turn”事务明确要求“校验 execution owner 与 revision”。`CommitTurnPlan.java:18–25` 没有调用者执行身份；`SqliteTurnCommitter.java:266–282` 不加载 owner/lease，最终只检查状态和 revision。revision 表达数据版本，不表达谁有权提交。

探针先在过去时间正常 claim/start，再于 lease 已过期的当前时间 `beginCommit` 并 commit，结果仍为 Committed。即使暂时只有单进程，超时后的旧任务、重复调度与恢复任务仍可能同时存活。**确定缺少所有权约束；尚未执行多执行者竞争实验，不宣称已复现双 owner 事故。** 详见 T2。

### 主审补充 Q5 · P1：完成回合可没有任何可恢复的完成事件

29 号完成事务要求“追加 OutboxEvent”；`CommitTurnPlan.java:31` 却明文允许空列表，`SqliteTurnCommitter.java:171,343–362` 空列表成功提交。探针返回 Committed 且 outbox 行数为 0。

这不是说“没有 SSE 就不算完成 K02”，而是事务接口允许产生未来无法可靠通知的完成事实。K06 即使做对 publisher，也无法补发从未持久化的事件。需要先消除文档与接口矛盾，推荐完成事件由 committer 保证，详见 T3。

### 主审补充 Q6 · P2：公开快照重建与通用 save 能绕过终态不变量

`Turn.java:76–97` 公开接受任意状态和 revision；`SqliteTurnRepository.java:61–74,95–104` 只验证 revision 前进 1，随后直接更新状态。将已完成 Turn 重建为 RUNNING、revision 加 1 再 save，实际返回 Saved，数据库回到 RUNNING。

这需要内部调用者误用 `reconstitute`，不是现有 HTTP 直接可利用路径，不能描述成外部越权漏洞。但它否定了“没有 complete 方法就无法绕过原子提交”的强保证。COMPLETED 的旧 output/completed_at 还会残留，形成相互矛盾的记录。详见 T7。

Spec 共 6 项（含 3 项主审补充）；Q3 与 Standards S3 是同一门禁缺口。最严重的本轴问题是会话归属与执行所有权不受完整约束；两者分别影响“请求属于哪里”和“谁能提交”。

## 5. 可交给后续实施者的修复任务

以下任务全部为 **A：本机可完成**，本轮只写规格，尚未实施。用户拥有的领域逻辑按既有 `OWNER: USER` 约定协作；不要因本文建议直接代写用户练习部分。

通用交付要求：每项报告修改文件、具体不变量、复现前后输出、测试命令/退出码和未覆盖限制；不能只说“测试通过”。不得顺带改 han-server、legacy、Guardian、模型供应商配置，不得提交或发布。已发布 migration 只新增下一号，不改旧 checksum。

### T1 · 修复请求身份与幂等回放（P1，进入 K03 前）

**相关位置**：`SqliteTurnCommitter.receiveInTransaction/findByClientRequestId`、`ReceiveTurnPlan`、`ReceiveTurnIdempotencyTest`、`ConversationHttpTest`。

**触发时序**：创建 A/B → A 接收 key=k、正文 X → B 接收 key=k、正文 X → 当前代码向 B 返回 A 的 turnId。

**不变量**：同一全局幂等键只能绑定一个完整业务请求，至少包括 conversationId 与原始正文/规范化 envelope。消息/Turn 的新生成 ID 不是重试一致性依据，否则 HTTP 重试生成新 ID 会被错误拒绝。

**实施路线**：

1. 查询既有请求时同时取 conversationId；先校验会话归属，再比较正文。
2. 不同会话复用全局 key 返回稳定冲突；保持已有记录完全不变。同会话同正文返回原 turnId。
3. 明确序号在重试中的地位：采用 T3 服务端分配后，不再将其作为客户端请求身份。JSON 比较策略集中一处；HTTP 当前 envelope 可确定序列化，不应无理由实现通用 JSON 语义比较框架。
4. 并发冲突处理放在 adapter 的短事务边界：发生唯一键竞争或可重试 BUSY 时，回滚后在新事务重读原请求，再决定 replay/conflict；设置总等待上限。不得将所有 SQL 失败都误判为幂等成功。

**验收**：同会话同键同正文只有一条 Turn/用户消息；同键异正文冲突；跨会话同键同/异正文均冲突，B 无新增消息；新生成 turnId/messageId 的合法重试仍回放。双连接同步起跑验证同一请求最多落一份，第二方最终 replay 或明确的可重试忙结果，不出现新 Turn。取消/失败后的原请求重试仍指向原 Turn，不自动重做业务。

**禁止修法**：删除全局唯一约束、直接覆盖旧请求、仅在 Controller 判重、给每次重试生成新 key、无限重试。

### T2 · 执行身份、lease 与提交临界区（P1，进入 K03 前）

**相关位置**：`ExecutionClaim`、`Turn.claim/start/beginCommit`、`TurnRepository`、`CommitTurnPlan`、`SqliteTurnCommitter` 及其测试。

**触发时序**：owner A 获得 claim 并运行 → lease 到期 → A 的慢任务返回 → 当前 beginCommit 和 commit 均不校验有效 owner，接受旧结果。

**不变量**：执行尝试具有独立身份；旧执行者不得因读到新 revision 而获得新执行权。`local-primary` 可以是设备/执行节点标识，不能同时充当所有尝试共享的 fencing 身份。

**推荐最小方案**：

1. 每次执行认领生成不同 executionId；CommitTurnPlan 带 expectedExecutionId。原有 revision 保留，二者分别校验。
2. `claim` 拒绝 `expiresAt <= now`，不能先保存已经过期的 CLAIMED。传入可控时间，不以 sleep 做测试。
3. 进入 COMMITTING 的持久化边界校验 owner、revision 和 lease；将取消与 beginCommit 的竞争交给同一 CAS 决胜。不能只在 JVM 对象里检查，随后无条件保存。
4. COMMITTING 需要明确唯一语义：建议进入后冻结本次 owner/最终计划，不再允许普通取消或另一个执行者接管；COMPLETED 的提交继续验证冻结身份。
5. **不要简单在最终 commit 增加“lease 过期即拒绝”就交差**：当前 COMMITTING 不能 fail/cancel，也没有恢复计划，这样会把已经进入提交区的回合永久卡住。应区分“过期后才尝试进入 COMMITTING”与“合法进入后事务/进程延迟”。后者必须可从已持久化计划或明确恢复记录继续提交；若选择另一语义，先同步修订 29 号状态机和恢复矩阵。
6. K03 的 Outcome/step 与 COMMITTING 接线时明确落库顺序；K09 可以负责完整强杀验收，但不能到 K09 才决定恢复所需事实是什么。

**验收**：过期 claim 无状态变化；错误 executionId 即使 revision 正确也拒绝且三表不变；过期 A 不能进入 COMMITTING；取消与 beginCommit 竞争只有一方成功；已合法冻结的提交遇到时间推进仍按已定义策略完成或可恢复，不能永远停留；COMPLETED 重试只查询/回放已提交结果，不再调用模型。

**禁止修法**：以单例线程或 synchronized 代替数据库约束；所有执行共用 `local-primary` 当唯一尝试 ID；错误 owner 自动换成当前 owner；延长所有 lease 掩盖超时；回滚后直接 SQL 改回 RUNNING。

### T3 · 保证完成事件与提交顺序（P1 事件缺口；序号为接线设计）

**相关位置**：`CommitTurnPlan`、`ReceiveTurnPlan`、`SqliteTurnCommitter`、HTTP DTO、SQL 及原子性测试。

**不变量**：一条成功完成的普通 Turn 至少有一条能定位该 Turn/助手消息的持久化完成事件；事件与消息、状态同事务。客户端和 Agent Loop 不承担数据库序号协调职责。

**实施路线**：

1. 推荐 committer 基于提交结果构造必需的完成事件；附加事件可以是计划输入。不要仅检查 `outboxEvents` 非空，因为无关事件也能蒙混通过。
2. 若保留调用者构造，必须验证必需 eventType、aggregateId 与 Turn 的对应关系，并在写入前拒绝缺失/错配；更新当前“可空列表”注释。
3. 将全局 outbox sequence 的分配收进最终写事务，保证更大游标被观察到后，不会再提交更小的序号。使用事务内数据库计数/自增方案，并明确保留清理后也不复用；不需要消息中间件。
4. 消息序号也由 receive/commit 的事务分配。HTTP 只提供用户意图和请求 key；如需临时兼容旧 DTO，明确弃用与冲突语义，禁止把客户端数字当权威。
5. 事务失败回滚事件、消息与状态。提交响应丢失后的重试应读取已提交事实；不要重新生成并提交第二份答案。

**可控场景**：A/B 的模型执行完成顺序与开始顺序相反。先提交 B 并读取当前 cursor，再提交 A；从该 cursor 增量读取必须包含 A 的事件。可先对 outbox 查询验证，无需提前实现 SSE。

**验收**：空/错配完成事件不会产生不可通知的 COMPLETED；成功时只有规定的一条完成事件；故意使事件插入失败三表回滚；多会话并发无重复/倒退游标；同会话用户消息与助手回复序号不冲突；重放不增加事件数。

**禁止修法**：事务外 `MAX(sequence_no)+1`、进程内计数器、不受约束的客户端序号、commit 后再单独补插事件、将序号跳号误当错误（需要单调，不需要连续无空洞）。

### T4 · 收紧备份清理边界与失败顺序（P2，尽快）

**相关位置**：`BackupBeforeMigrate.java:57–71,102–139`、`SqliteConsistencyBackup`、`ConsistencyBackupTest`。

**触发时序**：backups 目录已有手工恢复子目录，含同名 DB 或 JSON → 自动生成新备份 → 超出 retainCount → 手工目录被计数并递归删除。

**实施路线**：

1. 给自动快照定义受控命名与 manifest 格式版本/来源标记；完成快照数据库、摘要和 metadata 后才标记成功。残缺快照不算成功备份。
2. 清理候选必须同时满足受控目录名、自有且可解析的 manifest、实际数据库与成功状态。未知目录、手工恢复目录、仅有一个同名文件的目录全部保留。
3. 删除前验证解析后的实际路径仍在预期 backups 根内；拒绝符号链接/Windows junction 绕出边界。不能仅做字符串前缀判断。
4. 在 migration 校验/迁移成功后再决定旧备份淘汰；至少不要在校验失败的重复启动中持续淘汰最后可用备份。是否无 pending migration 也每次备份，应明确为策略，不能靠重启次数消耗恢复历史。
5. 独立测试空 history、首迁失败后重启、备份失败阻止 migration；目前仅检测 history 表存在，`readSchemaVersion` 对没有版本行会抛错，需明确这类启动残留如何处理。该启动失败场景本轮未动态复现。

**验收**：手工目录带 DB/JSON/额外笔记均保留；有效旧自有快照按保留策略删除；新备份失败不淘汰旧有效备份、不执行迁移；checksum 失败不清掉恢复依据；恢复演练仍在独立目录验证版本、digest、integrity_check 与完整 Turn 链。

**禁止修法**：把 backups 下全部内容视为可删、只用后缀认领所有权、吞异常继续迁移、复制活跃主库文件代替一致性备份、在当前真实库上做恢复实验。

### T5 · 保留原文并补齐输入边界（P2）

**相关位置**：`TurnController.contentJson`、HTTP 测试、幂等测试。

1. 保留 `text == null / isBlank()` 拒绝逻辑，envelope 保存原始 text，不调用 trim。
2. 明确 content envelope 的版本与字段；正常用户文本不能由后续摘要或模型处理静默覆盖。
3. 测试前导缩进、末尾换行、中文、emoji；逐字符相等。JSON 字符串转义变化可以接受，但解析后的文本必须一致。
4. 同 key 的 `"hello"` 与 `" hello "` 按不同正文冲突；全空白仍拒绝且不写表。

禁止用“显示时补空格”修复已损失的数据。请求长度/上下文预算在 K03/K06 设统一上限，不在各处随意截断用户文本。

### T6 · 补强依赖负例，不改变三模块结构（P2）

**相关位置**：`ModuleDependencyRulesTest`、三个模块 POM；不改生产模块职责。

1. 生产 kernel 的禁止范围覆盖所有 Spring、JDBC API、SQLite/Flyway、厂商 SDK 与 app；api 同样不能偷带技术实现。
2. 优先用精确包/依赖语义或允许集合，避免无止境增加 artifact 名片段。test scope 的 JUnit/AssertJ 合法，不应误禁。
3. 用测试 fixture 验证至少 kernel→JDBC、kernel→Spring Context、api→kernel/app、kernel→SDK 违规会失败；合法 ID/领域对象通过。
4. 不要为了做负例临时修改正在共享的生产源文件后忘记还原；使用隔离 fixture，并保存实际失败证据。

验收不是“规则文本看起来更长”，而是可控违规确实触发、合法依赖不误伤。现有正常门禁测试继续通过。

### T7 · 防止重建快照成为任意写状态入口（P2）

**相关位置**：`Turn.reconstitute`、`TurnRepository.save`、`SqliteTurnRepository.updateSnapshot`。

**复现**：读取 COMPLETED → `reconstitute(... RUNNING, revision+1 ...)` → `save(... oldRevision)` → 当前 Saved 且 RUNNING。

**实施路线**：明确加载快照与业务转换的职责；最小防线是在持久化保存边界排除非法源/目标组合，通用 save 永远不能写 COMPLETED，也不能从任意终态复活。可让对象携带受控迁移前状态/命令，由统一领域规则校验，不要在不同 Controller/Adapter 手抄多套状态表。公开 reconstitute 至少校验快照内部自洽，并明确仅供加载使用，但**仅补注释不算防护**。

**验收**：伪造完成、FAILED→RUNNING、COMPLETED→RUNNING 均拒绝且所有字段不变；正常单步迁移及 stale revision 冲突仍正确；只有 TurnCommitter 能完成消息/事件/终态原子提交。取消/失败时间与 outputMessageId 不形成矛盾状态。

不要把它包装成鉴权修复，也不要因此引入通用 ORM、反射权限框架或 class-per-state。

### T8 · 小范围维护与备份资源改善（P2，可随相关阶段）

- 标题纯策略回归 kernel，app 仅装配；删除“AI 只换 Bean”的错误承诺。现有标题规则及创建行为不变，不提前开发 AI 命名。
- `SqliteConsistencyBackup.java:130–133` 以 `Files.readAllBytes` 计算 digest，会额外申请与整个 DB 同量级的堆。改成固定缓冲流式 SHA-256；同一文件摘要一致，在受限堆/大文件下验证内存不随文件大小线性增长。完整 2 核 2 GB 验收仍属于 K09，本轮没有实测资源峰值。
- `SqliteConsistencyBackup.java:126–127` 手工 JSON 转义只处理反斜杠和引号，含换行等控制字符的 build-id 会生成非法 JSON。app 已有 JSON 库，使用序列化器；测试引号、反斜杠、换行与中文，不要求 kernel 引入 Jackson。
- `V001__initial.sql:141,143` 的索引与对应 UNIQUE 索引列重复。通过查询计划确认冗余后在后续 migration 清理，不改已发布 V001，不将其夸大成现阶段性能故障。

## 6. 从整体看后续阶段，先确定什么、暂不做什么

### 6.1 K03 需要 TurnEngine 边界，避免 Loop 变成数据库调度器

建议维持现有设计图：HTTP → TurnEngine → 领域认领/上下文/AgentLoop → TurnCommitter。AgentLoop 负责有预算的决策，不能自己读取 JDBC、挑序号、拼事务、发 SSE 或处理 lease 接管。

必须在接模型前明确：同会话是否允许多个 RUNNING Turn。推荐 v0.1 **同会话串行执行，不同会话有限并行**；receive 可以接收并排队，执行顺序与上下文快照有明确依据。这是设计建议，不是已确认产品需求，也不是要求现在实现分布式队列。若选择同会话并行，必须给出回复排序、上下文版本和关系 revision 冲突策略。

`COMMITTING` 是恢复协议的一部分，不是为了让枚举看起来完整。当前最终 Outcome 仍只有调用者内存对象：只把状态落成 COMMITTING 后退出，无法凭这个字符串重建答案。K03 应保存可恢复提交计划/最终 step，或明确等价原子落库方案；K09 再做强杀覆盖。不能选择“启动时全部重跑模型”作为默认恢复。

### 6.2 K03 统一的是错误语义，不是抹平所有业务结果

现有 `Accepted/Conflict/Rejected`、`Saved/RevisionConflict` 等封闭结果体现不同操作语义，值得保留。33 号“不再各建结果类型”的措辞容易被低级模型理解成全部改为 `Result<Object,String>`，这与 34 号反对通用结果袋矛盾。建议解释为：**错误 code 统一登记，跨边界映射与日志一致；业务成功/冲突的具名结果仍按接口保留。**

当前 SQL 异常大多被压成 PERSISTENCE_FAILED，原因缺失会妨碍区分 BUSY、约束冲突和真正缺陷。按已延期到 K03 的计划收编，不把补日志另算 K02 新功能。日志规范中“技术原因可进日志”与“不得含 SQL/堆栈”也应明确分级处理，避免实施者到处打印异常或完全吞掉诊断线索。

### 6.3 K05/K07 保持统一事务，别把占位 Object 变成永久协议

`List<?>` / `Object` 当前是明确延期占位，adapter 拒绝非空，没有假装支持 Memory/Relationship/Task，这个做法正确。本轮不要求提前实现。进入 K05 时替换为具体不可变 ApprovedChange 及 required expected revision；进入 K07 时引入真正 TaskDraft。三者只能由 TurnCommitter 同事务写入，不能为了减少该类代码量再拆成各自独立提交。

### 6.4 K06 的重连可靠性依赖 K02 事实，而非前端重试技巧

需同时满足：完成事件存在、序号与提交顺序相容、cursor 能增量定位、重连只重放。唯一 UUID 能去重但不能替代有序游标；唯一 sequence 也不能单独证明顺序。先把 T3 接口定好，再写 publisher/UI，避免前端用“没看到回复就重新发新请求”弥补数据协议漏洞。

### 6.5 部署前补齐最小鉴权的阶段归属

25 号 §2 明确单用户身份与最小鉴权，03 号 MVP 也要求个人鉴权，但 33 号 K01—K09 清单没有清晰对应任务。它不是 K01/K02 未实现即失败的理由，却是整体计划缺口。建议在 K06 对外 Web 入口接入时单列身份/鉴权验收，覆盖普通 HTTP、SSE 与 internal 探针操作权限；在此之前将开发启动范围明确为受控本机环境。不要等页面完成后才猜访问边界，也不要顺带开发多租户。

### 6.6 数据目录必须独立于制品的运行位置

`SqliteConfig.resolveDataDir` 只对源码 `app/pom.xml` 工作目录做特殊处理；默认 `data` 其余情况相对 cwd。`assertNotUnderTarget` 只拦路径组件 target，不能证明“所有 artifact 目录之外”。因此从不同工作目录启动同一 JAR，可能看到不同数据库。这是运行约定缺口，不是已经发生的数据丢失。

建议未来启动契约明确 `WANNIAN_DATA_DIR` 为固定绝对路径，并打印脱敏后的实际路径；测试同一数据配置从两个 cwd 启动仍打开同库。K08/K09 做打包运行验证时覆盖；无需现在实现 Guardian 来解决普通路径问题。

## 7. 对上一份审核的纠错

| 旧结论或容易产生的误解 | 本轮纠正 |
|---|---|
| RUNNING 可以直接 commit，F-01 待修 | 当前 `SqliteTurnCommitter.java:262–264` 只允许 COMMITTING；`TurnCommitterAtomicityTest.java:151` 有回归，本轮测试及探针均拒绝 RUNNING。该项已解决 |
| 唯一开工门闩就是旧 F-01 | 不成立；会话归属、owner 与完成事件仍有缺口，见 T1—T3 |
| 备份只有复制文件，没有真实恢复测试 | 不成立；ConsistencyBackupTest 在独立目录开快照，检查版本、digest、integrity_check、Conversation、Turn、Message、Outbox，值得保留 |
| 依赖门禁存在就证明故意违规必失败 | 不成立；现规则漏挡 JDBC/Spring Context 等。本轮通过的是正常路径，不是所有负例 |
| 有 CAS 就有完整状态机/执行者保护 | 不成立；CAS 防 stale revision，不验证请求归属、owner，也不自动阻止伪造新版本快照 |
| schema 循环引用说明 message.turn_id 绝对不能有外键 | 不宜这样理解；当前通过 committer 保证关联是实现选择，不应把注释升级为数据库能力定论。是否增补约束需另做迁移与延迟校验设计，不要求本轮改表 |
| 为可扩展性应现在建更多 Port/Factory | 不建议；优先让现有 TurnCommitter 真正封闭正确性规则，再按已确认第二实现增加 seam |

33 号开头及更新目录仍引用旧 F-01；本轮保留它们作为已有工作，不直接改变阶段状态。后续完成修复时应以新证据更新状态，并引用本报告与修复验收版本，不能只勾选 done。

## 8. 后续验收顺序与交付格式

建议顺序：**T1 → T2 的语义定案与约束 → T3 → T4 → T5/T6/T7；T8 随相关改动完成**。T2 涉及用户拥有的领域方法，先让用户理解 owner 与 COMMITTING 的含义，再进入实现。整个顺序不需要扩建模块，也不以测试数量作为完成标准。

每个修复批次提交以下证据给审阅者：

```text
任务编号 / 参与档 A：
审查基线（提交或工作树文件 hash）：
修改文件与接口变化：
修复前复现及关键输出：
明确保护的不变量：
正常 / 冲突 / 并发 / 取消 / 重试断言：
执行命令、退出码、报告位置：
未覆盖场景与留给后续 K 阶段的事项：
是否触及 OWNER: USER、migration 或协议兼容性：
```

修复后重新独立审核，不覆盖本文；只有对应负例不再破坏不变量，才能关闭相应问题。远端部署与真实资源验收另属后续任务，本报告不授权执行。

两轴结论：Standards 5 项，重点是原文保真和门禁覆盖；Spec 6 项，重点是会话归属和执行所有权。两轴有 1 项门禁交叉，不能按 11 个独立 bug 对外统计。
