package com.wannian.server.kernel.agent;

import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.tool.ToolDescriptor;
import java.util.List;
import java.util.Objects;

/**
 * 一次 Agent Loop 的不可变输入快照。
 *
 * <p>由 ContextAssembler / TurnEngine 在认领成功后组装；Loop 不得再打开 Repository
 * 或改写原始 Message 正文（含缩进与末尾换行）。
 *
 * <p>契约预留：{@code turnSource} 含 WORLD 等枚举值但用户回合用 USER；
 * {@code toolProfileId} 可空；{@code worldContext} 可空且本批恒不填。
 *
 * @param toolDescriptors 本轮可见工具描述；null 视为空列表；Loop 只消费、不挑选 profile
 * @param toolProfileId 可选工具画像 id；由可见集解析填入
 */
public record AgentInput(
        TurnId turnId,
        TurnSource turnSource,
        String conversationExcerpt,
        String memoryContext,
        String relationshipSnapshot,
        String userMessage,
        List<ToolDescriptor> toolDescriptors,
        String toolProfileId,
        String systemInstructions,
        String worldContext) {

    public AgentInput {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(turnSource, "turnSource");
        Objects.requireNonNull(conversationExcerpt, "conversationExcerpt");
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(systemInstructions, "systemInstructions");
        toolDescriptors = toolDescriptors == null ? List.of() : List.copyOf(toolDescriptors);
    }
}
