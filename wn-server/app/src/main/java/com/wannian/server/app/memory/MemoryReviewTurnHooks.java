package com.wannian.server.app.memory;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryReviewConstants;
import com.wannian.server.kernel.memory.MemoryReviewScheduler;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Turn 完成后的非阻塞钩子：按 USER 完成回数 % 10 enqueue INTERVAL。
 *
 * <p>{@code last_activity_at} 由 {@code SqliteTurnCommitter} 同事务更新。
 */
@Component
public class MemoryReviewTurnHooks {

    private static final System.Logger LOG = System.getLogger(MemoryReviewTurnHooks.class.getName());

    private final DataSource dataSource;
    private final MemoryReviewScheduler scheduler;

    public MemoryReviewTurnHooks(DataSource dataSource, MemoryReviewScheduler scheduler) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * 回合已成功 COMPLETED 后调用；异常只记日志，不抛给聊天路径。
     */
    public void afterTurnCompleted(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        try {
            long completedUserTurns = countCompletedTurns(conversationId.asString());
            if (completedUserTurns > 0
                    && completedUserTurns % MemoryReviewConstants.INTERVAL_USER_TURNS == 0) {
                scheduler.enqueue(
                        conversationId.asString(),
                        CompanionIdentity.YANHUO,
                        MemoryReviewScheduler.Trigger.INTERVAL);
            }
        } catch (RuntimeException ex) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () ->
                            "INTERVAL enqueue 失败 conversation="
                                    + conversationId.asString()
                                    + " msg="
                                    + ex.getMessage());
        }
    }

    private long countCompletedTurns(String conversationId) {
        String sql =
                """
                SELECT COUNT(*)
                FROM turn
                WHERE conversation_id = ? AND status = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, TurnStatus.COMPLETED.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("统计完成回合失败", ex);
        }
    }

    /** 测试辅助：按 UUID 字符串触发。 */
    public void afterTurnCompleted(String conversationId) {
        afterTurnCompleted(new ConversationId(UUID.fromString(conversationId)));
    }
}
