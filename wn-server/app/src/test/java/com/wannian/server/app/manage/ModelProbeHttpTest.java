package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** fake 模式下 probe 走 FakeModelAdapter，不访问公网。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelProbeHttpTest {

    private static final String TOKEN = "probe-token";

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("wannian.manage.token", () -> TOKEN);
        registry.add("wannian.model.mode", () -> "fake");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clear() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM model_listed");
            connection.createStatement().executeUpdate("DELETE FROM model_vendor");
        }
    }

    @Test
    void probeWithoutEnabledIsConflict() {
        ResponseEntity<JsonNode> response =
                restTemplate.exchange(
                        "/api/manage/model/probe",
                        HttpMethod.POST,
                        bearer(Map.of("text", "你好")),
                        JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo(ManageReason.MODEL_NOT_ENABLED);
    }

    @Test
    void probeWithEnabledReturnsFakeAnswer() {
        assertThat(
                        restTemplate
                                .exchange(
                                        "/api/manage/model/vendors/openai-main",
                                        HttpMethod.PUT,
                                        bearer(
                                                Map.of(
                                                        "displayName",
                                                        "桩",
                                                        "protocol",
                                                        "openai-compatible",
                                                        "baseUrl",
                                                        "https://example.test/v1",
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
        assertThat(
                        restTemplate
                                .exchange(
                                        "/api/manage/model/listed",
                                        HttpMethod.PUT,
                                        bearer(Map.of("vendorId", "openai-main", "modelId", "stub-chat")),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        restTemplate
                                .exchange(
                                        "/api/manage/model/enabled",
                                        HttpMethod.PUT,
                                        bearer(Map.of("vendorId", "openai-main", "modelId", "stub-chat")),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> probe =
                restTemplate.exchange(
                        "/api/manage/model/probe",
                        HttpMethod.POST,
                        bearer(Map.of("text", "你好")),
                        JsonNode.class);

        assertThat(probe.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(probe.getBody().get("outcome").asText()).isEqualTo("final");
        assertThat(probe.getBody().get("vendorId").asText()).isEqualTo("openai-main");
        assertThat(probe.getBody().get("modelId").asText()).isEqualTo("stub-chat");
        assertThat(probe.getBody().get("text").asText()).contains("你好");
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
