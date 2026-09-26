package com.wannian.server.kernel.model;

/**
 * 单次模型流式调用的公开增量观察。
 *
 * <p>只接收允许对用户展示的正文片段；适配器不得回调隐藏推理 / reasoning。
 * 观察失败不得打断最终 {@link ModelOutcome} 组装。
 */
@FunctionalInterface
public interface ModelStreamObserver {

    /** 无观察者。 */
    ModelStreamObserver NOOP = delta -> {};

    /** 正文增量；{@code delta} 非空且按到达顺序。 */
    void onTextDelta(String delta);
}
