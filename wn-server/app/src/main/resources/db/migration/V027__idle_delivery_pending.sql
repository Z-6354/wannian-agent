-- 2.5.7：Idle 完成待唤醒队列（系统唤模前 durable）。

CREATE TABLE idle_delivery_pending (
    id                TEXT PRIMARY KEY NOT NULL,
    conversation_id   TEXT NOT NULL,
    task_id           TEXT NOT NULL,
    terminal_status   TEXT NOT NULL
        CHECK (terminal_status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    payload_json      TEXT NOT NULL,
    status            TEXT NOT NULL
        CHECK (status IN ('QUEUED', 'DISPATCHING', 'DELIVERED', 'CANCELLED')),
    created_at        TEXT NOT NULL,
    delivered_at      TEXT NULL,
    wake_turn_id      TEXT NULL,
    UNIQUE (task_id, terminal_status),
    FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    FOREIGN KEY (task_id) REFERENCES background_task(id)
);

CREATE INDEX idx_idle_delivery_pending_status_created
    ON idle_delivery_pending (status, created_at);
