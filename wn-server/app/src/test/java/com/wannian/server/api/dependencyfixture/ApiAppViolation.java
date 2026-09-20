package com.wannian.server.api.dependencyfixture;

import com.wannian.server.app.WannianApplication;

/** 隔离负例：api 引用 app。 */
public final class ApiAppViolation {
    public Class<?> type = WannianApplication.class;
}
