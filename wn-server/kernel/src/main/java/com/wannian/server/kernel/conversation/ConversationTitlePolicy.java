package com.wannian.server.kernel.conversation;

/**
 * 新建会话时的默认标题策略。
 *
 * <p>当前实现只看已有会话数量。创建事务里没有首轮正文，也不能在这里发模型请求。
 * AI 标题若要做，必须等回合完成后再单独处理，不能靠换一个 Bean 假装已经支持。
 */
public interface ConversationTitlePolicy {

    /**
     * 根据当前已有会话数量生成下一个默认标题。
     *
     * @param existingConversationCount 库中已有 conversation 行数（不含正在插入的这一条）
     * @return 非空标题文本
     */
    String nextDefaultTitle(long existingConversationCount);
}
