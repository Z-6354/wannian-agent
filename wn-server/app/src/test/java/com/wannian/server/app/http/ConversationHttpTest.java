package com.wannian.server.app.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * K02 HTTP：建会话与接收回合。不暴露完成提交。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationHttpTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearBusinessTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
        }
    }

    @Test
    void createThenReceiveIsIdempotent() {
        ResponseEntity<CreateConversationResponse> created =
                restTemplate.postForEntity("/api/conversations", Map.of(), CreateConversationResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().result()).isEqualTo("created");
        String conversationId = created.getBody().conversationId();

        ResponseEntity<CreateConversationResponse> again =
                restTemplate.postForEntity(
                        "/api/conversations",
                        Map.of("conversationId", conversationId),
                        CreateConversationResponse.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().result()).isEqualTo("already_exists");

        Map<String, Object> body =
                Map.of("clientRequestId", "http-req-1", "text", "你好", "sequenceNo", 1);
        ResponseEntity<ReceiveTurnResponse> received =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns", body, ReceiveTurnResponse.class);
        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(received.getBody().result()).isEqualTo("accepted");
        assertThat(received.getBody().replayed()).isFalse();

        ResponseEntity<ReceiveTurnResponse> replayed =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns", body, ReceiveTurnResponse.class);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody().replayed()).isTrue();
        assertThat(replayed.getBody().turnId()).isEqualTo(received.getBody().turnId());
    }

    @Test
    void receiveUnknownConversationIsNotFound() {
        ResponseEntity<ReceiveTurnResponse> response =
                restTemplate.postForEntity(
                        "/api/conversations/00000000-0000-0000-0000-000000000001/turns",
                        Map.of("clientRequestId", "missing", "text", "你好", "sequenceNo", 1),
                        ReceiveTurnResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().reasonCode()).isEqualTo("CONVERSATION_NOT_FOUND");
    }

    @Test
    void blankTextIsRejected() throws Exception {
        ResponseEntity<CreateConversationResponse> created =
                restTemplate.postForEntity("/api/conversations", Map.of(), CreateConversationResponse.class);
        ResponseEntity<ReceiveTurnResponse> response =
                restTemplate.postForEntity(
                        "/api/conversations/" + created.getBody().conversationId() + "/turns",
                        Map.of("clientRequestId", "blank", "text", "  ", "sequenceNo", 1),
                        ReceiveTurnResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().reasonCode()).isEqualTo("ILLEGAL_ARGUMENT");
        try (Connection connection = dataSource.getConnection();
                java.sql.ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM message")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void rawTextIsStoredAndPaddingConflicts() throws Exception {
        ResponseEntity<CreateConversationResponse> created =
                restTemplate.postForEntity("/api/conversations", Map.of(), CreateConversationResponse.class);
        String conversationId = created.getBody().conversationId();
        String padded = "  hello\n";
        ResponseEntity<ReceiveTurnResponse> received =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns",
                        Map.of("clientRequestId", "raw-1", "text", padded),
                        ReceiveTurnResponse.class);
        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        try (Connection connection = dataSource.getConnection();
                java.sql.PreparedStatement ps =
                        connection.prepareStatement("SELECT content_json FROM message")) {
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(rs.getString(1));
                assertThat(node.get("text").asText()).isEqualTo(padded);
                assertThat(node.get("v").asInt()).isEqualTo(1);
            }
        }

        ResponseEntity<ReceiveTurnResponse> conflict =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns",
                        Map.of("clientRequestId", "raw-1", "text", "hello"),
                        ReceiveTurnResponse.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
