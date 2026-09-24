-- 0.2.3-L：Turn 逐步行为账本 + 进程生命周期事件。
-- turn_step 对齐 04-kernel-reference；process_event 承载无 turn FK 的启停。

CREATE TABLE turn_step (
    id              TEXT PRIMARY KEY,
    turn_id         TEXT NOT NULL,
    conversation_id TEXT,
    step_no         INTEGER NOT NULL,
    actor           TEXT NOT NULL,
    kind            TEXT NOT NULL,
    request_json    TEXT,
    result_json     TEXT,
    status          TEXT NOT NULL,
    error_code      TEXT,
    started_at      TEXT NOT NULL,
    finished_at     TEXT,
    UNIQUE (turn_id, step_no),
    FOREIGN KEY (turn_id) REFERENCES turn (id)
);

CREATE INDEX idx_turn_step_turn_kind ON turn_step (turn_id, kind);
CREATE INDEX idx_turn_step_started ON turn_step (started_at);

CREATE TABLE process_event (
    id            TEXT PRIMARY KEY,
    kind          TEXT NOT NULL,
    payload_json  TEXT,
    created_at    TEXT NOT NULL
);

CREATE INDEX idx_process_event_created ON process_event (created_at);
