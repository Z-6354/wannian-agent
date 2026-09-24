-- 记录 Review claim 时看到的会话活动水位。完成时间不能代表输入读取位置：
-- Review 执行期间进入的新 Turn 必须留待下一轮扫描。
ALTER TABLE memory_review_job ADD COLUMN reviewed_activity_watermark TEXT NULL;

-- V009 的回填值只是迁移时的当前 last_activity_at，并非历史 job 实际读取位置。
-- 清除这些无法证明的 IDLE 去重水位，让升级后的扫描保守补审。
UPDATE memory_review_job
SET activity_watermark = NULL
WHERE trigger = 'IDLE'
  AND reviewed_activity_watermark IS NULL;
