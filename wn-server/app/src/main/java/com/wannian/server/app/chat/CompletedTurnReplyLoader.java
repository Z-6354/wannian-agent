package com.wannian.server.app.chat;

import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.agent.ContextAssembler;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 读取已完成回合的助手正文（HTTP 重放用）。
 *
 * <p>{@link com.wannian.server.kernel.turn.TurnEngine} 对 COMPLETED 只返回
 * {@code AlreadyCompleted}，不附带正文；本类从库中按 output_message_id 取回。
 */
@Component
public class CompletedTurnReplyLoader {

    private final DataSource dataSource;

    public CompletedTurnReplyLoader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** @return 助手正文；缺行或 envelope 非法时 empty */
    public Optional<String> loadText(TurnId turnId) {
        Objects.requireNonNull(turnId, "turnId");
        String contentJson = loadOutputContent(turnId.asString());
        if (contentJson == null || contentJson.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ContextAssembler.textOf(contentJson));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private String loadOutputContent(String turnId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT m.content_json
                                FROM turn t
                                JOIN message m ON m.id = t.output_message_id
                                WHERE t.id = ?
                                """)) {
            ps.setString(1, turnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (SQLException ex) {
            return null;
        }
    }
}
