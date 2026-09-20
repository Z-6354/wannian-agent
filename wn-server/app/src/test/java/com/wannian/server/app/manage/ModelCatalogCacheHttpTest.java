package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

/** list 后 enable 应命中缓存，不再第二次打 /models。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelCatalogCacheHttpTest {

    private static final String TOKEN = "cache-token";
    private static HttpServer server;
    private static String vendorBaseUrl;
    private static final AtomicInteger modelHits = new AtomicInteger();

    @TempDir
    static Path tempDataDir;

    @BeforeAll
    static void startVendor() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/models",
                exchange -> {
                    modelHits.incrementAndGet();
                    byte[] body =
                            """
                            {"data":[{"id":"cached-chat"}]}
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
            return name -> "WANNIAN_MODEL_API_KEY".equals(name) ? "k" : null;
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private javax.sql.DataSource dataSource;

    @BeforeEach
    void clear() throws Exception {
        modelHits.set(0);
        try (var connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM model_listed");
            connection.createStatement().executeUpdate("DELETE FROM model_vendor");
        }
    }

    @Test
    void enableUsesCachedCatalog() {
        assertThat(
                        restTemplate
                                .exchange(
                                        "/api/manage/model/vendors/openai-main",
                                        HttpMethod.PUT,
                                        bearer(
                                                Map.of(
                                                        "displayName",
                                                        "缓存厂商",
                                                        "protocol",
                                                        "openai-compatible",
                                                        "baseUrl",
                                                        vendorBaseUrl,
                                                        "apiKeyEnv",
                                                        "WANNIAN_MODEL_API_KEY")),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(
                        restTemplate
                                .exchange(
                                        "/api/manage/model/vendors/openai-main/models:list",
                                        HttpMethod.POST,
                                        bearer(null),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(modelHits.get()).isEqualTo(1);

        assertThat(
                        restTemplate
                                .exchange(
                                        "/api/manage/model/listed",
                                        HttpMethod.PUT,
                                        bearer(Map.of("vendorId", "openai-main", "modelId", "cached-chat")),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(modelHits.get()).isEqualTo(1);

        ResponseEntity<JsonNode> enabled =
                restTemplate.exchange(
                        "/api/manage/model/enabled",
                        HttpMethod.PUT,
                        bearer(Map.of("vendorId", "openai-main", "modelId", "cached-chat")),
                        JsonNode.class);

        assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(modelHits.get()).isEqualTo(1);
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
