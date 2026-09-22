package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import java.util.Objects;

/**
 * {@link TurnEngine#execute} 的封闭结果；不得为 null。
 *
 * <p>{@link AlreadyCompleted} 不附带正文：调用方按已完成回合自行读取。
 * {@link Held} 表示本轮未正式完成；code 为稳定机器码。
 */
public sealed interface ExecuteTurnResult
        permits ExecuteTurnResult.Replied,
                ExecuteTurnResult.AlreadyCompleted,
                ExecuteTurnResult.Cancelled,
                ExecuteTurnResult.Held {

    /** 助手正文已冻结并提交（或从 COMMITTING 恢复提交）。 */
    record Replied(String text) implements ExecuteTurnResult {
        public Replied {
            Objects.requireNonNull(text, "text");
        }
    }

    /** 回合早已 COMPLETED；本调用未跑 Loop。 */
    record AlreadyCompleted(TurnId turnId) implements ExecuteTurnResult {
        public AlreadyCompleted {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /** 在合法取消点停止，并已尽量落 CANCELLED。 */
    record Cancelled(TurnId turnId) implements ExecuteTurnResult {
        public Cancelled {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * 本轮未正式完成。
     *
     * @param code 稳定机器码
     * @param detail 可展示或可记日志的短文案；不得含密钥
     */
    record Held(String code, String detail) implements ExecuteTurnResult {
        public Held {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
