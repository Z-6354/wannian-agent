package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.conversation.ConversationStatus;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * K02 1B-5a：显式新建会话验收（含默认标题 会话N）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CreateConversationTest {

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
            connection.createStatement().executeUpdate("DELETE FROM conversation");
        }
    }

    @Test
    void createWithExplicitTitlePersistsAsGiven() throws Exception {
        ConversationId id = ConversationId.generate();

        CreateConversationResult result =
                conversationStore.create(
                        new CreateConversationCommand(id, Optional.of("测试会话")));

        assertThat(result).isInstanceOf(CreateConversationResult.Created.class);
        assertThat(loadTitle(id)).isEqualTo("测试会话");
        assertThat(loadStatus(id)).isEqualTo(ConversationStatus.ACTIVE.name());
        assertThat(loadRevision(id)).isEqualTo(1L);
    }

    @Test
    void emptyDatabaseDefaultsToSessionOneThenTwo() throws Exception {
        ConversationId first = ConversationId.generate();
        ConversationId second = ConversationId.generate();

        assertThat(conversationStore.create(CreateConversationCommand.of(first)))
                .isInstanceOf(CreateConversationResult.Created.class);
        assertThat(loadTitle(first)).isEqualTo("会话1");

        assertThat(conversationStore.create(CreateConversationCommand.of(second)))
                .isInstanceOf(CreateConversationResult.Created.class);
        assertThat(loadTitle(second)).isEqualTo("会话2");
    }

    @Test
    void duplicateIdReturnsAlreadyExistsWithoutOverwrite() throws Exception {
        ConversationId id = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(id)))
                .isInstanceOf(CreateConversationResult.Created.class);
        assertThat(loadTitle(id)).isEqualTo("会话1");

        CreateConversationResult second =
                conversationStore.create(
                        new CreateConversationCommand(id, Optional.of("不应写入")));

        assertThat(second).isInstanceOf(CreateConversationResult.AlreadyExists.class);
        assertThat(loadTitle(id)).isEqualTo("会话1");
        assertThat(countConversations()).isEqualTo(1);
    }

    private String loadTitle(ConversationId id) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT title FROM conversation WHERE id = ?")) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private String loadStatus(ConversationId id) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT status FROM conversation WHERE id = ?")) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private long loadRevision(ConversationId id) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT revision FROM conversation WHERE id = ?")) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    private int countConversations() throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs =
                        connection.createStatement().executeQuery("SELECT COUNT(*) FROM conversation")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
