package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import java.util.Objects;

/**
 * 接收用户 Turn 的不可变计划（开聊后的下一步）。
 *
 * <p>会话必须已通过 {@code ConversationStore#create} 存在；本计划不创建会话。
 * {@code clientRequestId} 是全局幂等键：它绑定的是会话加原始正文，不是消息 id 或序号。
 * 同一会话、同一正文的重试回放原 Turn；换会话或换正文都是冲突，不能静默改归属。
 */
public record ReceiveTurnPlan(
        ConversationId conversationId,
        String clientRequestId,
        TurnId turnId,
        UserMessageDraft userMessage) {

    public ReceiveTurnPlan {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(userMessage, "userMessage");
        clientRequestId = clientRequestId.trim();
        if (clientRequestId.isEmpty()) {
            throw new IllegalArgumentException("clientRequestId 不能为空");
        }
    }

    /**
     * 用户消息草稿。
     *
     * @param messageId 预生成消息 id
     * @param role 应为 {@link MessageRole#USER}
     * @param contentJson 版本化 JSON envelope。幂等比较用这份原文，不比较消息 id 或序号
     * @param sequenceNo 已弃用。接收事务自行分配会话内序号，这个数字不会被写入
     */
    public record UserMessageDraft(
            MessageId messageId, MessageRole role, String contentJson, int sequenceNo) {

        public UserMessageDraft {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(contentJson, "contentJson");
        }
    }
}
