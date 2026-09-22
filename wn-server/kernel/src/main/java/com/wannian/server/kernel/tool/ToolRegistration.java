package com.wannian.server.kernel.tool;

import java.util.Set;

/**
 * 向 {@link ToolCatalog} 登记一条工具的载荷。
 *
 * <p>合法性由 {@link ToolCatalog#register} 校验并返回封闭结果；本 record 不在构造时抛业务拒绝。
 */
public record ToolRegistration(
        String toolName,
        String description,
        ToolParameterSchema parameters,
        Set<String> requiredCapabilities,
        ToolAdapter executor) {}
