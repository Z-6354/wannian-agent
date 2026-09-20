package com.wannian.server.kernel.model;

import java.util.Objects;

/** 模型请求的工具调用。 */
public record ToolCallRequest(String id, String name, String argumentsJson) {
    public ToolCallRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
