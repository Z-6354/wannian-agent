package com.wannian.server.app.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * conversation_search FTS 同步（0.2.4-B）。
 *
 * <p>由 {@code SqliteTurnCommitter} 在同事务插入用户/助手消息后调用；改名/删会话由
 * {@link SqliteConversationStore} 调用。
 */
public final class ConversationSearchSync {

    private ConversationSearchSync() {}

    public static void upsertMessageRow(
            Connection connection,
            String conversationId,
            String messageId,
            String status,
            String title,
            String bodyText)
            throws SQLException {
        try (PreparedStatement del =
                connection.prepareStatement(
                        "DELETE FROM conversation_search WHERE message_id = ?")) {
            del.setString(1, messageId);
            del.executeUpdate();
        }
        try (PreparedStatement ins =
                connection.prepareStatement(
                        """
                        INSERT INTO conversation_search
                          (conversation_id, message_id, status, title, body)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
            ins.setString(1, conversationId);
            ins.setString(2, messageId);
            ins.setString(3, status);
            ins.setString(4, title == null ? "" : title);
            ins.setString(5, bodyText == null ? "" : bodyText);
            ins.executeUpdate();
        }
    }

    /** 改名后刷新该会话所有 FTS 行的 title / status。 */
    public static void refreshConversationMeta(
            Connection connection, String conversationId, String status, String title)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE conversation_search
                        SET status = ?, title = ?
                        WHERE conversation_id = ?
                        """)) {
            ps.setString(1, status);
            ps.setString(2, title == null ? "" : title);
            ps.setString(3, conversationId);
            ps.executeUpdate();
        }
    }

    public static void deleteConversation(Connection connection, String conversationId)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "DELETE FROM conversation_search WHERE conversation_id = ?")) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        }
    }
}
