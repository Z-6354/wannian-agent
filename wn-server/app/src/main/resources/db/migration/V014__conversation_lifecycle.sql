-- =============================================================================
-- V014 · 会话生命周期与搜索（0.2.4-B）
-- 依据：docs/plans/k04-b-conversation-readwrite-implementation.md
-- =============================================================================
--
-- 追加 conversation 元数据列；FTS5 索引标题 + 已提交消息正文（envelope.text）。
-- 不删改 V001–V013；不碰 memory_record。
-- =============================================================================

ALTER TABLE conversation ADD COLUMN title_source TEXT NOT NULL DEFAULT 'AUTO';
ALTER TABLE conversation ADD COLUMN pre_trash_status TEXT NULL;
ALTER TABLE conversation ADD COLUMN trashed_at TEXT NULL;

-- 默认列表：ACTIVE × 最近活动
CREATE INDEX IF NOT EXISTS idx_conversation_status_activity
    ON conversation (status, last_activity_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_conversation_trash
    ON conversation (status, trashed_at);

-- FTS5：一行 = 一条可搜消息（或仅标题占位行 message_id 为空串）
-- conversation_id / message_id / status 不参与分词检索
CREATE VIRTUAL TABLE IF NOT EXISTS conversation_search USING fts5(
    conversation_id UNINDEXED,
    message_id UNINDEXED,
    status UNINDEXED,
    title,
    body,
    tokenize = 'unicode61'
);

-- 回填：每条已提交消息一行；title 取当时会话标题，body 尽量抽 text
-- SQLite json_extract 在常见 JDBC 构建可用；抽不到则 body 空（仍可搜标题行）
INSERT INTO conversation_search (conversation_id, message_id, status, title, body)
SELECT
    m.conversation_id,
    m.id,
    c.status,
    COALESCE(c.title, ''),
    COALESCE(json_extract(m.content_json, '$.text'), '')
FROM message m
JOIN conversation c ON c.id = m.conversation_id;

-- 无消息的会话：仍可按标题搜到（message_id 空串）
INSERT INTO conversation_search (conversation_id, message_id, status, title, body)
SELECT c.id, '', c.status, COALESCE(c.title, ''), ''
FROM conversation c
WHERE NOT EXISTS (
    SELECT 1 FROM message m WHERE m.conversation_id = c.id
);
