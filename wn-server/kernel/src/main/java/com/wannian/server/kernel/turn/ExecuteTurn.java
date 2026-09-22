package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.agent.AgentBudget;
import java.time.Duration;
import java.util.Objects;

/**
 * 执行一次已接收 USER 回合的不可变参数。
 *
 * <p>预算与人设由 app 按配置构造后传入；本记录不读文件、不碰 Spring。
 *
 * @param turnId 目标回合；不得为 null
 * @param userMessage 当前用户句原文；不得截断缩进与末尾换行
 * @param systemInstructions 人设与安全指示；不得为 null
 * @param budget 本轮 Loop 预算；不得为 null
 * @param claimLease 认领租约长度；须为正
 */
public record ExecuteTurn(
        TurnId turnId,
        String userMessage,
        String systemInstructions,
        AgentBudget budget,
        Duration claimLease) {

    public ExecuteTurn {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(systemInstructions, "systemInstructions");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease 须为正");
        }
    }
}
