package com.wannian.server.app.persona;

/**
 * 默认角色 overlay / 导入窗口长度护栏。
 *
 * <p>生产值来自 {@code application.yml}（{@code wannian.persona.*}）；测试可用 {@link #DEFAULT}。
 */
public record PersonaOverlayLimits(int layerCharBudget, int totalCharBudget, int importWindowChars) {

    /** 与 yml 默认一致：单层 1000 / 合计 1600 / 扫描窗 3000。 */
    public static final PersonaOverlayLimits DEFAULT = new PersonaOverlayLimits(1000, 1600, 3000);

    public PersonaOverlayLimits {
        if (layerCharBudget < 64) {
            throw new IllegalArgumentException("layerCharBudget 须 ≥ 64");
        }
        if (totalCharBudget < layerCharBudget) {
            throw new IllegalArgumentException("totalCharBudget 不得小于 layerCharBudget");
        }
        if (importWindowChars < 256) {
            throw new IllegalArgumentException("importWindowChars 须 ≥ 256");
        }
    }
}
