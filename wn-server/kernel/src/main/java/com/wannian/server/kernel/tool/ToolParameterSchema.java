package com.wannian.server.kernel.tool;

/**
 * 工具参数约束真源（供行为 3 校验）；本步只随登记存储，不解析调用实参。
 *
 * @param jsonSchemaObject {@code type=object} 的 JSON Schema 文本；登记时须非空白
 */
public record ToolParameterSchema(String jsonSchemaObject) {}
