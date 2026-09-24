package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentBudgetSettingsTest {

    @TempDir
    Path dir;

    @Test
    void writesSeedThenKeepsExisting() throws Exception {
        AgentBudgetSettings.Snapshot seed = AgentBudgetSettings.Snapshot.validate(3, 5, 15, 30);
        assertThat(AgentBudgetSettings.loadOrCreate(dir.resolve(AgentBudgetSettings.FILE_NAME), seed))
                .isEqualTo(seed);
        Path file = dir.resolve(AgentBudgetSettings.FILE_NAME);
        assertThat(Files.readString(file))
                .contains("\"agentBudget\"")
                .contains("\"maxModelDecisions\" : 3")
                .contains("\"maxSystemToolInvocationsPerTool\" : 5")
                .contains("\"softDeadlineSeconds\" : 15")
                .contains("\"hardDeadlineSeconds\" : 30");

        Files.writeString(
                file,
                """
                {
                  "later": true,
                  "agentBudget": {
                    "maxModelDecisions": 5,
                    "softDeadlineSeconds": 20,
                    "hardDeadlineSeconds": 60
                  }
                }
                """);
        // 缺省系统工具上限时回落默认 5
        assertThat(AgentBudgetSettings.loadOrCreate(file, seed))
                .isEqualTo(AgentBudgetSettings.Snapshot.validate(5, 5, 20, 60));
        AgentBudgetSettings.writeBudget(file, AgentBudgetSettings.Snapshot.validate(5, 5, 20, 60));
        assertThat(Files.readString(file)).contains("\"later\" : true");
    }

    @Test
    void migratesLegacyProperties() throws Exception {
        Path legacy = dir.resolve(AgentBudgetSettings.LEGACY_FILE_NAME);
        Files.writeString(
                legacy,
                """
                maxModelDecisions=4
                softDeadlineSeconds=10
                hardDeadlineSeconds=40
                """);
        AgentBudgetSettings.Snapshot seed = AgentBudgetSettings.Snapshot.validate(3, 5, 15, 30);
        assertThat(AgentBudgetSettings.loadOrCreate(dir.resolve(AgentBudgetSettings.FILE_NAME), seed))
                .isEqualTo(AgentBudgetSettings.Snapshot.validate(4, 5, 10, 40));
        assertThat(Files.readString(dir.resolve(AgentBudgetSettings.FILE_NAME)))
                .contains("\"maxModelDecisions\" : 4")
                .contains("\"maxSystemToolInvocationsPerTool\" : 5");
    }

    @Test
    void rejectsHardBeforeSoft() {
        assertThatThrownBy(() -> AgentBudgetSettings.Snapshot.validate(3, 5, 30, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hardDeadlineSeconds");
    }

    @Test
    void refusesWriteWhenJsonCorruptPreservesFile() throws Exception {
        Path file = dir.resolve(AgentBudgetSettings.FILE_NAME);
        String corrupt = "{ not-json keep-other-keys and sk-secret";
        Files.writeString(file, corrupt);
        assertThatThrownBy(
                        () ->
                                AgentBudgetSettings.writeBudget(
                                        file, AgentBudgetSettings.Snapshot.validate(3, 5, 15, 30)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("损坏");
        assertThat(Files.readString(file)).isEqualTo(corrupt);
    }

    @Test
    void loadOrCreateRejectsCorruptFileWithoutOverwrite() throws Exception {
        Path file = dir.resolve(AgentBudgetSettings.FILE_NAME);
        String corrupt = "{ broken";
        Files.writeString(file, corrupt);
        assertThatThrownBy(
                        () ->
                                AgentBudgetSettings.loadOrCreate(
                                        file, AgentBudgetSettings.Snapshot.validate(3, 5, 15, 30)))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo(corrupt);
    }
}
