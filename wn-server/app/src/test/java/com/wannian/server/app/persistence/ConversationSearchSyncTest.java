package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.kernel.conversation.ConversationSearchQuery;
import com.wannian.server.kernel.conversation.ConversationSearchResult;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 2.4.3 FTS：标题占位按会话隔离；创建会话不得互删其它会话的空 message_id 行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConversationSearchSyncTest {

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
    void createSecondConversationDoesNotWipeFirstTitleSearchRow() throws Exception {
        ConversationId first = ConversationId.generate();
        ConversationId second = ConversationId.generate();

        assertThat(conversationStore.create(CreateConversationCommand.of(first)))
                .isInstanceOf(CreateConversationResult.Created.class);
        // 有消息则不会被 G 的「新建清空壳」清掉
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                INSERT INTO message (id, conversation_id, role, content_json, sequence_no, created_at)
                                VALUES (?, ?, 'USER', '{"v":1,"text":"seed"}', 1, ?)
                                """)) {
            ps.setString(1, java.util.UUID.randomUUID().toString());
            ps.setString(2, first.asString());
            ps.setString(3, java.time.Instant.now().toString());
            ps.executeUpdate();
        }

        assertThat(conversationStore.create(CreateConversationCommand.of(second)))
                .isInstanceOf(CreateConversationResult.Created.class);

        assertThat(countSearchRows(first)).isEqualTo(1);
        assertThat(countSearchRows(second)).isEqualTo(1);

        ConversationSearchResult hits =
                conversationStore.search(
                        new ConversationSearchQuery(
                                "会话1",
                                java.util.Optional.empty(),
                                java.util.Optional.empty(),
                                20));
        assertThat(hits.hits()).isNotEmpty();
        assertThat(hits.hits().stream().anyMatch(h -> h.conversationId().equals(first))).isTrue();
    }

    @Test
    void upsertRealMessageRemovesTitlePlaceholderForSameConversation() throws Exception {
        ConversationId id = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(id)))
                .isInstanceOf(CreateConversationResult.Created.class);
        assertThat(countSearchRows(id)).isEqualTo(1);
        assertThat(countPlaceholderRows(id)).isEqualTo(1);

        String messageId = java.util.UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            ConversationSearchSync.upsertMessageRow(
                    connection,
                    id.asString(),
                    messageId,
                    "ACTIVE",
                    "会话1",
                    "你好世界");
            connection.commit();
        }

        assertThat(countPlaceholderRows(id)).isEqualTo(0);
        assertThat(countSearchRows(id)).isEqualTo(1);
        ConversationSearchResult hits =
                conversationStore.search(
                        new ConversationSearchQuery(
                                "你好世界",
                                java.util.Optional.empty(),
                                java.util.Optional.empty(),
                                20));
        assertThat(hits.hits().stream().anyMatch(h -> h.conversationId().equals(id))).isTrue();
    }

    private long countSearchRows(ConversationId id) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM conversation_search WHERE conversation_id = ?")) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    private long countPlaceholderRows(ConversationId id) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT COUNT(*) FROM conversation_search
                                WHERE conversation_id = ? AND message_id = ?
                                """)) {
            ps.setString(1, id.asString());
            ps.setString(2, ConversationSearchSync.TITLE_PLACEHOLDER_MESSAGE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }
}
