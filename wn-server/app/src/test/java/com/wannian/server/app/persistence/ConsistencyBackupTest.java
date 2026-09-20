package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.backup.BackupSnapshot;
import com.wannian.server.kernel.backup.ConsistencyBackup;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.CommitTurnResult;
import com.wannian.server.kernel.turn.ExecutionClaim;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import com.wannian.server.kernel.turn.FreezeCommitResult;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 一致性备份：快照在另一目录打开，不覆盖当前库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConsistencyBackupTest {

    @TempDir
    static Path tempDataDir;

    @TempDir
    Path restoreDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("wannian.backup.retain-count", () -> "2");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private TurnCommitter turnCommitter;

    @Autowired
    private TurnRepository turnRepository;

    @Autowired
    private ConsistencyBackup consistencyBackup;

    @Autowired
    private BackupBeforeMigrate backupBeforeMigrate;

    @BeforeEach
    void clearBusinessTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn_commit_plan");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
        }
    }

    @Test
    void snapshotOpensInAnotherDirectoryAndLiveDbStays() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(conversationId)))
                .isInstanceOf(CreateConversationResult.Created.class);
        TurnId turnId = TurnId.generate();
        MessageId userMessageId = MessageId.generate();
        ReceiveTurnResult received =
                turnCommitter.receive(
                        new ReceiveTurnPlan(
                                conversationId,
                                "backup-req-1",
                                turnId,
                                new ReceiveTurnPlan.UserMessageDraft(
                                        userMessageId,
                                        MessageRole.USER,
                                        "{\"v\":1,\"text\":\"备份\"}",
                                        1)));
        assertThat(received).isInstanceOf(ReceiveTurnResult.Accepted.class);
        MessageId assistantMessageId = MessageId.generate();
        advanceToCommitting(turnId, assistantMessageId, "{\"v\":1,\"text\":\"收到\"}");
        assertThat(turnCommitter.commit(turnCommitter.frozenCommitPlan(turnId).orElseThrow()))
                .isInstanceOf(CommitTurnResult.Committed.class);

        BackupSnapshot snapshot = consistencyBackup.createSnapshot(restoreDir);
        Path liveDb = tempDataDir.resolve("wannian.db").toAbsolutePath().normalize();
        assertThat(snapshot.backupFile().toAbsolutePath().normalize()).isNotEqualTo(liveDb);
        assertThat(snapshot.schemaVersion()).isEqualTo("004");
        assertThat(snapshot.applicationBuildId()).isEqualTo("unknown");
        assertThat(snapshot.databaseDigest()).isEqualTo(sha256(snapshot.backupFile()));
        String metadata = Files.readString(restoreDir.resolve("wannian-backup.json"));
        assertThat(metadata).contains("\"schemaVersion\":\"004\"");
        assertThat(metadata).contains("\"source\":\"wannian-auto-snapshot\"");
        assertThat(metadata).contains("\"status\":\"complete\"");
        assertThat(metadata).contains("\"applicationBuildId\":\"unknown\"");
        assertThat(metadata).contains(snapshot.databaseDigest());

        try (Connection restored =
                DriverManager.getConnection("jdbc:sqlite:" + snapshot.backupFile().toAbsolutePath())) {
            assertThat(scalar(restored, "PRAGMA integrity_check")).isEqualTo("ok");
            assertThat(scalar(restored, "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"))
                    .isEqualTo("004");
            assertThat(count(restored, "SELECT COUNT(*) FROM conversation")).isEqualTo(1);
            assertThat(scalar(restored, "SELECT title FROM conversation WHERE id = '" + conversationId.asString() + "'"))
                    .isEqualTo("会话1");
            assertThat(scalar(restored, "SELECT status FROM turn WHERE id = '" + turnId.asString() + "'"))
                    .isEqualTo(TurnStatus.COMPLETED.name());
            assertThat(scalar(restored, "SELECT input_message_id FROM turn WHERE id = '" + turnId.asString() + "'"))
                    .isEqualTo(userMessageId.asString());
            assertThat(count(restored, "SELECT COUNT(*) FROM message")).isEqualTo(2);
            assertThat(count(restored, "SELECT COUNT(*) FROM outbox_event")).isEqualTo(1);
        }

        try (Connection live = dataSource.getConnection()) {
            assertThat(scalar(live, "SELECT status FROM turn WHERE id = '" + turnId.asString() + "'"))
                    .isEqualTo(TurnStatus.COMPLETED.name());
        }
    }

    @Test
    void backupBeforeMigrateWritesBesideLiveDatabase() throws Exception {
        assertThat(conversationStore.create(CreateConversationCommand.of(ConversationId.generate())))
                .isInstanceOf(CreateConversationResult.Created.class);
        backupBeforeMigrate.backupExistingSchema();
        try (var backups = Files.list(tempDataDir.resolve("backups"))) {
            Path directory =
                    backups.filter(Files::isDirectory).findFirst().orElseThrow();
            assertThat(directory.resolve("wannian.db")).isRegularFile();
            assertThat(directory.resolve("wannian.db").toAbsolutePath().normalize())
                    .isNotEqualTo(tempDataDir.resolve("wannian.db").toAbsolutePath().normalize());
            assertThat(directory.resolve("wannian-backup.json")).isRegularFile();
        }
    }

    @Test
    void retainCountDropsOldestSnapshotsAndLeavesUnrelatedDirectories() throws Exception {
        Path backups = tempDataDir.resolve("backups");
        if (Files.exists(backups)) {
            deleteRecursively(backups);
        }
        Path keep = backups.resolve("keep-me");
        Files.createDirectories(keep);
        Files.writeString(keep.resolve("readme.txt"), "not a snapshot");
        Path manual = backups.resolve("000-manual");
        Files.createDirectories(manual);
        Files.writeString(manual.resolve("wannian.db"), "not-owned");
        Files.writeString(manual.resolve("wannian-backup.json"), "{\"source\":\"hand\"}");
        Files.writeString(manual.resolve("notes.txt"), "keep this restore dir");

        backupBeforeMigrate.backupExistingSchema();
        Path oldest;
        try (var listed = Files.list(backups)) {
            oldest =
                    listed.filter(Files::isDirectory)
                            .filter(path -> path.getFileName().toString().startsWith("wn-snap-"))
                            .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString()))
                            .findFirst()
                            .orElseThrow();
        }
        backupBeforeMigrate.backupExistingSchema();
        backupBeforeMigrate.backupExistingSchema();
        backupBeforeMigrate.pruneOwnedSnapshots();

        assertThat(oldest).doesNotExist();
        assertThat(keep.resolve("readme.txt")).isRegularFile();
        assertThat(manual.resolve("wannian.db")).isRegularFile();
        assertThat(manual.resolve("notes.txt")).isRegularFile();
        assertThat(manual.resolve("wannian-backup.json")).isRegularFile();
        try (var listed = Files.list(backups)) {
            long snapshots =
                    listed.filter(Files::isDirectory)
                            .filter(path -> path.getFileName().toString().startsWith("wn-snap-"))
                            .count();
            assertThat(snapshots).isEqualTo(2);
        }
        assertThat(tempDataDir.resolve("wannian.db")).isRegularFile();
    }

    @Test
    void rejectsSnapshotThatWouldReplaceLiveDatabase() {
        assertThatThrownBy(() -> consistencyBackup.createSnapshot(tempDataDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止把快照写回");
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            List<java.nio.file.Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.delete(path);
            }
        }
    }

    private long advanceToCommitting(TurnId turnId, MessageId assistantMessageId, String contentJson) {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        ExecutionClaim claim = new ExecutionClaim("local-primary", now.plusSeconds(60));
        Turn turn = turnRepository.find(turnId).orElseThrow();
        long revision = turn.revision();
        turn.claim(revision, claim, now);
        assertThat(turnRepository.save(turn, revision, now)).isInstanceOf(SaveTurnResult.Saved.class);
        turn = turnRepository.find(turnId).orElseThrow();
        revision = turn.revision();
        turn.start(now);
        assertThat(turnRepository.save(turn, revision, now)).isInstanceOf(SaveTurnResult.Saved.class);
        turn = turnRepository.find(turnId).orElseThrow();
        revision = turn.revision();
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(
                                turnId,
                                revision,
                                "local-primary",
                                now,
                                new CommitTurnPlan.AssistantMessageDraft(
                                        assistantMessageId, MessageRole.ASSISTANT, contentJson, 0)));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);
        return ((FreezeCommitResult.Frozen) frozen).committingRevision();
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (ResultSet rs = connection.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
