-- Review result writes are replay-safe and commit with the owning job's SUCCEEDED state.
CREATE TABLE memory_review_job_apply (
    job_id          TEXT NOT NULL REFERENCES memory_review_job(id) ON DELETE CASCADE,
    draft_key       TEXT NOT NULL,
    memory_record_id TEXT NOT NULL REFERENCES memory_record(id),
    created_at      TEXT NOT NULL,
    PRIMARY KEY (job_id, draft_key)
);
