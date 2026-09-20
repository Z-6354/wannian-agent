package com.wannian.server.kernel.model;

import java.util.Objects;

/** 对话消息。role 为 user / assistant / system。 */
public record ModelMessage(String role, String content) {
    public ModelMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
