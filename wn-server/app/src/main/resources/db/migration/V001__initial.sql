-- =============================================================================
-- V001 · 初始业务表（K02 1B-1）
-- 依据：docs/guide/29-v0.1-state-and-persistence-reference.md
-- 库文件：wn-server/data/wannian.db（可用 WANNIAN_DATA_DIR 覆盖）
-- =============================================================================
--
-- 本文件保存的是「已经发生的事实」与「当前可恢复状态」，不是缓存。
--
-- 范围（本版）：
--   conversation  — 一次连续对话（与用户的会话容器）
--   message       — 会话中的一条消息（用户/助手等）
--   turn          — 一次用户请求驱动的处理回合（状态机载体）
--   outbox_event  — 已提交后待可靠投递的事件（如 SSE），先落库再发送
--
-- 不含（后续 migration）：
--   turn_step / tool_operation / memory_record / relationship_state /
--   background_task / sub_agent_run
--
-- 约定：
--   - 主键 id 由应用层生成 UUID 字符串（TEXT），不用数据库自增
--   - 时间字段存 ISO-8601 文本（TEXT），便于跨端阅读与排序
--   - revision 为乐观并发版本，更新时必须 CAS，禁止盲写
--   - message.turn_id 不建 FK：与 turn.input_message_id 互相引用成环；
--     回合关联完整性由后续 TurnCommitter 在应用层保证
-- =============================================================================

-- -----------------------------------------------------------------------------
-- conversation：对话会话
-- 一条记录 = 用户与烟火的一次可延续对话线程。
-- -----------------------------------------------------------------------------
CREATE TABLE conversation (
    -- 会话稳定身份（UUID 字符串）
    id          TEXT    NOT NULL PRIMARY KEY,
    -- 可选标题；可空，后续由产品或摘要填充
    title       TEXT    NULL,
    -- 会话生命周期状态（如 ACTIVE）；具体枚举由领域层定义，禁止随意字符串跳转
    status      TEXT    NOT NULL,
    -- 乐观锁版本；并发修改时 expectedRevision 不匹配则整笔拒绝
    revision    INTEGER NOT NULL,
    -- 创建时间（ISO-8601 文本）
    created_at  TEXT    NOT NULL,
    -- 最后更新时间（ISO-8601 文本）
    updated_at  TEXT    NOT NULL
);

-- -----------------------------------------------------------------------------
-- message：消息
-- 一条记录 = 会话时间线上的一条已提交消息。
-- content_json 使用版本化 JSON envelope，避免把未来 tool 消息硬塞成纯文本。
-- UNIQUE(conversation_id, sequence_no) 保证同会话内顺序唯一、可重放。
-- -----------------------------------------------------------------------------
CREATE TABLE message (
    -- 消息稳定身份（UUID 字符串）
    id               TEXT    NOT NULL PRIMARY KEY,
    -- 所属会话
    conversation_id  TEXT    NOT NULL,
    -- 关联回合（可空：尚未挂上 turn，或非 turn 产物）；故意不建 FK，见文件头说明
    turn_id          TEXT    NULL,
    -- 角色：USER / ASSISTANT / …（枚举由领域层约束）
    role             TEXT    NOT NULL,
    -- 消息正文 envelope（JSON 文本），含版本与载荷，非裸字符串
    content_json     TEXT    NOT NULL,
    -- 会话内序号，从应用层分配；与 conversation_id 唯一
    sequence_no      INTEGER NOT NULL,
    -- 创建时间（ISO-8601 文本）
    created_at       TEXT    NOT NULL,
    UNIQUE (conversation_id, sequence_no),
    FOREIGN KEY (conversation_id) REFERENCES conversation (id)
);

-- -----------------------------------------------------------------------------
-- turn：处理回合
-- 一条记录 = 一次 client 请求对应的处理单元（接收 → claim → 运行 → 提交/失败）。
-- 状态机见 29 号文档；禁止 COMPLETED→RUNNING 等非法跳转，须由领域方法转换。
-- client_request_id 全局唯一：相同幂等键重复提交应回到同一 Turn，不得双写。
-- -----------------------------------------------------------------------------
CREATE TABLE turn (
    -- 回合稳定身份（UUID 字符串）
    id                  TEXT    NOT NULL PRIMARY KEY,
    -- 所属会话
    conversation_id     TEXT    NOT NULL,
    -- 客户端幂等键；重试携带相同 id 且相同正文应返回同一结果
    client_request_id   TEXT    NOT NULL UNIQUE,
    -- 回合状态：RECEIVED / CLAIMED / RUNNING / COMMITTING / COMPLETED / CANCELLED / FAILED
    status              TEXT    NOT NULL,
    -- 本回合用户输入消息 id（创建 Turn 时已存在）
    input_message_id    TEXT    NOT NULL,
    -- 本回合助手输出消息 id（完成提交前可空）
    output_message_id   TEXT    NULL,
    -- 当前执行 owner 标识；同一 Turn 不得有两个有效 execution owner
    execution_id        TEXT    NULL,
    -- claim 过期时间；过期后可被恢复逻辑重新 claim（ISO-8601 文本）
    claim_expires_at    TEXT    NULL,
    -- 乐观锁版本；完成提交时校验 expectedTurnRevision
    revision            INTEGER NOT NULL,
    -- 失败时的稳定错误码；成功可空
    error_code          TEXT    NULL,
    -- 创建时间
    created_at          TEXT    NOT NULL,
    -- 最后更新时间
    updated_at          TEXT    NOT NULL,
    -- 到达终态（COMPLETED/FAILED/CANCELLED）的时间；进行中可空
    completed_at        TEXT    NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversation (id),
    FOREIGN KEY (input_message_id) REFERENCES message (id),
    FOREIGN KEY (output_message_id) REFERENCES message (id)
);

-- -----------------------------------------------------------------------------
-- outbox_event：外发事件箱
-- 一条记录 = 业务事务已提交后、待推送给外部（如 SSE）的一条事件。
-- 「是否已被某个浏览器看到」不写全局布尔；各连接用自己的 cursor / lastEventId 判断。
-- sequence_no 全局唯一递增，供断线补发按序读取。
-- 原则：先与 Turn/Message 同事务写入 outbox，再异步投递；崩溃后只补发不重做业务。
-- -----------------------------------------------------------------------------
CREATE TABLE outbox_event (
    -- 事件稳定身份（UUID 字符串）
    id               TEXT    NOT NULL PRIMARY KEY,
    -- 聚合类型，如 turn / conversation（投递路由用）
    aggregate_type   TEXT    NOT NULL,
    -- 聚合 id，通常为 turnId 或 conversationId
    aggregate_id     TEXT    NOT NULL,
    -- 事件类型，如 TurnCompleted（对外契约，勿随意改名）
    event_type       TEXT    NOT NULL,
    -- 事件载荷（JSON 文本）；不得包含内部敏感 trace / API key
    payload_json     TEXT    NOT NULL,
    -- 全局投递序号；UNIQUE，断线重连按序补发
    sequence_no      INTEGER NOT NULL UNIQUE,
    -- 创建时间（与业务提交同时）
    created_at       TEXT    NOT NULL
);

-- -----------------------------------------------------------------------------
-- 索引：每个索引对应明确查询，禁止无目的堆索引
-- -----------------------------------------------------------------------------
-- 按会话列出回合（时间序）
CREATE INDEX idx_turn_conversation_created ON turn (conversation_id, created_at);
-- 恢复扫描：按状态与 claim 过期找可重新认领的回合
CREATE INDEX idx_turn_status_claim ON turn (status, claim_expires_at);
-- 按会话拉取消息（与 UNIQUE 查询形态一致，便于排序读）
CREATE INDEX idx_message_conversation_seq ON message (conversation_id, sequence_no);
-- Outbox 按序号补发
CREATE INDEX idx_outbox_sequence ON outbox_event (sequence_no);
