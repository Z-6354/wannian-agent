package com.wannian.server.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wannian.server.app.manage.StubModelCatalog;
import com.wannian.server.app.manage.VendorRecord;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelRequest;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelAdapterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    hits.incrementAndGet();
                    byte[] body =
                            """
                            {"choices":[{"message":{"role":"assistant","content":"你好世界"}}],
                             "usage":{"prompt_tokens":3,"completion_tokens":2}}
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void decideReturnsFinalAnswer() {
        OpenAiCompatibleModelAdapter adapter =
                new OpenAiCompatibleModelAdapter(
                        new VendorRecord("v1", StubModelCatalog.PROTOCOL, baseUrl, "KEY"),
                        "gpt-test",
                        HttpClient.newHttpClient(),
                        new ObjectMapper(),
                        Map.of("KEY", "secret")::get);

        ModelOutcome outcome =
                adapter.decide(
                        new ModelRequest(List.of(new ModelMessage("user", "hi"))),
                        new ModelCallContext("t1", 1, Instant.now().plusSeconds(30), false, "trace"));

        assertThat(outcome).isInstanceOf(ModelOutcome.FinalAnswer.class);
        assertThat(((ModelOutcome.FinalAnswer) outcome).text()).isEqualTo("你好世界");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void serializesToolRoundWithToolCallIdAndReasoning() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        server.removeContext("/v1/chat/completions");
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    hits.incrementAndGet();
                    captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] body =
                            """
                            {"choices":[{"message":{"role":"assistant","content":"ok"}}],
                             "usage":{"prompt_tokens":1,"completion_tokens":1}}
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });

        OpenAiCompatibleModelAdapter adapter =
                new OpenAiCompatibleModelAdapter(
                        new VendorRecord("v1", StubModelCatalog.PROTOCOL, baseUrl, "KEY"),
                        "gpt-test",
                        HttpClient.newHttpClient(),
                        new ObjectMapper(),
                        Map.of("KEY", "secret")::get);

        ModelOutcome outcome =
                adapter.decide(
                        new ModelRequest(
                                List.of(
                                        new ModelMessage("user", "几点"),
                                        ModelMessage.assistantWithTools(
                                                "查一下",
                                                "need clock",
                                                List.of(
                                                        new com.wannian.server.kernel.model.ToolCallRequest(
                                                                "c1", "current_time", "{}"))),
                                        ModelMessage.toolResult("c1", "{\"iso\":\"x\"}"))),
                        new ModelCallContext("t1", 2, Instant.now().plusSeconds(30), false, "trace"));

        assertThat(outcome).isInstanceOf(ModelOutcome.FinalAnswer.class);
        String json = captured.get();
        assertThat(json).contains("\"tool_call_id\":\"c1\"");
        assertThat(json).contains("\"reasoning_content\":\"need clock\"");
        assertThat(json).contains("\"tool_calls\"");
        assertThat(json).doesNotContain("tool_calls:[");
    }

    @Test
    void httpErrorIncludesVendorBodyHint() throws Exception {
        server.removeContext("/v1/chat/completions");
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    byte[] body =
                            "{\"error\":{\"message\":\"missing field `tool_call_id`\"}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(422, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });

        OpenAiCompatibleModelAdapter adapter =
                new OpenAiCompatibleModelAdapter(
                        new VendorRecord("v1", StubModelCatalog.PROTOCOL, baseUrl, "KEY"),
                        "gpt-test",
                        HttpClient.newHttpClient(),
                        new ObjectMapper(),
                        Map.of("KEY", "secret")::get);

        ModelOutcome outcome =
                adapter.decide(
                        new ModelRequest(List.of(new ModelMessage("user", "hi"))),
                        new ModelCallContext("t1", 1, Instant.now().plusSeconds(30), false, "trace"));

        assertThat(outcome).isInstanceOf(ModelOutcome.Failure.class);
        assertThat(((ModelOutcome.Failure) outcome).detail())
                .contains("HTTP 422")
                .contains("tool_call_id");
    }
}
