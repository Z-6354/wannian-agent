-- 2.5.2：background_task + sub_agent_run（建表；本号不插业务行）。
-- status CHECK 一次列全（D3）；定时列见 P2 §7；索引供 2.5.8 Ticker / 侧栏。

CREATE TABLE background_task (
    id                  TEXT PRIMARY KEY NOT NULL,
    origin_turn_id      TEXT NOT NULL,
    conversation_id     TEXT NOT NULL,
    companion_id        TEXT NULL,
    source              TEXT NOT NULL
        CHECK (source IN ('USER_LOOP', 'SYSTEM')),
    task_type           TEXT NOT NULL
        CHECK (task_type IN (
            'READ_ONLY_TOOL_BATCH',
            'USER_SCHEDULED_NOTIFY',
            'WORLD_TICK',
            'MEMORY_REVIEW'
        )),
    status              TEXT NOT NULL
        CHECK (status IN (
            'SCHEDULED',
            'CREATED',
            'READY',
            'WAITING',
            'RUNNING',
            'CANCEL_REQUESTED',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED'
        )),
    input_json          TEXT NOT NULL,
    result_json         TEXT NULL,
    notify_policy       TEXT NOT NULL
        CHECK (notify_policy IN ('USER_VISIBLE', 'SILENT')),
    retry_policy_json   TEXT NOT NULL,
    schedule_spec_json  TEXT NULL,
    timezone            TEXT NOT NULL,
    next_fire_at        TEXT NULL,
    last_fired_at       TEXT NULL,
    revision            INTEGER NOT NULL,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    completed_at        TEXT NULL,
    FOREIGN KEY (origin_turn_id) REFERENCES turn(id),
    FOREIGN KEY (conversation_id) REFERENCES conversation(id)
);

CREATE INDEX idx_background_task_status_updated
    ON background_task (status, updated_at);

CREATE INDEX idx_background_task_conversation_created
    ON background_task (conversation_id, created_at);

CREATE INDEX idx_background_task_status_next_fire
    ON background_task (status, next_fire_at);

CREATE TABLE sub_agent_run (
    id                  TEXT PRIMARY KEY NOT NULL,
    task_id             TEXT NOT NULL,
    attempt_no          INTEGER NOT NULL,
    status              TEXT NOT NULL
        CHECK (status IN (
            'CREATED',
            'LEASED',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED',
            'LOST'
        )),
    executor_id         TEXT NOT NULL,
    lease_token_hash    TEXT NULL,
    lease_expires_at    TEXT NULL,
    result_json         TEXT NULL,
    evidence_json       TEXT NULL,
    error_code          TEXT NULL,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    UNIQUE (task_id, attempt_no),
    FOREIGN KEY (task_id) REFERENCES background_task(id)
);

CREATE INDEX idx_sub_agent_run_task_attempt
    ON sub_agent_run (task_id, attempt_no);

CREATE INDEX idx_sub_agent_run_status_lease
    ON sub_agent_run (status, lease_expires_at);
