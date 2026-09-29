package com.wannian.server.app.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.app.log.SafeErrorLog;
import com.wannian.server.app.manage.StubModelCatalog;
import com.wannian.server.app.manage.VendorCredentialAccess;
import com.wannian.server.app.manage.VendorRecord;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.error.ErrorLogFields;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.model.ModelStreamObserver;
import com.wannian.server.kernel.model.ModelUsage;
import com.wannian.server.kernel.model.ToolCallRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI 兼容出站适配器。绑定一个 vendor + modelId；不负责拉目录。
 *
 * <p>协议 {@code openai-compatible} → {@code POST /chat/completions}；
 * {@code openai-responses} → {@code POST /responses}（Codex / gpt-6-*）。
 *
 * <p>2.4.4：默认走 {@code stream:true} SSE，累积完整 {@link ModelOutcome}，并向观察者推送正文
 * delta；不透出 {@code reasoning_content} / 隐藏推理。
 */
public final class OpenAiCompatibleModelAdapter implements ModelPort {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleModelAdapter.class);
    private static final Duration HARD_HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_HTTP_TIMEOUT = Duration.ofSeconds(120);

    private final VendorRecord vendor;
    private final String modelId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final VendorCredentialAccess credentials;
    private final boolean responsesApi;
    private final ModelSamplingSettings sampling;

    public OpenAiCompatibleModelAdapter(
            VendorRecord vendor,
            String modelId,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            VendorCredentialAccess credentials) {
        this(vendor, modelId, httpClient, objectMapper, credentials, ModelSamplingSettings.unset());
    }

    public OpenAiCompatibleModelAdapter(
            VendorRecord vendor,
            String modelId,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            VendorCredentialAccess credentials,
            ModelSamplingSettings sampling) {
        this.vendor = Objects.requireNonNull(vendor, "vendor");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.sampling = sampling == null ? ModelSamplingSettings.unset() : sampling;
        if (!StubModelCatalog.isSupportedProtocol(vendor.protocol())) {
            throw new IllegalArgumentException("protocol");
        }
        this.responsesApi = StubModelCatalog.isResponsesProtocol(vendor.protocol());
    }

    @Override
    public ModelOutcome decide(ModelRequest request, ModelCallContext context) {
        return decide(request, context, ModelStreamObserver.NOOP);
    }

    @Override
    public ModelOutcome decide(
            ModelRequest request, ModelCallContext context, ModelStreamObserver observer) {
        ModelStreamObserver sink = observer == null ? ModelStreamObserver.NOOP : observer;
        Instant started = Instant.now();
        String correlationId = context == null ? null : context.traceId();
        if (context != null && context.isCancelledNow()) {
            return new ModelOutcome.Failure(ErrorCodes.CANCELLED, "调用已取消", false);
        }
        String apiKey = credentials.getApiKey(vendor.id(), vendor.apiKeyEnv());
        if (apiKey == null || apiKey.isBlank()) {
            return failure(
                    ErrorCodes.DEPENDENCY_UNAVAILABLE,
                    "密钥未配置（文件或环境变量）",
                    true,
                    correlationId,
                    started,
                    "密钥缺失");
        }

        Duration requestTimeout = HARD_HTTP_TIMEOUT;
        if (context != null && context.deadline() != null) {
            Duration untilDeadline = Duration.between(Instant.now(), context.deadline());
            if (untilDeadline.isNegative() || untilDeadline.isZero()) {
                return failure(
                        ErrorCodes.MODEL_TIMEOUT,
                        "已超过截止时间",
                        true,
                        correlationId,
                        started,
                        "已超过截止时间");
            }
            // 调用方设了更长 deadline（如人物导入）时，允许把 HTTP 读超时抬到上限，而不是永远钉死 30s。
            Duration capped =
                    untilDeadline.compareTo(MAX_HTTP_TIMEOUT) < 0 ? untilDeadline : MAX_HTTP_TIMEOUT;
            requestTimeout = capped.compareTo(HARD_HTTP_TIMEOUT) > 0 ? capped : untilDeadline;
        }

        ObjectNode body;
        try {
            body = responsesApi ? buildResponsesBody(request) : buildChatCompletionsBody(request);
            body.put("stream", true);
        } catch (IOException ex) {
            return failure(
                    ErrorCodes.DEPENDENCY_UNAVAILABLE,
                    "无法编码模型请求",
                    false,
                    correlationId,
                    started,
                    "无法编码模型请求");
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (IOException ex) {
            return failure(
                    ErrorCodes.DEPENDENCY_UNAVAILABLE,
                    "无法编码模型请求",
                    false,
                    correlationId,
                    started,
                    "无法编码模型请求");
        }

        String path = responsesApi ? "/responses" : "/chat/completions";
        HttpRequest httpRequest;
        try {
            httpRequest =
                    HttpRequest.newBuilder(URI.create(vendor.baseUrl() + path))
                            .timeout(requestTimeout)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build();
        } catch (IllegalArgumentException ex) {
            return failure(
                    ErrorCodes.ILLEGAL_ARGUMENT,
                    "Base URL 无法组成模型地址",
                    false,
                    correlationId,
                    started,
                    "Base URL 非法");
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException ex) {
            return failure(
                    ErrorCodes.DEPENDENCY_UNAVAILABLE,
                    "无法连接模型供应商",
                    true,
                    correlationId,
                    started,
                    "连接供应商失败");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            SafeErrorLog.info(
                    LOG,
                    new ErrorLogFields(
                            ErrorCodes.CANCELLED,
                            "model.decide",
                            correlationId,
                            elapsedMs(started),
                            "调用被中断"));
            return new ModelOutcome.Failure(ErrorCodes.CANCELLED, "调用被中断", false);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            closeQuietly(response.body());
            return failure(
                    ErrorCodes.DEPENDENCY_UNAVAILABLE,
                    "供应商拒绝了密钥",
                    true,
                    correlationId,
                    started,
                    "供应商拒绝密钥");
        }
        if (status == 429) {
            closeQuietly(response.body());
            return failure(
                    ErrorCodes.MODEL_RATE_LIMITED,
                    "供应商限流",
                    true,
                    correlationId,
                    started,
                    "供应商限流");
        }
        if (status < 200 || status >= 300) {
            String vendorHint = readErrorBody(response.body());
            String detail =
                    vendorHint.isEmpty()
                            ? "供应商返回 HTTP " + status
                            : "供应商返回 HTTP " + status + ": " + vendorHint;
            return failure(
                    ErrorCodes.DEPENDENCY_UNAVAILABLE,
                    detail,
                    true,
                    correlationId,
                    started,
                    "供应商 HTTP " + status);
        }

        try {
            return readStreamingOutcome(response.body(), context, sink, correlationId, started);
        } catch (IOException ex) {
            return failure(
                    ErrorCodes.DEPENDENCY_UNAVAILABLE,
                    "无法解析模型流式响应",
                    true,
                    correlationId,
                    started,
                    "无法解析模型流式响应");
        }
    }

    private ObjectNode buildChatCompletionsBody(ModelRequest request) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelId);
        applySampling(body);
        ArrayNode messages = body.putArray("messages");
        for (ModelMessage message : request.messages()) {
            ObjectNode row = messages.addObject();
            row.put("role", message.role());
            if (!message.toolCalls().isEmpty()) {
                if (message.content() == null) {
                    row.putNull("content");
                } else {
                    row.put("content", message.content());
                }
                if (message.reasoningContent() != null) {
                    row.put("reasoning_content", message.reasoningContent());
                }
                ArrayNode toolCalls = row.putArray("tool_calls");
                for (ToolCallRequest call : message.toolCalls()) {
                    ObjectNode callNode = toolCalls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.argumentsJson());
                }
            } else {
                row.put("content", message.content());
                if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                    row.put("tool_call_id", message.toolCallId());
                }
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode toolsNode = body.putArray("tools");
            for (com.wannian.server.kernel.tool.ToolDescriptor descriptor : request.tools()) {
                ObjectNode tool = toolsNode.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", descriptor.name());
                function.put("description", descriptor.description());
                try {
                    function.set(
                            "parameters", objectMapper.readTree(descriptor.parametersJsonSchema()));
                } catch (IOException ex) {
                    ObjectNode fallback = function.putObject("parameters");
                    fallback.put("type", "object");
                }
            }
        }
        return body;
    }

    /** Responses API：system → instructions；其余进 input；工具扁平化。 */
    private ObjectNode buildResponsesBody(ModelRequest request) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelId);
        applySampling(body);
        StringBuilder instructions = new StringBuilder();
        ArrayNode input = body.putArray("input");
        for (ModelMessage message : request.messages()) {
            String role = message.role() == null ? "" : message.role().trim().toLowerCase();
            if ("system".equals(role) || "developer".equals(role)) {
                if (message.content() != null && !message.content().isBlank()) {
                    if (instructions.length() > 0) {
                        instructions.append("\n\n");
                    }
                    instructions.append(message.content());
                }
                continue;
            }
            if (!message.toolCalls().isEmpty()) {
                for (ToolCallRequest call : message.toolCalls()) {
                    ObjectNode callNode = input.addObject();
                    callNode.put("type", "function_call");
                    callNode.put("call_id", call.id());
                    callNode.put("name", call.name());
                    callNode.put("arguments", call.argumentsJson());
                }
                if (message.content() != null && !message.content().isBlank()) {
                    ObjectNode row = input.addObject();
                    row.put("role", "assistant");
                    row.put("content", message.content());
                }
                continue;
            }
            if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                ObjectNode out = input.addObject();
                out.put("type", "function_call_output");
                out.put("call_id", message.toolCallId());
                out.put("output", message.content() == null ? "" : message.content());
                continue;
            }
            ObjectNode row = input.addObject();
            row.put("role", role.isEmpty() ? "user" : role);
            row.put("content", message.content() == null ? "" : message.content());
        }
        if (instructions.length() > 0) {
            body.put("instructions", instructions.toString());
        }
        if (!request.tools().isEmpty()) {
            ArrayNode toolsNode = body.putArray("tools");
            for (com.wannian.server.kernel.tool.ToolDescriptor descriptor : request.tools()) {
                ObjectNode tool = toolsNode.addObject();
                tool.put("type", "function");
                tool.put("name", descriptor.name());
                tool.put("description", descriptor.description());
                try {
                    tool.set("parameters", objectMapper.readTree(descriptor.parametersJsonSchema()));
                } catch (IOException ex) {
                    ObjectNode fallback = tool.putObject("parameters");
                    fallback.put("type", "object");
                }
            }
        }
        return body;
    }

    private void applySampling(ObjectNode body) {
        // Responses API 对 presence_penalty / 部分采样字段常直接 400；只传 temperature。
        if (responsesApi) {
            if (sampling.temperature() != null) {
                body.put("temperature", sampling.temperature());
            }
            return;
        }
        if (sampling.temperature() != null) {
            body.put("temperature", sampling.temperature());
        }
        if (sampling.topP() != null) {
            body.put("top_p", sampling.topP());
        }
        if (sampling.presencePenalty() != null) {
            body.put("presence_penalty", sampling.presencePenalty());
        }
    }

    private ModelOutcome readStreamingOutcome(
            InputStream body,
            ModelCallContext context,
            ModelStreamObserver observer,
            String correlationId,
            Instant started)
            throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        ModelUsage usage = new ModelUsage(0, 0);
        String finishReason = null;
        String refusal = null;

        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder dataBuf = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (context != null && context.isCancelledNow()) {
                    return new ModelOutcome.Failure(ErrorCodes.CANCELLED, "调用已取消", false);
                }
                if (context != null
                        && context.deadline() != null
                        && !Instant.now().isBefore(context.deadline())) {
                    return failure(
                            ErrorCodes.MODEL_TIMEOUT,
                            "已超过截止时间",
                            true,
                            correlationId,
                            started,
                            "流式超过截止时间");
                }
                if (line.isEmpty()) {
                    if (dataBuf.length() == 0) {
                        continue;
                    }
                    String data = dataBuf.toString();
                    dataBuf.setLength(0);
                    if ("[DONE]".equals(data.trim())) {
                        break;
                    }
                    JsonNode root = objectMapper.readTree(data);
                    if (responsesApi || (root.hasNonNull("type") && root.get("type").asText("").startsWith("response."))) {
                        applyResponsesEvent(
                                root, content, toolCalls, observer);
                        if (root.has("response") && root.path("response").has("usage")) {
                            usage = readResponsesUsage(root.path("response").path("usage"));
                        }
                        if ("response.completed".equals(root.path("type").asText())
                                && root.path("response").has("usage")) {
                            usage = readResponsesUsage(root.path("response").path("usage"));
                        }
                        continue;
                    }
                    JsonNode choices = root.get("choices");
                    if (choices != null && choices.isArray() && !choices.isEmpty()) {
                        JsonNode choice = choices.get(0);
                        if (choice.hasNonNull("finish_reason")) {
                            finishReason = choice.get("finish_reason").asText();
                        }
                        JsonNode delta = choice.path("delta");
                        if (!delta.isMissingNode()) {
                            if (delta.has("content") && !delta.get("content").isNull()) {
                                String piece = delta.get("content").asText("");
                                if (!piece.isEmpty()) {
                                    content.append(piece);
                                    try {
                                        observer.onTextDelta(piece);
                                    } catch (RuntimeException ignored) {
                                        // 观察者不得打断组装
                                    }
                                }
                            }
                            // reasoning_content 只累积给回灌，绝不回调观察者
                            if (delta.has("reasoning_content")
                                    && !delta.get("reasoning_content").isNull()) {
                                reasoning.append(delta.get("reasoning_content").asText(""));
                            }
                            if (delta.has("refusal") && !delta.get("refusal").isNull()) {
                                refusal = delta.get("refusal").asText();
                            }
                            JsonNode deltaTools = delta.get("tool_calls");
                            if (deltaTools != null && deltaTools.isArray()) {
                                for (JsonNode callDelta : deltaTools) {
                                    int index = callDelta.path("index").asInt(0);
                                    ToolCallAccumulator acc =
                                            toolCalls.computeIfAbsent(
                                                    index, ignored -> new ToolCallAccumulator());
                                    if (callDelta.hasNonNull("id")) {
                                        acc.id = callDelta.get("id").asText();
                                    }
                                    JsonNode function = callDelta.path("function");
                                    if (function.hasNonNull("name")) {
                                        acc.name = function.get("name").asText();
                                    }
                                    if (function.has("arguments")
                                            && !function.get("arguments").isNull()) {
                                        acc.arguments.append(function.get("arguments").asText(""));
                                    }
                                }
                            }
                        }
                        // 部分供应商在非流式兼容块里给 message
                        JsonNode message = choice.path("message");
                        if (!message.isMissingNode() && message.has("content")) {
                            String piece = message.path("content").asText("");
                            if (!piece.isEmpty() && content.length() == 0) {
                                content.append(piece);
                                try {
                                    observer.onTextDelta(piece);
                                } catch (RuntimeException ignored) {
                                }
                            }
                        }
                    }
                    if (root.has("usage") && !root.get("usage").isMissingNode()) {
                        usage = readUsage(root.path("usage"));
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    String payload = line.substring(5);
                    if (payload.startsWith(" ")) {
                        payload = payload.substring(1);
                    }
                    if (dataBuf.length() > 0) {
                        dataBuf.append('\n');
                    }
                    dataBuf.append(payload);
                }
                // 忽略 event:/id:/comment 行
            }
            if (dataBuf.length() > 0) {
                String data = dataBuf.toString().trim();
                if (!data.isEmpty() && !"[DONE]".equals(data)) {
                    // 尾包无空行时尽量解析
                    try {
                        JsonNode root = objectMapper.readTree(data);
                        if (root.has("usage")) {
                            usage = readUsage(root.path("usage"));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        if (!toolCalls.isEmpty()) {
            List<ToolCallRequest> calls = new ArrayList<>();
            for (ToolCallAccumulator acc : toolCalls.values()) {
                if (acc.name == null || acc.name.isBlank()) {
                    continue;
                }
                String args = acc.arguments.length() == 0 ? "{}" : acc.arguments.toString();
                if (!isCompleteJson(args)) {
                    return failure(
                            ErrorCodes.INVALID_MODEL_OUTPUT,
                            "工具调用参数不是完整合法 JSON",
                            false,
                            correlationId,
                            started,
                            "工具参数 JSON 不完整");
                }
                String id = acc.id == null || acc.id.isBlank() ? "tool" : acc.id;
                calls.add(new ToolCallRequest(id, acc.name, args));
            }
            if (!calls.isEmpty()) {
                String assistantContent = content.length() == 0 ? null : content.toString();
                String reasoningContent = reasoning.length() == 0 ? null : reasoning.toString();
                return new ModelOutcome.ToolCalls(calls, usage, assistantContent, reasoningContent);
            }
        }

        String text = content.toString();
        if (text.isBlank() && (refusal != null && !refusal.isBlank())) {
            return new ModelOutcome.ModelRefusal(refusal, usage);
        }
        if (text.isBlank() && "content_filter".equals(finishReason)) {
            return new ModelOutcome.ModelRefusal("内容被供应商过滤", usage);
        }
        return new ModelOutcome.FinalAnswer(text, usage);
    }

    private void applyResponsesEvent(
            JsonNode root,
            StringBuilder content,
            Map<Integer, ToolCallAccumulator> toolCalls,
            ModelStreamObserver observer) {
        String type = root.path("type").asText("");
        if ("response.output_text.delta".equals(type)) {
            String piece = root.path("delta").asText("");
            if (!piece.isEmpty()) {
                content.append(piece);
                try {
                    observer.onTextDelta(piece);
                } catch (RuntimeException ignored) {
                }
            }
            return;
        }
        if ("response.function_call_arguments.delta".equals(type)) {
            int index = root.path("output_index").asInt(0);
            ToolCallAccumulator acc =
                    toolCalls.computeIfAbsent(index, ignored -> new ToolCallAccumulator());
            if (root.hasNonNull("call_id")) {
                acc.id = root.get("call_id").asText();
            }
            if (root.hasNonNull("name")) {
                acc.name = root.get("name").asText();
            }
            String piece = root.path("delta").asText("");
            if (!piece.isEmpty()) {
                acc.arguments.append(piece);
            }
            return;
        }
        if ("response.output_item.added".equals(type) || "response.output_item.done".equals(type)) {
            JsonNode item = root.path("item");
            String itemType = item.path("type").asText("");
            if ("function_call".equals(itemType)) {
                int index = root.path("output_index").asInt(0);
                ToolCallAccumulator acc =
                        toolCalls.computeIfAbsent(index, ignored -> new ToolCallAccumulator());
                if (item.hasNonNull("call_id")) {
                    acc.id = item.get("call_id").asText();
                } else if (item.hasNonNull("id")) {
                    acc.id = item.get("id").asText();
                }
                if (item.hasNonNull("name")) {
                    acc.name = item.get("name").asText();
                }
                if (item.hasNonNull("arguments") && acc.arguments.length() == 0) {
                    acc.arguments.append(item.get("arguments").asText(""));
                }
            }
            return;
        }
        if ("response.completed".equals(type)) {
            // 终态兜底：若流式未收集到正文，从 output 里取
            if (content.length() == 0) {
                JsonNode output = root.path("response").path("output");
                if (output.isArray()) {
                    for (JsonNode item : output) {
                        if (!"message".equals(item.path("type").asText())) {
                            continue;
                        }
                        JsonNode parts = item.path("content");
                        if (!parts.isArray()) {
                            continue;
                        }
                        for (JsonNode part : parts) {
                            if ("output_text".equals(part.path("type").asText())) {
                                String text = part.path("text").asText("");
                                if (!text.isEmpty()) {
                                    content.append(text);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static ModelUsage readResponsesUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode()) {
            return new ModelUsage(0, 0);
        }
        int input = usage.path("input_tokens").asInt(usage.path("prompt_tokens").asInt(0));
        int output = usage.path("output_tokens").asInt(usage.path("completion_tokens").asInt(0));
        return new ModelUsage(input, output);
    }

    private boolean isCompleteJson(String args) {
        try {
            objectMapper.readTree(args);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private ModelOutcome failure(
            String code,
            String detail,
            boolean retryable,
            String correlationId,
            Instant started,
            String safeReason) {
        SafeErrorLog.warn(
                LOG,
                new ErrorLogFields(code, "model.decide", correlationId, elapsedMs(started), safeReason));
        return new ModelOutcome.Failure(code, detail, retryable);
    }

    private static long elapsedMs(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }

    private static ModelUsage readUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode()) {
            return new ModelUsage(0, 0);
        }
        return new ModelUsage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
    }

    private static String readErrorBody(InputStream body) {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(800);
            return truncateVendorBody(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return "";
        }
    }

    private static void closeQuietly(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
        }
    }

    /** 截断供应商错误正文，便于实机排障；不含密钥（响应体通常无密钥）。 */
    static String truncateVendorBody(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.replaceAll("\\s+", " ").trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int max = 400;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private static final class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
