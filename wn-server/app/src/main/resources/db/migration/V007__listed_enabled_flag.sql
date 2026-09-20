-- 当前使用是已加入列表上的字段，不再单独存 model_enabled。
-- 最多一行 enabled=1。供应商检索目录仍不落盘。

ALTER TABLE model_listed ADD COLUMN enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1));

UPDATE model_listed
SET enabled = 1
WHERE EXISTS (
    SELECT 1
    FROM model_enabled e
    WHERE e.vendor_id = model_listed.vendor_id
      AND e.model_id = model_listed.model_id
);

INSERT INTO model_listed (vendor_id, model_id, display_name, enabled, added_at)
SELECT e.vendor_id, e.model_id, e.model_id, 1, e.updated_at
FROM model_enabled e
WHERE NOT EXISTS (
    SELECT 1
    FROM model_listed l
    WHERE l.vendor_id = e.vendor_id
      AND l.model_id = e.model_id
);

CREATE UNIQUE INDEX uq_model_listed_one_enabled
    ON model_listed (enabled)
    WHERE enabled = 1;

DROP TABLE model_enabled;
