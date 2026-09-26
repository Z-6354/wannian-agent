-- Keep the legacy default persona available as the target for existing and new bindings.
INSERT INTO persona_definition(
    id, status, display_name, profile_json, revision, source_id, request_key, created_at, updated_at
)
VALUES (
    'yanhuo', 'ACTIVE', '杜小洛',
    '{"schemaVersion":1,"displayName":"杜小洛","soul":"杜小洛（画像由 prompts/SOUL.md 加载）","voice":"杜小洛（画像由 prompts/VOICE.md 加载）","identity":"杜小洛（画像由 prompts/IDENTITY.md 加载）","sources":[],"evidence":[]}',
    0, NULL, NULL, '1970-01-01T00:00:00Z', '1970-01-01T00:00:00Z'
)
ON CONFLICT(id) DO NOTHING;

-- Fail with a clear message before rebuilding if historical rows have no definition.
-- The migration intentionally preserves those rows instead of inventing or deleting personas.
CREATE TEMP TRIGGER validate_turn_persona_persona_id
BEFORE UPDATE OF persona_id ON turn_persona
FOR EACH ROW
WHEN NOT EXISTS (
    SELECT 1 FROM persona_definition WHERE id = NEW.persona_id
)
BEGIN
    SELECT RAISE(ABORT, 'V023: turn_persona references a missing persona_definition; migration aborted without deleting rows');
END;

UPDATE turn_persona SET persona_id = persona_id;
DROP TRIGGER validate_turn_persona_persona_id;

CREATE TABLE turn_persona_v023 (
    turn_id TEXT PRIMARY KEY NOT NULL,
    conversation_id TEXT NOT NULL,
    persona_id TEXT NOT NULL,
    definition_revision INTEGER NOT NULL,
    binding_revision INTEGER NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (turn_id) REFERENCES turn(id),
    FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    FOREIGN KEY (persona_id) REFERENCES persona_definition(id)
);

INSERT INTO turn_persona_v023(
    turn_id, conversation_id, persona_id, definition_revision, binding_revision, snapshot_json, created_at
)
SELECT
    turn_id, conversation_id, persona_id, definition_revision, binding_revision, snapshot_json, created_at
FROM turn_persona;

DROP TABLE turn_persona;
ALTER TABLE turn_persona_v023 RENAME TO turn_persona;
