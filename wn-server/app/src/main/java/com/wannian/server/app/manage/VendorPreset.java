package com.wannian.server.app.manage;

/**
 * 内置（或未来自定义）供应商预设：用户只选 id 并贴密钥，不填协议/地址。
 *
 * @param id 稳定 id，与 {@code model_vendor.id} 一致
 * @param displayName 展示名
 * @param protocol {@link StubModelCatalog#PROTOCOL} 或 {@link StubModelCatalog#PROTOCOL_RESPONSES}
 * @param baseUrl 规范化后的根地址（无尾斜杠）
 * @param apiKeyEnvHint 兼容旧环境变量名；文件密钥优先
 * @param secretId 密钥文件名（无 {@code .key}）；同源网关可共享，例如两个万年协议共用 {@code wannian-ai}
 */
public record VendorPreset(
        String id,
        String displayName,
        String protocol,
        String baseUrl,
        String apiKeyEnvHint,
        String secretId) {

    public VendorPreset {
        if (secretId == null || secretId.isBlank()) {
            secretId = id;
        }
    }

    public VendorPreset(
            String id, String displayName, String protocol, String baseUrl, String apiKeyEnvHint) {
        this(id, displayName, protocol, baseUrl, apiKeyEnvHint, id);
    }
}
