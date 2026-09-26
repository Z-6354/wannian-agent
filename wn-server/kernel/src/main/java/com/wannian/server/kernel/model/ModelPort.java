package com.wannian.server.kernel.model;

/**
 * Kernel 对模型调用的唯一入口。app 提供适配器；不得把厂商 SDK 类型传进本接口。
 *
 * <p>流式：实现方可覆盖带 {@link ModelStreamObserver} 的重载，在同一次请求内累积完整
 * {@link ModelOutcome}，并向观察者推送安全正文增量。默认实现忽略观察者并走非流式
 * {@link #decide(ModelRequest, ModelCallContext)}。
 */
public interface ModelPort {

    ModelOutcome decide(ModelRequest request, ModelCallContext context);

    /**
     * 同一次模型请求：累积完整结果，并可向 {@code observer} 推送正文 delta。
     *
     * @param observer 不得为 null；无观察需求时传 {@link ModelStreamObserver#NOOP}
     */
    default ModelOutcome decide(
            ModelRequest request, ModelCallContext context, ModelStreamObserver observer) {
        return decide(request, context);
    }
}
