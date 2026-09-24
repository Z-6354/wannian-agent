package com.wannian.server.kernel.memory;

/**
 * 召回注入限额（Top-N / 字数预算）。
 *
 * <p>生产值由 app {@code application.yml}（{@code wannian.memory.recall.*}）注入；
 * kernel 不读配置文件、不写死业务默认。测试可直接 {@code new MemoryRecallLimits(...)}。
 */
public record MemoryRecallLimits(int topN, int charBudget) {

    public MemoryRecallLimits {
        if (topN < 1) {
            throw new IllegalArgumentException("topN 须 >= 1");
        }
        if (charBudget < 1) {
            throw new IllegalArgumentException("charBudget 须 >= 1");
        }
    }
}
