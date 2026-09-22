package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

/** 管理页工具配置 GET/PUT。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolManageHttpTest {

    private static final String TOKEN = "manage-tools-token";

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("wannian.manage.token", () -> TOKEN);
        registry.add("wannian.model.mode", () -> "fake");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void getToolsReturnsPoolAndDefaults() {
        ResponseEntity<JsonNode> response =
                restTemplate.exchange(
                        "/api/manage/agent/tools", HttpMethod.GET, bearer(TOKEN), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("pool").isArray()).isTrue();
        assertThat(body.get("pool").size()).isGreaterThanOrEqualTo(6);
        assertThat(body.get("enabled").isArray()).isTrue();
        assertThat(body.get("yanhuo").get("chat").isArray()).isTrue();
        assertThat(body.get("hostCapabilities").isArray()).isTrue();
        assertThat(body.get("modelVisiblePreview").get("chat").isArray()).isTrue();
        JsonNode entry = body.get("pool").get(0);
        assertThat(entry.has("status")).isTrue();
        assertThat(entry.has("selectable")).isTrue();
        assertThat(entry.get("status").asText())
                .isIn("IN_USE", "NOT_USING", "UNAVAILABLE");
    }

    @Test
    void putToolsDisablesCalculateAndMutexPowershell() {
        Map<String, Object> body =
                Map.of(
                        "enabled",
                        List.of(
                                "current_time",
                                "http_read",
                                "powershell_resolve_5",
                                "powershell_resolve_7"),
                        "yanhuo",
                        Map.of(
                                "chat",
                                List.of("current_time"),
                                "work",
                                List.of("current_time", "http_read"),
                                "research",
                                List.of("current_time", "http_read")));
        ResponseEntity<JsonNode> response =
                restTemplate.exchange(
                        "/api/manage/agent/tools",
                        HttpMethod.PUT,
                        bearer(TOKEN, body),
                        JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String enabled = response.getBody().get("enabled").toString();
        assertThat(enabled).doesNotContain("calculate");
        assertThat(response.getBody().get("yanhuo").get("chat").toString()).contains("current_time");
        boolean has5 = enabled.contains("powershell_resolve_5");
        boolean has7 = enabled.contains("powershell_resolve_7");
        assertThat(has5 && has7).as("PS 5/7 must be mutex after clamp").isFalse();
        assertThat(response.getBody().get("modelVisiblePreview").get("chat").toString())
                .contains("current_time");
    }

    @Test
    void putToolsRejectsFacetOutsideEnabled() {
        Map<String, Object> body =
                Map.of(
                        "enabled",
                        List.of("current_time"),
                        "yanhuo",
                        Map.of(
                                "chat",
                                List.of("calculate"),
                                "work",
                                List.of(),
                                "research",
                                List.of()));
        ResponseEntity<JsonNode> response =
                restTemplate.exchange(
                        "/api/manage/agent/tools",
                        HttpMethod.PUT,
                        bearer(TOKEN, body),
                        JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("ILLEGAL_ARGUMENT");
    }

    private static HttpEntity<Object> bearer(String token) {
        return bearer(token, null);
    }

    private static HttpEntity<Object> bearer(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(body, headers);
    }
}
