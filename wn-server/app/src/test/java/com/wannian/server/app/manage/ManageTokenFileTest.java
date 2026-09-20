package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManageTokenFileTest {

    @TempDir
    Path dir;

    @Test
    void writesDefaultThenKeepsExisting() throws Exception {
        assertThat(ManageTokenFile.loadOrCreate(dir)).isEqualTo(ManageTokenFile.DEFAULT_TOKEN);
        Path file = dir.resolve(ManageTokenFile.FILE_NAME);
        assertThat(Files.readString(file)).contains("token=" + ManageTokenFile.DEFAULT_TOKEN);

        Files.writeString(file, "token=from-file\n");
        assertThat(ManageTokenFile.loadOrCreate(dir)).isEqualTo("from-file");
    }
}
