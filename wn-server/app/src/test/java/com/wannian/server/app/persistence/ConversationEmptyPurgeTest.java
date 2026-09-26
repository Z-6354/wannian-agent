package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
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

/** 0.2.4-G：空会话可清；有消息的会话保留。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConversationEmptyPurgeTest {

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
    void clear() throws Exception {
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
    void purgeRemovesEmptyActiveOnly() throws Exception {
        ConversationId filled = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(filled)))
                .isInstanceOf(CreateConversationResult.Created.class);
        String now = Instant.now().toString();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            """
                            INSERT INTO message (id, conversation_id, role, content_json, sequence_no, created_at)
                            VALUES (?, ?, 'USER', '{"v":1,"text":"hi"}', 1, ?)
                            """)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, filled.asString());
                ps.setString(3, now);
                ps.executeUpdate();
            }
            // 绕过 create()，直接插空会话（模拟历史空壳）
            ConversationId empty = ConversationId.generate();
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            """
                            INSERT INTO conversation
                              (id, title, status, revision, created_at, updated_at, title_source, last_activity_at)
                            VALUES (?, '空壳', 'ACTIVE', 1, ?, ?, 'AUTO', ?)
                            """)) {
                ps.setString(1, empty.asString());
                ps.setString(2, now);
                ps.setString(3, now);
                ps.setString(4, now);
                ps.executeUpdate();
            }

            int deleted = conversationStore.purgeEmptyActiveConversations(20);
            assertThat(deleted).isEqualTo(1);
            assertThat(exists(empty)).isFalse();
            assertThat(exists(filled)).isTrue();
        }
    }

    private boolean exists(ConversationId id) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT 1 FROM conversation WHERE id = ?")) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
