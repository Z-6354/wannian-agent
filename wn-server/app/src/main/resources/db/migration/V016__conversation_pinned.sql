-- Durable conversation pinning. Only ACTIVE rows may retain pinned=1.
ALTER TABLE conversation ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_conversation_active_pinned_activity
    ON conversation (status, pinned DESC, last_activity_at DESC, id DESC);
