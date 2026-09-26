package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.conversation.MessageRole;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface ConversationHistoryResult {

    record Ok(List<HistoryMessage> messages, Optional<Integer> nextAfterSeq)
            implements ConversationHistoryResult {
        public Ok {
            Objects.requireNonNull(messages, "messages");
            Objects.requireNonNull(nextAfterSeq, "nextAfterSeq");
            messages = List.copyOf(messages);
        }
    }

    record Rejected(String reasonCode, String detail) implements ConversationHistoryResult {
        public Rejected {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * @param text 已从 content_json 抽出的公开正文
     * @param turnId 可空
     */
    record HistoryMessage(
            MessageId id,
            MessageRole role,
            String text,
            int sequenceNo,
            String turnId,
            String createdAt) {
        public HistoryMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(role, "role");
            text = text == null ? "" : text;
            turnId = turnId == null ? "" : turnId;
            createdAt = createdAt == null ? "" : createdAt;
        }
    }
}
