package com.wannian.server.api.common;

import java.util.Objects;
import java.util.UUID;

/**
 * 后台任务用户待审单身份，对应表 {@code task_review_pending.id}。
 */
public record TaskReviewId(UUID value) {

    public TaskReviewId {
        Objects.requireNonNull(value, "TaskReviewId.value");
    }

    public static TaskReviewId generate() {
        return new TaskReviewId(UUID.randomUUID());
    }

    public String asString() {
        return value.toString();
    }

    public static TaskReviewId parse(String text) {
        Objects.requireNonNull(text, "text");
        return new TaskReviewId(UUID.fromString(text.trim()));
    }
}
