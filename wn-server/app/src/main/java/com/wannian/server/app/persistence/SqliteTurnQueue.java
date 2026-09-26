package com.wannian.server.app.persistence;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.turn.TurnStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Durable Turn 队列查询：真源为 SQLite，不靠内存队列。
 */
@Component
public class SqliteTurnQueue {

    private final DataSource dataSource;

    public SqliteTurnQueue(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 选出全局最早、且同会话当前无 CLAIMED/RUNNING/COMMITTING 的 RECEIVED Turn。
     */
    public Optional<QueuedTurn> pollNextRunnable() {
        return pollNextRunnable(ignored -> true);
    }

    /**
     * 扫描 FIFO RECEIVED 回合，跳过调用方当前不可执行的项，避免它们阻塞后续可执行回合。
     */
    public Optional<QueuedTurn> pollNextRunnable(Predicate<QueuedTurn> eligible) {
        Objects.requireNonNull(eligible, "eligible");
        try (Connection connection = dataSource.getConnection()) {
            final int batchSize = 128;
            int offset = 0;
            while (true) {
                List<QueuedTurn> candidates = listReceivedFifo(connection, batchSize, offset);
                for (QueuedTurn candidate : candidates) {
                    if (!conversationBusy(connection, candidate.conversationId())
                            && eligible.test(candidate)) {
                        return Optional.of(candidate);
                    }
                }
                if (candidates.size() < batchSize) break;
                offset += batchSize;
            }
            return Optional.empty();
        } catch (SQLException ex) {
            throw new IllegalStateException("扫描待执行回合失败", ex);
        }
    }

    public boolean conversationBusy(ConversationId conversationId) {
        try (Connection connection = dataSource.getConnection()) {
            return conversationBusy(connection, conversationId);
        } catch (SQLException ex) {
            throw new IllegalStateException("检查会话忙闲失败", ex);
        }
    }

    /**
     * 启动 reconcile：进程重启后无内存 owner，所有 CLAIMED/RUNNING 均视为孤儿。
     */
    public List<TurnId> listClaimedOrRunning() {
        return listByStatuses(TurnStatus.CLAIMED, TurnStatus.RUNNING);
    }

    /** 启动 / 周期 reconcile：需恢复提交的 COMMITTING 回合。 */
    public List<TurnId> listCommitting() {
        return listByStatuses(TurnStatus.COMMITTING);
    }

    /**
     * 周期 reconcile：lease 已过期的 CLAIMED/RUNNING（进程仍在但 Worker 丢失时）。
     */
    public List<TurnId> listExpiredClaims(Instant now) {
        Objects.requireNonNull(now, "now");
        String sql =
                """
                SELECT id FROM turn
                WHERE status IN (?, ?)
                  AND (claim_expires_at IS NULL OR claim_expires_at <= ?)
                ORDER BY created_at ASC, id ASC
                LIMIT 64
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TurnStatus.CLAIMED.name());
            ps.setString(2, TurnStatus.RUNNING.name());
            ps.setString(3, now.toString());
            return readTurnIds(ps);
        } catch (SQLException ex) {
            throw new IllegalStateException("扫描过期认领失败", ex);
        }
    }

    private List<TurnId> listByStatuses(TurnStatus... statuses) {
        if (statuses == null || statuses.length == 0) {
            return List.of();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < statuses.length; i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }
        String sql =
                "SELECT id FROM turn WHERE status IN ("
                        + placeholders
                        + ") ORDER BY created_at ASC, id ASC LIMIT 64";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < statuses.length; i++) {
                ps.setString(i + 1, statuses[i].name());
            }
            return readTurnIds(ps);
        } catch (SQLException ex) {
            throw new IllegalStateException("扫描中间态回合失败", ex);
        }
    }

    private static List<TurnId> readTurnIds(PreparedStatement ps) throws SQLException {
        List<TurnId> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new TurnId(UUID.fromString(rs.getString("id"))));
            }
        }
        return out;
    }

    private static List<QueuedTurn> listReceivedFifo(Connection connection, int limit, int offset)
            throws SQLException {
        String sql =
                """
                SELECT id, conversation_id, client_request_id, input_message_id, created_at
                FROM turn
                WHERE status = ?
                ORDER BY created_at ASC, id ASC
                LIMIT ? OFFSET ?
                """;
        List<QueuedTurn> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TurnStatus.RECEIVED.name());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(
                            new QueuedTurn(
                                    new TurnId(UUID.fromString(rs.getString("id"))),
                                    new ConversationId(
                                            UUID.fromString(rs.getString("conversation_id"))),
                                    rs.getString("client_request_id"),
                                    rs.getString("input_message_id"),
                                    rs.getString("created_at")));
                }
            }
        }
        return out;
    }

    private static boolean conversationBusy(Connection connection, ConversationId conversationId)
            throws SQLException {
        String sql =
                """
                SELECT 1 FROM turn
                WHERE conversation_id = ?
                  AND status IN (?, ?, ?)
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId.asString());
            ps.setString(2, TurnStatus.CLAIMED.name());
            ps.setString(3, TurnStatus.RUNNING.name());
            ps.setString(4, TurnStatus.COMMITTING.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 读取用户消息正文（envelope text），供 ExecuteTurn。 */
    public Optional<String> loadUserText(TurnId turnId) {
        Objects.requireNonNull(turnId, "turnId");
        String sql =
                """
                SELECT m.content_json
                FROM turn t
                JOIN message m ON m.id = t.input_message_id
                WHERE t.id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, turnId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(extractText(rs.getString(1)));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("加载回合用户正文失败", ex);
        }
    }

    private static String extractText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        int key = contentJson.indexOf("\"text\"");
        if (key < 0) {
            return contentJson;
        }
        int colon = contentJson.indexOf(':', key);
        int firstQuote = contentJson.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return contentJson;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = firstQuote + 1; i < contentJson.length(); i++) {
            char c = contentJson.charAt(i);
            if (c == '\\' && i + 1 < contentJson.length()) {
                sb.append(contentJson.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public record QueuedTurn(
            TurnId turnId,
            ConversationId conversationId,
            String clientRequestId,
            String inputMessageId,
            String createdAt) {}
}
