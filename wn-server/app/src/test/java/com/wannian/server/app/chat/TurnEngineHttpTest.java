package com.wannian.server.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wannian.server.app.http.CreateConversationResponse;
import com.wannian.server.app.http.ReceiveTurnResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
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

/**
 * HTTP 经 TurnEngine 完成回合：已启用模型应带回 reply；未启用则停在 RECEIVED。
 *
 * <p>本类用 {@code mode=fake} 作接线回归；0.2.1 Loop 行为验收仍须另走 live。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TurnEngineHttpTest {

    private static final String TOKEN = "turn-engine-token";

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
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn_commit_plan");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
            connection.createStatement().executeUpdate("DELETE FROM model_listed");
            connection.createStatement().executeUpdate("DELETE FROM model_vendor");
        }
    }

    @Test
    void withoutEnabledModelTurnStaysReceivedAndExplains() throws Exception {
        String conversationId = createConversation();
        String clientRequestId = UUID.randomUUID().toString();

        ResponseEntity<ReceiveTurnResponse> received =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns",
                        Map.of("clientRequestId", clientRequestId, "text", "你好"),
                        ReceiveTurnResponse.class);

        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(received.getBody()).isNotNull();
        assertThat(received.getBody().result()).isEqualTo("accepted");
        assertThat(received.getBody().reply()).isNull();
        assertThat(received.getBody().detail()).contains("尚未启用模型");
        assertThat(received.getBody().reasonCode()).isNotBlank();

        try (Connection connection = dataSource.getConnection();
                var rs =
                        connection
                                .createStatement()
                                .executeQuery("SELECT status FROM turn")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("RECEIVED");
        }
    }

    @Test
    void withEnabledFakeModelReturnsReplyAndCompletes() throws Exception {
        enableStubChat();
        String conversationId = createConversation();
        String clientRequestId = UUID.randomUUID().toString();

        ResponseEntity<ReceiveTurnResponse> received =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns",
                        Map.of("clientRequestId", clientRequestId, "text", "对话探针"),
                        ReceiveTurnResponse.class);

        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(received.getBody()).isNotNull();
        assertThat(received.getBody().result()).isEqualTo("accepted");
        assertThat(received.getBody().reply()).contains("对话探针");
        assertThat(received.getBody().detail()).isNull();

        try (Connection connection = dataSource.getConnection()) {
            try (var rs = connection.createStatement().executeQuery("SELECT status FROM turn")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("COMPLETED");
            }
            try (var rs =
                    connection
                            .createStatement()
                            .executeQuery("SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }

        ResponseEntity<ReceiveTurnResponse> replayed =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns",
                        Map.of("clientRequestId", clientRequestId, "text", "对话探针"),
                        ReceiveTurnResponse.class);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody().replayed()).isTrue();
        assertThat(replayed.getBody().reply()).isEqualTo(received.getBody().reply());
    }

    private String createConversation() {
        ResponseEntity<CreateConversationResponse> created =
                restTemplate.postForEntity("/api/conversations", Map.of(), CreateConversationResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().conversationId();
    }

    private void enableStubChat() {
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
