-- =============================================================================
-- V004 · 可恢复完成计划
-- 进入 COMMITTING 时与状态迁移同事务写入。进程退出后只提交这份计划，不重跑模型。
-- 不改 V001—V003。完成提交成功后由应用删除对应行。
-- =============================================================================

CREATE TABLE turn_commit_plan (
    turn_id         TEXT    NOT NULL PRIMARY KEY,
    format_version  INTEGER NOT NULL,
    execution_id    TEXT    NOT NULL,
    plan_json       TEXT    NOT NULL,
    frozen_at       TEXT    NOT NULL,
    FOREIGN KEY (turn_id) REFERENCES turn (id)
);
