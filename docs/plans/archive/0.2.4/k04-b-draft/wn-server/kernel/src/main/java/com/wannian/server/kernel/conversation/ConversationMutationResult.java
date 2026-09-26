package com.wannian.server.kernel.conversation;

import java.util.Objects;

/** 改名/归档/回收站等 CAS 变更结果。 */
public sealed interface ConversationMutationResult {

    record Ok(ConversationSummary conversation) implements ConversationMutationResult {
        public Ok {
            Objects.requireNonNull(conversation, "conversation");
        }
    }

    record Rejected(String reasonCode, String detail) implements ConversationMutationResult {
        public Rejected {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
