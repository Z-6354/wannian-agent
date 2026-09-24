package com.wannian.server.app.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryCommand;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.SourceKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemoryHttpTest {

    @TempDir static Path tempDataDir;

    @DynamicPropertySource
    static void configureDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("wannian.memory.review.tick-ms", () -> "3600000");
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired MemoryStore memoryStore;
    @Autowired MemoryCommand memoryCommand;
    @Autowired javax.sql.DataSource dataSource;

    @BeforeEach
    void clearMemory() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM memory_review_job");
            connection.createStatement().executeUpdate("DELETE FROM memory_subject_generation");
            connection.createStatement().executeUpdate("DELETE FROM memory_record");
        }
    }

    @Test
    void getCorrectAndForgetUseExplicitCompanionAndRevisionCas() {
        MemoryCommand.CommandResult.Applied seeded = (MemoryCommand.CommandResult.Applied)
                memoryCommand.applyReview(new ApprovedMemoryChange(CompanionIdentity.YANHUO,
                        "pref.tea", "用户喜欢龙井", ContentKind.USER_PREFERENCE, SourceKind.EXPLICIT,
                        MemoryScope.COMPANION, 0.8, null, ApprovedMemoryChange.PROPOSE_LLM_REVIEW)
                        .withExpectedGeneration(0));
        String originalId = seeded.memoryId();

        ResponseEntity<Map> missingCompanion = restTemplate.getForEntity("/api/memory", Map.class);
        assertThat(missingCompanion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> listed = restTemplate.getForEntity("/api/memory?companion=yanhuo", Map.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        java.util.List<?> entries = (java.util.List<?>) listed.getBody().get("entries");
        assertThat(entries).hasSize(1);
        Map<?, ?> entry = (Map<?, ?>) entries.getFirst();
        assertThat(entry.get("contentKind")).isEqualTo("USER_PREFERENCE");
        assertThat(entry.get("sourceKind")).isEqualTo("EXPLICIT");
        assertThat(entry.get("scope")).isEqualTo("COMPANION");
        assertThat(entry.containsKey("path")).isTrue();

        Map<String, Object> correction = new java.util.HashMap<>(Map.of(
                "id", originalId,
                "expectedRevision", 99,
                "subjectKey", "pref.tea",
                "claim", "用户喜欢西湖龙井",
                "contentKind", "USER_PREFERENCE",
                "sourceKind", "EXPLICIT",
                "scope", "COMPANION",
                "importance", 0.9));
        ResponseEntity<Map> stale = restTemplate.postForEntity("/api/memory/correct", correction, Map.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(memoryStore.findById(originalId)).get().extracting(row -> row.claim()).isEqualTo("用户喜欢龙井");

        correction.put("expectedRevision", 1);
        ResponseEntity<Map> corrected = restTemplate.postForEntity("/api/memory/correct", correction, Map.class);
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.OK);
        String replacementId = (String) corrected.getBody().get("id");
        assertThat(memoryStore.findById(originalId)).get().extracting(row -> row.lifecycle().name())
                .isEqualTo("SUPERSEDED");
        assertThat(memoryStore.findById(replacementId)).get().extracting(row -> row.claim())
                .isEqualTo("用户喜欢西湖龙井");

        ResponseEntity<Map> forgotten = restTemplate.postForEntity("/api/memory/forget",
                Map.of("id", replacementId, "expectedRevision", 1), Map.class);
        assertThat(forgotten.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO)).isEmpty();
        assertThat(memoryStore.findById(replacementId))
                .get()
                .satisfies(row -> {
                    assertThat(row.lifecycle().name()).isEqualTo("FORGOTTEN");
                    assertThat(row.claim()).isEmpty();
                });
        assertThat(memoryStore.listByCompanion(
                        CompanionIdentity.YANHUO,
                        com.wannian.server.kernel.memory.MemoryLifecycle.FORGOTTEN))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.id()).isEqualTo(replacementId);
                    assertThat(row.claim()).isEmpty();
                });
        ResponseEntity<Map> afterForget = restTemplate.getForEntity("/api/memory?companion=yanhuo", Map.class);
        assertThat((java.util.List<?>) afterForget.getBody().get("entries")).isEmpty();
    }

    @Test
    void correctCannotCreateAnotherActiveForOccupiedSubjectKey() {
        MemoryCommand.CommandResult.Applied first = (MemoryCommand.CommandResult.Applied)
                memoryCommand.applyReview(memory("pref.tea", "用户喜欢龙井"));
        MemoryCommand.CommandResult.Applied second = (MemoryCommand.CommandResult.Applied)
                memoryCommand.applyReview(memory("pref.coffee", "用户喜欢咖啡"));

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/memory/correct",
                Map.of(
                        "id", first.memoryId(),
                        "expectedRevision", 1,
                        "subjectKey", "pref.coffee",
                        "claim", "用户喜欢浅烘咖啡",
                        "contentKind", "USER_PREFERENCE",
                        "sourceKind", "EXPLICIT",
                        "scope", "COMPANION",
                        "importance", 0.9),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("MEMORY_SUBJECT_CONFLICT");
        assertThat(memoryStore.findById(first.memoryId())).get()
                .satisfies(row -> {
                    assertThat(row.lifecycle().name()).isEqualTo("ACTIVE");
                    assertThat(row.claim()).isEqualTo("用户喜欢龙井");
                });
        assertThat(memoryStore.findById(second.memoryId())).get()
                .satisfies(row -> {
                    assertThat(row.lifecycle().name()).isEqualTo("ACTIVE");
                    assertThat(row.claim()).isEqualTo("用户喜欢咖啡");
                });
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO)).hasSize(2);
    }

    @Test
    void staleReviewDraftCannotOverwriteCorrectionOrReviveForgottenSubject() {
        MemoryCommand.CommandResult.Applied first =
                (MemoryCommand.CommandResult.Applied) memoryCommand.applyReview(memory("pref.tea", "龙井"));
        long observedBeforeCorrection = memoryStore.subjectGenerations(CompanionIdentity.YANHUO)
                .get("pref.tea");
        MemoryCommand.CommandResult corrected = memoryCommand.correct(
                first.memoryId(),
                1,
                new ApprovedMemoryChange(
                        CompanionIdentity.YANHUO,
                        "pref.tea",
                        "西湖龙井",
                        ContentKind.USER_PREFERENCE,
                        SourceKind.EXPLICIT,
                        MemoryScope.COMPANION,
                        0.9,
                        null,
                        ApprovedMemoryChange.PROPOSE_HTTP_CORRECT));
        assertThat(corrected).isInstanceOf(MemoryCommand.CommandResult.Applied.class);

        MemoryCommand.CommandResult lateCorrectionReview = memoryCommand.applyReview(
                memory("pref.tea", "迟到的 Review").withExpectedGeneration(observedBeforeCorrection));
        assertThat(lateCorrectionReview)
                .isInstanceOf(MemoryCommand.CommandResult.Rejected.class);
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO))
                .singleElement()
                .extracting(row -> row.claim())
                .isEqualTo("西湖龙井");

        MemoryCommand.CommandResult.Applied forgetTarget =
                (MemoryCommand.CommandResult.Applied) memoryCommand.applyReview(memory("pref.coffee", "咖啡"));
        long observedBeforeForget = memoryStore.subjectGenerations(CompanionIdentity.YANHUO)
                .get("pref.coffee");
        assertThat(memoryCommand.forget(forgetTarget.memoryId(), 1))
                .isInstanceOf(MemoryCommand.CommandResult.Applied.class);
        MemoryCommand.CommandResult lateForgetReview = memoryCommand.applyReview(
                memory("pref.coffee", "被遗忘后迟到的 Review")
                        .withExpectedGeneration(observedBeforeForget));
        assertThat(lateForgetReview)
                .isInstanceOf(MemoryCommand.CommandResult.Rejected.class);
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO))
                .extracting(row -> row.subjectKey())
                .containsExactly("pref.tea");
    }

    private static ApprovedMemoryChange memory(String subjectKey, String claim) {
        return new ApprovedMemoryChange(
                CompanionIdentity.YANHUO,
                subjectKey,
                claim,
                ContentKind.USER_PREFERENCE,
                SourceKind.EXPLICIT,
                MemoryScope.COMPANION,
                0.8,
                null,
                ApprovedMemoryChange.PROPOSE_LLM_REVIEW)
                .withExpectedGeneration(0);
    }
}
