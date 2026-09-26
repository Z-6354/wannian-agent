CREATE TABLE persona_source (
    source_id TEXT PRIMARY KEY,
    file_name TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    byte_count INTEGER NOT NULL,
    codepoint_count INTEGER NOT NULL,
    storage_name TEXT NOT NULL,
    deleted_at TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE persona_import_job (
    import_id TEXT PRIMARY KEY,
    request_key TEXT UNIQUE,
    source_id TEXT NOT NULL REFERENCES persona_source(source_id),
    character_hint TEXT NOT NULL,
    status TEXT NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    error_code TEXT,
    error_summary TEXT,
    model_id TEXT,
    extraction_version TEXT NOT NULL,
    scan_chapters INTEGER NOT NULL DEFAULT 0,
    matched_chapters INTEGER NOT NULL DEFAULT 0,
    model_chapters INTEGER NOT NULL DEFAULT 0,
    model_windows INTEGER NOT NULL DEFAULT 0,
    input_chars INTEGER NOT NULL DEFAULT 0,
    unmodeled_chapters INTEGER NOT NULL DEFAULT 0,
    window_offsets_json TEXT NOT NULL DEFAULT '[]',
    candidate_persona_id TEXT,
    lease_until TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX idx_persona_import_status ON persona_import_job(status, created_at);
