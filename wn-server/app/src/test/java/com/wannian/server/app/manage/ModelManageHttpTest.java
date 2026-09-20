package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
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

/** K03-W1：管理口令、供应商、桩目录与唯一启用。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelManageHttpTest {

    private static final String TOKEN = "manage-test-token";

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
    void loopbackIgnoresWrongToken() throws Exception {
        ResponseEntity<JsonNode> response = putVendor("wrong-token", "openai-main", "旧名", "openai-compatible", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count("model_vendor")).isEqualTo(1);
    }

    @Test
    void createThenGetDoesNotExposeSecretFields() throws Exception {
        ResponseEntity<JsonNode> created =
                putVendor(TOKEN, "openai-main", "主供应商", "openai-compatible", null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("revision").asLong()).isZero();
        assertThat(created.getBody().get("apiKeyEnv").asText()).isEqualTo("WANNIAN_MODEL_API_KEY");
        assertNoSecretFields(created.getBody());

        ResponseEntity<JsonNode> listed =
                restTemplate.exchange("/api/manage/model/vendors", HttpMethod.GET, bearer(TOKEN, null), JsonNode.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(1);
        assertThat(listed.getBody().get(0).get("displayName").asText()).isEqualTo("主供应商");
        assertNoSecretFields(listed.getBody().get(0));
    }

    @Test
    void staleRevisionDoesNotChangeDisplayName() throws Exception {
        assertThat(putVendor(TOKEN, "openai-main", "旧名", "openai-compatible", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> conflict =
                putVendor(TOKEN, "openai-main", "新名", "openai-compatible", 9L);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code").asText()).isEqualTo(ManageReason.REVISION_CONFLICT);
        assertThat(displayName("openai-main")).isEqualTo("旧名");
    }

    @Test
    void unsupportedProtocolDoesNotInsert() throws Exception {
        ResponseEntity<JsonNode> response = putVendor(TOKEN, "openai-main", "旧名", "anthropic", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo(ManageReason.PROTOCOL_UNSUPPORTED);
        assertThat(count("model_vendor")).isZero();
    }

    @Test
    void fileBaseUrlDoesNotInsert() throws Exception {
        ResponseEntity<JsonNode> response =
                restTemplate.exchange(
                        "/api/manage/model/vendors/openai-main",
                        HttpMethod.PUT,
                        bearer(
                                TOKEN,
                                Map.of(
                                        "displayName",
                                        "旧名",
                                        "protocol",
                                        "openai-compatible",
                                        "baseUrl",
                                        "file:///tmp",
                                        "apiKeyEnv",
                                        "WANNIAN_MODEL_API_KEY")),
                        JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo(ManageReason.ILLEGAL_ARGUMENT);
        assertThat(count("model_vendor")).isZero();
    }

    @Test
    void listMissingVendorIsNotFound() {
        ResponseEntity<JsonNode> response =
                restTemplate.exchange(
                        "/api/manage/model/vendors/missing-vendor/models:list",
                        HttpMethod.POST,
                        bearer(TOKEN, null),
                        JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo(ManageReason.VENDOR_NOT_FOUND);
    }

    @Test
    void listReturnsStubCatalog() throws Exception {
        assertThat(putVendor(TOKEN, "openai-main", "主供应商", "openai-compatible", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> response =
                restTemplate.exchange(
                        "/api/manage/model/vendors/openai-main/models:list",
                        HttpMethod.POST,
                        bearer(TOKEN, null),
                        JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("entries")).hasSize(2);
        assertThat(response.getBody().get("entries").get(0).get("id").asText()).isEqualTo("stub-chat");
        assertThat(response.getBody().get("entries").get(1).get("id").asText()).isEqualTo("stub-reasoner");
        assertThat(count("model_listed")).isZero();
    }

    @Test
    void listedRowIsDurableAndCatalogIsNot() throws Exception {
        assertThat(putVendor(TOKEN, "openai-main", "主供应商", "openai-compatible", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(
                        restTemplate
                                .exchange(
                                        "/api/manage/model/vendors/openai-main/models:list",
                                        HttpMethod.POST,
                                        bearer(TOKEN, null),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(count("model_listed")).isZero();

        ResponseEntity<JsonNode> added =
                restTemplate.exchange(
                        "/api/manage/model/listed",
                        HttpMethod.PUT,
                        bearer(TOKEN, Map.of("vendorId", "openai-main", "modelId", "stub-chat")),
                        JsonNode.class);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("model_listed")).isEqualTo(1);

        ResponseEntity<JsonNode> listed =
                restTemplate.exchange("/api/manage/model/listed", HttpMethod.GET, bearer(TOKEN, null), JsonNode.class);
        assertThat(listed.getBody().get("entries")).hasSize(1);
        assertThat(listed.getBody().get("entries").get(0).get("modelId").asText()).isEqualTo("stub-chat");
        assertThat(listed.getBody().get("entries").get(0).get("enabled").asBoolean()).isFalse();
    }

    @Test
    void enableReplacesSingleRowAndRejectsUnknownModel() throws Exception {
        assertThat(putVendor(TOKEN, "openai-main", "主供应商", "openai-compatible", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(addListed("openai-main", "stub-chat").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(addListed("openai-main", "stub-reasoner").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> first = enable("openai-main", "stub-chat");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("enabled").get("modelId").asText()).isEqualTo("stub-chat");

        ResponseEntity<JsonNode> second = enable("openai-main", "stub-reasoner");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("model_listed")).isEqualTo(2);
        assertThat(countEnabled()).isEqualTo(1);
        ResponseEntity<JsonNode> current =
                restTemplate.exchange("/api/manage/model/enabled", HttpMethod.GET, bearer(TOKEN, null), JsonNode.class);
        assertThat(current.getBody().get("enabled").get("vendorId").asText()).isEqualTo("openai-main");
        assertThat(current.getBody().get("enabled").get("modelId").asText()).isEqualTo("stub-reasoner");
        assertThat(listedEnabled("openai-main", "stub-reasoner")).isTrue();
        assertThat(listedEnabled("openai-main", "stub-chat")).isFalse();

        ResponseEntity<JsonNode> rejected = enable("openai-main", "not-a-model");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody().get("code").asText()).isEqualTo(ManageReason.MODEL_NOT_LISTED);
        assertThat(modelId()).isEqualTo("stub-reasoner");
        assertThat(countEnabled()).isEqualTo(1);
    }

    @Test
    void deleteEnabledVendorClearsBothRows() throws Exception {
        assertThat(putVendor(TOKEN, "openai-main", "主供应商", "openai-compatible", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(addListed("openai-main", "stub-chat").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enable("openai-main", "stub-chat").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> deleted =
                restTemplate.exchange(
                        "/api/manage/model/vendors/openai-main",
                        HttpMethod.DELETE,
                        bearer(TOKEN, null),
                        Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("model_vendor")).isZero();
        assertThat(count("model_listed")).isZero();
    }

    @Test
    void wrongManageTokenDoesNotBlockLiveProbe() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("wrong-token");
        ResponseEntity<JsonNode> response =
                restTemplate.exchange("/internal/live", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("live");
    }

    private ResponseEntity<JsonNode> putVendor(
            String token, String id, String displayName, String protocol, Long expectedRevision) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("displayName", displayName);
        body.put("protocol", protocol);
        body.put("baseUrl", "https://example.test/v1/");
        body.put("apiKeyEnv", "WANNIAN_MODEL_API_KEY");
        if (expectedRevision != null) {
            body.put("expectedRevision", expectedRevision);
        }
        return restTemplate.exchange(
                "/api/manage/model/vendors/" + id, HttpMethod.PUT, bearer(token, body), JsonNode.class);
    }

    private ResponseEntity<JsonNode> addListed(String vendorId, String modelId) {
        return restTemplate.exchange(
                "/api/manage/model/listed",
                HttpMethod.PUT,
                bearer(TOKEN, Map.of("vendorId", vendorId, "modelId", modelId)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> enable(String vendorId, String modelId) {
        return restTemplate.exchange(
                "/api/manage/model/enabled",
                HttpMethod.PUT,
                bearer(TOKEN, Map.of("vendorId", vendorId, "modelId", modelId)),
                JsonNode.class);
    }

    private static HttpEntity<?> bearer(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(body, headers);
    }

    private static void assertNoSecretFields(JsonNode node) {
        Set<String> forbidden = Set.of("apiKey", "secret", "token", "password", "authorization");
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            assertThat(forbidden).doesNotContain(names.next());
        }
        assertThat(node.toString()).doesNotContain("sk-");
    }

    private String displayName(String id) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT display_name FROM model_vendor WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private String modelId() throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs =
                        connection
                                .createStatement()
                                .executeQuery("SELECT model_id FROM model_listed WHERE enabled = 1")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private long countEnabled() throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs =
                        connection
                                .createStatement()
                                .executeQuery("SELECT COUNT(*) FROM model_listed WHERE enabled = 1")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private boolean listedEnabled(String vendorId, String modelId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT enabled FROM model_listed WHERE vendor_id = ? AND model_id = ?")) {
            ps.setString(1, vendorId);
            ps.setString(2, modelId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1) == 1;
            }
        }
    }

    private long count(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }
}
