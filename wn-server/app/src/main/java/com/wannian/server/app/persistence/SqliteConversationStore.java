package com.wannian.server.app.persistence;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.conversation.ConversationStatus;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.kernel.conversation.ConversationMessage;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.ConversationTitlePolicy;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * {@link ConversationStore} 的 SQLite 实现。
 *
 * <p>创建仅 INSERT；冲突不更新已有会话。receive 不得调用本类来「补建」会话。
 * 列近讯只读，不改写 {@code message} 正文。
 *
 * <p>未提供 title 时委托 {@link ConversationTitlePolicy} 生成默认名。
 */
@Component
public class SqliteConversationStore implements ConversationStore {

    private final DataSource dataSource;
    private final ConversationTitlePolicy titlePolicy;

    public SqliteConversationStore(DataSource dataSource, ConversationTitlePolicy titlePolicy) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.titlePolicy = Objects.requireNonNull(titlePolicy, "titlePolicy");
    }

    @Override
    public CreateConversationResult create(CreateConversationCommand command) {
        Objects.requireNonNull(command, "command");
        ConversationId id = command.conversationId();
        String now = Instant.now().toString();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String title =
                        command.title().isPresent()
                                ? command.title().get()
                                : titlePolicy.nextDefaultTitle(countConversations(connection));
                insert(connection, id, title, now);
                connection.commit();
                return new CreateConversationResult.Created(id);
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                if (isUniqueViolation(ex)) {
                    return new CreateConversationResult.AlreadyExists(id);
                }
                return new CreateConversationResult.Rejected(
                        ErrorCodes.PERSISTENCE_FAILED, "创建会话失败，id=" + id.asString());
            } catch (RuntimeException ex) {
                rollbackQuietly(connection);
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            return new CreateConversationResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, "无法打开数据库连接以创建会话");
        }
    }

    @Override
    public List<ConversationMessage> listRecentMessages(ConversationId conversationId, int limit) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 须为正");
        }

        String sql =
                """
                SELECT id, role, content_json, sequence_no
                FROM message
                WHERE conversation_id = ?
                ORDER BY sequence_no DESC
                LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId.asString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<ConversationMessage> newestFirst = new ArrayList<>();
                while (rs.next()) {
                    newestFirst.add(
                            new ConversationMessage(
                                    new MessageId(UUID.fromString(rs.getString("id"))),
                                    MessageRole.valueOf(rs.getString("role")),
                                    rs.getString("content_json"),
                                    rs.getInt("sequence_no")));
                }
                Collections.reverse(newestFirst);
                return List.copyOf(newestFirst);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "读取近讯失败，conversationId=" + conversationId.asString(), ex);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 调用方只看原失败对应的结果，不把回滚失败再抛出去。
        }
    }

    private static long countConversations(Connection connection) throws SQLException {
        try (ResultSet rs =
                connection.createStatement().executeQuery("SELECT COUNT(*) FROM conversation")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void insert(Connection connection, ConversationId id, String title, String now)
            throws SQLException {
        String sql =
                """
                INSERT INTO conversation (id, title, status, revision, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.asString());
            ps.setString(2, title);
            ps.setString(3, ConversationStatus.ACTIVE.name());
            ps.setString(4, now);
            ps.setString(5, now);
            ps.executeUpdate();
        }
    }

    private static boolean isUniqueViolation(SQLException ex) {
        return SqliteErrors.isUniqueViolation(ex);
    }
}
