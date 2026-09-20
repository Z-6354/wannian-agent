package com.wannian.server.kernel.conversation;

/**
 * v0.1 默认标题：{@code 会话1}、{@code 会话2}、…
 *
 * <p>只根据已有会话数量命名。首轮内容不在创建事务里，因此这里不能、也不承诺换成模型命名。
 * 以后若要 AI 标题，应在回合完成后另开流程，不能塞进创建会话的数据库事务。
 */
public final class SequentialConversationTitlePolicy implements ConversationTitlePolicy {

    @Override
    public String nextDefaultTitle(long existingConversationCount) {
        if (existingConversationCount < 0) {
            throw new IllegalArgumentException("已有会话数不能为负数: " + existingConversationCount);
        }
        return "会话" + (existingConversationCount + 1);
    }
}
