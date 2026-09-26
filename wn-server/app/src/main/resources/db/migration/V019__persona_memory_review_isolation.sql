DROP INDEX idx_memory_review_job_activity_watermark;
CREATE UNIQUE INDEX idx_memory_review_job_persona_activity_watermark
    ON memory_review_job (conversation_id, companion_id, activity_watermark)
    WHERE trigger = 'IDLE' AND activity_watermark IS NOT NULL;
