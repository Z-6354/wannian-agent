package com.wannian.server.app.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** 按会话从 Outbox 按 sequence_no 补发。 */
@Component
public class SqliteOutboxQuery {

    private final DataSource dataSource;

    public SqliteOutboxQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<OutboxRow> listAfter(String conversationId, long afterSequence, int limit) {
        Objects.requireNonNull(conversationId, "conversationId");
        int capped = Math.min(Math.max(limit, 1), 200);
        String sql =
                """
                SELECT id, aggregate_type, aggregate_id, event_type, payload_json, sequence_no, created_at
                FROM outbox_event
                WHERE sequence_no > ?
                  AND json_extract(payload_json, '$.conversationId') = ?
                ORDER BY sequence_no ASC
                LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, afterSequence);
            ps.setString(2, conversationId);
            ps.setInt(3, capped);
            List<OutboxRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(
                            new OutboxRow(
                                    rs.getString("id"),
                                    rs.getString("aggregate_type"),
                                    rs.getString("aggregate_id"),
                                    rs.getString("event_type"),
                                    rs.getString("payload_json"),
                                    rs.getLong("sequence_no"),
                                    rs.getString("created_at")));
                }
            }
            return out;
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 Outbox 失败", ex);
        }
    }

    /** 返回此会话已写入 Outbox 的最大全局序号；没有事件时返回 0。 */
    public long latestSequence(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        String sql =
                """
                SELECT COALESCE(MAX(sequence_no), 0)
                FROM outbox_event
                WHERE json_extract(payload_json, '$.conversationId') = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 Outbox 游标失败", ex);
        }
    }

    public record OutboxRow(
            String id,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payloadJson,
            long sequenceNo,
            String createdAt) {}
}
