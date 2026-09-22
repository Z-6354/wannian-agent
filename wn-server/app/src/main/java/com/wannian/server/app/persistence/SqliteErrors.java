package com.wannian.server.app.persistence;

import com.wannian.server.kernel.error.ErrorCodes;
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

    /**
     * 把 JDBC 异常译成 {@link ErrorCodes}；不把 SQL 原文带出边界。
     *
     * <p>唯一约束由调用方按业务语义处理（幂等冲突等），此处默认 {@code PERSISTENCE_FAILED}。
     */
    static String toErrorCode(SQLException ex) {
        if (isBusy(ex)) {
            return ErrorCodes.RETRYABLE_BUSY;
        }
        return ErrorCodes.PERSISTENCE_FAILED;
    }
}
