# 01 · 实施清单

`status`: **v0.1 已封版** — 2026-09-20 重置。v0.1 = 当前已交付（骨架、持久化、管理页、直接回答）。下一步是 **v0.2 单核 harness + 单核节点**（**0.2.1** 起）。世界树与多核是 **v0.3**，本清单不提前开工。

`plan-revised`: **2026-09-20** — 版本三档见 [产品概览 §3](../product/01-overview.md)。v0.2 正式小版本为 **0.2.1–0.2.7**（`K01`–`K07` 为施工别名）。`/chat/` 直接回答留在 v0.1，不再算成 0.2.1。旧号对照仍有效。审查原文不改写。

## 版本

| 档 | 状态 | 含 | 不含 |
|----|------|----|------|
| **v0.1** | 已交付 | H1–H4、`/chat/` 直接回答 | Agent Loop、工具、记忆策略、Outbox、多节点 |
| **v0.2** | 未开工 | 单核 harness（**0.2.1–0.2.7**）、一个烟火节点 | 世界树、第二个节点、Guardian |
| **v0.3** | 未开工 | 世界树、多核节点 | 宿主主备、wn-agent |

## 使用规则

- 每次只实施一个 **0.2.x** 中的一个小批次。
- 用户编写 `OWNER: USER`，Codex 不擅自补全。
- 每批完成后提交实际差异、命令和测试证据供审阅。
- 未明确授权时不 commit、push、部署或接触生产凭据。
- 超出计划的协议改变、扩大模块或提前实现 v0.3 / 更后，先补充方案再实施。
- 每阶段开工先读自己的“防复发提示”，验收同时检查拒绝/冲突路径的数据库不变断言，不能仅凭正常路径或测试总数勾选完成。
- 除 **0.2.7** 明确区分的外部资源验收外，下列批次均为 **A · 本机可验证**；`OWNER: USER` 是实现分工，不免除 Agent 准备骨架、解释与验证证据的责任。
- 旧文档若仍写旧号或 `K0x`，以本文件对照表换算；进度只认本文件 **0.2.x** 编号。

## 编号对照

| 旧号 / 别名 | 现行正式号 | 说明 |
|---|---|---|
| K01（工程骨架） | 历史 H1 | 已交付 |
| K02（持久化） | 历史 H2 | 已交付；补修证据在 H3 |
| K03-P | 历史 H3 | 已放行，见 [02 放行](../reviews/02-release-h3.md) |
| K03-W / K03-B / K03-X | 历史 H4 | 管理页、适配器、索引清洗已交付 |
| K03-A / C / D / E；施工别名 K01 | **0.2.1** 子批 A / B / C / D | 错误码、Loop、Turn 接线、execute |
| K04；施工别名 K02 | **0.2.2** | ToolRuntime |
| K05；施工别名 K03 | **0.2.3** | Memory / Relationship |
| K06；施工别名 K04 | **0.2.4** | Outbox / SSE / 内嵌对话页 |
| K07；施工别名 K05 | **0.2.5** | Task / BackgroundTask |
| K08；施工别名 K06 | **0.2.6** | 生命周期探针 |
| K09；施工别名 K07 | **0.2.7** | 故障、恢复与资源 |

审查稿、测试工作簿里的旧号是当时写法；新开工一律用 **0.2.x**。施工单文件名可仍用 `k01-…`，文首须写正式号。

---

## 历史交付

下列工作已关闭。细节以代码、[02 放行](../reviews/02-release-h3.md) 与 [08 批次记录](history/08-manage-adapter.md) 为准。不要按历史目标重做。

### H1 · 工程骨架与依赖门禁（旧 K01）

`api` / `kernel` / `app` 三模块；`/internal/live`；依赖门禁与负例（含 P-E / T6）。

### H2 · SQLite、Conversation、Message、Turn（旧 K02）

Flyway V001–V004；`receive` 幂等；Turn 状态机；`commit` 同事务写 Message/Turn/Outbox；一致性备份；HTTP 建会话与接收回合。不做完成回合的模型调用。

### H3 · 接入模型前的补修与协议收口（旧 K03-P）

T1—T7 关闭；T8 重复索引延期到相关 migration 或最迟现行 **0.2.7**。约束仍生效：全局 `clientRequestId`；独立 `executionId`；COMMITTING 冻结计划；完成事件与序号由 committer 分配。R03 进程强杀、R10 整库堆峰值属 **0.2.7**。

### H4 · 管理页、模型适配器与索引清洗（旧 K03-W / B / X）

- 管理页：V005–V006、`/api/manage/model`、本机免口令、`/manage/` 静态壳。
- 适配器：`VendorAdapter`、`ModelPort.decide`、TTL 目录缓存、`POST /probe`。探测不是会话入口。
- 清洗：索引进度与 `.gitignore` 的 `target/`；未删审查原文、研究存档或仍有独立断言的测试。

---

## 当前 · v0.1 内嵌对话页

`status`: **已完成，计入 v0.1** — `/chat/` 与管理壳共用导航；已启用模型时发送会在同一次请求里完成直接回答；未启用时只收下回合并说明原因。这不是 v0.2 harness。

- 页面在 `/chat/`，不嵌进管理面板。管理侧栏只链出去。关掉管理页后这个入口仍在。
- 页面脚本不写 URL、不 `fetch`。唯一 HTTP 出口是 `/chat/api.js` 的 `createConversation` / `sendTurn`。
- 这两个函数打 `POST /api/conversations` 和 `POST /api/conversations/{id}/turns`。已启用模型时响应带 `reply`，回合进入 COMPLETED。同一 `clientRequestId` 重放已完成回合只回放已保存回复，不再调用模型。
- 未启用模型时回合停在 RECEIVED，`detail` / `reasonCode` 说明原因。页面不得在没有 `reply` 时装作已经回答。
- 不做 SSE、Outbox 补发、消息回读，也不执行工具。刷新后本页不恢复历史。
- Loop 完成后只改 `api.js`（以及届时的服务端对话接口），不把厂商调用写进页面。

---

## v0.2 · 0.2.1 起（未开工）

下列 **0.2.1–0.2.7** 全部属于 **v0.2 单核 harness + 单核节点**。不要在 v0.1 上补做。

## 0.2.1 · 错误码、Agent Loop 与 Turn 接线（别名 K01）

`status`: **v0.2 第一步，未开工** — 顺序见 [路线图](../plans/roadmap.md)；分步见 [0.2.1 施工单](../plans/k01-agent-loop.md)。

### 目标

使用已启用模型完成有预算的决策循环，并把结果经 Turn 提交。同时完成**全进程唯一**的错误 code 与日志约定；**0.2.2** 及以后引用这套规范。

### 子批

| 子批 | 旧称 | 状态 | 交付 |
|---|---|---|---|
| 0.2.1-A | K03-A / K01-A | 可与 B 交错 | 全进程错误 code 与日志约定；收编历史 H2 的 `reasonCode` |
| 0.2.1-B | K03-C / K01-B | **下一步** | Loop 类型与 `OWNER: USER` 骨架；直接回答 |
| 0.2.1-C | K03-D / K01-C | 等 B | TurnEngine：认领 → Loop → 冻结计划 → 提交；R01—R05 复验 |
| 0.2.1-D | K03-E / K01-D | 等 C | live 装配与对外 `execute` 入口 |

### 防复发提示（T1/T2/T3/T5/T7）

TurnEngine 管认领、上下文与提交，Loop 只返回 Outcome。认领成功后才调用模型；重复请求、旧 owner 的迟到结果不能触发第二次业务。原始 Message 不因 prompt 裁剪改变。沿用 H3 的持久化计划恢复。管理页不直连厂商、不分配 Turn 序号。

### 异常系统与日志系统

分类起点见 [`28`](04-kernel-reference.md) 第 14 节；预期业务失败用封闭结果（见 [`34`](03-architecture.md) 第 11 节）。

- 稳定 `code` 只登记一处。
- 厂商 SDK、JDBC、HTTP 异常在 app 边界译成上述 code，不得原样进入 kernel。
- 编程缺陷与进程级不可恢复故障仍走异常。
- 日志只记稳定 code、操作类别、关联 ID、耗时与脱敏原因；不含密钥、SQL、堆栈或原始敏感正文。
- 历史 H2 的 `reasonCode` 收进这一套，不保留第二套字符串。

### Codex 可生成

- **A：** 错误模型与日志约定的类型、登记处和最小实现；
- **B—D：** `AgentInput/Outcome/Budget/Trace`；`DefaultAgentLoop` 带 `OWNER: USER` TODO；TurnEngine 与提交恢复测试；live/`execute`。

### 用户实现（OWNER: USER）

- `DefaultAgentLoop.run`（B）；
- ContextAssembler 中上下文取舍策略；
- 模型拒绝、空回答和预算耗尽的产品行为；
- 用户可见的基础错误文案。

### 验收

```text
[ ] 直接回答测试（0.2.1-B）
[ ] 工具分支占位测试
[ ] 最大 3 次模型决策
[ ] 15 秒软预算、30 秒硬上限可配置
[ ] 全进程只有一套错误 code 与一套日志约定
[ ] 历史 reasonCode 已纳入，没有第二套
[ ] 超时/限流/格式错误使用该套 code
[ ] 日志不含 API key、SQL 或堆栈
[ ] kernel 不依赖具体日志实现或厂商异常类型
[ ] Loop 不 import SDK/SQL/Controller
[ ] 同键重试不增加模型调用；错误 owner 的迟到 Outcome 不写正式事实
[ ] 模型完成但提交响应丢失时回放已保存结果，不重跑模型
[ ] 同会话执行顺序与上下文一致，跨会话并发有配置上限
[ ] 原文保存与 prompt 裁剪分离；COMMITTING 恢复不依赖进程内 Outcome
```

### 禁止

- 按 Controller、持久化、模型、工具各写一套错误码或日志格式；
- 把 manage `probe` 当成 Turn 业务路径，或在 B 之前把 Loop 接到 HTTP 会话接口；
- 把管理页扩大成 **0.2.4** 对话页 / SSE，或建成独立 wn-manage 工程。

## 0.2.2 · ToolRuntime 与只读工具（别名 K02）

### 防复发提示（T1/T2 的同类问题）

`operationId` 须绑定工具名、规范化参数摘要与来源 Turn/Run。执行权用当前尝试身份。外部调用在短事务之外，超时不是“肯定没执行”。

### 目标

深 Module `ToolRuntime`，提供 `current_time`、`calculate`，可选受限 `http_read`。

### Codex 可生成

- descriptor/schema/request/result；
- `ToolRuntime` Interface 与封闭 outcome；
- 内部 catalog、validator、policy、budget；
- ToolOperation 存储；
- Local/Fake Adapter 骨架与幂等测试。

### 用户实现

- 工具对模型可见的描述；
- Loop 只接入 `ToolRuntime.execute`；
- 工具错误如何形成 observation（使用 **0.2.1** 的错误 code）。

### 验收

```text
[ ] 不存在工具不会执行
[ ] 非法参数不会进入 Adapter
[ ] 每次执行有 operationId
[ ] 结果受大小限制；超时有限
[ ] 相同 operationId 参数冲突被拒绝
[ ] Agent Loop 不直接依赖 Validator、Policy、OperationStore 或具体 Adapter
[ ] 无 Shell、任意文件写入、桌面工具
[ ] 改工具名/参数/来源时冲突，Adapter 调用次数不增加
[ ] 响应丢失可查原结果；UNKNOWN 不盲重试
```

## 0.2.3 · Memory 与 Relationship（别名 K03）

### 防复发提示（T1/T2/T7）

核验 `sourceTurnId` 的身份与作用域。历史 H2 的占位换成不可变 `ApprovedChange`；冲突后重新评估，不能把 expected revision 改成最新值后原样覆盖。

### 目标

最小真实长期记忆与关系状态。正式变更只能作为 `ApprovedChange` 经 `TurnCommitter` 与 Turn 同事务提交。

### Codex 可生成

- candidate/decision/change/snapshot 类型；
- `MemoryRuntime` / `RelationshipRuntime`；
- Repository 与 SQLite Adapter；
- 06 工作簿测试骨架。

### 用户实现（OWNER: USER）

- MemoryPolicy；Recall 排序与预算；Relationship evaluate；beforeTurn/afterTurn。

### 验收

```text
[ ] 正式记忆有 sourceTurnId
[ ] 敏感推断不自动保存；冲突不静默覆盖
[ ] 过期记忆不默认召回；Relationship 使用 revision
[ ] Runtime 只返回 ApprovedChange，经 TurnCommitter 原子提交
[ ] 用户可查看和纠正；Prompt 不泄露未许可敏感内容
[ ] 身份/作用域错配拒绝；合法跨会话记忆不误拒
[ ] owner 或 expected revision 冲突时五表全部不变
[ ] 同一提交重试不重复应用；恢复接口不绕过生命周期
```

## 0.2.4 · Outbox、SSE 与内嵌网页（别名 K04）

### 防复发提示（T1/T3/T5，审查 §6.5）

复用 H3 的必需完成事件与事务内序号。最小单用户鉴权覆盖 HTTP、SSE、历史补发和 internal；**0.2.6** 再扩展探针测试。

### 目标

可靠交付已提交事件；页面断线不重做业务。

### Codex 可生成

- OutboxPublisher；SSE；event cursor；简单内嵌页面；断线重连与鉴权测试。

### 用户实现

- 页面展示文案与顺序；哪些内部事件不暴露。

### 验收

```text
[ ] 提交后才发送 SSE；lastEventId 补发正确
[ ] 重连不增加模型/工具调用
[ ] 慢客户端有缓存上限；页面关闭不取消已提交 Turn
[ ] 内部敏感 trace 不经 SSE 暴露
[ ] 逆序完成仍能按 cursor 补发
[ ] 未认证不能读会话/SSE/历史；越界 cursor 不扩大可见范围
[ ] 请求 key 与会话绑定；缩进/换行可往返保存
```

## 0.2.5 · TaskRuntime、BackgroundTask 与 SubAgentRun（别名 K05）

### 防复发提示（T1/T2/T3/T7）

每次 attempt 独立身份与 lease。旧 attempt 迟到结果不能更新正式任务结果。TaskDraft 仍经 TurnCommitter。

### 目标

长任务不占住对话；失败可追踪、可重试。

### Codex 可生成

- `TaskRuntime`、TaskDraft、状态机；Repository；LocalTaskExecutor；lease/retry/cancel 骨架。

### 用户实现

- BackgroundPolicy；TaskResult 语义；任务完成后的交付表达。

### 验收

```text
[ ] Task 与确认回复同事务；TaskDraft 只经 CommitTurnPlan
[ ] 原 Turn 完成后可继续聊天；重试创建新 Run
[ ] LOST lease 不复活；取消阻止新 Run
[ ] SubAgent 不直接写 Memory/Relationship，不冒充最终回复
[ ] 旧 attempt 不能提交新 attempt 的结果
[ ] 取消与完成竞争有唯一裁决；任一步失败全部回滚
```

## 0.2.6 · 外部生命周期探针（别名 K06）

### 防复发提示（T4，审查 §6.6）

固定绝对数据目录。沿用 **0.2.4** 访问策略。drain 不能强行取消已冻结 COMMITTING，也不能释放 owner 让双执行并存。

### 目标

为未来 Guardian 提供最小稳定协议，不实现 Guardian。

### Codex 可生成

- `/internal/live`、`ready`、`version`、`drain`；probe DTO 与测试。

### 用户实现

- ready 所需依赖；drain 体验与最大等待时间。

### 验收

```text
[ ] live 不把所有依赖当必要条件；ready 检查库与核心装配
[ ] version 返回 build/source/protocol
[ ] drain 后不接新 Turn，等待现有 Turn 至终态或超时
[ ] 无 stable/previous/active 管理；无进程自我替换
[ ] 两 cwd 同绝对数据路径仍开同库
[ ] drain 超时不伪造完成或重置 COMMITTING
```

## 0.2.7 · 故障、恢复与资源验收（别名 K07）

### 防复发提示（T1—T8 的最终交叉验证）

验证已确定的恢复协议，不在此时才补 owner、序号或提交计划。不得把新 JDBC 连接等同于完整进程重启，也不得把普通主机实测等同于 2 核 2 GB。

参与拆分：**0.2.7-A** 本机故障注入与临时库恢复；**0.2.7-B** 仅在需要等价资源限制时由用户或对端回传证据。B 未回传不得勾选资源验收。

### 目标

证明 0.1 在声明故障模型内可恢复，并适配国内 2 核 2 GB 节点。

### Codex 可生成

- FailureInjector；恢复扫描器骨架；资源测试脚本与验收报告模板。

### 用户实现

- 运行并理解每个故障场景；修正不符合预期的核心行为。

### 验收

```text
[ ] 04/07 的故障点已运行；无双 owner；无盲目重复副作用
[ ] 已提交 outbox 可补发；SQLite 备份恢复成功
[ ] 2 核 2 GB 等价环境通过；R01—R12 全部完成
[ ] COMMITTING 前按 step/operation 判定能否重试；进入后只提交冻结计划
[ ] 迁移/备份失败、残缺快照、手工同名目录均已验证
[ ] 重复索引已以查询计划处理或明确保留（T8），不重写已发布 migration
```

## 最终交付证据

```text
实现提交/工作树状态：
参与档 A / B 与 B 侧待回传证据：
审查 T1—T8 对应关闭状态 / 修复验收报告：
模块与文件清单：
OWNER: USER 完成位置：
全部测试命令与退出码：
错误输入 / 冲突 / 并发 / 取消 / 重试的关键断言与实际输出：
故障注入报告：
SQLite 备份恢复报告：
2 核 2 GB 资源报告：
已知限制：
明确延期：v0.3 世界树与多核；更后的 Guardian / wn-agent。
```

**0.2.1–0.2.7** 全部通过，才能把 **v0.2** 标成完成。v0.1 已按当前进度封版，不把 harness 欠账算回去。
