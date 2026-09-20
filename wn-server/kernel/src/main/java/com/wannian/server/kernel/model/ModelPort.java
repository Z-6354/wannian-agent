package com.wannian.server.kernel.model;

/** Kernel 对模型调用的唯一入口。app 提供适配器；不得把厂商 SDK 类型传进本接口。 */
public interface ModelPort {

    ModelOutcome decide(ModelRequest request, ModelCallContext context);
}
