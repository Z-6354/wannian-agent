# 0.2.3 · Memory 与 Relationship 施工单（别名 K03）

`status`: **已交付**（2026-09-24）· R1 锁定；A–D+/L 入仓；自动化 + 真人路径可用  
`version`: **0.2.3**（施工别名 K03）  
`design`: [memory-system-0.2.3.md](../research/memory-system-0.2.3.md) §4 + **§4.1 R1 增补** — 冲突以本施工单为准  
`b-impl`: [k03-b-hotpath-impl.md](./k03-b-hotpath-impl.md) — 热路径（实现与代码审、全量自动化完成）  
`d-impl`: [k03-d-recall-tombstone-http.md](./k03-d-recall-tombstone-http.md) — 召回 A / 弱 B / HTTP（实现与代码审、全量自动化完成）  
`prerequisite`: **0.2.1 / 0.2.2 已交付**；0.2.3 A–D/D+ 实现和代码审已完成，V010 已加入  
`build-policy`: **Maven 在线构建；本地仓库固定指向 `C:\Users\han\.m2\repository`，避免沙箱用户 home 改变 Maven 默认仓库路径。**  
`code-gate`: 阶段 D 以 `k03-d-recall-tombstone-http` **生产文件**清单为准；须分文件审核通过后再写。协作门见 [version-stage-workflow.md](./version-stage-workflow.md)（普通阶段不交付测文件；测+真人归版本末段）。

### Astra 审查后续修复（2026-09-24）

- 用每个伴身/主题的持久 generation 对热 Turn、Review、HTTP correct/forget 和 tombstone 做并发围栏；旧冻结计划中的无代次记忆变更会跳过，Turn 本身仍可完成。同一 Review 批次同主题的不同草案全部跳过，完全重复的草案仍幂等去重。
- Review 在 claim 时记录实际活动水位；扫描按成功任务的观察水位判断新内容。V012 清除 V009 无法证明的历史入队水位，升级后允许保守补审。
- Recall touch 改为受 SQLite 参数上限约束的批量 UPDATE；生产默认 Review LLM 改成无状态空实现，Fake 移入测试源集；工具结果按 JSON 结构脱敏并保持截断后的 JSON 合法。
- 定时 Review ticker 增加配置开关：生产默认启用，测试源集默认关闭，避免定时任务在临时数据库目录回收后继续运行。
- HTTP 记忆 GET 投影补齐更正表单所需的 content/source/scope/path 字段，避免客户端仅靠用户重新选择或猜测原值。
- 本轮新增专项回归测试；构建已恢复在线模式；根因是 Maven 进程用户 home 为 `C:\Users\CodexSandboxOffline`，默认仓库与已有缓存 `C:\Users\han\.m2\repository` 不同。设置 `MAVEN_OPTS=-Dmaven.repo.local=C:\Users\han\.m2\repository` 后全量测试通过：kernel 90 + app 165 = 255 项，0 failures/errors/skips。运行中修复 MemoryHttpTest wildcard 泛型断言及 ConsistencyBackupTest 的旧 V010 版本断言（当前 V012）；`git diff --check` 通过。
- 2026-09-24 本机 live：预算种子改为 10/60/100；系统工具独立上限与枚举可见后，记忆写入/召回可用；随版本收口勾选完成。
- 2026-09-24 预算种子改为 **决策 10 / 软 60s / 硬 100s**（`application.yml` + 运行期 `wannian.json`）。系统工具不计决策轮与预算错误码分化已实施，见 [k03-l-run-journal.md](./k03-l-run-journal.md) §6。

给执行者（luna）的指令。关键设计已钉死；缺口先回报，禁止自行扩大范围。

---

## 0. 选定摘要（必须遵守）

```text
S0-b      回话 + remember / 关系 tool（分 descriptor）
S1-b+c    热：仅 tool；冷：间隔 10 user-turn + idle 30min
          冷执行面：队列 Job + 可替换 Worker（同 JVM 单消费者起步）
S2-ba     B=tool 交 facts；C=Worker 独立 LLM（对齐 Open WebUI）
          同一次输出须含：规范化 claim + contentKind/sourceKind + importance
S3-ca     B=tool 类型校验；C=同次 JSON 轴校验/映射本仓枚举
          + claim 规范化检查（见 §0.1）+ importance 必填/钳制
S4-a-min  仅拒密钥类；其余一律可存（本地私人助手；**无** S0/S1/S2 敏感度轴）
S5-c-tool 关系专用 tool；明示或整理发现时调；与记忆解耦
S6-a+b    热：Freeze 同事务；冷：短事务 Committer 族
S7-a      SQLite；Store 只读；表含 importance
S8-a-min  【R1】衰减召回：score = w_r×decay + w_i×importance（固定 HL=30d）；无向量
S10-a-mem 【R1】Assembler 注入按 score 排序的 ACTIVE Top-N；Rel 照旧
S11-a     HTTP GET / correct / forget（人主动忘；与弱 B 正交）
S12-weak-B【R1】确定性扫墓 Job（score+minAge → FORGOTTEN）；不做 dreaming / LLM 评分提升
```

**本批范围代号：R1**（用户 2026-09-23 确认）= 规范化 claim + 模型 importance 落库 + A 衰减召回 + 弱 B 扫墓。

**禁止：** 内容启发式/正则抽记（含用正则把「今天」换成日期——须模型 + 宿主注入的绝对时间锚）；tool/Worker 直写表；每轮同步 Propose（S1-a）；本批向量；本批 Jev；Open WebUI/Hermes Python 常驻进程；用 importance LLM **第二次**单独打分（分必须在 S2 同一次 tool/Review JSON 里给出）；OpenClaw Deep 式启发式评分提升。

### 0.1 产品增补（用户 2026-09-23 · R1 已锁定）

#### （1）重要性：模型打分

- 落库字段：`importance`（`REAL`，范围 **\[0.0, 1.0\]**；越界钳制或 Shape 拒——缺省策略实施写死：**缺字段则 Shape 拒**，不默默填 0.5）。
- **谁打分：** 回话 tool / Review LLM 在**同一次**结构化输出里给出。
- **示例语义（验收用语，非正则规则）：**
  - 「我叫小明」→ 高分（身份类长期事实）→ 很久仍应可召回。
  - 「我今天晚上要吃炒蛋」→ 低分（短时意图）→ 今天/明天有用，过段时间 `score` 应偏低，并可被弱 B 墓碑。
- Policy：**不**因低分 REJECT；低分仍可 ACTIVE。

#### （2）时间遗忘：A + 弱 B

**衰减公式（用户 2026-09-23 锁定 · 对齐 CrewAI 形，无向量）：**

```text
decay = 0.5 ^ (ageDays / HALF_LIFE_DAYS)     // HALF_LIFE_DAYS = 30（固定）
score = w_r × decay + w_i × importance       // w_r=0.4, w_i=0.6；importance∈[0,1] 钳制
```

- **不**用 halfLife(importance) 调制半衰期：轻重交给模型 importance；代码只做固定时间淡化 + 加权。  
- 高分身份：时间项→0 后仍有 `w_i×高分` 垫底，长期可召回。  
- 低分琐事：靠模型打低分 + 弱 B 清掉。

| 档 | 行为 |
|----|------|
| **A 召回衰减** | 仍为 `ACTIVE`；按 `score` desc 排序（同分 `created_at` desc）；截断 Top-N / 字数预算。**无向量**。 |
| **弱 B** | **A+B（用户 2026-09-23）**：满足任一即墓碑候选（仍须短事务）：① `score < TOMBSTONE_EPSILON`（`0.15`）且 `ageDays > TOMBSTONE_MIN_AGE_DAYS`（`7`）；② `importance < IMPORTANCE_EPHEMERAL`（`0.35`）且 `ageDays > EPHEMERAL_MAX_AGE_DAYS`（`14`）。确定性；零 LLM。 |
| **人主动 forget** | S11；与弱 B 独立。 |

常数写死在 `MemoryDecay`；禁止魔法数散落。

#### （3）claim 规范化（写入前修正用户话）

落库 `claim` **禁止**原样搬运未锚定的指示语；须在 **S2 同一次**模型输出中写成可跨会话复用的命题。宿主必须向 tool/Review **注入锚**（至少）：

- **绝对时间锚：** 宿主注入 Mem0 式 **Observation Date**（时区写死 `Asia/Shanghai` 墙钟）→ 模型把「今天/明天/昨晚」写成该日历日。实现见 [k03-b-hotpath-impl.md](./k03-b-hotpath-impl.md) §3.6；**不**靠模型先调 `current_time` 才算锚定。
- **实体锚：** 已知地点/场景由用户话明确时模型写入 claim；未知则**不记或写明地点未说明**（对齐 Mem0）。**本批不做 IP/geo 工具。** Shape **不**用夹具词拒写（用户 2026-09-23 选 A）。

示例：

```text
用户：我今天吃蛋炒饭
落库：2026-09-23 吃蛋炒饭

用户：我今天在这里旅游玩得很开心
落库：2026-09-23 在{具体地点}旅游，玩得很开心
```

**不抄：** 正则替换「今天」；无锚时瞎编城市。

#### （4）本批范围

| 本批做（R1） | 更后 |
|--------------|------|
| 规范化 claim + `importance` 落库 + Shape | 向量、用户手改分 UI |
| A 衰减召回（固定 HL + 加 importance）+ Assembler Top-N | 语义 relevance / 向量 |
| 弱 B 确定性扫墓（score+minAge） | dreaming / Deep 评分提升 |

---

## 1. 事实 / 推断 / 已定

### 已验证

- `wn-server` 当前无 memory/relationship 生产包（已回退 0.2.2）。  
- 0.2.2 已有：`TurnEngine`、`ToolRuntime`、`ContextAssembler`、`SqliteTurnCommitter`、`FreezeCommitPlan`、`CommitTurnPlan`。  
- 设计选定见设计文 §4；R1 增补见 §4.1 / 本单 §0.1。

### 已定（实施不得偏离）

| 项 | 决定 |
|----|------|
| companion | 本批常量 `yanhuo`（`CompanionIdentity.YANHUO`） |
| 写缝热路径 | 正式变更只经 `TurnCommitter.commit` + Freeze |
| 写缝冷路径 | Review / 弱 B / 无 Turn → **短事务** Committer 族 API，须 CAS、真写、稳定错误码 |
| Policy | **仅密钥类 REJECT**；其余一律可存（本地私人助手；无敏感度分级/加密） |
| Review | `interval=10`；`idle=30m`；enqueue 立即返回；Worker 可换 |
| 轴 | 落库须带本仓枚举 + importance；WebUI `user/context` 须**映射表**写死 |
| 召回 | **S8-a-min**：ACTIVE × `score` 排序 Top-N；无向量 |
| 弱 B | `score < ε` 且 `ageDays > minAge` → FORGOTTEN；非 dreaming |
| Task | `taskDraft` 非空仍 `UNSUPPORTED_EXTENSION` |
| 2C2G | 不嵌第二本地大模型进程；Review 与回话 ModelPort **可分 bean**；错峰优先 idle；弱 B **零 LLM** |

### 待实施时写死（小参数；挡 Freeze / 召回 / 弱 B 接线）

- [x] 空闲检测：会话 `last_activity_at` + 定时扫（建议 ≥1min tick）  
- [ ] 密钥词表/模式最终列表（见 §3.3）  
- [x] Review 与 tool 同 subject 去重（建议：同 claim 规范化相等则跳过）  
- [x] tool 正式名：**`remember_fact`** / **`update_relationship`**（用户推进阶段 B 时写死）  
- [x] `importance`：Draft/JSON 缺字段在进 Shape 前拒；Shape 内越界钳制 \[0,1\]（用户选 A：无夹具词）  
- [x] `MemoryDecay` 常数已钉：HL=30、w_r=0.4、w_i=0.6；弱 B=A+B（ε=0.15/minAge=7 **或** importance<0.35/age>14）（改须改类+单测）  
- [x] 召回 Top-N 与字数预算（见 k03-d：TOP_N=12 / CHAR_BUDGET=2000）  
- [x] 时间锚时区 `Asia/Shanghai` + Mem0 Observation Date（宿主注入；见 k03-b）  
- [x] 地点锚本批：固定「未说明」；不做 IP；会话元数据/近讯抽地名 → 更后  
 
- [x] 弱 B 调度：与 IdleScanner **同 tick 末尾**（见 k03-d）

---

## 2. 目标与非目标

### 目标

1. B 轨：模型调记忆 tool（规范化 claim + importance）→ Shape → Policy → Approved* → Freeze 同事务。  
2. C 轨：间隔/空闲 → Job → Worker LLM → Shape → Policy → 短事务；不挡聊天。  
3. 关系：独立 tool → 独立草案 → 同事务或短事务；记忆路径不得偷改 rel。  
4. HTTP：列表 ACTIVE、纠正 SUPERSEDE、遗忘 FORGOTTEN。  
5. Assembler：**按 score 注入记忆 Top-N**；Rel beforeTurn 可注入。  
6. 弱 B：`score < ε` 且过 minAge → FORGOTTEN 墓碑。  
7. Port 齐全，Worker 可替换（为副节点预留）。

### 非目标（本批）

S1-a 每轮同步 Propose；Jev；Mem0 Provider；完整 memory.md Job lease 全套（允许**最小** SQLite job 表 + 单消费者）；0.2.4 SSE/历史恢复 UX；**向量**；OpenClaw Deep 启发式评分提升；**第二次**专用 importance LLM；dreaming；用户手改 importance UI。

---

## 3. 类型、Port、模块落点

包：`com.wannian.server.kernel.memory` · `...relationship` · app 侧 Adapter / Config / SQLite。

### 3.1 枚举（kernel）

```text
ContentKind: USER_PREFERENCE | USER_FACT | SHARED_HISTORY | TASK_CONTEXT
SourceKind: EXPLICIT | OBSERVED | INFERRED | REFLECTION   // 本批 tool/review 默认 EXPLICIT 或 OBSERVED；INFERRED 不强制拒（S4-min）
MemoryLifecycle: ACTIVE | SUPERSEDED | REJECTED | FORGOTTEN
MemoryScope: COMPANION | CONVERSATION | TASK   // 默认 COMPANION
```

**不设 `MemorySensitivity`（用户 2026-09-23）：** 产品为**本地私人助手**；除密钥外均可存储。S0/S1/S2 分级与 S1 加密**本批不做、不建枚举、表不建 sensitivity 列**。机密仍仅由 `SecretOnlyMemoryPolicy`（S4-a-min）拒写，不落「S2 行」叙事。

**SourceKind 取舍注明（2026-09-23 审阅）：**

- **语义：** 证据/认识论轴（凭什么信这条 claim），与 `ContentKind`（记什么）、`propose_id`（谁提出：`tool_remember` / `llm_review`）正交。勿与 Open WebUI `meta.created_by`（`manual|tool|background_review`）混成一字段——那是**写入通道**，本仓用 `propose_id` + `source_turn_id` 对齐。
- **业界：** 多数 Agent **无**此四值。本仓因 memory.md 权威序保留；本批 S4-min 几乎不按 SourceKind 裁决，**不实现**全量提升机。

WebUI 映射（写死）：

```text
type=user    → USER_PREFERENCE 或 USER_FACT（由模型字段 contentKind 优先；缺省 USER_FACT）
type=context → SHARED_HISTORY 或 TASK_CONTEXT（缺省 SHARED_HISTORY）
path         → 可写入 content_json.meta.path；不替代 subject_key
```

### 3.2 Port 清单

| Port | 默认实现 | 说明 |
|------|----------|------|
| 记忆 tool Adapter | app | 只出 `MemoryToolDraft`（含 claim/importance/轴）；禁写库；注入时间/地点锚 |
| 关系 tool Adapter | app | 只出 `RelationshipToolDraft`；禁写库 |
| `MemoryShape`/`Validate` | kernel | **本批（用户选 A）**：仅钳制 importance∈[0,1]；claim 规范化靠宿主锚+模型，**无**夹具词拒 |
| `MemoryPolicy` | `SecretOnlyMemoryPolicy` | 仅密钥拒 |
| `MemoryStore` / `RelationshipStore` | Sqlite* | 只读 |
| `MemoryDecay` | kernel 纯函数 | `score(importance, age)` = w_r×decay + w_i×importance；单测钉表 |
| `MemoryRecall` | Sqlite + Decay | ACTIVE 过滤 + score 排序 + Top-N；**非 Noop** |
| `MemoryReviewScheduler` | Sqlite enqueue | Turn 后/空闲扫 |
| `MemoryReviewWorkerPort` | `InProcessMemoryReviewWorker` | 可换 Remote |
| `MemoryReviewLlm` | app ModelPort 分 bean | structured operations（含 importance + 规范化 claim） |
| `MemoryTombstoneScanner` | app 定时 | 弱 B：零 LLM；短事务 FORGOTTEN |
| `TurnCommitter` | Sqlite 扩展 | 热路径 Freeze 含 memory+rel |
| `MemoryCommand` | app 短事务 | correct/forget + Review APPLY + 弱 B |

### 3.3 密钥判定（S4-a-min · 实施写死）

**产品前提：** 本地私人助手；除密钥外用户内容均可长期存储，不做 S0/S1/S2 分级。

命中任一则 REJECT（不落库）：

- 明文密码/口令上下文（如 `密码是`、`password=`）  
- `api_key` / `access_token` / `refresh_token` / `Bearer eyJ`  
- PEM `BEGIN PRIVATE KEY` / `BEGIN RSA PRIVATE KEY`  
- 连续 16+ 位似卡号且伴随 CVV/验证码  

**不拦：** 收入、健康、偏好、城市、银行名、含糊冲突等——本地私人助手一律可存；仅密钥类拒。

---

## 4. 运行时序

### 4.1 热路径（B + 关系 tool）

```text
Loop（ModelPort + tools；descriptor/上下文含时间锚、地点锚）
  → remember_* / update_relationship 调用
  → Adapter → Draft{规范化 claim, importance, 轴…}（禁写库）
  → Shape（枚举/importance/规范化）→ Policy（密钥）
  → 累积到本轮 pending Approved*
sealReply / Freeze
  → FreezeCommitPlan{assistant, memoryChanges, relationshipChange?}
  → commit 同事务（写入 importance）
Turn 完成后（非阻塞）
  → 若 userTurnCount % 10 == 0 → enqueue(INTERVAL_REVIEW)
更新 last_activity_at
```

### 4.2 冷路径（C）

```text
IdleScanner：now - last_activity_at >= 30min 且会话有新内容自上次 review
  → enqueue(IDLE_REVIEW)
Worker（单线程消费）
  → 读近讯 + ACTIVE 摘要（条数上限，对齐 WebUI ~16 消息 / ~80 记忆行量级，常数可配）
  → 注入时间/地点锚
  → MemoryReviewLlm.stream=false 结构化 JSON（claim 规范化 + importance + 轴）
  → Shape → Policy → MemoryCommand 短事务 APPLY
  → 失败：job RETRY_WAIT / DEAD；日志；不挡聊天
```

Review 系统提示：持久才记、优先 replace/remove；**须打 importance**；相对时间/代称须锚定；密钥仍过 S4。

### 4.3 召回（S8-a-min / S10）

```text
ContextAssembler / beforeTurn
  → MemoryRecall：ACTIVE only
  → score = MemoryDecay.score(importance, now - created_at)
  → 按 score 排序，截断 Top-N / 字数预算
  → 可选 bump last_recalled_at（本批建议：仅注入成功后 bump；实施写死是否同事务）
  → relText = relationshipRuntime.beforeTurn(...).toPromptText() 若有
  → 近讯照旧
```

### 4.4 弱 B 扫墓

```text
TombstoneScanner（与 idle tick 错峰或同 tick 末尾；单线程）
  → 扫描 ACTIVE（可分页）
  → 若 score < ε 且 ageDays > minAge
  → MemoryCommand.forgetInternal / tombstone（短事务，CAS）
  → 零 LLM；失败记日志，不挡聊天
```

---

## 5. 持久化

### 5.1 Schema

记忆、关系和 Review schema 按 Flyway 顺序由 V008、V009 建立；V010 新增 Review 应用幂等账本，见 `app/src/main/resources/db/migration/V010__memory_review_apply_ledger.sql`。

**memory_record**（最小列）：

```text
id, companion_identity_id, subject_key,
content_kind, source_kind, scope,
content_json, source_turn_id,
source_message_id NULL, propose_id, triage_id,
status, valid_from, valid_until, supersedes_id,
revision, created_at, last_recalled_at NULL,
importance REAL NOT NULL   -- [0,1]；A 衰减与弱 B
```

（无 `sensitivity` 列：本地私人助手 + S4-a-min 只挡密钥，见 §3.1。）

本批 `propose_id`：`tool_remember` | `llm_review` | `tombstone_scan`（弱 B）；`triage_id`：`validate`。  
索引：`(companion_identity_id, subject_key, status)`；弱 B 可加 `(status, created_at)`。

**relationship_state**：companion PK + state_json + reason_json + source_turn_id + revision + updated_at。

**memory_review_job**（最小）：

```text
id, conversation_id, companion_id, trigger (INTERVAL|IDLE),
status (PENDING|RUNNING|SUCCEEDED|RETRY_WAIT|DEAD),
attempt, lease_owner, lease_until, last_error,
created_at, updated_at
```

唯一意向：避免同一 conversation 重复堆积过多 PENDING（实施可：同 trigger 类型合并或跳过已有 PENDING）。

弱 B **不必**进 review_job 表（可用独立 scanner 游标/进度）；若复用 job 表须新 trigger 值并写死，禁止空壳 SUCCEEDED。

### 5.2 SqliteTurnCommitter（热）

同事务顺序：校验 Freeze → Message/Turn/Outbox → memory 条（含 SUPERSEDE CAS、写 importance）→ relationship CAS → 删 freeze。  
幂等：COMPLETED + 同 executionId → 回放，不双写 memory。

### 5.3 短事务（冷 / S11 / 弱 B）

独立连接短事务：APPLY review、correct/forget、弱 B 墓碑；revision CAS；失败稳定 `ErrorCodes`。

---

## 6. HTTP（S11-a）

| 方法 | 路径 | 行为 |
|------|------|------|
| GET | `/api/memory?companion=yanhuo` | 列 ACTIVE（可带 importance；无密钥正文；本批无 S1 正文） |
| POST | `/api/memory/correct` | 旧 id + 新 claim（须仍规范化）→ SUPERSEDE + CAS；新行须带 importance |
| POST | `/api/memory/forget` | id → FORGOTTEN（正文清空策略写死一种）+ CAS |

禁止假 200。

---

## 7. 分阶段实施（依赖顺序）

### 阶段 A · 契约与表

- [x] kernel 枚举 + Draft/Approved/Stored（含 `importance`）；`MemoryToolDraft` 将 importance 定义为 primitive `double`，已构造的 Draft 不存在 null/missing 状态  
- [x] Port 接口齐；`MemoryDecay` 纯函数 + `MemoryDecayTest`；`MemoryRecall` 接口 + `DefaultMemoryRecallTest`  
- [x] Flyway 表（含 importance）：`MigrationSmokeTest`；空 memory 热路径：`PersistenceLineSmokeTest.createReceiveThenCommitPersistsFullChainAndSurvivesReopen`  
- [x] SecretOnlyMemoryPolicy 单测：`MemoryShapePolicyTest.rejectsSecretButAcceptsOrdinaryLowImportancePreference`  

### 阶段 B · 热路径 tool + Freeze

**实施指令真源：** [k03-b-hotpath-impl.md](./k03-b-hotpath-impl.md)（分文件审核 1–10 已通过 · 代码已落地 · 测留总收口）。  
另含：工具 `locked|on|off`、锁死四名、`byName` 存盘（不兼容旧 enabled）、管理页、Mem0 锚。

- [x] `remember_*` / `update_relationship` 注册且默认可见（锁死 + 种子 + byName）  
- [x] descriptor/上下文注入 Mem0 观察日 + 地点未说明（ContextAssembler）  
- [x] Adapter → Shape（含 importance）→ Policy → pending Approved*（半成品核对）  
- [x] Freeze/Commit 含 memory+rel+importance；plan_json v2 缺 importance 抛错  
- [x] 密钥样例拒、普通低 importance 偏好放行、越界钳制且不改写「今天/这里」：`MemoryShapePolicyTest`  
- [x] 缺 importance 在 Draft 构造前的 Tool JSON 边界拒绝且不入 pending：`RememberFactToolAdapterTest.missingImportanceIsRejectedAtInputBoundaryBeforeDraftCreation`；`MemoryToolDraft` 的 `double` 不支持缺失值，构造器拒绝 NaN/Infinity。冻结计划缺 importance 拒绝由 app `RecoverableCommitPlanTest.rejectsMalformedV2FrozenPlanInsteadOfSilentlyDroppingMemory` 覆盖  
- [x] memory 写入失败时助手消息、outbox 与 memory 同事务回滚：`TurnCommitterAtomicityTest.memoryWriteFailureRollsBackAssistantOutboxAndMemoryAfterFrozenPlan`  


### 阶段 C · Review Job + Worker

- [x] enqueue（间隔计数 + idle 30min）  
- [x] InProcess Worker + 假 ModelPort（输出须含规范化 claim + importance）  
- [x] 短事务 APPLY；失败不挡 Turn  
- [x] Worker Port 可替换（接口 + 单测 fake）  
- [x] 过期 RUNNING lease 在认领事务内回收；Review draft batch、job 成功状态与 draft 幂等账本在同一 lease-fenced SQLite 事务提交（V010）；IDLE 按入队活动水位去重，DEAD 不因同一水位重复入队（V009）。应用回归覆盖 worker 重启、过期 lease、attempt 上限、跨 tick DEAD、APPLY 回滚与完成重放。  

### 阶段 D · 召回 A + 弱 B + HTTP

**实施指令真源：** [k03-d-recall-tombstone-http.md](./k03-d-recall-tombstone-http.md)。基线 D 与 D+ 已交付；验收见 [实施清单 · 0.2.3](../guide/01-checklist.md)。

- [x] `MemoryRecall` + Assembler 注入 Top-N（高 importance 久后仍在；低 importance 时效后掉出）  
- [x] `MemoryTombstoneScanner`：score+minAge → FORGOTTEN；零 LLM  
- [x] GET/correct/forget  
- [x] 窄测：衰减公式、弱 B 墓碑、主动 forget 不召回  

### 阶段 E · 收口

- [x] 清单勾选 + roadmap/plans README 状态  
- [x] 全量自动化通过（含 V008–V013）  
- [x] 真人/live 验收（预算/枚举修复后可记可搜）  

---

## 8. 测试策略

| 类 | 断言 |
|----|------|
| Policy | 密钥拒；普通偏好 ACCEPT；低 importance 不拒 |
| Shape | 越界 importance 钳制；**不**夹具拒「今天/这里」（规范化归模型+锚；对齐 WebUI/Mem0） |
| Decay | 固定 HL；高 importance 大 age 仍有垫底分；低 importance 时效后 score 低 |
| Recall | Top-N 按 score；FORGOTTEN/SUPERSEDED 不注入 |
| 弱 B | score+minAge 满足 → FORGOTTEN；不调 LLM；不挡 Turn |
| HTTP | correct SUPERSEDE；forget 不召回 |
| Review Job | 过期 RUNNING 重启恢复；认领递增 attempt；达到上限转 DEAD；同一 IDLE 活动水位跨 tick（含 DEAD）只建一个 job；水位更新可再入队 |
| 回归 | 0.2.2 Turn/Tool 窄测绿 |

窄测：`scripts/verify-local.ps1` 或 `*Memory*` / `*Relationship*` / `*Decay*` / `TurnCommitter*`。

---

## 9. 禁止的错误修法

- Loop/Tool/Policy/Worker/Scanner **直接** JDBC 写 memory/rel（须经 Committer/MemoryCommand）。  
- 正则抽「住在/叫我」或正则替换「今天」当生产路径。  
- 为过测改 expected revision 后重放旧 Approved*。  
- 用向量「顺便」做 relevance。  
- 同机再拉 Python Open WebUI/Hermes 或第二份本地大模型。  
- 记忆 JSON 夹带 rel 当正式关系写。  
- 空壳 Job / 扫墓假 SUCCEEDED。  
- 第二次 LLM 只打 importance。  
- 弱 B 调模型决定忘不忘。  

---

## 10. 依赖与授权

- 依赖 0.2.1 / 0.2.2。  
- 分文件审核通过后方可写入对应文件；未授权不 commit / push。  
- 越出本单（含擅自改 Decay 常数语义为向量）先问。  

---

## 11. 执行者交付格式

```text
批次：0.2.3 阶段 A|B|C|D|E
层选择摘要：S0-b S1-b+c S2-ba S3-ca(+norm+importance) S4-a-min S5-c-tool S6-a+b S7-a S8-a-min S10-a-mem S11-a S12-weak-B · 范围 R1
实际 diff：
测试命令与退出码：
关键断言摘录（含 Decay / 弱 B / 规范化）：
未完成 / 指令缺口：
```

---

## 12. 与后续边界

| 能力 | 去向 |
|------|------|
| S8-a-min 衰减召回 + 弱 B | **本批 R1** |
| 向量 / 语义 relevance | 更后 |
| 用户手改 importance UI | 更后 |
| Remote MemoryReviewWorker | v0.3 副节点 |
| S4 全量敏感/冲突机 | 产品再开时升档 |
| 0.2.4 账本/SSE | k04 |

---

**一句话：** tool+间隔/空闲 review + Freeze/短事务；Policy 只挡密钥；关系独立 tool；**claim 绝对时间/实体锚定 + 模型 importance**；召回按衰减分；遗忘 = 人主动 forget ∪ 弱 B 确定性墓碑。
