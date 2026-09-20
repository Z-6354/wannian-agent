package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.ConversationId;
import java.util.Objects;
import java.util.Optional;

/**
 * 显式新建会话的命令（开聊的唯一入口形状）。
 *
 * <p>调用方必须先 {@code create}，再对同一 {@link ConversationId} 做 receive；
 * 禁止在 receive 中隐式建会话。
 *
 * @param conversationId 应用层预生成的会话身份
 * @param title 可选标题；空则由实现填充默认 {@code 会话N}（v0.1）；v0.2 再改为首轮后 AI 生成
 */
public record CreateConversationCommand(ConversationId conversationId, Optional<String> title) {

    public CreateConversationCommand {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(title, "title");
        title = title.map(String::trim).filter(s -> !s.isEmpty());
    }

    /** 无标题的新建。 */
    public static CreateConversationCommand of(ConversationId conversationId) {
        return new CreateConversationCommand(conversationId, Optional.empty());
    }
}
