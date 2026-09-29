package com.wannian.server.kernel.task;

/**
 * {@link TaskRuntime#dispatchNext} 的调度预算（P2 §6.2 / kernel-reference）。
 *
 * <p>本号接口占位；真语义在 2.5.4。{@code maxBackgroundRuns} 只限制<strong>后台</strong>槽
 * （与 Review 合计），不限制前台主 Loop——Q7 为 <strong>1+1</strong> 可并存；
 * 「聊天优先」= 抢模型时 defer，不是聊天时关掉本预算。
 * 字段刻意最小，后号可加 allowReviewOverlap 等而不改方法名。
 */
public record DispatchBudget(int maxBackgroundRuns) {

    /** 默认：全进程后台至多 1 个 Run（Q7；可与 1 个主 Loop 并存）。 */
    public static final DispatchBudget DEFAULT = new DispatchBudget(1);

    public DispatchBudget {
        if (maxBackgroundRuns < 1) {
            throw new IllegalArgumentException("maxBackgroundRuns 须 ≥ 1");
        }
    }
}
