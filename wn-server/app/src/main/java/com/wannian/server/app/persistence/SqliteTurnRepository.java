package com.wannian.server.app.persistence;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnRepository;
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
import org.springframework.stereotype.Component;

/**
 * {@link TurnRepository} 的 SQLite 实现：按主键加载，并以 revision 比较并交换写回单步迁移。
 *
 * <p>本类在写入前核对源状态。COMPLETED 只能由 {@link com.wannian.server.kernel.turn.TurnCommitter}
 * 同事务写入；COMMITTING 只能由 {@code freezeCommit} 与计划同事务写入。终态不能靠重建快照复活。
 * 进入 RUNNING 时还要核对库中的 executionId 与 lease。
 */
@Component
public class SqliteTurnRepository implements TurnRepository {

    private final DataSource dataSource;

    public SqliteTurnRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<Turn> find(TurnId id) {
        Objects.requireNonNull(id, "id");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT conversation_id, client_request_id, status, input_message_id,
                                       execution_id, claim_expires_at, revision, error_code, updated_at
                                FROM turn
                                WHERE id = ?
                                """)) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapTurn(id, rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("加载回合失败: " + id.asString(), ex);
        }
    }

    @Override
    public SaveTurnResult save(Turn turn, long expectedRevision, Instant now) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(now, "now");
        if (turn.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException(
                    "save 只持久化一次领域迁移：expectedRevision="
                            + expectedRevision
                            + "，turn.revision="
                            + turn.revision());
        }

        try (Connection connection = dataSource.getConnection()) {
            StoredRow current = loadStored(connection, turn.id());
            if (current == null) {
                return new SaveTurnResult.NotFound(turn.id());
            }
            if (current.revision() != expectedRevision) {
                return new SaveTurnResult.RevisionConflict(turn.id(), current.revision());
            }
            SaveTurnResult rejected = rejectIllegalTransition(turn, current, now);
            if (rejected != null) {
                return rejected;
            }
            int updated = updateSnapshot(connection, turn, current, expectedRevision, now);
            if (updated == 1) {
                return new SaveTurnResult.Saved(turn.id(), turn.revision());
            }
            StoredRow again = loadStored(connection, turn.id());
            if (again == null) {
                return new SaveTurnResult.NotFound(turn.id());
            }
            return new SaveTurnResult.RevisionConflict(turn.id(), again.revision());
        } catch (SQLException ex) {
            return new SaveTurnResult.Rejected(
                    turn.id(), ErrorCodes.PERSISTENCE_FAILED, "保存回合迁移失败: " + turn.id().asString());
        }
    }

    /**
     * 通用保存不能完成回合，也不能从终态改回去，更不能单独写成 COMMITTING。
     * 进入 RUNNING 时核对库里的 owner，以及库里的 lease 是否仍晚于这次 {@code now}。
     */
    private static SaveTurnResult rejectIllegalTransition(Turn turn, StoredRow current, Instant now) {
        if (!isLegalSave(current.status(), turn.status())) {
            return new SaveTurnResult.Rejected(
                    turn.id(),
                    ErrorCodes.ILLEGAL_TRANSITION,
                    "不能从 " + current.status() + " 写成 " + turn.status());
        }
        if (turn.status() == TurnStatus.CLAIMED
                && (turn.claimExpiresAt() == null || !turn.claimExpiresAt().isAfter(now))) {
            return new SaveTurnResult.Rejected(
                    turn.id(), ErrorCodes.CLAIM_EXPIRED, "不能保存已经过期的认领");
        }
        if (turn.status() == TurnStatus.RUNNING) {
            if (!Objects.equals(current.executionId(), turn.executionId())) {
                return new SaveTurnResult.Rejected(
                        turn.id(), ErrorCodes.OWNER_MISMATCH, "executionId 与库中冻结身份不一致");
            }
            if (current.claimExpiresAt() == null || !current.claimExpiresAt().isAfter(now)) {
                return new SaveTurnResult.Rejected(
                        turn.id(), ErrorCodes.CLAIM_EXPIRED, "lease 已过期，不能进入下一步");
            }
        }
        return null;
    }

    private static boolean isLegalSave(TurnStatus from, TurnStatus to) {
        return switch (from) {
            case RECEIVED -> to == TurnStatus.CLAIMED || to == TurnStatus.CANCELLED;
            case CLAIMED ->
                    to == TurnStatus.RUNNING || to == TurnStatus.FAILED || to == TurnStatus.CANCELLED;
            case RUNNING -> to == TurnStatus.FAILED || to == TurnStatus.CANCELLED;
            case COMMITTING, COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    private static int updateSnapshot(
            Connection connection, Turn turn, StoredRow current, long expectedRevision, Instant now)
            throws SQLException {
        String completedAt =
                turn.status() == TurnStatus.FAILED || turn.status() == TurnStatus.CANCELLED
                        ? turn.updatedAt().toString()
                        : null;
        boolean requireActiveLease = turn.status() == TurnStatus.RUNNING;
        String sql =
                """
                UPDATE turn
                SET status = ?,
                    execution_id = ?,
                    claim_expires_at = ?,
                    revision = ?,
                    error_code = ?,
                    updated_at = ?,
                    completed_at = COALESCE(?, completed_at)
                WHERE id = ? AND revision = ? AND status = ?
                """
                        + (requireActiveLease ? " AND claim_expires_at > ?" : "");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, turn.status().name());
            setNullableString(ps, 2, turn.executionId());
            setNullableString(
                    ps, 3, turn.claimExpiresAt() == null ? null : turn.claimExpiresAt().toString());
            ps.setLong(4, turn.revision());
            setNullableString(ps, 5, turn.errorCode());
            ps.setString(6, turn.updatedAt().toString());
            setNullableString(ps, 7, completedAt);
            ps.setString(8, turn.id().asString());
            ps.setLong(9, expectedRevision);
            ps.setString(10, current.status().name());
            if (requireActiveLease) {
                ps.setString(11, now.toString());
            }
            return ps.executeUpdate();
        }
    }

    private static StoredRow loadStored(Connection connection, TurnId id) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT status, revision, execution_id, claim_expires_at
                        FROM turn
                        WHERE id = ?
                        """)) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String statusName = rs.getString("status");
                TurnStatus status;
                try {
                    status = TurnStatus.valueOf(statusName);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalStateException("库中回合状态无法识别: " + statusName, ex);
                }
                return new StoredRow(
                        status,
                        rs.getLong("revision"),
                        rs.getString("execution_id"),
                        parseInstant(rs.getString("claim_expires_at")));
            }
        }
    }

    private record StoredRow(
            TurnStatus status, long revision, String executionId, Instant claimExpiresAt) {}

    private static Turn mapTurn(TurnId id, ResultSet rs) throws SQLException {
        String statusName = rs.getString("status");
        TurnStatus status;
        try {
            status = TurnStatus.valueOf(statusName);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("库中回合状态无法识别: " + statusName, ex);
        }
        return Turn.reconstitute(
                id,
                new ConversationId(UUID.fromString(rs.getString("conversation_id"))),
                rs.getString("client_request_id"),
                new MessageId(UUID.fromString(rs.getString("input_message_id"))),
                status,
                rs.getLong("revision"),
                rs.getString("execution_id"),
                parseInstant(rs.getString("claim_expires_at")),
                rs.getString("error_code"),
                Instant.parse(rs.getString("updated_at")));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    private static void setNullableString(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
