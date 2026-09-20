package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import java.util.Objects;

/**
 * {@link TurnCommitter#receive} 的封闭结果。
 */
public sealed interface ReceiveTurnResult
        permits ReceiveTurnResult.Accepted,
                ReceiveTurnResult.Conflict,
                ReceiveTurnResult.Rejected {

    /**
     * 接收成功。
     *
     * @param turnId 回合 id（回放时为库中已有 id，不一定等于计划中的 turnId）
     * @param replayed {@code true} 表示幂等命中、未新建行
     */
    record Accepted(TurnId turnId, boolean replayed) implements ReceiveTurnResult {
        public Accepted {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * 同一全局 {@code clientRequestId} 已经绑定了别的会话，或同一会话下正文不一致。
     */
    record Conflict(TurnId existingTurnId, String detail) implements ReceiveTurnResult {
        public Conflict {
            Objects.requireNonNull(existingTurnId, "existingTurnId");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** 会话不存在、参数非法等。 */
    record Rejected(String reasonCode, String detail) implements ReceiveTurnResult {
        public Rejected {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
