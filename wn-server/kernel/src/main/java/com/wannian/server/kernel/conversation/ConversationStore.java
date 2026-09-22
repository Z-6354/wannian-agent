package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.ConversationId;
import java.util.List;

/**
 * 会话存储口：显式开聊与只读近讯。
 *
 * <p>实现放在 app（SQLite）；kernel 不依赖 JDBC。
 * 写入消息仍走 {@code TurnCommitter}；本接口不提供改写正文的方法。
 */
public interface ConversationStore {

    /**
     * 创建会话；主键已存在时返回 {@link CreateConversationResult.AlreadyExists}，不覆盖。
     *
     * @param command 预生成 id 与可选标题
     * @return 非 null 的封闭结果
     */
    CreateConversationResult create(CreateConversationCommand command);

    /**
     * 按会话时间线升序返回最近 {@code limit} 条已提交消息。
     *
     * <p>实现应先按 {@code sequence_no} 取最新若干条，再升序交还给调用方。
     * 会话不存在或尚无消息时返回空列表，不得返回 null。
     *
     * @param conversationId 目标会话；不得为 null
     * @param limit 条数上界；须为正
     * @return 不可变列表；不得为 null
     */
    List<ConversationMessage> listRecentMessages(ConversationId conversationId, int limit);
}
