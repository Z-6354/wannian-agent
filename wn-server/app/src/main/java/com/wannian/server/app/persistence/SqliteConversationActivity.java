package com.wannian.server.app.persistence;

import com.wannian.server.api.common.ConversationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 会话是否存在进行中/排队 Turn（0.2.4-B 归档/回收站门禁）。
 *
 * <p>口径：status ∈ RECEIVED, CLAIMED, RUNNING, COMMITTING。
 */
@Component
public class SqliteConversationActivity {

    private final DataSource dataSource;

    public SqliteConversationActivity(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public boolean hasActiveTurn(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        try (Connection connection = dataSource.getConnection()) {
            return hasActiveTurn(connection, conversationId.asString());
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "查询会话活动 Turn 失败: " + conversationId.asString(), ex);
        }
    }

    /** 供同事务调用（清空回收站 / 生命周期）。 */
    public static boolean hasActiveTurn(Connection connection, String conversationId)
            throws SQLException {
        String sql =
                """
                SELECT 1 FROM turn
                WHERE conversation_id = ?
                  AND status IN ('RECEIVED','CLAIMED','RUNNING','COMMITTING')
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
