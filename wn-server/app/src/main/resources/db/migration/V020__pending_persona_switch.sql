CREATE TABLE pending_persona_switch (
    source_turn_id TEXT PRIMARY KEY NOT NULL,
    conversation_id TEXT NOT NULL,
    persona_id TEXT NOT NULL,
    expected_binding_revision INTEGER NOT NULL,
    operation_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK(status IN ('PENDING','APPLIED','ABORTED','CONFLICT')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (source_turn_id) REFERENCES turn(id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE,
    FOREIGN KEY (persona_id) REFERENCES persona_definition(id)
);
CREATE INDEX idx_pending_persona_switch_status ON pending_persona_switch(status,created_at);
