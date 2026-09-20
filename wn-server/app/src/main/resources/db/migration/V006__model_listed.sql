-- 已加入列表的模型。供应商检索结果不进这张表。
-- 当前使用仍在 model_enabled。不改 V001–V005。

CREATE TABLE model_listed (
    vendor_id     TEXT NOT NULL,
    model_id      TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    added_at      TEXT NOT NULL,
    PRIMARY KEY (vendor_id, model_id),
    FOREIGN KEY (vendor_id) REFERENCES model_vendor (id)
);
