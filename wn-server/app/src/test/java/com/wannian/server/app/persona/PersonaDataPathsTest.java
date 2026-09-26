package com.wannian.server.app.persona;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonaDataPathsTest {
    @TempDir Path data;

    @Test
    void migrateLegacyLayoutMovesFlatDirsUnderPersonaRoot() throws Exception {
        Files.createDirectories(data.resolve("persona-sources"));
        Files.writeString(data.resolve("persona-sources/a.txt"), "src", StandardCharsets.UTF_8);
        Files.createDirectories(data.resolve("persona-import-checkpoint"));
        Files.writeString(data.resolve("persona-import-checkpoint/job.json"), "{}", StandardCharsets.UTF_8);
        Files.createDirectories(data.resolve("persona-import-debug"));
        Files.writeString(data.resolve("persona-import-debug/x-last.txt"), "raw", StandardCharsets.UTF_8);
        Files.createDirectories(data.resolve("persona-review/imp-1"));
        Files.writeString(data.resolve("persona-review/imp-1/SOUL.md"), "# s", StandardCharsets.UTF_8);
        Files.createDirectories(data.resolve("persona-review-backups/b1"));
        Files.writeString(data.resolve("persona-review-backups/b1/SOUL.md"), "# b", StandardCharsets.UTF_8);
        Files.createDirectories(data.resolve("persona-overlay-backups/yanhuo-initial"));
        Files.writeString(data.resolve("persona-overlay-backups/yanhuo-initial/SOUL.md"), "# o", StandardCharsets.UTF_8);

        PersonaDataPaths.migrateLegacyLayout(data);
        PersonaDataPaths.migrateLegacyLayout(data); // idempotent

        assertThat(PersonaDataPaths.sources(data).resolve("a.txt")).exists();
        assertThat(PersonaDataPaths.importCheckpoint(data).resolve("job.json")).exists();
        assertThat(PersonaDataPaths.importDebug(data).resolve("x-last.txt")).exists();
        assertThat(PersonaDataPaths.reviewStaging(data).resolve("imp-1/SOUL.md")).exists();
        assertThat(PersonaDataPaths.reviewBackups(data).resolve("b1/SOUL.md")).exists();
        assertThat(PersonaDataPaths.overlayBackups(data).resolve("yanhuo-initial/SOUL.md")).exists();

        assertThat(data.resolve("persona-sources")).doesNotExist();
        assertThat(data.resolve("persona-import-checkpoint")).doesNotExist();
        assertThat(data.resolve("persona-import-debug")).doesNotExist();
        assertThat(data.resolve("persona-review")).doesNotExist();
        assertThat(data.resolve("persona-review-backups")).doesNotExist();
        assertThat(data.resolve("persona-overlay-backups")).doesNotExist();
    }
}
