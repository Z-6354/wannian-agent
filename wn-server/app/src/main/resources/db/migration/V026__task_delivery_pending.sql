-- 2.5.6：Busy 完成待交付队列（确认展示前 durable）。

CREATE TABLE task_delivery_pending (
    id                TEXT PRIMARY KEY NOT NULL,
    conversation_id   TEXT NOT NULL,
    task_id           TEXT NOT NULL,
    terminal_status   TEXT NOT NULL
        CHECK (terminal_status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    payload_json      TEXT NOT NULL,
    status            TEXT NOT NULL
        CHECK (status IN ('QUEUED', 'DELIVERED', 'CANCELLED')),
    created_at        TEXT NOT NULL,
    delivered_at      TEXT NULL,
    UNIQUE (task_id, terminal_status),
    FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    FOREIGN KEY (task_id) REFERENCES background_task(id)
);

CREATE INDEX idx_task_delivery_pending_conversation_status
    ON task_delivery_pending (conversation_id, status, created_at);
