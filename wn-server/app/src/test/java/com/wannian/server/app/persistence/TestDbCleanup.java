package com.wannian.server.app.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 测试库清空：先关 FK 再删依赖 turn/conversation 的子表，避免漏表导致约束失败。
 */
public final class TestDbCleanup {

    private TestDbCleanup() {}

    /** 删除依赖 turn/conversation 的表（表不存在时忽略）；须在 DELETE turn 之前调用。 */
    public static void deleteTaskTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            try {
                ignoreMissing(statement, "DELETE FROM task_delivery_pending");
                ignoreMissing(statement, "DELETE FROM idle_delivery_pending");
                ignoreMissing(statement, "DELETE FROM task_review_pending");
                ignoreMissing(statement, "DELETE FROM sub_agent_run");
                ignoreMissing(statement, "DELETE FROM background_task");
                ignoreMissing(statement, "DELETE FROM pending_persona_switch");
                ignoreMissing(statement, "DELETE FROM turn_persona");
            } finally {
                statement.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    private static void ignoreMissing(Statement statement, String sql) throws SQLException {
        try {
            statement.executeUpdate(sql);
        } catch (SQLException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            if (!msg.contains("no such table")) {
                throw ex;
            }
        }
    }
}
