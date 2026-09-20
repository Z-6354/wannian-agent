package com.wannian.server.app.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.app.manage.EnvAccess;
import com.wannian.server.app.manage.ManageReason;
import com.wannian.server.app.manage.StubModelCatalog;
import com.wannian.server.app.manage.VendorRecord;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.model.ModelUsage;
import com.wannian.server.kernel.model.ToolCallRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * OpenAI 兼容 chat/completions。绑定一个 vendor + modelId；不负责拉目录。
 */
public final class OpenAiCompatibleModelAdapter implements ModelPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final VendorRecord vendor;
    private final String modelId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EnvAccess envAccess;

    public OpenAiCompatibleModelAdapter(
            VendorRecord vendor,
            String modelId,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            EnvAccess envAccess) {
        this.vendor = Objects.requireNonNull(vendor, "vendor");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.envAccess = Objects.requireNonNull(envAccess, "envAccess");
        if (!StubModelCatalog.PROTOCOL.equals(vendor.protocol())) {
            throw new IllegalArgumentException("protocol");
        }
    }

    @Override
    public ModelOutcome decide(ModelRequest request, ModelCallContext context) {
        if (context != null && context.cancelled()) {
            return new ModelOutcome.Failure("CANCELLED", "调用已取消", false);
        }
        String apiKey = envAccess.get(vendor.apiKeyEnv());
        if (apiKey == null || apiKey.isBlank()) {
            return new ModelOutcome.Failure(ManageReason.DEPENDENCY_UNAVAILABLE, "密钥环境变量未设置或为空", true);
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelId);
        ArrayNode messages = body.putArray("messages");
        for (ModelMessage message : request.messages()) {
            ObjectNode row = messages.addObject();
            row.put("role", message.role());
            row.put("content", message.content());
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (IOException ex) {
            return new ModelOutcome.Failure(ManageReason.DEPENDENCY_UNAVAILABLE, "无法编码模型请求", false);
        }

        HttpRequest httpRequest;
        try {
            httpRequest =
                    HttpRequest.newBuilder(URI.create(vendor.baseUrl() + "/chat/completions"))
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build();
        } catch (IllegalArgumentException ex) {
            return new ModelOutcome.Failure(ManageReason.ILLEGAL_ARGUMENT, "Base URL 无法组成 chat 地址", false);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            return new ModelOutcome.Failure(ManageReason.DEPENDENCY_UNAVAILABLE, "无法连接模型供应商", true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ModelOutcome.Failure("CANCELLED", "调用被中断", false);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            return new ModelOutcome.Failure(ManageReason.DEPENDENCY_UNAVAILABLE, "供应商拒绝了密钥", true);
        }
        if (status == 429) {
            return new ModelOutcome.Failure("MODEL_RATE_LIMITED", "供应商限流", true);
        }
        if (status < 200 || status >= 300) {
            return new ModelOutcome.Failure(
                    ManageReason.DEPENDENCY_UNAVAILABLE, "供应商返回 HTTP " + status, true);
        }

        try {
            return parse(response.body());
        } catch (IOException | IllegalArgumentException ex) {
            return new ModelOutcome.Failure(ManageReason.DEPENDENCY_UNAVAILABLE, "无法解析模型响应", true);
        }
    }

    private ModelOutcome parse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new IllegalArgumentException("missing choices");
        }
        JsonNode message = choices.get(0).path("message");
        ModelUsage usage = readUsage(root.path("usage"));
        if (message.has("tool_calls") && message.get("tool_calls").isArray() && !message.get("tool_calls").isEmpty()) {
            List<ToolCallRequest> calls = new ArrayList<>();
            for (JsonNode call : message.get("tool_calls")) {
                String id = call.path("id").asText("tool");
                String name = call.path("function").path("name").asText();
                String args = call.path("function").path("arguments").asText("{}");
                if (!name.isBlank()) {
                    calls.add(new ToolCallRequest(id, name, args));
                }
            }
            if (!calls.isEmpty()) {
                return new ModelOutcome.ToolCalls(calls, usage);
            }
        }
        String content = message.path("content").asText("");
        if (content.isBlank() && "assistant".equals(message.path("role").asText())) {
            String refusal = message.path("refusal").asText("");
            if (!refusal.isBlank()) {
                return new ModelOutcome.ModelRefusal(refusal, usage);
            }
        }
        return new ModelOutcome.FinalAnswer(content, usage);
    }

    private static ModelUsage readUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode()) {
            return new ModelUsage(0, 0);
        }
        return new ModelUsage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
    }
}
