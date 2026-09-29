package com.wannian.server.app.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * conversation_search FTS 同步（2.4.3）。
 *
 * <p>由 {@code SqliteTurnCommitter} 在同事务插入用户/助手消息后调用；改名/删会话由
 * {@link SqliteConversationStore} 调用。
 */
public final class ConversationSearchSync {

    /** 仅标题占位行（建会话时写入）；须按 conversation_id 限定删除。 */
    public static final String TITLE_PLACEHOLDER_MESSAGE_ID = "";

    private ConversationSearchSync() {}

    public static void upsertMessageRow(
            Connection connection,
            String conversationId,
            String messageId,
            String status,
            String title,
            String bodyText)
            throws SQLException {
        String mid = messageId == null ? TITLE_PLACEHOLDER_MESSAGE_ID : messageId;
        // 正式消息到来时去掉本会话标题占位，避免双行命中
        if (!TITLE_PLACEHOLDER_MESSAGE_ID.equals(mid)) {
            deleteRow(connection, conversationId, TITLE_PLACEHOLDER_MESSAGE_ID);
        }
        deleteRow(connection, conversationId, mid);
        try (PreparedStatement ins =
                connection.prepareStatement(
                        """
                        INSERT INTO conversation_search
                          (conversation_id, message_id, status, title, body)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
            ins.setString(1, conversationId);
            ins.setString(2, mid);
            ins.setString(3, status);
            ins.setString(4, title == null ? "" : title);
            ins.setString(5, bodyText == null ? "" : bodyText);
            ins.executeUpdate();
        }
    }

    private static void deleteRow(Connection connection, String conversationId, String messageId)
            throws SQLException {
        try (PreparedStatement del =
                connection.prepareStatement(
                        """
                        DELETE FROM conversation_search
                        WHERE conversation_id = ? AND message_id = ?
                        """)) {
            del.setString(1, conversationId);
            del.setString(2, messageId);
            del.executeUpdate();
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
