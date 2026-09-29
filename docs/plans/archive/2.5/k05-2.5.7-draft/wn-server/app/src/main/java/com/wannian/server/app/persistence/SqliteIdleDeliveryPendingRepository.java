package com.wannian.server.app.persistence;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.IdleDeliveryId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.IdleDeliveryPending;
import com.wannian.server.kernel.task.IdleDeliveryPendingRepository;
import com.wannian.server.kernel.task.IdleDeliveryStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** {@link IdleDeliveryPendingRepository} SQLite 实现（V027）。 */
public final class SqliteIdleDeliveryPendingRepository implements IdleDeliveryPendingRepository {

    private final DataSource dataSource;

    public SqliteIdleDeliveryPendingRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void insert(IdleDeliveryPending pending) {
        Objects.requireNonNull(pending, "pending");
        String sql =
                """
                INSERT INTO idle_delivery_pending(
                    id, conversation_id, task_id, terminal_status,
                    payload_json, status, created_at, delivered_at, wake_turn_id
                ) VALUES (?,?,?,?,?,?,?,?,?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            bindRow(ps, pending);
            ps.executeUpdate();
        } catch (SQLException ex) {
            if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("unique")) {
                return;
            }
            throw new IllegalStateException("insert idle_delivery_pending 失败", ex);
        }
    }

    @Override
    public Optional<IdleDeliveryPending> findByTaskTerminal(
            BackgroundTaskId taskId, BackgroundTaskStatus terminalStatus) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        String sql =
                """
                SELECT id, conversation_id, task_id, terminal_status,
                       payload_json, status, created_at, delivered_at, wake_turn_id
                FROM idle_delivery_pending
                WHERE task_id = ? AND terminal_status = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskId.asString());
            ps.setString(2, terminalStatus.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("find idle_delivery_pending 失败", ex);
        }
    }

    @Override
    public Optional<IdleDeliveryPending> claimNext(Instant now) {
        Objects.requireNonNull(now, "now");
        String select =
                """
                SELECT id, conversation_id, task_id, terminal_status,
                       payload_json, status, created_at, delivered_at, wake_turn_id
                FROM idle_delivery_pending
                WHERE status = ?
                ORDER BY created_at ASC
                LIMIT 1
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                IdleDeliveryPending candidate;
                try (PreparedStatement ps = connection.prepareStatement(select)) {
                    ps.setString(1, IdleDeliveryStatus.QUEUED.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                        candidate = mapRow(rs);
                    }
                }
                String update =
                        """
                        UPDATE idle_delivery_pending
                        SET status = ?
                        WHERE id = ? AND status = ?
                        """;
                try (PreparedStatement ps = connection.prepareStatement(update)) {
                    ps.setString(1, IdleDeliveryStatus.DISPATCHING.name());
                    ps.setString(2, candidate.deliveryId().asString());
                    ps.setString(3, IdleDeliveryStatus.QUEUED.name());
                    if (ps.executeUpdate() != 1) {
                        connection.rollback();
                        return Optional.empty();
                    }
                }
                connection.commit();
                return Optional.of(
                        new IdleDeliveryPending(
                                candidate.deliveryId(),
                                candidate.conversationId(),
                                candidate.taskId(),
                                candidate.terminalStatus(),
                                candidate.payloadJson(),
                                IdleDeliveryStatus.DISPATCHING,
                                candidate.createdAt(),
                                candidate.deliveredAt(),
                                candidate.wakeTurnId()));
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("claimNext idle_delivery_pending 失败", ex);
        }
    }

    @Override
    public boolean casRequeue(IdleDeliveryId id) {
        Objects.requireNonNull(id, "id");
        String sql =
                """
                UPDATE idle_delivery_pending
                SET status = ?, wake_turn_id = NULL
                WHERE id = ? AND status = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, IdleDeliveryStatus.QUEUED.name());
            ps.setString(2, id.asString());
            ps.setString(3, IdleDeliveryStatus.DISPATCHING.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("casRequeue idle_delivery_pending 失败", ex);
        }
    }

    @Override
    public boolean attachWakeTurn(IdleDeliveryId id, TurnId wakeTurnId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(wakeTurnId, "wakeTurnId");
        String sql =
                """
                UPDATE idle_delivery_pending
                SET wake_turn_id = ?
                WHERE id = ? AND status = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, wakeTurnId.asString());
            ps.setString(2, id.asString());
            ps.setString(3, IdleDeliveryStatus.DISPATCHING.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("attachWakeTurn idle_delivery_pending 失败", ex);
        }
    }

    @Override
    public Optional<IdleDeliveryPending> findByWakeTurnId(TurnId wakeTurnId) {
        Objects.requireNonNull(wakeTurnId, "wakeTurnId");
        String sql =
                """
                SELECT id, conversation_id, task_id, terminal_status,
                       payload_json, status, created_at, delivered_at, wake_turn_id
                FROM idle_delivery_pending
                WHERE wake_turn_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, wakeTurnId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("findByWakeTurnId idle_delivery_pending 失败", ex);
        }
    }

    @Override
    public boolean casDelivered(IdleDeliveryId id, TurnId wakeTurnId, Instant deliveredAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(wakeTurnId, "wakeTurnId");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        String sql =
                """
                UPDATE idle_delivery_pending
                SET status = ?, delivered_at = ?, wake_turn_id = ?
                WHERE id = ? AND status = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, IdleDeliveryStatus.DELIVERED.name());
            ps.setString(2, deliveredAt.toString());
            ps.setString(3, wakeTurnId.asString());
            ps.setString(4, id.asString());
            ps.setString(5, IdleDeliveryStatus.DISPATCHING.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("casDelivered idle_delivery_pending 失败", ex);
        }
    }

    @Override
    public boolean casRequeueByWakeTurn(TurnId wakeTurnId) {
        Objects.requireNonNull(wakeTurnId, "wakeTurnId");
        String sql =
                """
                UPDATE idle_delivery_pending
                SET status = ?, wake_turn_id = NULL
                WHERE wake_turn_id = ? AND status = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, IdleDeliveryStatus.QUEUED.name());
            ps.setString(2, wakeTurnId.asString());
            ps.setString(3, IdleDeliveryStatus.DISPATCHING.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("casRequeueByWakeTurn 失败", ex);
        }
    }

    @Override
    public boolean casCancelledByWakeTurn(TurnId wakeTurnId) {
        Objects.requireNonNull(wakeTurnId, "wakeTurnId");
        String sql =
                """
                UPDATE idle_delivery_pending
                SET status = ?
                WHERE wake_turn_id = ? AND status = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, IdleDeliveryStatus.CANCELLED.name());
            ps.setString(2, wakeTurnId.asString());
            ps.setString(3, IdleDeliveryStatus.DISPATCHING.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("casCancelledByWakeTurn 失败", ex);
        }
    }

    private static void bindRow(PreparedStatement ps, IdleDeliveryPending pending)
            throws SQLException {
        ps.setString(1, pending.deliveryId().asString());
        ps.setString(2, pending.conversationId().asString());
        ps.setString(3, pending.taskId().asString());
        ps.setString(4, pending.terminalStatus().name());
        ps.setString(5, pending.payloadJson());
        ps.setString(6, pending.status().name());
        ps.setString(7, pending.createdAt().toString());
        if (pending.deliveredAt() == null) {
            ps.setNull(8, Types.VARCHAR);
        } else {
            ps.setString(8, pending.deliveredAt().toString());
        }
        if (pending.wakeTurnId() == null) {
            ps.setNull(9, Types.VARCHAR);
        } else {
            ps.setString(9, pending.wakeTurnId().asString());
        }
    }

    private static IdleDeliveryPending mapRow(ResultSet rs) throws SQLException {
        String wake = rs.getString("wake_turn_id");
        String delivered = rs.getString("delivered_at");
        return new IdleDeliveryPending(
                IdleDeliveryId.parse(rs.getString("id")),
                new ConversationId(UUID.fromString(rs.getString("conversation_id"))),
                new BackgroundTaskId(UUID.fromString(rs.getString("task_id"))),
                BackgroundTaskStatus.valueOf(rs.getString("terminal_status")),
                rs.getString("payload_json"),
                IdleDeliveryStatus.valueOf(rs.getString("status")),
                Instant.parse(rs.getString("created_at")),
                delivered == null || delivered.isBlank() ? null : Instant.parse(delivered),
                wake == null || wake.isBlank() ? null : new TurnId(UUID.fromString(wake)));
    }
}
