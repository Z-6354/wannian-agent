package com.wannian.server.kernel.tool;

/**
 * 深模块：执行一次工具 → 封闭结果。对 Agent Loop 只暴露本接口。
 */
public interface ToolRuntime {

    ToolExecutionOutcome execute(ToolInvocation invocation, ToolExecutionContext context);
}
