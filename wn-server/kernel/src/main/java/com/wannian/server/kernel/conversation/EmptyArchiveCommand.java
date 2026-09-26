package com.wannian.server.kernel.conversation;

import java.util.Objects;

/**
 * @param confirmToken 必须等于 {@link #CONFIRM_TOKEN}
 * @param batchLimit 每批最多删除会话数，1..50
 */
public record EmptyArchiveCommand(String confirmToken, int batchLimit) {

    public static final String CONFIRM_TOKEN = "EMPTY_ARCHIVE";

    public EmptyArchiveCommand {
        Objects.requireNonNull(confirmToken, "confirmToken");
        if (batchLimit < 1 || batchLimit > 50) {
            throw new IllegalArgumentException("batchLimit 须在 1..50");
        }
    }
}
