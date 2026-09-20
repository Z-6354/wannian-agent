package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** mode=live 时管理接口走真实适配器，但只打本机假服务器。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelManageLiveCatalogTest {

    private static final String TOKEN = "manage-live-token";
    private static HttpServer server;
    private static String vendorBaseUrl;

    @TempDir
    static Path tempDataDir;

    @BeforeAll
    static void startVendor() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/models",
                exchange -> {
                    byte[] body =
                            """
                            {"data":[{"id":"live-chat"},{"id":"live-reasoner"}]}
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        vendorBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopVendor() {
        if (server != null) {
            server.stop(0);
        }
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("wannian.manage.token", () -> TOKEN);
        registry.add("wannian.model.mode", () -> "live");
    }

    @TestConfiguration
    static class LiveEnv {
        @Bean
        @Primary
        EnvAccess testEnv() {
            return name -> "WANNIAN_MODEL_API_KEY".equals(name) ? "live-test-key" : null;
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private javax.sql.DataSource dataSource;

    @BeforeEach
    void clear() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM model_listed");
            connection.createStatement().executeUpdate("DELETE FROM model_vendor");
        }
    }

    @Test
    void listModelsUsesLiveAdapterAgainstLocalServer() {
        ResponseEntity<JsonNode> created =
                restTemplate.exchange(
                        "/api/manage/model/vendors/openai-main",
                        HttpMethod.PUT,
                        bearer(
                                Map.of(
                                        "displayName",
                                        "本地假厂商",
                                        "protocol",
                                        "openai-compatible",
                                        "baseUrl",
                                        vendorBaseUrl,
                                        "apiKeyEnv",
                                        "WANNIAN_MODEL_API_KEY")),
                        JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> listed =
                restTemplate.exchange(
                        "/api/manage/model/vendors/openai-main/models:list",
                        HttpMethod.POST,
                        bearer(null),
                        JsonNode.class);

        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody().get("entries")).hasSize(2);
        assertThat(listed.getBody().get("entries").get(0).get("id").asText()).isEqualTo("live-chat");
        assertThat(listed.getBody().get("entries").get(1).get("id").asText()).isEqualTo("live-reasoner");
    }

    private static HttpEntity<?> bearer(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(body, headers);
    }
}
