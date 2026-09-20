package com.wannian.server.api.dependencyfixture;

import java.sql.Connection;

/** 隔离负例：api 引用 JDBC。 */
public final class ApiJdbcViolation {
    public Connection leak;
}
