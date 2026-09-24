package com.wannian.server.app.memory;

import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryReviewScheduler;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * SQLite Review 入队：INTERVAL 合并 PENDING；IDLE 按入队时活动水位去重全部历史状态。
 */
@Component
public class SqliteMemoryReviewScheduler implements MemoryReviewScheduler {

    private final DataSource dataSource;

    public SqliteMemoryReviewScheduler(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void enqueue(String conversationId, CompanionIdentity companionIdentity, Trigger trigger) {
        enqueue(conversationId, companionIdentity, trigger, null);
    }

    @Override
    public void enqueue(
            String conversationId,
            CompanionIdentity companionIdentity,
            Trigger trigger,
            String activityWatermark) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        Objects.requireNonNull(trigger, "trigger");
        String cid = conversationId.trim();
        if (cid.isEmpty()) {
            throw new IllegalArgumentException("conversationId 不得空白");
        }
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String watermark =
                        trigger == Trigger.IDLE
                                ? activityWatermark != null
                                        ? activityWatermark
                                        : loadActivityWatermark(connection, cid)
                                : null;
                if (hasDuplicate(connection, cid, trigger, watermark)) {
                    connection.commit();
                    return;
                }
                insertPending(
                        connection,
                        cid,
                        companionIdentity.value(),
                        trigger,
                        watermark,
                        now);
                connection.commit();
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                if (trigger == Trigger.IDLE && isActivityWatermarkConflict(ex)) {
                    return;
                }
                throw new IllegalStateException("enqueue Review 失败: " + cid, ex);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("无法打开数据库连接以 enqueue Review", ex);
        }
    }

    private static boolean hasDuplicate(
            Connection connection,
            String conversationId,
            Trigger trigger,
            String activityWatermark)
            throws SQLException {
                String statusFilter =
                trigger == Trigger.IDLE && activityWatermark != null
                        ? "AND (activity_watermark = ? OR (activity_watermark IS NULL AND status IN ('PENDING', 'RUNNING')))"
                        : "AND status = 'PENDING'";
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT 1 FROM memory_review_job
                        WHERE conversation_id = ? AND trigger = ? %s
                        LIMIT 1
                        """.formatted(statusFilter))) {
            ps.setString(1, conversationId);
            ps.setString(2, trigger.name());
            if (trigger == Trigger.IDLE && activityWatermark != null) {
                ps.setString(3, activityWatermark);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String loadActivityWatermark(Connection connection, String conversationId)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT last_activity_at FROM conversation WHERE id = ?")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void insertPending(
            Connection connection,
            String conversationId,
            String companionId,
            Trigger trigger,
            String activityWatermark,
            Instant now)
            throws SQLException {
        String nowText = now.toString();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        INSERT INTO memory_review_job (
                            id, conversation_id, companion_id, trigger, status,
                            attempt, lease_owner, lease_until, last_error, activity_watermark,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'PENDING', 0, NULL, NULL, NULL, ?, ?, ?)
                        """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, conversationId);
            ps.setString(3, companionId);
            ps.setString(4, trigger.name());
            ps.setString(5, activityWatermark);
            ps.setString(6, nowText);
            ps.setString(7, nowText);
            ps.executeUpdate();
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 调用方看主失败
        }
    }

    private static boolean isActivityWatermarkConflict(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if (current.getErrorCode() == 19
                    && current.getMessage() != null
                    && current.getMessage().contains("activity_watermark")) {
                return true;
            }
        }
        return false;
    }
}
