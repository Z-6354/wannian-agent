package com.wannian.server.kernel.model;

import java.util.List;
import java.util.Objects;

/** 一次模型请求。 */
public record ModelRequest(List<ModelMessage> messages) {
    public ModelRequest {
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
    }
}
