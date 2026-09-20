package com.wannian.server.app.persistence;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/** SQLite 错误分类。只认出唯一约束和可重试的忙，不能把其它失败当成幂等成功。 */
final class SqliteErrors {

    private SqliteErrors() {}

    static boolean isUniqueViolation(SQLException ex) {
        for (SQLException current = ex; current != null; current = current.getNextException()) {
            if (current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (current instanceof SQLiteException sqlite) {
                SQLiteErrorCode code = sqlite.getResultCode();
                if (code == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY
                        || code == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && (message.contains("UNIQUE") || message.contains("PRIMARY KEY"))) {
                return true;
            }
        }
        return false;
    }

    static boolean isBusy(SQLException ex) {
        for (SQLException current = ex; current != null; current = current.getNextException()) {
            if (current instanceof SQLiteException sqlite) {
                SQLiteErrorCode code = sqlite.getResultCode();
                if (code == SQLiteErrorCode.SQLITE_BUSY
                        || code == SQLiteErrorCode.SQLITE_BUSY_RECOVERY
                        || code == SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT
                        || code == SQLiteErrorCode.SQLITE_LOCKED
                        || code == SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null
                    && (message.contains("SQLITE_BUSY") || message.contains("database is locked"))) {
                return true;
            }
        }
        return false;
    }
}
