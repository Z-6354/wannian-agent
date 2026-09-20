package com.wannian.server.api.dependencyfixture;

import com.wannian.server.kernel.turn.Turn;

/** 隔离负例：api 引用 kernel。 */
public final class ApiKernelViolation {
    public Class<?> type = Turn.class;
}
