package com.wannian.server.kernel.conversation;

/**
 * 会话存储口：显式开聊。
 *
 * <p>实现放在 app（SQLite）；kernel 不依赖 JDBC。
 * 本批只提供 {@link #create}；查询/归档后续再扩。
 */
public interface ConversationStore {

    /**
     * 创建会话；主键已存在时返回 {@link CreateConversationResult.AlreadyExists}，不覆盖。
     *
     * @param command 预生成 id 与可选标题
     * @return 非 null 的封闭结果
     */
    CreateConversationResult create(CreateConversationCommand command);
}
