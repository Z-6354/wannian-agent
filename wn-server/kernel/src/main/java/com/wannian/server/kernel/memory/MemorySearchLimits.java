package com.wannian.server.kernel.memory;

/**
 * search_memory 限额（D+）。
 *
 * <p>生产值由 app {@code application.yml}（{@code wannian.memory.search.default-limit} /
 * {@code max-limit}）注入；kernel 不读配置、不写死业务默认。测试可直接 {@code new
 * MemorySearchLimits(...)}。
 *
 * <p>{@code defaultLimit} 用于请求未带 {@code limit} 时；{@code maxLimit} 钳制上限（含显式
 * limit）。须 {@code 1 <= defaultLimit <= maxLimit}。
 */
public record MemorySearchLimits(int defaultLimit, int maxLimit) {

    public static final int HARD_MAX_LIMIT = 20;

    public MemorySearchLimits {
        if (defaultLimit < 1) {
            throw new IllegalArgumentException("defaultLimit 须 >= 1");
        }
        if (maxLimit < 1) {
            throw new IllegalArgumentException("maxLimit 须 >= 1");
        }
        if (maxLimit > HARD_MAX_LIMIT) {
            throw new IllegalArgumentException("maxLimit 不得超过 " + HARD_MAX_LIMIT);
        }
        if (defaultLimit > maxLimit) {
            throw new IllegalArgumentException("defaultLimit 不得大于 maxLimit");
        }
    }
}
