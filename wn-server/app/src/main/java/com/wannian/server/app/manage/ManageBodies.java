package com.wannian.server.app.manage;

import java.util.List;

/** 管理接口的 JSON 形状。字段名保持驼峰，与现有回合请求一致。 */
public final class ManageBodies {

    private ManageBodies() {}

    public record ErrorBody(String code, String detail) {}

    public record VendorBody(
            String id,
            String displayName,
            String protocol,
            String baseUrl,
            String apiKeyEnv,
            long revision) {}

    public record UpsertVendorRequest(
            String displayName, String protocol, String baseUrl, String apiKeyEnv, Long expectedRevision) {}

    public record ModelEntryBody(String id, String displayName) {}

    public record ModelListBody(String vendorId, List<ModelEntryBody> entries) {}

    public record EnabledSelection(String vendorId, String modelId) {}

    public record EnabledBody(EnabledSelection enabled) {}

    public record EnableRequest(String vendorId, String modelId) {}

    public record ListedModel(String vendorId, String modelId, String displayName, boolean enabled) {}

    public record ListedBody(List<ListedModel> entries) {}

    public record AddListedRequest(String vendorId, String modelId) {}

    public record ProbeRequest(String text) {}

    public record ProbeResponse(
            String outcome, String text, String vendorId, String modelId, String code, String detail) {}
}
