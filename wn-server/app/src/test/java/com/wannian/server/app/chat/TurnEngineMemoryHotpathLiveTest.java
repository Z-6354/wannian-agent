package com.wannian.server.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wannian.server.app.http.CreateConversationResponse;
import com.wannian.server.app.http.ReceiveTurnResponse;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryStore;
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
 * 0.2.3-E live：真模型经 HTTP Turn 调用 remember_fact 并落库。
 *
 * <p>需 {@code DEEPSEEK_API_KEY}；无密钥时跳过，不退 Fake 冒充通过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TurnEngineMemoryHotpathLiveTest {

    private static final String TOKEN = "memory-hotpath-live-token";

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("wannian.manage.token", () -> TOKEN);
        registry.add("wannian.model.mode", () -> "live");
        registry.add("wannian.agent.budget.max-model-decisions", () -> "10");
        registry.add("wannian.agent.budget.soft-deadline-seconds", () -> "60");
        registry.add("wannian.agent.budget.hard-deadline-seconds", () -> "100");
        registry.add("wannian.memory.review.tick-ms", () -> "3600000");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MemoryStore memoryStore;

    @BeforeEach
    void clear() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn_commit_plan");
            connection.createStatement().executeUpdate("DELETE FROM turn_step");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
            connection.createStatement().executeUpdate("DELETE FROM model_listed");
            connection.createStatement().executeUpdate("DELETE FROM model_vendor");
            connection.createStatement().executeUpdate("DELETE FROM memory_review_job");
            connection.createStatement().executeUpdate("DELETE FROM memory_subject_generation");
            connection.createStatement().executeUpdate("DELETE FROM memory_record");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void liveRememberFactPersistsActiveMemory() throws Exception {
        enableDeepseek();

        String conversationId = createConversation();
        String prompt =
                "请立刻调用工具 remember_fact 记住一条事实，参数必须是："
                        + "claim=用户喜欢龙井，subjectKey=pref.tea，"
                        + "contentKind=USER_PREFERENCE，importance=0.9。"
                        + "工具成功后只用一句话确认，不要再调其它工具。";

        ResponseEntity<ReceiveTurnResponse> received =
                restTemplate.postForEntity(
                        "/api/conversations/" + conversationId + "/turns",
                        Map.of(
                                "clientRequestId",
                                UUID.randomUUID().toString(),
                                "text",
                                prompt),
                        ReceiveTurnResponse.class);

        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(received.getBody()).isNotNull();
        assertThat(received.getBody().result()).isEqualTo("accepted");
        assertThat(received.getBody().reply()).isNotBlank();
        assertThat(received.getBody().detail()).isNull();
        assertThat(scalar("SELECT status FROM turn")).isEqualTo("COMPLETED");
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO))
                .isNotEmpty()
                .anySatisfy(
                        row -> {
                            assertThat(row.subjectKey()).containsIgnoringCase("tea");
                            assertThat(row.claim()).contains("龙井");
                            assertThat(row.importance()).isGreaterThan(0.0);
                        });
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

        ResponseEntity<JsonNode> listedRemote =
                restTemplate.exchange(
                        "/api/manage/model/vendors/deepseek/models:list",
                        HttpMethod.POST,
                        bearer(null),
                        JsonNode.class);
        assertThat(listedRemote.getStatusCode()).isEqualTo(HttpStatus.OK);

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
                restTemplate.postForEntity(
                        "/api/conversations", Map.of(), CreateConversationResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().conversationId();
    }

    private HttpEntity<?> bearer(Object body) {
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
}
