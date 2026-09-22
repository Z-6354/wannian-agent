package com.wannian.server.kernel.tool;

/**
 * Adapter 层封闭结果（成功 / 失败 / 未知）。拒绝类结果由 Catalog / Validator / Policy 在进入 Adapter 前产生。
 */
public sealed interface ToolAdapterResult {

    record Succeeded(String observationJson) implements ToolAdapterResult {}

    record Failed(String code, String message, boolean retryable) implements ToolAdapterResult {}

    record Unknown(String code, String message) implements ToolAdapterResult {}
}
