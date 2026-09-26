# 0.2.4-A · 行为账本加厚阶段施工单（已确认）

`status`: **已交付 · 2026-09-24**（A1–A9 入仓；F 阶段窄测仍按 §8 预告补齐） — 原稿确认日 2026-09-24  
`upstream`: [0.2.4 已审范围](./k04-conversation-system-draft.md) · [系统设计](./k04-conversation-system-design.md) · [A 立项](./k04-behavior-journal.md)  
`workflow`: [版本与阶段工作流](../../version-stage-workflow.md) §2–§4

## 0. 执行者先读

本单只开 A：补正式 `MEMORY_WRITE`、修正步骤编号与原子性、收紧公开工具投影、定义账本/Outbox 分工。不要提前写 B 的会话管理、C 的 SSE、D 的页面或 E 的 Stop/标题。生产文件先写到 `docs/plans/archive/0.2.4/k04-a-draft/`，按 §7 的 MANIFEST 逐文件审；用户逐文件通过后才写入 `wn-server/`。测试代码与真人验收留给 0.2.4-F，A 只保证现有编译/测试不被故意破坏。

**确认边界**：本单的范围、事务语义、编号权威和文件审序已获确认。§10 四项实现细节留给相应草稿定稿，不再作为确认整份施工单的前置问题；草稿中若无法满足本单不变量，必须先回报并修订 MANIFEST。

## 1. 已核实的代码事实

1. `SqliteTurnCommitter.commitInTransaction` 先插助手 Message、更新 Turn、插 `TurnCompleted` 与附加 Outbox，再调用 `SqliteMemoryCommitWriter.applyAll`、关系 Writer、活动时间更新、删除冻结计划；外层只在 `Committed` 时提交数据库事务。
2. `SqliteMemoryCommitWriter.applyAll` 目前返回 `void`。代次不匹配会跳过单条变更；`expectedGeneration == null` 也可能返回 null。因此**不能仅凭 `plan.approvedMemoryChanges()` 记成功**。
3. `SqliteTurnStepJournal` 每条用独立连接插入，SQL 失败只警告。`SequencingRunJournal` 每 Turn 缓存 `MAX(step_no)` 后递增，`FINALIZE` 时移除计数器。事务内新增步骤会使缓存落后，进程重启/恢复时还可能重试；不能在 Committer 里随手 `MAX+1` 而继续保留旧缓存方案。
4. `TurnToolCallProjector` 当前直接输出账本中的 `argumentsJson`。`JournalSettings.includeFullMessages` 默认 true，`JournalJson` 的裁剪只限制长度，不是密钥脱敏。当前 `ToolCallView` 注释“已脱敏”不能作为安全证据。
5. V013 已有 `turn_step`：`id` 主键、`UNIQUE(turn_id, step_no)`，kind 为 TEXT，没有 kind CHECK。仅新增 `MEMORY_WRITE` 枚举值**不需要** migration。

## 2. A 的不可变语义

### 2.1 两类事实

| 事实 | 写入点 | 失败处理 | 页面可见性 |
|---|---|---|---|
| USER_INPUT、MODEL_CALL、TOOL_CALL、现有 FINALIZE | 现有运行观察通道，可尽力写 `turn_step` / JSONL | 保持“不挡回复”，但不得被称为完整事务审计 | 只有服务端安全投影后可见；缺项时 UI 不伪造 |
| 正式 MEMORY_WRITE 成功事实 | 与对应 memory_record、助手 Message、Turn 完成、Outbox 同一 `SqliteTurnCommitter.commit` 事务 | 插入失败则整笔 commit 回滚；保留 COMMITTING 冻结计划供原恢复协议处理 | 默认不进入聊天 SSE；管理/调试只读可查 |
| Turn 完成事实 | Turn 状态 + `TurnCompleted` Outbox | 与 Message 同事务；失败回滚 | B/C 通过 Outbox 交付 |

`FINALIZE` 当前由 TurnEngine 在提交后走尽力日志，**不冒充**原子完成事实。A 不扩大为“所有运行观察必须同事务”；若未来要将 TOOL_CALL 变为强审计，须另立协议。JSONL 是辅助运维记录，不参与完成事务；DB commit 成功后 JSONL 失败不逆转已完成 Turn。

### 2.2 MEMORY_WRITE 的准确含义

- 仅对 `SqliteMemoryCommitWriter` **实际落库**的正式变更写 `status=SUCCEEDED` 的 `MEMORY_WRITE`。代次过期或旧冻结计划缺观察点而跳过的变更不能写成功步骤；可以保留现有运行观察/错误码，但不能伪造正式写入。
- `request_json/result_json` 只含安全标识和操作元数据，例如 proposal ID、memory record ID、变更类型、作用域、是否 supersede。不得包含 claim 正文、path、原始用户话语、密钥或完整 Tool result。字段形状在草稿文件中固定，历史查询按版本化 schema 解析。
- 每条成功写入对应一条正式步骤，使用稳定 `id` 或幂等键，使 COMMITTING 恢复时最多存在一条。该标识由 turnId + 实际生成的 memory record ID 或同等稳定信息构成；不能只用 `UUID.randomUUID()` 后期待重试自行去重。
- 如果一轮无实际 Memory 变更，则无 `MEMORY_WRITE` 步骤；不能为了满足测试每轮加占位成功行。

## 3. 编号与原子写入决议

1. **唯一数据库分配点**：把 `step_no` 的最终分配和 `turn_step` INSERT 收到一个接受 JDBC `Connection` 的写入能力中。它在**同一个写事务**内读取该 Turn 当前最大 step_no，顺序插入所需行；运行观察通道用自己的短事务调用同一能力，正式 MEMORY_WRITE 用 Committer 当前事务调用。不得先在连接 A 读 MAX、再在连接 B INSERT。
2. **撤掉缓存的权威性**：`SequencingRunJournal` 的 `byTurn` 不能继续决定 SQLite step_no。可以保留它给纯 JSONL 模式编号，或改成委托 DB 写入返回已分配的条目；但 DB 的 `UNIQUE(turn_id, step_no)` 及事务内分配是唯一权威。草稿须展示 JournalConfig 装配在 sqlite/jsonl 两种开关组合下如何工作。
3. **并发顺序**：SQLite 单写者语义与短事务保证 DB 行唯一。若同时有运行日志和 Committer，忙锁按现有受控 busy timeout/重试策略处理；运行观察写失败可丢并打安全警告，正式 MEMORY_WRITE 写失败必须回滚本次 commit。不得因 `INSERT OR IGNORE` 忽略正式步骤冲突。
4. **恢复幂等**：Committer 进入 COMMITTING 后仅依据已冻结计划恢复。提交完全成功时 Turn 已完成，恢复不会再次写 memory/step；提交失败回滚时两者都不存在，重试从同一冻结计划再提交。稳定 MEMORY_WRITE id 是额外保险，不能代替检查 Turn 状态与 executionId。
5. **事务中的具体位置**：在 `memoryWriter.applyAll` 返回实际落库结果之后、`frozenPlanStore.delete` 之前，且仍处于 `commitInTransaction` 的同一连接/事务内，写各条正式 `MEMORY_WRITE`。关系 Writer 与其相对顺序允许施工单按现有逻辑保持，但整个成功路径在任何一步失败时共同回滚。

## 4. Memory Writer 的最小调整

`applyAll` 应返回不可变的“实际写入结果”列表，至少有新 memory record ID、对应 proposal ID、操作类型/范围和 superseded ID（若适用）。`applyOne` 已在内部返回新 ID，可沿当前私有调用链上抬，不要重新查询整张表猜测哪条写成功。代次过期被跳过不进入返回列表；真正 SQL/JSON 失败继续抛出以回滚。不要把 Write result 泄漏进外部通用 `CommitTurnPlan`，也不要修改冻结计划 JSON 契约。

若同一 Turn 多条变更的代次互相冲突，沿用现有 Memory Writer 语义，逐条以实际返回值记账；A 不偷偷改变 Memory Policy。必要时将“跳过原因”作为非正式运行观察，但正式成功步骤只对应已落库记录。

## 5. 公开工具投影与脱敏

1. 明确两层数据：内部 `turn_step` 的有限诊断载荷，与可返回浏览器的 `ToolCallView`。内部已有原始或截断参数时，不得将它无条件复制到 HTTP/SSE。所有出口复用一套服务端安全投影，后续 C 的实时工具事件也必须走它，避免历史与直播两套规则。
2. **默认拒绝原始参数/结果**。每个工具明确允许展示的字段白名单与大小上限；未列入白名单时只返回工具名、调用 ID/operationId、时间、状态、稳定错误码及“参数/结果已隐藏”的说明。`remember_fact`、`update_relationship`、`search_memory` 的事实正文/查询原文不公开；`http_read` 不公开含凭据 URL；PowerShell 家族工具不公开未经审查的命令与输出。
3. 即使某字段在白名单，也要拒绝密钥形态、Authorization、cookie、令牌、控制字符与超长正文。截断不是脱敏；无法确定安全时隐藏整个字段。不要靠浏览器正则去“清理”已经发出的秘密。
4. `ToolCallView` 的字段及注释与实际公开语义一致。若继续保留 `argumentsJson`，它必须是安全投影而非内部原始 JSON；可改成安全摘要字段并同步 HTTP DTO/`api.js` 契约，但页面重做属于 D，不在 A 提前改布局。
5. `TurnStepStore.listByTurnId` 目前是内部全量查询；A 只新增必要的管理/调试只读能力或限定查询，不把它直接暴露成原始公开 API。查询按 turnId 校验来源与授权，B/C 仍须再覆核访问策略。

## 6. Migration 结论

按目前目标，**A 不建 V014**：现有 `turn_step.kind TEXT` 能容纳 `MEMORY_WRITE`；`id` 主键足以容纳确定性步骤 ID，`UNIQUE(turn_id, step_no)` 足以验证顺序，查询按 turnId 已有索引。若草稿实现发现确需新增 operationId/幂等列，先回报用户，写出该列无法用现有 id 表达的具体反例、升级回填和索引查询依据，获阶段范围修订后才追加 V014+。绝不改 V013 原文件。

## 7. 文件 MANIFEST 与审阅顺序

以下为预计**生产**文件；草稿置于 `docs/plans/archive/0.2.4/k04-a-draft/` 对应路径。每项单独提交审阅，前一项未通过不写入项目。若审阅指出要增文件，先更新本表和依赖序，不“顺手”入仓。

| 序 | 文件 | 目的 | 先决条件 |
|---|---|---|---|
| A1 | `wn-server/kernel/src/main/java/com/wannian/server/kernel/journal/JournalKind.java` | 加 `MEMORY_WRITE`，不改旧 kind 含义 | 无 |
| A2 | `wn-server/app/src/main/java/com/wannian/server/app/persistence/SqliteMemoryCommitWriter.java` | 返回实际落库结果，不改 Memory Policy | A1 |
| A3 | `wn-server/app/src/main/java/com/wannian/server/app/journal/SqliteTurnStepJournal.java` | 同连接/事务的步骤写入与 DB 编号能力，区分强/弱失败语义 | A1 |
| A4 | `wn-server/app/src/main/java/com/wannian/server/app/journal/SequencingRunJournal.java` | 撤掉 SQLite 模式缓存编号权威；纯 JSONL 模式仍可工作 | A3 |
| A5 | `wn-server/app/src/main/java/com/wannian/server/app/journal/JournalConfig.java` | 按 sqlite/jsonl 开关正确装配新写入路径 | A3–A4 |
| A6 | `wn-server/app/src/main/java/com/wannian/server/app/persistence/SqliteTurnCommitter.java` | 同事务插 MEMORY_WRITE；失败回滚，完成路径幂等 | A2–A5 |
| A7 | `wn-server/app/src/main/java/com/wannian/server/app/http/TurnToolCallProjector.java` | 公开字段白名单、安全摘要和默认隐藏 | A1–A6 |
| A8 | `wn-server/app/src/main/java/com/wannian/server/app/http/ToolCallView.java` | 如字段语义变化则同步 DTO；若不变记录“不需修改” | A7 |
| A9 | `wn-server/app/src/main/java/com/wannian/server/app/http/TurnController.java` / `/chat/api.js` | 仅当 A8 改了响应契约时做最小兼容接线；不得改页面布局 | A8 |

**明确禁止触及**：`db/migration/V001`–`V013`、`TurnEngine`/`DefaultAgentLoop` 业务流程、`ContextAssembler`、Memory Policy/Review 决策、会话列表/状态、SSE/Outbox Publisher、`chat/app.js` 页面布局、提示词、BackgroundTask。若 A3 需要独立新类而非扩旧类，先说明接口与现有类如何合并/替代，并更新 MANIFEST。

## 8. A 阶段完成检查与 F 阶段测试预告

普通阶段完成时，审阅者检查：正式 Memory 写入只来自实际 Writer 结果；同连接同事务；编号单调且不会被缓存覆盖；JSONL/SQLite 开关组合可装配；安全投影不会透出原始敏感参数；没有顺带实施 B/C/D。阶段代码审核只对已入仓差异，不把计划或草稿算成代码交付。

F 阶段必须补的窄测：成功 Turn 有实际 MEMORY_WRITE；无变更/代次过期无假成功；Memory Writer 或步骤写入失败回滚助手 Message、Turn、Outbox、Memory 和步骤；COMMITTING 重试不重复；进程重启后追加运行步骤不撞号；SQLite/JSONL 开关组合；带 Authorization、密钥、敏感记忆与 URL token 的工具参数不进入 HTTP 投影。测试要真实检验事务和公开响应，不照抄实现构造同义断言。

## 9. 开工门

本单**已确认**，下一步可按工作流建立 `k04-a-draft/` 草稿并逐文件审阅；只有某文件通过审阅，才能将该文件写入 `wn-server/`。没有“整份施工单一通过就批量改生产文件”的授权。需要改动本单禁区、migration 或强/弱事实边界时，先回报并重新审阅受影响部分。

## 10. 草稿中必须钉死、但不阻塞本单确认的细节

| 项 | 最迟审阅点 | 草稿必须给出的内容 |
|---|---|---|
| `MEMORY_WRITE` 的 `request_json/result_json` schema | A3/A6 审阅前 | 字段名、类型、版本号、空值与脱敏规则；实际写入成功/跳过的映射；旧步骤读取兼容 |
| 稳定 step ID 拼法 | A3/A6 审阅前 | `turnId + memoryId` 的确定性编码/哈希方案、命名空间、碰撞处理与重试行为；不得以随机 ID 冒充幂等 |
| 各工具公开白名单 | A7 审阅前 | 逐工具列出可见参数/结果字段、截断上限、敏感字段和未知工具默认隐藏；同一投影供历史与实时复用 |
| A3 文件形态 | A3 草稿前 | 说明扩 `SqliteTurnStepJournal` 或抽新类的理由、接口和 `JournalConfig` 接线；如抽新类，先更新 §7 MANIFEST 与逐文件审序再写草稿 |
