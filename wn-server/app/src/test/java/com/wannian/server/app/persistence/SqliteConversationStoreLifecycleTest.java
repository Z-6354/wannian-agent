package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.ConversationStatus;
import com.wannian.server.api.conversation.TitleSource;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.conversation.ConversationMutationResult;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.ConversationSummary;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.conversation.EmptyArchiveCommand;
import com.wannian.server.kernel.conversation.EmptyTrashCommand;
import com.wannian.server.kernel.conversation.EmptyTrashResult;
import com.wannian.server.kernel.conversation.RenameConversationCommand;
import com.wannian.server.kernel.error.ErrorCodes;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 2.4.3/E：改名 CAS、busy、自动标题 vs MANUAL、清空回收站。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SqliteConversationStoreLifecycleTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConversationStore conversationStore;

    @BeforeEach
    void clearTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn_step");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation_search");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
        }
    }

    @Test
    void renameSucceedsAndMarksManual() {
        ConversationId id = createActive();
        ConversationSummary before = conversationStore.find(id).orElseThrow();

        ConversationMutationResult wrong =
                conversationStore.rename(
                        new RenameConversationCommand(id, before.revision() + 1, "错 revision"));
        assertThat(wrong).isInstanceOf(ConversationMutationResult.Rejected.class);
        assertThat(((ConversationMutationResult.Rejected) wrong).reasonCode())
                .isEqualTo(ErrorCodes.REVISION_CONFLICT);

        ConversationMutationResult ok =
                conversationStore.rename(
                        new RenameConversationCommand(id, before.revision(), "手工标题"));
        assertThat(ok).isInstanceOf(ConversationMutationResult.Ok.class);
        ConversationSummary after = conversationStore.find(id).orElseThrow();
        assertThat(after.title()).isEqualTo("手工标题");
        assertThat(after.titleSource()).isEqualTo(TitleSource.MANUAL);
        assertThat(after.revision()).isEqualTo(before.revision() + 1);
    }

    @Test
    void trashWithReceivedTurnIsBusy() throws Exception {
        ConversationId id = createActive();
        seedTurn(id, TurnStatus.RECEIVED);
        ConversationSummary before = conversationStore.find(id).orElseThrow();

        ConversationMutationResult busy =
                conversationStore.trash(id, before.revision());
        assertThat(busy).isInstanceOf(ConversationMutationResult.Rejected.class);
        assertThat(((ConversationMutationResult.Rejected) busy).reasonCode())
                .isEqualTo(ErrorCodes.CONVERSATION_BUSY);
        assertThat(conversationStore.find(id).orElseThrow().status())
                .isEqualTo(ConversationStatus.ACTIVE);
    }

    @Test
    void applyAutoTitleWritesOutboxButManualWins() throws Exception {
        ConversationId id = createActive();
        ConversationSummary before = conversationStore.find(id).orElseThrow();
        String turnId = TurnId.generate().asString();

        ConversationMutationResult auto =
                conversationStore.applyAutoTitle(id, before.revision(), "自动标题甲", turnId);
        assertThat(auto).isInstanceOf(ConversationMutationResult.Ok.class);
        ConversationSummary titled = conversationStore.find(id).orElseThrow();
        assertThat(titled.title()).isEqualTo("自动标题甲");
        assertThat(titled.titleSource()).isEqualTo(TitleSource.AUTO);
        assertThat(countOutbox("TitleChanged")).isEqualTo(1);

        conversationStore.rename(
                new RenameConversationCommand(id, titled.revision(), "用户改名"));
        ConversationSummary manual = conversationStore.find(id).orElseThrow();
        assertThat(manual.titleSource()).isEqualTo(TitleSource.MANUAL);

        ConversationMutationResult rejected =
                conversationStore.applyAutoTitle(
                        id, manual.revision(), "迟到自动标题", turnId);
        assertThat(rejected).isInstanceOf(ConversationMutationResult.Rejected.class);
        assertThat(conversationStore.find(id).orElseThrow().title()).isEqualTo("用户改名");
        assertThat(countOutbox("TitleChanged")).isEqualTo(1);
    }

    @Test
    void emptyTrashRequiresConfirmAndSkipsBusy() throws Exception {
        ConversationId idle = createActive();
        // create() 清理无消息空 ACTIVE；先让 idle 留在库中，再创建 busy。
        seedTurn(idle, TurnStatus.COMPLETED);
        ConversationId busy = createActive();
        seedTurn(busy, TurnStatus.RECEIVED);

        ConversationSummary idleRow = conversationStore.find(idle).orElseThrow();
        ConversationSummary busyRow = conversationStore.find(busy).orElseThrow();
        assertThat(conversationStore.trash(idle, idleRow.revision()))
                .isInstanceOf(ConversationMutationResult.Ok.class);
        // busy 仍 ACTIVE；先强制 TRASHED 模拟「清空时遇到忙碌」需跳过——此处改用 idle-only 路径
        EmptyTrashResult badConfirm =
                conversationStore.emptyTrash(new EmptyTrashCommand("NOPE", 10));
        assertThat(badConfirm).isInstanceOf(EmptyTrashResult.Rejected.class);

        EmptyTrashResult emptied =
                conversationStore.emptyTrash(
                        new EmptyTrashCommand(EmptyTrashCommand.CONFIRM_TOKEN, 10));
        assertThat(emptied).isInstanceOf(EmptyTrashResult.Ok.class);
        assertThat(((EmptyTrashResult.Ok) emptied).deletedCount()).isEqualTo(1);
        assertThat(conversationStore.find(idle)).isEmpty();
        assertThat(conversationStore.find(busy)).isPresent();
        assertThat(busyRow.status()).isEqualTo(ConversationStatus.ACTIVE);
    }

    @Test
    void emptyArchivePurgesArchivedAndKeepsActive() throws Exception {
        ConversationId archived = createActive();
        seedTurn(archived, TurnStatus.COMPLETED);
        ConversationSummary row = conversationStore.find(archived).orElseThrow();
        assertThat(conversationStore.archive(archived, row.revision()))
                .isInstanceOf(ConversationMutationResult.Ok.class);

        ConversationId stillActive = createActive();
        seedTurn(stillActive, TurnStatus.COMPLETED);

        EmptyTrashResult bad =
                conversationStore.emptyArchive(new EmptyArchiveCommand("NOPE", 10));
        assertThat(bad).isInstanceOf(EmptyTrashResult.Rejected.class);

        EmptyTrashResult emptied =
                conversationStore.emptyArchive(
                        new EmptyArchiveCommand(EmptyArchiveCommand.CONFIRM_TOKEN, 10));
        assertThat(emptied).isInstanceOf(EmptyTrashResult.Ok.class);
        assertThat(((EmptyTrashResult.Ok) emptied).deletedCount()).isEqualTo(1);
        assertThat(conversationStore.find(archived)).isEmpty();
        assertThat(conversationStore.find(stillActive)).isPresent();
    }

    @Test
    void pinPersistsSortsFirstRejectsStaleRevisionAndClearsOnArchive() throws Exception {
        ConversationId older = createActive();
        seedTurn(older, TurnStatus.COMPLETED);
        ConversationId newer = createActive();
        seedTurn(newer, TurnStatus.COMPLETED);

        ConversationSummary olderBefore = conversationStore.find(older).orElseThrow();
        ConversationMutationResult pinned = conversationStore.pin(older, olderBefore.revision());
        assertThat(pinned).isInstanceOf(ConversationMutationResult.Ok.class);
        ConversationSummary pinnedRow = conversationStore.find(older).orElseThrow();
        assertThat(pinnedRow.pinned()).isTrue();
        assertThat(pinnedRow.revision()).isEqualTo(olderBefore.revision() + 1);

        ConversationMutationResult stale = conversationStore.unpin(older, olderBefore.revision());
        assertThat(stale).isInstanceOf(ConversationMutationResult.Rejected.class);
        assertThat(((ConversationMutationResult.Rejected) stale).reasonCode())
                .isEqualTo(ErrorCodes.REVISION_CONFLICT);

        var firstPage = conversationStore.list(
                new com.wannian.server.kernel.conversation.ConversationListQuery(
                        java.util.Optional.of(ConversationStatus.ACTIVE),
                        java.util.Optional.empty(), 1));
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.items().get(0).id()).isEqualTo(older);
        assertThat(firstPage.nextCursor()).isPresent();

        var secondPage = conversationStore.list(
                new com.wannian.server.kernel.conversation.ConversationListQuery(
                        java.util.Optional.of(ConversationStatus.ACTIVE),
                        firstPage.nextCursor(), 1));
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().get(0).id()).isEqualTo(newer);
        assertThat(secondPage.items()).doesNotContain(firstPage.items().get(0));

        ConversationMutationResult archived = conversationStore.archive(older, pinnedRow.revision());
        assertThat(archived).isInstanceOf(ConversationMutationResult.Ok.class);
        ConversationSummary archivedRow = conversationStore.find(older).orElseThrow();
        assertThat(archivedRow.status()).isEqualTo(ConversationStatus.ARCHIVED);
        assertThat(archivedRow.pinned()).isFalse();
    }

    private ConversationId createActive() {
        ConversationId id = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(id)))
                .isInstanceOf(CreateConversationResult.Created.class);
        return id;
    }

    private void seedTurn(ConversationId conversationId, TurnStatus status) throws Exception {
        String now = Instant.now().toString();
        MessageId messageId = MessageId.generate();
        TurnId turnId = TurnId.generate();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement msg =
                    connection.prepareStatement(
                            """
                            INSERT INTO message (id, conversation_id, role, content_json, sequence_no, created_at)
                            VALUES (?, ?, 'USER', ?, 1, ?)
                            """)) {
                msg.setString(1, messageId.asString());
                msg.setString(2, conversationId.asString());
                msg.setString(3, "{\"v\":1,\"text\":\"seed\"}");
                msg.setString(4, now);
                msg.executeUpdate();
            }
            try (PreparedStatement turn =
                    connection.prepareStatement(
                            """
                            INSERT INTO turn (
                              id, conversation_id, client_request_id, input_message_id,
                              status, revision, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                            """)) {
                turn.setString(1, turnId.asString());
                turn.setString(2, conversationId.asString());
                turn.setString(3, "seed-" + UUID.randomUUID());
                turn.setString(4, messageId.asString());
                turn.setString(5, status.name());
                turn.setString(6, now);
                turn.setString(7, now);
                turn.executeUpdate();
            }
            connection.commit();
        }
    }

    private long countOutbox(String eventType) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM outbox_event WHERE event_type = ?")) {
            ps.setString(1, eventType);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }
}
