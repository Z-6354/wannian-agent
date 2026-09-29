package com.wannian.server.kernel.task;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.memory.CompanionIdentity;
import java.util.Objects;

/**
 * prepare 所需的最小来源 Turn 上下文（P2 §4.4）。
 *
 * <p>{@code companionId} 可空，多陪伴预留；本号成功路径可不填。
 */
public record OriginTurn(TurnId turnId, ConversationId conversationId, CompanionIdentity companionId) {

    /**
     * @param turnId 非空
     * @param conversationId 非空
     * @param companionId 可空
     */
    public OriginTurn {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(conversationId, "conversationId");
    }
}
