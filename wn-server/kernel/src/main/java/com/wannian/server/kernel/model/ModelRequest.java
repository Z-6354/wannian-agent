package com.wannian.server.kernel.model;

import com.wannian.server.kernel.tool.ToolDescriptor;
import java.util.List;
import java.util.Objects;

/** 一次模型请求（含本轮可见工具描述）。 */
public record ModelRequest(List<ModelMessage> messages, List<ToolDescriptor> tools) {
    public ModelRequest {
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 无工具的请求。 */
    public ModelRequest(List<ModelMessage> messages) {
        this(messages, List.of());
    }
}
