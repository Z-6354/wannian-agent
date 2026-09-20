package com.wannian.server.app.manage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容 {@code GET {baseUrl}/models}。密钥只从环境变量读取，不写日志。
 */
@Component
public class OpenAiCompatibleVendorAdapter implements VendorAdapter {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EnvAccess envAccess;

    @Autowired
    public OpenAiCompatibleVendorAdapter(ObjectMapper objectMapper, EnvAccess envAccess) {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), objectMapper, envAccess);
    }

    OpenAiCompatibleVendorAdapter(HttpClient httpClient, ObjectMapper objectMapper, EnvAccess envAccess) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.envAccess = Objects.requireNonNull(envAccess, "envAccess");
    }

    @Override
    public ListModelsOutcome listModels(VendorRecord vendor) {
        if (vendor == null || !StubModelCatalog.PROTOCOL.equals(vendor.protocol())) {
            return new ListModelsOutcome.Rejected(ManageReason.PROTOCOL_UNSUPPORTED, "当前只接受 openai-compatible");
        }
        String apiKeyEnv = vendor.apiKeyEnv();
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
            return new ListModelsOutcome.Rejected(ManageReason.ILLEGAL_ARGUMENT, "密钥变量名不能为空");
        }
        String apiKey = envAccess.get(apiKeyEnv);
        if (apiKey == null || apiKey.isBlank()) {
            return new ListModelsOutcome.Rejected(
                    ManageReason.DEPENDENCY_UNAVAILABLE, "密钥环境变量未设置或为空");
        }

        URI uri;
        try {
            uri = URI.create(vendor.baseUrl() + "/models");
        } catch (IllegalArgumentException ex) {
            return new ListModelsOutcome.Rejected(ManageReason.ILLEGAL_ARGUMENT, "Base URL 无法组成 models 地址");
        }

        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "application/json")
                        .GET()
                        .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            return new ListModelsOutcome.Rejected(ManageReason.DEPENDENCY_UNAVAILABLE, "无法连接模型供应商");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ListModelsOutcome.Rejected(ManageReason.DEPENDENCY_UNAVAILABLE, "检索模型被中断");
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            return new ListModelsOutcome.Rejected(ManageReason.DEPENDENCY_UNAVAILABLE, "供应商拒绝了密钥");
        }
        if (status == 404) {
            return new ListModelsOutcome.Rejected(ManageReason.DEPENDENCY_UNAVAILABLE, "供应商没有 models 端点");
        }
        if (status < 200 || status >= 300) {
            return new ListModelsOutcome.Rejected(
                    ManageReason.DEPENDENCY_UNAVAILABLE, "供应商返回 HTTP " + status);
        }

        try {
            return new ListModelsOutcome.Listed(parseEntries(response.body()));
        } catch (IOException | IllegalArgumentException ex) {
            return new ListModelsOutcome.Rejected(ManageReason.DEPENDENCY_UNAVAILABLE, "无法解析供应商目录");
        }
    }

    private List<ModelCatalogEntry> parseEntries(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalArgumentException("missing data array");
        }
        List<ModelCatalogEntry> entries = new ArrayList<>();
        for (JsonNode item : data) {
            if (item == null || !item.hasNonNull("id")) {
                continue;
            }
            String id = item.get("id").asText();
            if (id.isBlank()) {
                continue;
            }
            String display =
                    item.hasNonNull("owned_by")
                            ? id + " (" + item.get("owned_by").asText() + ")"
                            : id;
            entries.add(new ModelCatalogEntry(id, display));
        }
        return List.copyOf(entries);
    }
}
