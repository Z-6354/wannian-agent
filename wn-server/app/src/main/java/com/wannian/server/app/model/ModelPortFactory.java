package com.wannian.server.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.app.manage.StubModelCatalog;
import com.wannian.server.app.manage.VendorCredentialAccess;
import com.wannian.server.app.manage.VendorRecord;
import com.wannian.server.kernel.model.ModelPort;
import java.net.http.HttpClient;
import java.util.Objects;

/**
 * 按供应商协议装配 {@link ModelPort}。密钥经 {@link VendorCredentialAccess}，不直接读环境。
 */
public final class ModelPortFactory {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final VendorCredentialAccess credentials;
    private final ModelSamplingSettings sampling;

    public ModelPortFactory(
            HttpClient httpClient, ObjectMapper objectMapper, VendorCredentialAccess credentials) {
        this(httpClient, objectMapper, credentials, ModelSamplingSettings.unset());
    }

    public ModelPortFactory(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            VendorCredentialAccess credentials,
            ModelSamplingSettings sampling) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.sampling = sampling == null ? ModelSamplingSettings.unset() : sampling;
    }

    public ModelPort createLive(VendorRecord vendor, String modelId) {
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(modelId, "modelId");
        if (!StubModelCatalog.isSupportedProtocol(vendor.protocol())) {
            throw new IllegalArgumentException("protocol");
        }
        return new OpenAiCompatibleModelAdapter(
                vendor, modelId, httpClient, objectMapper, credentials, sampling);
    }
}

