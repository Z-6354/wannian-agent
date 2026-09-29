-- 2.5.8：多次定时开火需可重复入交付队（每火一次 SUCCEEDED）。
-- 去掉 UNIQUE(task_id, terminal_status)；活跃幂等改由应用层 findActiveQueued。

CREATE TABLE task_delivery_pending__v028 (
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
    FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    FOREIGN KEY (task_id) REFERENCES background_task(id)
);

INSERT INTO task_delivery_pending__v028
SELECT id, conversation_id, task_id, terminal_status, payload_json, status, created_at, delivered_at
FROM task_delivery_pending;

DROP TABLE task_delivery_pending;
ALTER TABLE task_delivery_pending__v028 RENAME TO task_delivery_pending;

CREATE INDEX idx_task_delivery_pending_conversation_status
    ON task_delivery_pending (conversation_id, status, created_at);

CREATE INDEX idx_task_delivery_pending_task_terminal_status
    ON task_delivery_pending (task_id, terminal_status, status);

CREATE TABLE idle_delivery_pending__v028 (
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
    FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    FOREIGN KEY (task_id) REFERENCES background_task(id)
);

INSERT INTO idle_delivery_pending__v028
SELECT id, conversation_id, task_id, terminal_status, payload_json, status,
       created_at, delivered_at, wake_turn_id
FROM idle_delivery_pending;

DROP TABLE idle_delivery_pending;
ALTER TABLE idle_delivery_pending__v028 RENAME TO idle_delivery_pending;

CREATE INDEX idx_idle_delivery_pending_status_created
    ON idle_delivery_pending (status, created_at);

CREATE INDEX idx_idle_delivery_pending_task_terminal_status
    ON idle_delivery_pending (task_id, terminal_status, status);
