CREATE TABLE yanhuo_profile_overlay_revision (
    revision INTEGER PRIMARY KEY CHECK (revision > 0),
    source_persona_id TEXT NOT NULL,
    source_profile_revision INTEGER NOT NULL,
    soul_overlay TEXT NOT NULL,
    voice_overlay TEXT NOT NULL,
    evidence_summary TEXT NOT NULL,
    operation_id TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    CHECK (length(soul_overlay) + length(voice_overlay) <= 1600)
);

CREATE TABLE yanhuo_profile_overlay_pointer (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    revision INTEGER NULL REFERENCES yanhuo_profile_overlay_revision(revision)
);

INSERT INTO yanhuo_profile_overlay_pointer(singleton_id, revision) VALUES (1, NULL);

CREATE TRIGGER yanhuo_profile_overlay_revision_no_update
BEFORE UPDATE ON yanhuo_profile_overlay_revision
BEGIN
    SELECT RAISE(ABORT, 'overlay revisions are immutable');
END;

CREATE TRIGGER yanhuo_profile_overlay_revision_no_delete
BEFORE DELETE ON yanhuo_profile_overlay_revision
BEGIN
    SELECT RAISE(ABORT, 'overlay revisions are immutable');
END;
