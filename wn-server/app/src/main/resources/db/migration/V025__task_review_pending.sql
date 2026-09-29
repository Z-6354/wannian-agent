-- 2.5.5：用户待审后台提案（确认前不插 background_task）。

CREATE TABLE task_review_pending (
    id                   TEXT PRIMARY KEY NOT NULL,
    conversation_id      TEXT NOT NULL,
    turn_id              TEXT NOT NULL,
    proposal_json        TEXT NOT NULL,
    acknowledgement_text TEXT NOT NULL,
    status               TEXT NOT NULL
        CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'EXPIRED')),
    created_at           TEXT NOT NULL,
    expires_at           TEXT NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    FOREIGN KEY (turn_id) REFERENCES turn(id)
);

CREATE INDEX idx_task_review_pending_conversation_status
    ON task_review_pending (conversation_id, status, created_at);

CREATE INDEX idx_task_review_pending_status_expires
    ON task_review_pending (status, expires_at);
