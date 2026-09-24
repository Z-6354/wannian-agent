-- Review IDLE 去重水位。保存入队时的会话活动时间，使 DEAD/已完成的 job
-- 仍能抑制同一活动快照，而新的会话活动允许产生新的 Review job。
ALTER TABLE memory_review_job ADD COLUMN activity_watermark TEXT NULL;

-- 每个会话仅给最新 IDLE job 回填当前 last_activity_at，避免升级后重复入队；
-- 旧任务保持历史记录，其他行水位留空以避开历史重复。
UPDATE memory_review_job
SET activity_watermark = (SELECT c.last_activity_at
                          FROM conversation c
                          WHERE c.id = memory_review_job.conversation_id)
WHERE trigger = 'IDLE'
  AND activity_watermark IS NULL
  AND id = (SELECT j.id
            FROM memory_review_job j
            WHERE j.conversation_id = memory_review_job.conversation_id
              AND j.trigger = 'IDLE'
            ORDER BY j.created_at DESC, j.id DESC
            LIMIT 1);

CREATE UNIQUE INDEX idx_memory_review_job_activity_watermark
    ON memory_review_job (conversation_id, activity_watermark)
    WHERE trigger = 'IDLE' AND activity_watermark IS NOT NULL;
