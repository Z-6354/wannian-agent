package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.conversation.MessageRole;
import java.util.Objects;

/**
 * 会话时间线上一条已提交消息的只读快照。
 *
 * <p>供阶段 C 的 ContextAssembler 裁近讯；不得回写原文。正文仍是版本化
 * {@code content_json} envelope，解码由 Assembler / app 完成。
 *
 * @param messageId 消息稳定身份
 * @param role USER / ASSISTANT
 * @param contentJson 库中 envelope 原文；不得为 null
 * @param sequenceNo 会话内序号（升序即时间线）
 */
public record ConversationMessage(
        MessageId messageId, MessageRole role, String contentJson, int sequenceNo) {

    public ConversationMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(contentJson, "contentJson");
    }
}
