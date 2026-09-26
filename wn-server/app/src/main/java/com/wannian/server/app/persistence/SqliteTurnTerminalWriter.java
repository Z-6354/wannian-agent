package com.wannian.server.app.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnTerminalWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 终态 CANCELLED / FAILED 与 Outbox 同事务写入。
 */
@Component
public class SqliteTurnTerminalWriter implements TurnTerminalWriter {

    static final String TURN_CANCELLED = "TurnCancelled";
    static final String TURN_FAILED = "TurnFailed";
    private static final String AGGREGATE_TURN = "turn";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteTurnTerminalWriter(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public SaveTurnResult saveCancelled(Turn turn, long expectedRevision, Instant now) {
        return saveTerminal(turn, expectedRevision, now, TURN_CANCELLED, null);
    }

    @Override
    public SaveTurnResult saveFailed(Turn turn, long expectedRevision, Instant now) {
        return saveTerminal(turn, expectedRevision, now, TURN_FAILED, turn.errorCode());
    }

    private SaveTurnResult saveTerminal(
            Turn turn, long expectedRevision, Instant now, String eventType, String errorCode) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(now, "now");
        if (turn.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException(
                    "saveTerminal 只持久化一次领域迁移：expectedRevision="
                            + expectedRevision
                            + "，turn.revision="
                            + turn.revision());
        }
        if (turn.status() != TurnStatus.CANCELLED && turn.status() != TurnStatus.FAILED) {
            return new SaveTurnResult.Rejected(
                    turn.id(),
                    ErrorCodes.ILLEGAL_ARGUMENT,
                    "终态写入只接受 CANCELLED/FAILED，实际 " + turn.status());
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                StoredRow current = load(connection, turn.id().asString());
                if (current == null) {
                    connection.rollback();
                    return new SaveTurnResult.NotFound(turn.id());
                }
                if (current.revision() != expectedRevision) {
                    connection.rollback();
                    return new SaveTurnResult.RevisionConflict(turn.id(), current.revision());
                }
                if (!isLegal(current.status(), turn.status())) {
                    connection.rollback();
                    return new SaveTurnResult.Rejected(
                            turn.id(),
                            ErrorCodes.ILLEGAL_TRANSITION,
                            "不能从 " + current.status() + " 写成 " + turn.status());
                }
                int updated = updateTurn(connection, turn, current, expectedRevision, now);
                if (updated != 1) {
                    connection.rollback();
                    StoredRow again = load(connection, turn.id().asString());
                    if (again == null) {
                        return new SaveTurnResult.NotFound(turn.id());
                    }
                    return new SaveTurnResult.RevisionConflict(turn.id(), again.revision());
                }
                insertOutbox(
                        connection,
                        turn,
                        eventType,
                        errorCode == null ? turn.errorCode() : errorCode,
                        now.toString());
                connection.commit();
                return new SaveTurnResult.Saved(turn.id(), turn.revision());
            } catch (SQLException | RuntimeException ex) {
                rollbackQuietly(connection);
                return new SaveTurnResult.Rejected(
                        turn.id(),
                        ErrorCodes.PERSISTENCE_FAILED,
                        "终态写入失败: " + turn.id().asString());
            }
        } catch (SQLException ex) {
            return new SaveTurnResult.Rejected(
                    turn.id(),
                    ErrorCodes.PERSISTENCE_FAILED,
                    "终态写入失败: " + turn.id().asString());
        }
    }

    private void insertOutbox(
            Connection connection, Turn turn, String eventType, String errorCode, String now)
            throws SQLException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("v", 1);
        payload.put("turnId", turn.id().asString());
        payload.put("conversationId", turn.conversationId().asString());
        if (turn.executionId() != null) {
            payload.put("executionId", turn.executionId());
        }
        if (errorCode != null && !errorCode.isBlank()) {
            payload.put("errorCode", errorCode);
        }
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new SQLException("无法序列化终态 Outbox", ex);
        }
        long sequence = allocateOutboxSequence(connection);
        String sql =
                """
                INSERT INTO outbox_event (
                    id, aggregate_type, aggregate_id, event_type, payload_json, sequence_no, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, AGGREGATE_TURN);
            ps.setString(3, turn.id().asString());
            ps.setString(4, eventType);
            ps.setString(5, payloadJson);
            ps.setLong(6, sequence);
            ps.setString(7, now);
            ps.executeUpdate();
        }
    }

    private static long allocateOutboxSequence(Connection connection) throws SQLException {
        String sql =
                """
                UPDATE sequence_counter
                SET next_value = next_value + 1
                WHERE name = 'outbox'
                RETURNING next_value - 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("outbox 序号计数器不存在");
            }
            return rs.getLong(1);
        }
    }

    private static int updateTurn(
            Connection connection,
            Turn turn,
            StoredRow current,
            long expectedRevision,
            Instant now)
            throws SQLException {
        String sql =
                """
                UPDATE turn
                SET status = ?,
                    execution_id = ?,
                    claim_expires_at = ?,
                    revision = ?,
                    error_code = ?,
                    updated_at = ?,
                    completed_at = ?
                WHERE id = ? AND revision = ? AND status = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, turn.status().name());
            setNullable(ps, 2, turn.executionId());
            setNullable(
                    ps,
                    3,
                    turn.claimExpiresAt() == null ? null : turn.claimExpiresAt().toString());
            ps.setLong(4, turn.revision());
            setNullable(ps, 5, turn.errorCode());
            ps.setString(6, turn.updatedAt().toString());
            ps.setString(7, now.toString());
            ps.setString(8, turn.id().asString());
            ps.setLong(9, expectedRevision);
            ps.setString(10, current.status().name());
            return ps.executeUpdate();
        }
    }

    private static StoredRow load(Connection connection, String turnId) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT status, revision FROM turn WHERE id = ?
                        """)) {
            ps.setString(1, turnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new StoredRow(TurnStatus.valueOf(rs.getString("status")), rs.getLong("revision"));
            }
        }
    }

    private static boolean isLegal(TurnStatus from, TurnStatus to) {
        return switch (from) {
            case RECEIVED -> to == TurnStatus.CANCELLED;
            case CLAIMED -> to == TurnStatus.FAILED || to == TurnStatus.CANCELLED;
            case RUNNING -> to == TurnStatus.FAILED || to == TurnStatus.CANCELLED;
            case COMMITTING -> to == TurnStatus.FAILED;
            default -> false;
        };
    }

    private static void setNullable(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private record StoredRow(TurnStatus status, long revision) {}
}
