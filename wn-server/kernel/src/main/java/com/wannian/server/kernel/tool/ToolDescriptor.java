package com.wannian.server.kernel.tool;

import java.util.Objects;

/**
 * 对模型可见的工具描述（由绑定生成；Loop 只消费，不解析角色）。
 */
public record ToolDescriptor(String name, String description, String parametersJsonSchema) {
    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJsonSchema, "parametersJsonSchema");
    }
}
