package com.wannian.server.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.app.http.AsyncTurnController.AsyncReceiveResponse;
import com.wannian.server.app.http.CreateConversationResponse;
import com.wannian.server.app.http.TurnControlController.TurnStatusResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Async HTTP safety routing must complete crisis turns even with no enabled model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncCrisisSafetyE2ETest {

    private static final Path dataDir = createTempDataDir();

    private static Path createTempDataDir() {
        try {
            return Files.createTempDirectory("wannian-async-crisis-e2e-");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> dataDir.toAbsolutePath().toString());
        registry.add("wannian.model.mode", () -> "live");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void clearIsolatedDatabase() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM turn_step");
            statement.executeUpdate("DELETE FROM outbox_event");
            statement.executeUpdate("DELETE FROM turn_commit_plan");
            statement.executeUpdate("DELETE FROM turn");
            statement.executeUpdate("DELETE FROM message");
            statement.executeUpdate("DELETE FROM conversation");
            statement.executeUpdate("DELETE FROM model_listed");
            statement.executeUpdate("DELETE FROM model_vendor");
        }
    }

    @Test
    void asyncCrisisTurnCompletesAndPersistsStructuredAuditWithoutEnabledModel() throws Exception {
        String conversationId = createConversation();
        ResponseEntity<AsyncReceiveResponse> ordinary = postAsync(conversationId, "请帮我查一下天气");
        assertThat(ordinary.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ordinary.getBody()).isNotNull();
        Thread.sleep(900);
        assertThat(restTemplate.getForObject(
                "/api/conversations/" + conversationId + "/turns/" + ordinary.getBody().turnId(),
                TurnStatusResponse.class).status()).isEqualTo("RECEIVED");
        ResponseEntity<AsyncReceiveResponse> received = postAsync(
                conversationId, "我想伤害别人，手里有武器，马上要去找他。");

        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(received.getBody()).isNotNull();
        assertThat(received.getBody().status()).isIn("RECEIVED", "RUNNING", "COMPLETED");
        TurnStatusResponse status = awaitStatus(conversationId, received.getBody().turnId(), "COMPLETED");
        assertThat(status.errorCode()).isNull();
        assertThat(restTemplate.getForObject(
                "/api/conversations/" + conversationId + "/turns/" + ordinary.getBody().turnId(),
                TurnStatusResponse.class).status()).isEqualTo("RECEIVED");

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT request_json, result_json FROM turn_step WHERE turn_id = ? AND kind = 'CRISIS_DECISION'")) {
            statement.setString(1, received.getBody().turnId());
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                String audit = rs.getString("request_json") + rs.getString("result_json");
                assertThat(audit).contains("IMMEDIATE", "HARM_TO_OTHERS_EXPLICIT", "WEAPON_HELD_EXPLICIT",
                        "CRISIS_DETERMINISTIC_RESPONSE");
                assertThat(audit).doesNotContain("我想伤害别人", "马上要去找他");
                assertThat(rs.next()).isFalse();
            }
        }
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM turn_step WHERE turn_id = ? AND kind = 'CRISIS_DECISION'")) {
            statement.setString(1, ordinary.getBody().turnId());
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void ordinaryAsyncTurnStillWaitsForEnabledModel() throws Exception {
        String conversationId = createConversation();
        ResponseEntity<AsyncReceiveResponse> received = postAsync(conversationId, "请帮我查一下天气");

        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(received.getBody()).isNotNull();
        Thread.sleep(1800);
        TurnStatusResponse status = restTemplate.getForObject(
                "/api/conversations/" + conversationId + "/turns/" + received.getBody().turnId(),
                TurnStatusResponse.class);
        assertThat(status.status()).isEqualTo("RECEIVED");
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM turn_step WHERE turn_id = ? AND kind = 'CRISIS_DECISION'")) {
            statement.setString(1, received.getBody().turnId());
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    private String createConversation() {
        ResponseEntity<CreateConversationResponse> created = restTemplate.postForEntity(
                "/api/conversations", Map.of(), CreateConversationResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().conversationId();
    }

    private ResponseEntity<AsyncReceiveResponse> postAsync(String conversationId, String text) {
        return restTemplate.postForEntity(
                "/api/conversations/" + conversationId + "/turns/async",
                Map.of("clientRequestId", UUID.randomUUID().toString(), "text", text),
                AsyncReceiveResponse.class);
    }

    private TurnStatusResponse awaitStatus(String conversationId, String turnId, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(12).toNanos();
        TurnStatusResponse last = null;
        while (System.nanoTime() < deadline) {
            last = restTemplate.getForObject(
                    "/api/conversations/" + conversationId + "/turns/" + turnId,
                    TurnStatusResponse.class);
            if (last != null && expected.equals(last.status())) return last;
            if (last != null && ("FAILED".equals(last.status()) || "CANCELLED".equals(last.status()))) break;
            Thread.sleep(100);
        }
        assertThat(last).as("async turn terminal status").isNotNull();
        assertThat(last.status()).as("async turn status response: %s", last).isEqualTo(expected);
        return last;
    }
}
