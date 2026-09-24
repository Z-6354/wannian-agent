package com.wannian.server.kernel.tool;

import java.util.Objects;

/**
 * 对模型可见的工具描述（由绑定生成；Loop 只消费，不解析角色）。
 *
 * @param countsTowardDecisionBudget false 时该工具所属的纯系统 ToolCalls decide 不计入
 *     {@code maxModelDecisions}；仍受每工具 {@code maxSystemToolInvocationsPerTool} 上限约束
 */
public record ToolDescriptor(
        String name, String description, String parametersJsonSchema, boolean countsTowardDecisionBudget) {

    /** 默认计入决策预算。 */
    public ToolDescriptor(String name, String description, String parametersJsonSchema) {
        this(name, description, parametersJsonSchema, true);
    }

    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJsonSchema, "parametersJsonSchema");
    }
}
