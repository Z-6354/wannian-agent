package com.wannian.server.kernel.dependencyfixture;

import com.wannian.server.app.WannianApplication;

/** 隔离负例：kernel 引用 app。 */
public final class KernelAppViolation {
    public Class<?> type = WannianApplication.class;
}
