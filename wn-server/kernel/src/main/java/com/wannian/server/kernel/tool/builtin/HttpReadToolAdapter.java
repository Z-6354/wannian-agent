package com.wannian.server.kernel.tool.builtin;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 受限 GET 读网页（大小与超时有界）。 */
public final class HttpReadToolAdapter implements ToolAdapter {

    /** 默认 UA：产品名、无版本号；版本可由 app 构造注入。 */
    public static final String DEFAULT_USER_AGENT = "wannian-agent";

    static final int MAX_BYTES = 64 * 1024;
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final String userAgent;

    public HttpReadToolAdapter() {
        this(DEFAULT_USER_AGENT);
    }

    public HttpReadToolAdapter(String userAgent) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                userAgent);
    }

    HttpReadToolAdapter(HttpClient client) {
        this(client, DEFAULT_USER_AGENT);
    }

    HttpReadToolAdapter(HttpClient client, String userAgent) {
        this.client = Objects.requireNonNull(client, "client");
        String ua = userAgent == null ? "" : userAgent.trim();
        if (ua.isEmpty()) {
            throw new IllegalArgumentException("userAgent 不得为空");
        }
        this.userAgent = ua;
    }

    @Override
    public ToolAdapterResult execute(ToolAdapterRequest request) {
        try {
            Map<String, String> fields = ToolJson.parseFlatObject(request.argumentsJson());
            String url = ToolJson.requireString(fields, "url").trim();
            HttpRequest httpRequest =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(TIMEOUT)
                            .GET()
                            .header("User-Agent", userAgent)
                            .build();
            HttpResponse<byte[]> response =
                    client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body() == null ? new byte[0] : response.body();
            boolean truncated = body.length > MAX_BYTES;
            if (truncated) {
                byte[] cut = new byte[MAX_BYTES];
                System.arraycopy(body, 0, cut, 0, MAX_BYTES);
                body = cut;
            }
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            out.put("url", url);
            out.put("status", Integer.toString(response.statusCode()));
            out.put("truncated", Boolean.toString(truncated));
            out.put("body", text);
            return new ToolAdapterResult.Succeeded(ToolJson.object(out));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ToolAdapterResult.Unknown(
                    ErrorCodes.CANCELLED, "http_read 被中断");
        } catch (java.net.http.HttpTimeoutException ex) {
            return new ToolAdapterResult.Unknown(
                    ErrorCodes.DEPENDENCY_UNAVAILABLE, "http_read 超时");
        } catch (Exception ex) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.DEPENDENCY_UNAVAILABLE,
                    "http_read 失败: " + ex.getMessage(),
                    true);
        }
    }
}
