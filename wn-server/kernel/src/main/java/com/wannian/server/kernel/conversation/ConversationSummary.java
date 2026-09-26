package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.conversation.ConversationStatus;
import com.wannian.server.api.conversation.TitleSource;
import java.util.Objects;

/**
 * 会话摘要（列表/详情/最近）。
 *
 * @param lastActivityAt ISO-8601；可空（旧行）
 * @param trashedAt 仅 TRASHED 有值
 */
public record ConversationSummary(
        ConversationId id,
        String title,
        ConversationStatus status,
        TitleSource titleSource,
        long revision,
        String createdAt,
        String updatedAt,
        String lastActivityAt,
        String trashedAt,
        boolean pinned) {

    public ConversationSummary {
        Objects.requireNonNull(id, "id");
        title = title == null ? "" : title;
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(titleSource, "titleSource");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        lastActivityAt = lastActivityAt == null ? "" : lastActivityAt;
        trashedAt = trashedAt == null ? "" : trashedAt;
    }
}
