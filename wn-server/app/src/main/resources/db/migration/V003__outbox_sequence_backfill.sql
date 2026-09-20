-- =============================================================================
-- V003 · 抬高 outbox 序号计数器
-- V002 建计数器时写成了 1，没有看 outbox_event 里已经存在的序号。
-- 这里只把 next_value 抬到 MAX(sequence_no)+1，已经更大的计数器保持不变。
-- 不改 V001 / V002，不删除已经写出的事件，不把计数器调小。
-- =============================================================================

UPDATE sequence_counter
SET next_value = max(
    next_value,
    COALESCE((SELECT MAX(sequence_no) FROM outbox_event), 0) + 1
)
WHERE name = 'outbox';
