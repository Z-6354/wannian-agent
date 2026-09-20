package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
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

/** 未设置环境变量口令时，本机回环仍可写；默认口令写入数据目录。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelManageUnconfiguredTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("wannian.manage.token", () -> "");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearVendors() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM model_listed");
            connection.createStatement().executeUpdate("DELETE FROM model_vendor");
        }
    }

    @Test
    void loopbackWritesWithoutEnvTokenAndCreatesLocalFile() throws Exception {
        ResponseEntity<JsonNode> response =
                restTemplate.exchange(
                        "/api/manage/model/vendors/openai-main",
                        HttpMethod.PUT,
                        json(null, vendorBody("旧名", "openai-compatible", "https://example.test/v1", "WANNIAN_MODEL_API_KEY", null)),
                        JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count("model_vendor")).isEqualTo(1);
        Path file = tempDataDir.resolve(ManageTokenFile.FILE_NAME);
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("token=" + ManageTokenFile.DEFAULT_TOKEN);
    }

    private static HttpEntity<Map<String, Object>> json(String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private static Map<String, Object> vendorBody(
            String displayName, String protocol, String baseUrl, String apiKeyEnv, Long expectedRevision) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("displayName", displayName);
        body.put("protocol", protocol);
        body.put("baseUrl", baseUrl);
        body.put("apiKeyEnv", apiKeyEnv);
        if (expectedRevision != null) {
            body.put("expectedRevision", expectedRevision);
        }
        return body;
    }

    private long count(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }
}
