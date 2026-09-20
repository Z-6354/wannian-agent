package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 对本地假服务器解析 OpenAI 风格 /models，不访问公网。 */
class OpenAiCompatibleVendorAdapterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> authHeader = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/models",
                exchange -> {
                    authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] body =
                            """
                            {"data":[{"id":"gpt-test","owned_by":"org"},{"id":"gpt-mini"}]}
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
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listsModelsWithBearerFromEnv() {
        OpenAiCompatibleVendorAdapter adapter =
                new OpenAiCompatibleVendorAdapter(
                        HttpClient.newHttpClient(),
                        new ObjectMapper(),
                        Map.of("WANNIAN_MODEL_API_KEY", "secret-test-key")::get);

        ListModelsOutcome outcome =
                adapter.listModels(
                        new VendorRecord(
                                "openai-main",
                                StubModelCatalog.PROTOCOL,
                                baseUrl,
                                "WANNIAN_MODEL_API_KEY"));

        assertThat(outcome).isInstanceOf(ListModelsOutcome.Listed.class);
        ListModelsOutcome.Listed listed = (ListModelsOutcome.Listed) outcome;
        assertThat(listed.entries()).extracting(ModelCatalogEntry::id).containsExactly("gpt-test", "gpt-mini");
        assertThat(authHeader.get()).isEqualTo("Bearer secret-test-key");
    }

    @Test
    void missingEnvIsDependencyUnavailable() {
        OpenAiCompatibleVendorAdapter adapter =
                new OpenAiCompatibleVendorAdapter(
                        HttpClient.newHttpClient(), new ObjectMapper(), name -> null);

        ListModelsOutcome outcome =
                adapter.listModels(
                        new VendorRecord(
                                "openai-main", StubModelCatalog.PROTOCOL, baseUrl, "MISSING_KEY"));

        assertThat(outcome).isInstanceOf(ListModelsOutcome.Rejected.class);
        ListModelsOutcome.Rejected rejected = (ListModelsOutcome.Rejected) outcome;
        assertThat(rejected.code()).isEqualTo(ManageReason.DEPENDENCY_UNAVAILABLE);
        assertThat(rejected.detail()).doesNotContain("secret");
    }
}
