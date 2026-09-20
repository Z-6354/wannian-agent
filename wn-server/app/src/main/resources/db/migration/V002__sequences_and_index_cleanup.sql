-- =============================================================================
-- V002 · 服务端序号与冗余索引清理
-- 不修改 V001。序号只增不减：删除消息或 outbox 行后也不把计数器调小，避免复用。
-- 序号需要单调，不要求连续无空洞。
-- =============================================================================

-- 全局 outbox 序号。next_value 是下一次要分配的值。
CREATE TABLE sequence_counter (
    name       TEXT    NOT NULL PRIMARY KEY,
    next_value INTEGER NOT NULL
);

INSERT INTO sequence_counter (name, next_value) VALUES ('outbox', 1);

-- 每个会话自己的消息序号。默认 1；已有消息的会话回填到 MAX+1。
ALTER TABLE conversation ADD COLUMN next_message_seq INTEGER NOT NULL DEFAULT 1;

UPDATE conversation
SET next_message_seq = (
    SELECT COALESCE(MAX(message.sequence_no), 0) + 1
    FROM message
    WHERE message.conversation_id = conversation.id
);

-- V001 的这两个索引与对应 UNIQUE 索引列相同。查询仍走 UNIQUE 自动索引。
DROP INDEX IF EXISTS idx_message_conversation_seq;
DROP INDEX IF EXISTS idx_outbox_sequence;
