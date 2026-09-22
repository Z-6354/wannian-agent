package com.wannian.server.kernel.tool;

/**
 * 工具本机执行入口（ToolRuntime 内部 seam；不对 AgentLoop 暴露）。
 *
 * <p>调用方保证参数已校验；实现须返回可序列化、有界结果，不得返回 Stream、线程、文件句柄或 SDK 对象。
 */
public interface ToolAdapter {

    ToolAdapterResult execute(ToolAdapterRequest request);
}
