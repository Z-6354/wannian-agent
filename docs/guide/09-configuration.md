# 运行时配置（禁止业务默认硬编码）

`status`: **现行** — 2026-09-26  
`owns`: `wn-server` 可调预算与开关的唯一对照表  
`source of truth`: [`wn-server/app/src/main/resources/application.yml`](../../wn-server/app/src/main/resources/application.yml)

## 准则

1. **需要运维/产品可调的数值与开关，一律进 `application.yml`（可用环境变量覆盖），禁止在 Java 里写死业务默认。**
2. `@Value("${…:fallback}")` 的 fallback **必须与 yml 默认一致**，仅作无 Spring 上下文时的测试兜底，不以代码为真源。
3. kernel 模块不读 yml；由 app 装配层注入（如 `MemoryRecallLimits`、`TurnEngine(recentMessageLimit)`）。
4. 改预算前先看本表与模型窗口：常驻人设预算是**产品护栏**（防挤近讯/记忆），不是 `gpt-6-luna` / DeepSeek ~1M 上下文的硬顶。

覆盖方式：改 yml，或设对应 `WANNIAN_*` 环境变量（见下表）。

---

## 1. 数据与库

| yml 键 | 环境变量 | 默认 | 作用 |
|--------|----------|------|------|
| `wannian.data-dir` | `WANNIAN_DATA_DIR` | `data`（解析到 `wn-server/data`） | SQLite / prompts / skills / secrets 根目录 |
| `wannian.build-id` | `WANNIAN_BUILD_ID` | `unknown` | 备份元数据构建标识 |
| `wannian.backup.retain-count` | `WANNIAN_BACKUP_RETAIN_COUNT` | `7` | 一致性备份保留份数 |
| `wannian.sqlite.busy-timeout-ms` | `WANNIAN_SQLITE_BUSY_TIMEOUT_MS` | `5000` | SQLite busy 等待毫秒 |

---

## 2. 模型与 Agent 预算

| yml 键 | 环境变量 | 默认 | 作用 |
|--------|----------|------|------|
| `wannian.model.mode` | `WANNIAN_MODEL_MODE` | `live` | `live` / `fake` |
| `wannian.model.sampling.temperature` | `WANNIAN_MODEL_TEMPERATURE` | `0.85` | 采样温度 |
| `wannian.model.sampling.top-p` | `WANNIAN_MODEL_TOP_P` | `0.95` | top-p |
| `wannian.model.sampling.presence-penalty` | `WANNIAN_MODEL_PRESENCE_PENALTY` | `2.35` | presence penalty |
| `wannian.agent.budget.max-model-decisions` | `WANNIAN_AGENT_MAX_MODEL_DECISIONS` | `10` | **种子**；运行期以 `data/wannian.json` 为准 |
| `wannian.agent.budget.max-system-tool-invocations-per-tool` | `WANNIAN_AGENT_MAX_SYSTEM_TOOL_INVOCATIONS_PER_TOOL` | `5` | 同上，种子 |
| `wannian.agent.budget.soft-deadline-seconds` | `WANNIAN_AGENT_SOFT_DEADLINE_SECONDS` | `60` | 同上，种子 |
| `wannian.agent.budget.hard-deadline-seconds` | `WANNIAN_AGENT_HARD_DEADLINE_SECONDS` | `100` | 同上，种子 |
| `wannian.manage.token` | `WANNIAN_MANAGE_TOKEN` | 空 | 外网管理口令；空则读 `manage.properties` |

---

## 3. 上下文 / 提示词 / Skill（与 4000 相关）

| yml 键 | 环境变量 | 默认 | 作用 |
|--------|----------|------|------|
| `wannian.context.recent-message-limit` | `WANNIAN_CONTEXT_RECENT_MESSAGE_LIMIT` | `20` | 近讯条数上限（进 excerpt） |
| `wannian.prompt.layer-char-budget` | `WANNIAN_PROMPT_LAYER_CHAR_BUDGET` | `4000` | **每层** SOUL/VOICE/IDENTITY/USER/SAFETY 字符硬切 |
| `wannian.skill.index-max-entries` | `WANNIAN_SKILL_INDEX_MAX_ENTRIES` | `16` | Skill 索引最多条目 |
| `wannian.skill.index-desc-chars` | `WANNIAN_SKILL_INDEX_DESC_CHARS` | `160` | 索引 description 截断 |
| `wannian.skill.index-char-budget` | `WANNIAN_SKILL_INDEX_CHAR_BUDGET` | `3000` | 整段索引字符预算 |
| `wannian.skill.body-char-budget` | `WANNIAN_SKILL_BODY_CHAR_BUDGET` | `8000` | `load_skill` 正文截断 |
| `wannian.skill.allowed-ids` | `WANNIAN_SKILL_ALLOWED_IDS` | 空 | 空=全部安全 id；逗号白名单 |

**说明：** 当前盘上 VOICE≈3k 字，默认 4000 一般不截；相对 `gpt-6-luna` 1.05M 窗口，4000 是护栏。需要更多 few-shot 时可调到 6000–8000，勿跟模型窗口同量级膨胀。

---

## 4. 记忆 / 关系注入

| yml 键 | 环境变量 | 默认 | 作用 |
|--------|----------|------|------|
| `wannian.memory.recall.top-n` | `WANNIAN_MEMORY_RECALL_TOP_N` | `12` | 召回条数 |
| `wannian.memory.recall.char-budget` | `WANNIAN_MEMORY_RECALL_CHAR_BUDGET` | `2000` | 记忆块字符预算 |
| `wannian.memory.search.default-limit` | `WANNIAN_MEMORY_SEARCH_DEFAULT_LIMIT` | `5` | `search_memory` 默认条数 |
| `wannian.memory.search.max-limit` | `WANNIAN_MEMORY_SEARCH_MAX_LIMIT` | `20` | 搜索上限 |
| `wannian.memory.review.scheduler-enabled` | `WANNIAN_MEMORY_REVIEW_SCHEDULER_ENABLED` | `true` | Review 调度 |
| `wannian.memory.review.tick-ms` | `WANNIAN_MEMORY_REVIEW_TICK_MS` | `60000` | Review tick |
| `wannian.memory.tombstone.batch-limit` | `WANNIAN_MEMORY_TOMBSTONE_BATCH_LIMIT` | `50` | 墓碑批上限 |

---

## 5. 人物导入 / overlay

| yml 键 | 环境变量 | 默认 | 作用 |
|--------|----------|------|------|
| `wannian.persona.overlay-layer-char-budget` | `WANNIAN_PERSONA_OVERLAY_LAYER_CHAR_BUDGET` | `1000` | overlay 单层（soul/voice）上限 |
| `wannian.persona.overlay-total-char-budget` | `WANNIAN_PERSONA_OVERLAY_TOTAL_CHAR_BUDGET` | `1600` | soul+voice 合计上限 |
| `wannian.persona.import-window-chars` | `WANNIAN_PERSONA_IMPORT_WINDOW_CHARS` | `3000` | TXT 扫描窗宽度 |

---

## 6. 运行日志 / 会话卫生

| yml 键 | 环境变量 | 默认 | 作用 |
|--------|----------|------|------|
| `wannian.journal.enabled` | `WANNIAN_JOURNAL_ENABLED` | `true` | 账本总开关 |
| `wannian.journal.jsonl-enabled` | `WANNIAN_JOURNAL_JSONL_ENABLED` | `true` | JSONL |
| `wannian.journal.sqlite-enabled` | `WANNIAN_JOURNAL_SQLITE_ENABLED` | `true` | SQLite turn_step |
| `wannian.journal.include-full-messages` | `WANNIAN_JOURNAL_INCLUDE_FULL_MESSAGES` | `true` | 是否记全文 |
| `wannian.journal.max-payload-chars` | `WANNIAN_JOURNAL_MAX_PAYLOAD_CHARS` | `65536` | 单条 payload 截断 |
| `wannian.conversation.idle-archive-days` | `WANNIAN_CONVERSATION_IDLE_ARCHIVE_DAYS` | `7` | 闲置归档天数 |
| `wannian.conversation.archive-*` | `WANNIAN_CONVERSATION_ARCHIVE_*` | 见 yml | 归档调度与评估器 |

---

## 7. 仍允许在代码中的常量（非「可调配置」）

下列**不是**运维旋钮，可留在代码：错误码字符串、协议字面量、正则、状态机枚举、测试桩、提交路径上的极短 busy（如 attempt 级 30ms）、协议字段名校验长度等。

新增可调预算时：**先加 yml + 本表一行**，再在 app 装配注入；禁止只改 Java 魔法数。

---

## 8. 相关代码入口

| 区域 | 装配 / 读取 |
|------|-------------|
| 近讯条数 | `StreamDeliveryConfig` → `TurnEngine` |
| 提示词层截断 | `PromptConfig` → `FilePromptLayerStore` |
| Skill 预算 | `PromptConfig` → `FileSkillCatalog` |
| 记忆召回 | `TurnEngineConfig` → `MemoryRecallLimits` |
| 人物 overlay | `SqlitePersonaCore` / `PersonaImportService` / `PersonaOverlayQualityGate` |
| SQLite busy | `SqliteConfig#dataSource` |
