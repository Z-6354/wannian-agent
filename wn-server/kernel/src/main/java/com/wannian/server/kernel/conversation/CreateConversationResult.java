package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.ConversationId;
import java.util.Objects;

/**
 * {@link ConversationStore#create} 的封闭结果。
 *
 * <p>{@code sealed}（密封接口）：只允许下方 {@code permits} 列出的实现类型，
 * 调用方用 {@code switch}/模式匹配时可穷尽所有结果，不会漏掉未处理分支。
 */
public sealed interface CreateConversationResult
        permits CreateConversationResult.Created,
                CreateConversationResult.AlreadyExists,
                CreateConversationResult.Rejected {

    /** 新会话已写入。 */
    record Created(ConversationId conversationId) implements CreateConversationResult {
        public Created {
            Objects.requireNonNull(conversationId, "conversationId");
        }
    }

    /**
     * 相同 id 已存在；不覆盖已有行。
     *
     * <p>与 Turn 的 clientRequestId 幂等不同：建会话重复 id 视为冲突，不视为成功回放。
     */
    record AlreadyExists(ConversationId conversationId) implements CreateConversationResult {
        public AlreadyExists {
            Objects.requireNonNull(conversationId, "conversationId");
        }
    }

    /** 校验失败等。 */
    record Rejected(String reasonCode, String detail) implements CreateConversationResult {
        public Rejected {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
