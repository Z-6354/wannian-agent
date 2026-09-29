-- 2.5 修复：审核确认中间态 CLAIMED（PENDING→CLAIMED→CONFIRMED），支持崩溃对账。

CREATE TABLE task_review_pending__v029 (
    id                   TEXT PRIMARY KEY NOT NULL,
    conversation_id      TEXT NOT NULL,
    turn_id              TEXT NOT NULL,
    proposal_json        TEXT NOT NULL,
    acknowledgement_text TEXT NOT NULL,
    status               TEXT NOT NULL
        CHECK (status IN ('PENDING', 'CLAIMED', 'CONFIRMED', 'REJECTED', 'EXPIRED')),
    created_at           TEXT NOT NULL,
    expires_at           TEXT NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    FOREIGN KEY (turn_id) REFERENCES turn(id)
);

INSERT INTO task_review_pending__v029
SELECT id, conversation_id, turn_id, proposal_json, acknowledgement_text, status,
       created_at, expires_at
FROM task_review_pending;

DROP TABLE task_review_pending;
ALTER TABLE task_review_pending__v029 RENAME TO task_review_pending;

CREATE INDEX idx_task_review_pending_conversation_status
    ON task_review_pending (conversation_id, status, created_at);

CREATE INDEX idx_task_review_pending_status_expires
    ON task_review_pending (status, expires_at);

CREATE INDEX idx_task_review_pending_status
    ON task_review_pending (status, created_at);
