# 0.2.3-D · 召回 A + 弱 B + HTTP 实施单

`status`: **已交付**（2026-09-24；验收见 [实施清单 · 0.2.3](../guide/01-checklist.md)；**D+** 见 [k03-d-search-tool](./k03-d-search-tool.md)）  
`version`: **0.2.3** 阶段 D  
`parent`: [k03-memory.md](./k03-memory.md)  
`design-anchor`: S8-a-min 衰减召回；弱 B=A+B（`MemoryDecay.shouldTombstone`）；S11-a HTTP  
`code-gate`: 仅本单列出的文件；禁止扩大到阶段 E 清单勾选、向量、用户手改 importance UI、Committer 整文件拆分  
`compat`: 无迁移破坏（表已在 V008）；HTTP 为新路径

给执行者（luna）的指令。缺口先回报，禁止自行发挥。

---

## 0. 已定摘要（本单写死）

```text
召回           ACTIVE only；score = MemoryDecay.score；score desc + createdAt desc
Top-N          配置 `wannian.memory.recall.top-n`（种子默认 12）→ `MemoryRecallLimits`
字数预算       配置 `wannian.memory.recall.char-budget`（种子默认 2000）；D+：超预算 continue
注入点         ContextAssembler → AgentInput.memoryContext / relationshipSnapshot
bump           注入选定后 best-effort touch last_recalled_at；失败只打日志，不挡 assemble
关系           SqliteRelationshipStore 只读；toPromptText()；无行则 null/空
弱 B           MemoryDecay.shouldTombstone（A+B 两条款）；零 LLM；经 MemoryCommand.tombstone
弱 B 调度      与 Review 同 tick 末尾（idle → worker×N → tombstone.scanOnce）
弱 B 批次      配置 `wannian.memory.tombstone.batch-limit`（种子默认 50）
forget 清空    status=FORGOTTEN + content_json={"v":1,"claim":"","forgotten":true} + CAS
correct        须 Shape+Policy；旧行 SUPERSEDE CAS；新行 propose_id 写死为 "http_correct"
subjectKey     同伴身下至多一条 ACTIVE；目标键被其他 ACTIVE 占用时稳定返回 MEMORY_SUBJECT_CONFLICT / HTTP 409
历史读取       FORGOTTEN 正文可为空并可按 id/lifecycle 读取；ACTIVE claim 仍须非空
HTTP           GET /api/memory；POST /api/memory/correct；POST /api/memory/forget；禁止假 200
禁止           向量；Scanner/HTTP 直写 JDBC；第二次 importance LLM；dreaming；kernel 写死 Top-N/字数
```

**companion 本批**：仅 `yanhuo`；GET `?companion=` 缺省或非 yanhuo → `ILLEGAL_ARGUMENT`。

---

## 1. 代码证据（审阅时已核对）

| 项 | 状态 | 路径 / 说明 |
|----|------|-------------|
| `MemoryDecay` + 单测钉表 | **已有** | `kernel/.../MemoryDecay.java`；`MemoryDecayTest` |
| `MemoryRecall` 接口 | **已有空口** | `recallTop(companion, now, limit)`；尚无实现 |
| `MemoryTombstoneScanner` 接口 | **已有空口** | `scanOnce()`；尚无实现 |
| `MemoryStore` / `SqliteMemoryStore` | **已有** | `listActive` / `findById`；只读 |
| `RelationshipStore` | **仅接口** | 无 Sqlite 实现；`StoredRelationshipState.toPromptText()` 已有 |
| `MemoryCommand` correct/forget/tombstone | **桩** | 现返回 `UNSUPPORTED_EXTENSION` |
| `ContextAssembler` | **B 已锚** | `memoryContext`/`relationshipSnapshot` 仍恒 null |
| `DefaultAgentLoop` | **已消费** | 非空则注入 system message（rel 先于 memory） |
| `MemoryReviewConfig` tick | **C 已有** | idle → worker；本单在末尾加 tombstone |
| V008 表/索引 | **已有** | `memory_record` 含 `importance`、`last_recalled_at`；`(status, created_at)` 索引 |

### 待本单写死（原父单空勾）

- [x] 召回 Top-N = **12**；字数预算 = **2000**  
- [x] 弱 B 调度 = **同 tick 末尾**  
- [x] `MemoryDecay` 常数以类为准（已钉；D 窄测复用 `MemoryDecayTest`，不改公式）

---

## 2. 分文件审核对照（已通过 · 实施须对齐）

| # | 文件 | 决议 |
|---|------|------|
| 1 | `MemoryRecallLimits` + `application.yml` | **配置** `top-n`/`char-budget`；kernel 仅 record；禁止 `MemoryRecallConstants` 硬编码 |
| 2 | `DefaultMemoryRecall.java`（**新建** kernel 或 app） | 纯：`listActive` → score → sort → limit；**不**碰字数预算、**不** bump；放 **kernel** 便于单测（依赖 `MemoryStore` 注入） |
| 3 | `ContextAssembler.java` | 注入 `MemoryRecall` + `RelationshipStore`（可 null=关）；填 `memoryContext`/`relationshipSnapshot`；字数预算 **D+ continue**；可选 `MemoryRecallTouch` |
| 4 | `TurnEngineConfig.java` | 装配 Assembler 时注入 Recall + RelStore + Toucher |
| 5 | `SqliteRelationshipStore.java`（**新建** app） | 只读 `findByCompanion`；解析 `state_json`/`reason_json` |
| 6 | `MemoryRecallToucher.java`（**新建** app） | `touch(List<String> ids, Instant now)`：`UPDATE last_recalled_at`；失败吞掉打日志 |
| 7 | `SqliteMemoryCommand.java` | 实现 `correct` / `forget` / `tombstone`；复用 Writer SUPERSEDE；**禁止**业务绕过 |
| 8 | `SqliteDefaultTombstoneScanner.java`（**新建** app） | 全量只读 `listActive` 后按 `shouldTombstone` 过滤；最多尝试 50 个候选 CAS；失败记日志继续下一候选 |
| 9 | `MemoryReviewConfig.java` | tick 末尾调用 `tombstoneScanner.scanOnce()`；bean 注册 Scanner |
| 10 | `MemoryHttpController.java` + bodies（**新建** app） | GET/correct/forget；映射 `CommandResult`；禁止假 200 |
| 11 | `ApprovedMemoryChange` / propose 常量 | 增 `PROPOSE_HTTP_CORRECT = "http_correct"`；`PROPOSE_TOMBSTONE_SCAN` 若未有则补（tombstone 行不必新建 claim） |
| 12 | 窄测（**不在本阶段交付**） | 清单预告：Recall 排序/截断；弱 B 墓碑；forget 后不召回；HTTP correct CAS；Assembler 注入。按 [version-stage-workflow](./version-stage-workflow.md)：普通阶段只审生产代码；测 + 真人归 **0.2.3 末段** |

作废决议：向量 relevance；Noop Recall；Scanner 调 LLM；forget 只改 status 不清正文（本单选清空）；独立弱 B 进程。

---

## 3. 依赖顺序（必须按序）

```text
MemoryRecallConstants
  → DefaultMemoryRecall（+ kernel 单测）
  → SqliteRelationshipStore
  → MemoryRecallToucher
  → SqliteMemoryCommand（correct/forget/tombstone）
  → ContextAssembler + TurnEngineConfig 接线
  → SqliteDefaultTombstoneScanner + MemoryReviewConfig tick
  → MemoryHttpController
  → 窄测
```

---

## 4. 分步实施

### 4.1 `MemoryRecallLimits` + `application.yml`

路径：

- `wn-server/kernel/.../memory/MemoryRecallLimits.java`（record；无默认业务常数）
- `wn-server/app/src/main/resources/application.yml` → `wannian.memory.recall.*`

```yaml
wannian.memory.recall.top-n: 12          # 可被 WANNIAN_MEMORY_RECALL_TOP_N 覆盖
wannian.memory.recall.char-budget: 2000
```

Assembler 注入 `MemoryRecallLimits`；禁止再引入 `MemoryRecallConstants`。
### 4.2 `DefaultMemoryRecall`

路径：`wn-server/kernel/src/main/java/com/wannian/server/kernel/memory/DefaultMemoryRecall.java`

- 构造注入 `MemoryStore`。  
- `recallTop`：`limit<=0` → 空列表；否则 `listActive` → 对每条 `score(importance, Duration.between(createdAt, now))`（若 `createdAt` 在 now 之后，age 按 `Duration.ZERO`，与 Decay 负 age 语义一致：调用方应传 `Duration.between` 后若负则 `ZERO`）。  
- 排序：`Comparator.comparingDouble(score).reversed().thenComparing(createdAt, reverse)`。  
- 截断 `limit`（Assembler 传入 `TOP_N`）。  
- **禁止**写库；**禁止**过滤 FORGOTTEN（Store 已只给 ACTIVE）。

### 4.3 Assembler 注入

路径：`ContextAssembler.java`

- 新增可选依赖：`MemoryRecall recall`、`RelationshipStore relationships`、`MemoryRecallToucher toucher`（toucher 在 app；kernel Assembler **不要**依赖 app —— **断缝**：

**决议（避免 kernel→app）：**

- `ContextAssembler` 只依赖 kernel Port：`MemoryRecall`、`RelationshipStore`。  
- bump：新增 kernel 口 **`MemoryRecallTouch`**（单方法 `touchRecalled(List<String> ids, Instant now)`），app 实现；Assembler 注入可 null。  
- 格式化 `memoryContext`：

```text
记忆（按相关性）：
- {claim}（importance={x}）
…
```

- 按条累加字符；超过 `CHAR_BUDGET` 时对该条 **continue**（D+ 已采纳；不 break），只 touch 实际拼入的 id。  
- `relationshipSnapshot`：`findByCompanion(YANHUO).map(StoredRelationshipState::toPromptText).filter(notBlank).orElse(null)`。  
- 仍保留 Observation Date 锚块（B）。  
- 召回失败（Store 抛错）：**打日志，memoryContext=null**，不挡 Turn。

`TurnEngineConfig`：注入 `DefaultMemoryRecall`、`SqliteRelationshipStore`、`SqliteMemoryRecallTouch`。

### 4.4 `SqliteRelationshipStore`

- 读 `relationship_state`；解析 `preferredAddress`/`boundaries`/`reason`；无行 → empty。

### 4.5 `SqliteMemoryCommand` 补齐

#### `forget(id, expectedRevision)`

```text
UPDATE memory_record
SET status=FORGOTTEN, revision=revision+1, content_json='{"v":1,"claim":"","forgotten":true}',
    valid_until=now
WHERE id=? AND revision=? AND status=ACTIVE
```

- 影响行≠1 → `Rejected(REVISION_CONFLICT 或 ILLEGAL_ARGUMENT)`：若行不存在/`status≠ACTIVE` 用明确消息；revision 不符用 `REVISION_CONFLICT`。  
- 成功 → `Applied(id, newRevision)`。

#### `tombstone(id, expectedRevision)`

- 与 forget **同一清空策略**；另：可写 `propose_id` 不变或保持原值（**不**改 propose_id 列亦可）。  
- 本批 **不**强制改 propose_id（墓碑是状态迁移，不是新 claim）。  
- Scanner 调用本方法；HTTP forget 调 `forget`（语义同清空，通道为人主动）。

#### `correct(oldId, expectedRevision, replacement)`

1. 校验 `replacement` 非 null；`proposeId` 须为 `http_correct`。  
2. 短事务：  
   - 读旧行：须 ACTIVE 且 revision 匹配，否则 Rejected。  
   - SUPERSEDE 旧行（与 Writer 同 CAS 语义）。  
   - INSERT 新 ACTIVE（可复用 `SqliteMemoryCommitWriter.applyOne`，但 Writer 当前按 subject_key 找 ACTIVE —— **注意**：correct 应对 **指定 id** SUPERSEDE，不是按 subject 盲替。  

**决议：** 为 correct 在 Writer 或 Command 内写专用路径：

```text
UPDATE ... SET status=SUPERSEDED ... WHERE id=? AND revision=? AND status=ACTIVE
INSERT 新行 supersedes_id=oldId，subject/claim/importance 来自 replacement
```

- 新行 `source_turn_id` NULL；`propose_id=http_correct`；`triage_id=validate`。  
- **HTTP 层**在调 Command 前：把请求体编成 `MemoryToolDraft` → Shape → Policy；REJECT 则 400，不写库。

### 4.6 弱 B Scanner

路径：`wn-server/app/src/main/java/com/wannian/server/app/memory/SqliteDefaultTombstoneScanner.java`

```text
active = memoryStore.listActive(YANHUO) // 全量只读
candidates = active.filter(row -> MemoryDecay.shouldTombstone(importance, age))
candidates.sort(createdAt ASC, id ASC) // 优先处理最老候选
for each candidate (最多尝试 50 个 CAS)：
  result = memoryCommand.tombstone(id, revision)
  成功计数++；Rejected log 后继续下一候选
return 成功数
```

- **零 LLM**；不经 Review job 表。  
- `listActive` 当前全量读到内存；batch-limit 仅限制候选 CAS 尝试数。先过滤并按最老候选优先，再限额，避免按 `created_at DESC` 排列时新记录或长期不符合条件的记录挡住旧候选。若 ACTIVE 规模增长到需分页，应为 Store 增加稳定游标分页，再将游标推进与批次语义一并设计。  
- `MemoryReviewConfig.MemoryReviewTicker.tick` 末尾：`tombstoneScanner.scanOnce()`。

### 4.7 HTTP

路径：`wn-server/app/src/main/java/com/wannian/server/app/http/MemoryHttpController.java`  
Bodies：同文件或 `MemoryHttpBodies.java`。

| 方法 | 路径 | 请求 | 成功 | 失败 |
|------|------|------|------|------|
| GET | `/api/memory?companion=yanhuo` | query | 200 + ACTIVE 列表（id, subjectKey, claim, importance, revision, createdAt） | 400 非法 companion |
| POST | `/api/memory/correct` | `{id, expectedRevision, subjectKey, claim, contentKind, sourceKind, scope, importance, path?}` | 200 `{id, revision}` 新行 | 400 Shape/Policy/参数；409 revision；404 非 ACTIVE |
| POST | `/api/memory/forget` | `{id, expectedRevision}` | 200 `{id, revision}` | 同上 |

- 鉴权：与 `/api/conversations` 同级（本批无额外 manage token；若后续统一再加）。  
- **禁止**成功体假装写入。  
- GET **不**返回已 FORGOTTEN；claim 已清空的行本就不会 ACTIVE。
- `findById` / `listByCompanion(..., FORGOTTEN)` 可读取清空正文的墓碑行；`StoredMemoryRecord` 仅对 ACTIVE 强制 claim 非空。
- V008 创建 memory_record 时建立部分唯一索引 `(companion_identity_id, subject_key) WHERE status='ACTIVE'`。该表此前不存在，无旧表重复 ACTIVE 需迁移归并；correct 在事务内预检目标键，并由索引兜住并发写入。
- correct 目标 subjectKey 被另一 ACTIVE 记忆占用时返回 `MEMORY_SUBJECT_CONFLICT` / HTTP 409；仅旧行自身占用目标键时允许正常替换。

HTTP 错误码映射：

- `Rejected` + `REVISION_CONFLICT` → 409  
- `ILLEGAL_ARGUMENT` / Policy / Shape → 400  
- 行不存在 → 404（可用 `ILLEGAL_ARGUMENT` 文案或登记 `MEMORY_NOT_FOUND`——**若新增须写入 ErrorCodes 登记表**）

**决议：** 新增 `ErrorCodes.MEMORY_NOT_FOUND`（VALIDATION, false），HTTP 404。

### 4.8 窄测（本阶段必做）

| 类 | 断言 |
|----|------|
| `DefaultMemoryRecallTest` | 高 importance 大 age 仍进 Top；低分排后；FORGOTTEN 不出现（Store 不返回） |
| `MemoryTombstoneScannerTest`（app） | 满足 A+B → FORGOTTEN；不调 LLM；失败一条不影响下一条 |
| `MemoryHttp*Test` 或 `SqliteMemoryCommandTest` | correct SUPERSEDE；forget 后 `recallTop` 不含；revision 冲突 Rejected |
| `ContextAssemblerMemoryInjectTest` | memoryContext 含 claim；超 CHAR_BUDGET 截断；rel 文本可选 |

复用：`MemoryDecayTest` 已存在，D 不改公式。

---

## 5. 禁止

- 向量 / embedding / 语义 relevance。  
- Scanner、Controller、Recall **直接** JDBC 写（除 Store 只读与 Toucher/Command 实现类内部）。  
- 弱 B 调模型；空壳 job SUCCEEDED。  
- 第二次 LLM 只打 importance。  
- 扩大改 `SqliteTurnCommitter` 非本单需要处。  
- commit / push（未授权）。  
- 阶段 E 清单大扫除（E 另做）。

---

## 6. 验收（实施完成后自检）

```text
[ ] recallTop 排序符合 score desc；Top-N=12
[ ] Assembler 注入 memoryContext；字数 ≤2000；Observation Date 仍在
[ ] relationshipSnapshot 有行则非空
[ ] touch last_recalled_at 失败不挡聊天
[ ] 弱 B tick 末尾运行；应墓碑行变 FORGOTTEN
[ ] GET 仅 ACTIVE；forget 后不再出现
[ ] correct：旧 SUPERSEDED、新 ACTIVE、importance 落库；CAS 冲突非 200
[ ] 窄测绿；0.2.2 相关回归不要求本单全跑（E 收口）
```

交付格式：

```text
批次：0.2.3-D 召回/弱B/HTTP
实际 diff 文件列表：
测试命令与退出码：
关键断言摘录：
未完成 / 指令缺口：
```

---

## 7. 与父单关系

- 阶段 D 四项及「召回 Top-N / 弱 B 调度」参数已在父单标记完成。  
- V010 后阶段自动化全量通过；Maven 模块汇总与 Surefire XML 差异见 [实施清单 · 0.2.3](../guide/01-checklist.md)。真人/live 验收仍归 **E**。  
- 当前下一步：完成人工/live 验收并记录结果。
