package com.wannian.server.app.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.skill.SkillSummary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 2.4.2：Skill 发现、索引有界、白名单与 load_skill 正文。 */
class FileSkillCatalogTest {

    @TempDir Path tempDir;

    @Test
    void listsAndLoadsAuthorizedSkillBody() throws Exception {
        writeSkill("how-to-remember", "记事", "短说明", "正文：用 remember_fact。");
        FileSkillCatalog catalog =
                new FileSkillCatalog(tempDir, 16, 160, 8000, 3000, Set.of());

        List<SkillSummary> rows = catalog.listSummaries();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id()).isEqualTo("how-to-remember");
        assertThat(rows.get(0).description()).contains("短说明");

        assertThat(catalog.loadBody("how-to-remember"))
                .isPresent()
                .get()
                .asString()
                .contains("remember_fact");
        assertThat(catalog.loadBody("missing")).isEmpty();
        assertThat(catalog.indexPromptText())
                .contains("load_skill")
                .contains("how-to-remember");
    }

    @Test
    void indexCapDoesNotBlockLoadOfExtraSkills() throws Exception {
        writeSkill("aaa-first", "A", "desc-a", "body-a");
        writeSkill("zzz-last", "Z", "desc-z", "body-z");

        FileSkillCatalog catalog =
                new FileSkillCatalog(tempDir, 1, 160, 8000, 3000, Set.of());

        assertThat(catalog.listSummaries()).hasSize(1);
        assertThat(catalog.listSummaries().get(0).id()).isEqualTo("aaa-first");
        // 超索引上限仍可按 id 读正文
        assertThat(catalog.loadBody("zzz-last")).contains("body-z");
    }

    @Test
    void allowlistBlocksUnauthorizedIds() throws Exception {
        writeSkill("allowed-one", "A", "ok", "body-ok");
        writeSkill("denied-two", "B", "no", "body-no");

        FileSkillCatalog catalog =
                new FileSkillCatalog(
                        tempDir, 16, 160, 8000, 3000, Set.of("allowed-one"));

        assertThat(catalog.listSummaries())
                .extracting(SkillSummary::id)
                .containsExactly("allowed-one");
        assertThat(catalog.loadBody("allowed-one")).contains("body-ok");
        assertThat(catalog.loadBody("denied-two")).isEmpty();
        assertThat(catalog.indexPromptText()).doesNotContain("denied-two");
    }

    @Test
    void indexPromptTextRespectsCharBudget() throws Exception {
        writeSkill(
                "long-skill",
                "L",
                "x".repeat(200),
                "body");
        FileSkillCatalog catalog =
                new FileSkillCatalog(tempDir, 16, 200, 8000, 80, Set.of());

        String index = catalog.indexPromptText();
        assertThat(index.length()).isLessThanOrEqualTo(80);
        // 预算过紧时至少保住 header，或整段为空
        assertThat(index.isEmpty() || index.startsWith("可用 Skill")).isTrue();
    }

    @Test
    void parseAllowedIds_csv() {
        assertThat(FileSkillCatalog.parseAllowedIds("")).isEmpty();
        assertThat(FileSkillCatalog.parseAllowedIds(" a ,b-c , bad/id "))
                .containsExactlyInAnyOrder("a", "b-c");
    }

    @Test
    void bodyIsClipped() throws Exception {
        writeSkill("clip-me", "C", "d", "B".repeat(200));
        FileSkillCatalog catalog =
                new FileSkillCatalog(tempDir, 16, 160, 64, 3000, Set.of());
        assertThat(catalog.loadBody("clip-me").orElseThrow()).hasSize(64);
    }

    private void writeSkill(String id, String name, String description, String body)
            throws Exception {
        Path dir = tempDir.resolve(id);
        Files.createDirectories(dir);
        String md =
                """
                ---
                name: %s
                description: %s
                version: "1"
                ---

                %s
                """
                        .formatted(name, description, body);
        Files.writeString(dir.resolve("SKILL.md"), md, StandardCharsets.UTF_8);
    }
}
