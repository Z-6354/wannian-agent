package com.wannian.server.kernel.dependencyfixture;

import org.springframework.context.ApplicationContext;

/** 隔离负例：kernel 引用 Spring Context。 */
public final class KernelSpringViolation {
    public ApplicationContext context;
}
