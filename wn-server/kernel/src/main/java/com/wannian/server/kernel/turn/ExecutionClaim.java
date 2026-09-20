package com.wannian.server.kernel.turn;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次执行尝试的围栏身份，不是设备名。
 *
 * <p>{@code local-primary} 可以表示本机节点，但不能当作所有尝试共用的 executionId。
 * 每次新的认领都要使用不同的 id；旧尝试读到新 revision 也不能因此获得执行权。
 *
 * @param executionId 这一次尝试的唯一身份
 * @param expiresAt claim 过期时刻；不晚于调用方给出的 now 时，认领必须被拒绝
 */
public record ExecutionClaim(String executionId, Instant expiresAt) {

    public ExecutionClaim {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        executionId = executionId.trim();
        if (executionId.isEmpty()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
    }

    /** 新的一次尝试。每次调用生成不同的 executionId。 */
    public static ExecutionClaim attempt(Instant expiresAt) {
        return new ExecutionClaim(UUID.randomUUID().toString(), expiresAt);
    }

    /** 在 {@code now} 时刻是否仍有效。过期时刻等于 now 时无效。 */
    public boolean isActiveAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return expiresAt.isAfter(now);
    }
}
