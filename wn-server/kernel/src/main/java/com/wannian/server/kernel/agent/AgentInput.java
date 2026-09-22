package com.wannian.server.kernel.agent;

import com.wannian.server.api.common.TurnId;
import java.util.List;
import java.util.Objects;

/**
 * 一次 Agent Loop 的不可变输入快照。
 *
 * <p>由 ContextAssembler / TurnEngine 在认领成功后组装；Loop 不得再打开 Repository
 * 或改写原始 Message 正文（含缩进与末尾换行）。
 *
 * <p>契约预留（0.2.1 不实现世界线）：{@code turnSource} 含 WORLD 等枚举值但 C 仅用 USER；
 * {@code toolProfileId} 可空 ≡ chat.default；{@code worldContext} 可空且本批恒不填。
 *
 * @param turnId 本回合身份；与持久化 Turn 对应
 * @param turnSource 触发来源；0.2.1-C 仅 {@code USER}，枚举预留 WORLD 等
 * @param conversationExcerpt 已裁剪的近讯摘录；不得回写原始 Message
 * @param memoryContext 可选长期记忆摘要；未注入时为 null
 * @param relationshipSnapshot 可选关系快照；未注入时为 null
 * @param userMessage 当前用户句原文；裁剪不得截断本字段
 * @param toolDescriptors 本轮可见工具描述；null 视为空列表；Loop 只消费、不挑选 profile
 * @param toolProfileId 可选工具目录 id；null ≡ chat.default
 * @param systemInstructions 安全与人设等系统指示；Assembler 注入，不得为 null
 * @param worldContext 可选世界线上下文；0.2.1 恒不填（null）
 */
public record AgentInput(
        TurnId turnId,
        TurnSource turnSource,
        String conversationExcerpt,
        String memoryContext,
        String relationshipSnapshot,
        String userMessage,
        List<String> toolDescriptors,
        String toolProfileId,
        String systemInstructions,
        String worldContext) {

    public AgentInput {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(turnSource, "turnSource");
        Objects.requireNonNull(conversationExcerpt, "conversationExcerpt");
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(systemInstructions, "systemInstructions");
        // memoryContext / relationshipSnapshot / toolProfileId / worldContext 可为 null（预留或未注入）
        toolDescriptors = toolDescriptors == null ? List.of() : List.copyOf(toolDescriptors);
    }
}
