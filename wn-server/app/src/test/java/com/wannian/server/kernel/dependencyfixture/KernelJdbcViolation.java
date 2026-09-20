package com.wannian.server.kernel.dependencyfixture;

import java.sql.Connection;

/** 隔离负例：kernel 引用 JDBC。不要放到 main。 */
public final class KernelJdbcViolation {
    public Connection leak;
}
