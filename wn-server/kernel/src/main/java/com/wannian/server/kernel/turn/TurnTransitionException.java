package com.wannian.server.kernel.turn;

import java.util.Objects;

/**
 * Turn 状态非法跳转。
 *
 * <p>用户可见文案后续可映射；此处 {@link #getMessage()} 为中文技术说明。
 */
public final class TurnTransitionException extends IllegalStateException {

    private final String reasonCode;

    public TurnTransitionException(String reasonCode, String detail) {
        super(detail);
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public String reasonCode() {
        return reasonCode;
    }
}
