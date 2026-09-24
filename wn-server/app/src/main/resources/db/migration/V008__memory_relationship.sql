-- =============================================================================
-- V008 · Memory / Relationship（0.2.3 阶段 A · 别名 K03）
-- 依据：docs/plans/k03-memory.md §5.1；设计 research/memory-system-0.2.3.md §4.1
-- =============================================================================
--
-- 本文件新增「可跨会话复用的记忆与关系真源」，以及冷路径 Review Job 最小表。
-- 正式写入只经 TurnCommitter（热）或 MemoryCommand 短事务（冷 / S11 / 弱 B）；
-- Store 接口只读；禁止业务路径直写 JDBC。
--
-- 范围：
--   conversation.last_activity_at  — idle Review 判定用（30min）
--   memory_record                  — 长期记忆行（含 importance；无 sensitivity）
--   relationship_state             — 伴身级关系快照（称呼/边界）
--   memory_review_job              — 间隔/空闲 Review 队列（最小单消费者）
--
-- 不含（更后）：
--   向量索引 / embedding 列；弱 B 独立进度表（本批扫 ACTIVE 即可）；
--   turn_step 行为账本（0.2.3-L · V013）；tool_operation / background_task 等
--
-- 约定：
--   - 主键 id 由应用层生成 UUID 字符串（TEXT）
--   - 时间字段存 ISO-8601 文本（TEXT）
--   - revision 乐观锁；更新必须 CAS
--   - 枚举字符串由领域层约束（ContentKind / SourceKind / MemoryLifecycle / …）
--   - 无 sensitivity 列：产品为本地私人助手；密钥仅由 SecretOnlyMemoryPolicy 拒写
-- =============================================================================

-- -----------------------------------------------------------------------------
-- conversation 补列：最近活动时间
-- 用于 IdleScanner：now - last_activity_at >= 30min 且有新内容自上次 review
-- → enqueue(IDLE_REVIEW)。Turn 完成后由应用更新；旧行可空（视为无活动记录）。
-- -----------------------------------------------------------------------------
ALTER TABLE conversation ADD COLUMN last_activity_at TEXT NULL;

-- -----------------------------------------------------------------------------
-- memory_record：长期记忆
-- 一条记录 = 一条可召回（或已墓碑）的命题 claim。
-- content_json 存版本化 envelope（至少含 claim；可选 path/meta）。
-- importance ∈ [0,1]：模型同一次输出打分；召回用 MemoryDecay.score；弱 B 亦吃此列。
-- status：ACTIVE | SUPERSEDED | REJECTED | FORGOTTEN（本批密钥默认不落 REJECTED 行）。
-- propose_id 本批：tool_remember | llm_review | tombstone_scan
-- triage_id 本批：validate
-- -----------------------------------------------------------------------------
CREATE TABLE memory_record (
    -- 行稳定身份（UUID）
    id                     TEXT    NOT NULL PRIMARY KEY,
    -- 伴身 id（本批常量 yanhuo）
    companion_identity_id  TEXT    NOT NULL,
    -- 主题键（同伴身下去重 / SUPERSEDE 对齐用）
    subject_key            TEXT    NOT NULL,
    -- ContentKind：USER_PREFERENCE | USER_FACT | SHARED_HISTORY | TASK_CONTEXT
    content_kind           TEXT    NOT NULL,
    -- SourceKind：EXPLICIT | OBSERVED | INFERRED | REFLECTION
    source_kind            TEXT    NOT NULL,
    -- MemoryScope：COMPANION | CONVERSATION | TASK（本批默认 COMPANION）
    scope                  TEXT    NOT NULL,
    -- 正文 envelope（JSON）；claim 在此，禁止把未锚定「今天/这里」当长期命题的责任在模型+锚
    content_json           TEXT    NOT NULL,
    -- 提出时的 Turn；冷路径可空
    source_turn_id         TEXT    NULL,
    -- 可选来源消息
    source_message_id      TEXT    NULL,
    -- 写入通道：tool_remember / llm_review / tombstone_scan
    propose_id             TEXT    NOT NULL,
    -- 校验路径标记；本批固定 validate
    triage_id              TEXT    NOT NULL,
    -- MemoryLifecycle 落库值
    status                 TEXT    NOT NULL,
    -- 生效起点（通常等于创建时）
    valid_from             TEXT    NOT NULL,
    -- 失效点；本批可空（弱 B / forget 改 status，不必填 until）
    valid_until            TEXT    NULL,
    -- 被本行替代的旧记忆 id（SUPERSEDE 链）
    supersedes_id          TEXT    NULL,
    -- 乐观锁
    revision               INTEGER NOT NULL,
    -- 创建时间（衰减 age 基准）
    created_at             TEXT    NOT NULL,
    -- 最近一次成功注入 Assembler 的时间；可空
    last_recalled_at       TEXT    NULL,
    -- 重要性 [0,1]；A 衰减与弱 B 共用
    importance             REAL    NOT NULL
);

-- 按伴身+主题+状态查 ACTIVE / 做 SUPERSEDE
CREATE INDEX idx_memory_companion_subject_status
    ON memory_record (companion_identity_id, subject_key, status);

-- 同一伴身+主题仅允许一个 ACTIVE；保留 SUPERSEDED/FORGOTTEN 历史链。
-- V008 首次创建 memory_record（此前没有该表的历史数据需回填/归并），因此可直接加约束。
CREATE UNIQUE INDEX idx_memory_one_active_subject
    ON memory_record (companion_identity_id, subject_key)
    WHERE status = 'ACTIVE';

-- 弱 B / 衰减扫描：按 status + created_at 分页
CREATE INDEX idx_memory_status_created
    ON memory_record (status, created_at);

-- -----------------------------------------------------------------------------
-- relationship_state：伴身级关系快照
-- 一行 = 一个 CompanionIdentity 的当前称呼/边界等（非好感度分数）。
-- state_json：至少 preferredAddress / boundaries（可缺省字段为 null）。
-- reason_json：最近一次变更原因（可含 propose 元数据）。
-- 与 memory_record 解耦：记忆路径不得偷改本表。
-- -----------------------------------------------------------------------------
CREATE TABLE relationship_state (
    -- 伴身 PK（本批 yanhuo）
    companion_identity_id  TEXT    NOT NULL PRIMARY KEY,
    -- 关系状态 JSON（称呼、边界等）
    state_json             TEXT    NOT NULL,
    -- 最近变更原因 JSON
    reason_json            TEXT    NOT NULL,
    -- 热路径带来的 Turn；短事务可空
    source_turn_id         TEXT    NULL,
    -- 乐观锁；CAS 更新
    revision               INTEGER NOT NULL,
    -- 最后更新时间
    updated_at             TEXT    NOT NULL
);

-- -----------------------------------------------------------------------------
-- memory_review_job：冷路径 Review 队列（最小）
-- trigger：INTERVAL | IDLE
-- status：PENDING | RUNNING | SUCCEEDED | RETRY_WAIT | DEAD
-- 同 conversation + trigger 的 PENDING 宜合并或跳过，避免堆积。
-- 弱 B 不进本表（独立 TombstoneScanner）。
-- lease_*：单消费者租约；本批同 JVM 起步，为副节点预留列。
-- -----------------------------------------------------------------------------
CREATE TABLE memory_review_job (
    -- Job 稳定身份（UUID）
    id               TEXT    NOT NULL PRIMARY KEY,
    -- 目标会话
    conversation_id  TEXT    NOT NULL,
    -- 伴身 id
    companion_id     TEXT    NOT NULL,
    -- INTERVAL | IDLE
    trigger          TEXT    NOT NULL,
    -- PENDING | RUNNING | SUCCEEDED | RETRY_WAIT | DEAD
    status           TEXT    NOT NULL,
    -- 已尝试次数
    attempt          INTEGER NOT NULL,
    -- 租约持有者（节点/线程标识）；可空
    lease_owner      TEXT    NULL,
    -- 租约到期（ISO-8601）；可空
    lease_until      TEXT    NULL,
    -- 最近一次失败摘要；可空
    last_error       TEXT    NULL,
    -- 创建时间
    created_at       TEXT    NOT NULL,
    -- 最后更新时间
    updated_at       TEXT    NOT NULL
);

-- Worker 按状态取 PENDING
CREATE INDEX idx_memory_review_job_status
    ON memory_review_job (status, created_at);

-- 合并/跳过：同会话同 trigger 查已有 PENDING
CREATE INDEX idx_memory_review_job_conversation_trigger
    ON memory_review_job (conversation_id, trigger, status);
