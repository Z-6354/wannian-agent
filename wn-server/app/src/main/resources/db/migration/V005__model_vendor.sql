-- =============================================================================
-- V005 · 模型供应商与唯一启用选择（K03-W）
-- 这是已确认的配置事实，不是缓存。密钥本身不在此表，只存环境变量名。
-- 不改 V001–V004。
-- =============================================================================

CREATE TABLE model_vendor (
    -- 用户填写的稳定 id，创建后不可改
    id            TEXT    NOT NULL PRIMARY KEY,
    display_name  TEXT    NOT NULL,
    -- 线协议，本批只接受 openai-compatible
    protocol      TEXT    NOT NULL,
    -- 去掉末尾斜杠后的 base URL
    base_url      TEXT    NOT NULL,
    -- 环境变量名，不是密钥值
    api_key_env   TEXT    NOT NULL,
    revision      INTEGER NOT NULL,
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL
);

-- 全库最多一行。singleton 只能为 1，因此不能插入第二行。
CREATE TABLE model_enabled (
    singleton   INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
    vendor_id   TEXT    NOT NULL,
    model_id    TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL,
    FOREIGN KEY (vendor_id) REFERENCES model_vendor (id)
);
