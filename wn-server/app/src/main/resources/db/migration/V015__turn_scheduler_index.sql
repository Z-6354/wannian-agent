-- 0.2.4-C：Durable Turn 调度按会话 FIFO 查 RECEIVED
CREATE INDEX IF NOT EXISTS idx_turn_status_conversation_created
    ON turn (status, conversation_id, created_at, id);
