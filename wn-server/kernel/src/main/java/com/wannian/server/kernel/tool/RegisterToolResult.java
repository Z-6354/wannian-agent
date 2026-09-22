
package com.wannian.server.kernel.tool;

/**
 * {@link ToolCatalog#register} 的封闭结果。
 */
public sealed interface RegisterToolResult {

    record Accepted(String toolName) implements RegisterToolResult {}

    record Rejected(String code, String message) implements RegisterToolResult {}
}
