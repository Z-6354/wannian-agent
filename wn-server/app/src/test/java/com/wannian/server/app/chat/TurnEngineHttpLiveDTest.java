package com.wannian.server.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wannian.server.app.http.CreateConversationResponse;
import com.wannian.server.app.http.ReceiveTurnResponse;
import com.wannian.server.app.manage.ManageReason;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
 * 0.2.1-D：HTTP 对外入口走 TurnEngine+Loop（live）；probe 不建会话。
 *
 * <p>live 用例需 {@code DEEPSEEK_API_KEY}。未启用 / probe 隔离用例始终可跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TurnEngineHttpLiveDTest {

    private static final String TOKEN = "turn-engine-d-token";

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("wannian.manage.token", () -> TOKEN);
        registry.add("wannian.model.mode", () -> "live");
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
    void withoutEnabledModelTurnStaysReceived() throws Exception {
        String conversationId = createConversation();
        ResponseEntity<ReceiveTurnResponse> received =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns",
                        Map.of("clientRequestId", UUID.randomUUID().toString(), "text", "你好"),
                        ReceiveTurnResponse.class);

        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(received.getBody()).isNotNull();
        assertThat(received.getBody().reply()).isNull();
        assertThat(received.getBody().reasonCode()).isEqualTo(ManageReason.MODEL_NOT_ENABLED);
        assertThat(received.getBody().detail()).contains("尚未启用模型");
        assertThat(scalar("SELECT status FROM turn")).isEqualTo("RECEIVED");
    }

    @Test
    void probeDoesNotCreateConversationOrTurn() throws Exception {
        enableDeepseekIfPossible();

        long conversationsBefore = count("SELECT COUNT(*) FROM conversation");
        long turnsBefore = count("SELECT COUNT(*) FROM turn");

        ResponseEntity<JsonNode> probe =
                restTemplate.exchange(
                        "/api/manage/model/probe",
                        HttpMethod.POST,
                        bearer(Map.of("text", "只回复一个字：好")),
                        JsonNode.class);

        if (probe.getStatusCode() == HttpStatus.CONFLICT) {
            assertThat(probe.getBody().get("code").asText()).isEqualTo(ManageReason.MODEL_NOT_ENABLED);
        } else {
            assertThat(probe.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(probe.getBody().get("outcome").asText()).isIn("final", "failure", "refusal");
        }

        assertThat(count("SELECT COUNT(*) FROM conversation")).isEqualTo(conversationsBefore);
        assertThat(count("SELECT COUNT(*) FROM turn")).isEqualTo(turnsBefore);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void liveHttpTurnReturnsReplyViaTurnEngine() throws Exception {
        enableDeepseek();

        String conversationId = createConversation();
        String clientRequestId = UUID.randomUUID().toString();
        ResponseEntity<ReceiveTurnResponse> received =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns",
                        Map.of("clientRequestId", clientRequestId, "text", "只回复一个字：好"),
                        ReceiveTurnResponse.class);

        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(received.getBody()).isNotNull();
        assertThat(received.getBody().result()).isEqualTo("accepted");
        assertThat(received.getBody().reply()).isNotBlank();
        assertThat(received.getBody().detail()).isNull();
        assertThat(scalar("SELECT status FROM turn")).isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'")).isEqualTo(1);

        ResponseEntity<ReceiveTurnResponse> replayed =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns",
                        Map.of("clientRequestId", clientRequestId, "text", "只回复一个字：好"),
                        ReceiveTurnResponse.class);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody().replayed()).isTrue();
        assertThat(replayed.getBody().reply()).isEqualTo(received.getBody().reply());
        assertThat(count("SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'")).isEqualTo(1);
    }

    @Test
    void chatApiDoesNotCallProbe() {
        ResponseEntity<String> api = restTemplate.getForEntity("/chat/api.js", String.class);
        assertThat(api.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.getBody())
                .contains("createConversation", "sendTurn")
                .doesNotContain("probe")
                .doesNotContain("/api/manage/model/probe");

        ResponseEntity<String> app = restTemplate.getForEntity("/chat/app.js", String.class);
        assertThat(app.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(app.getBody()).doesNotContain("probe").doesNotContain("/api/manage");
    }

    private void enableDeepseekIfPossible() {
        if (System.getenv("DEEPSEEK_API_KEY") == null || System.getenv("DEEPSEEK_API_KEY").isBlank()) {
            return;
        }
        enableDeepseek();
    }

    private void enableDeepseek() {
        assertThat(
                        restTemplate
                                .exchange(
                                        "/api/manage/model/vendors/deepseek",
                                        HttpMethod.PUT,
                                        bearer(
                                                Map.of(
                                                        "displayName",
                                                        "DeepSeek",
                                                        "protocol",
                                                        "openai-compatible",
                                                        "baseUrl",
                                                        "https://api.deepseek.com/v1",
                                                        "apiKeyEnv",
                                                        "DEEPSEEK_API_KEY")),
                                        JsonNode.class)
                                .getStatusCode())
                .isIn(HttpStatus.CREATED, HttpStatus.OK);

        ResponseEntity<JsonNode> listed =
                restTemplate.exchange(
                        "/api/manage/model/vendors/deepseek/models:list",
                        HttpMethod.POST,
                        bearer(null),
                        JsonNode.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(
                        restTemplate
                                .exchange(
                                        "/api/manage/model/listed",
                                        HttpMethod.PUT,
                                        bearer(Map.of("vendorId", "deepseek", "modelId", "deepseek-flash")),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        restTemplate
                                .exchange(
                                        "/api/manage/model/enabled",
                                        HttpMethod.PUT,
                                        bearer(Map.of("vendorId", "deepseek", "modelId", "deepseek-flash")),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private String createConversation() {
        ResponseEntity<CreateConversationResponse> created =
                restTemplate.postForEntity("/api/conversations", Map.of(), CreateConversationResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().conversationId();
    }

    private static HttpEntity<?> bearer(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(body, headers);
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.createStatement().executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private long count(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.createStatement().executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }
}
