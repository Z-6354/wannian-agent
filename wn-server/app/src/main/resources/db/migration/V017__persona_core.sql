CREATE TABLE persona_definition (
    id TEXT PRIMARY KEY NOT NULL,
    status TEXT NOT NULL,
    display_name TEXT NOT NULL,
    profile_json TEXT NOT NULL,
    revision INTEGER NOT NULL,
    source_id TEXT NULL,
    request_key TEXT NULL UNIQUE,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE conversation_persona (
    conversation_id TEXT PRIMARY KEY NOT NULL,
    persona_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    FOREIGN KEY (persona_id) REFERENCES persona_definition(id)
);

CREATE TABLE turn_persona (
    turn_id TEXT PRIMARY KEY NOT NULL,
    conversation_id TEXT NOT NULL,
    persona_id TEXT NOT NULL,
    definition_revision INTEGER NOT NULL,
    binding_revision INTEGER NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (turn_id) REFERENCES turn(id),
    FOREIGN KEY (conversation_id) REFERENCES conversation(id)
);
