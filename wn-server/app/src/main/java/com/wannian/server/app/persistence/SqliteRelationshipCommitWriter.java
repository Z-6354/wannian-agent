package com.wannian.server.app.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.relationship.ApprovedRelationshipChange;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/**
 * 热路径同事务写入 {@code relationship_state}（revision CAS）。
 *
 * <p>从 {@link SqliteTurnCommitter} 抽出；null 字段合并保留旧值；不写 memory 表。
 */
final class SqliteRelationshipCommitWriter {

    private final ObjectMapper objectMapper;

    SqliteRelationshipCommitWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    void apply(Connection connection, TurnId turnId, Instant now, ApprovedRelationshipChange change)
            throws SQLException, JsonProcessingException {
        if (change == null) {
            return;
        }
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(now, "now");

        String companion = change.companionIdentity().value();
        String stateJson = stateEnvelope(change);
        String reasonJson = reasonEnvelope(change);
        String nowText = now.toString();
        String turn = turnId.asString();

        Long currentRevision = null;
        String existingAddress = null;
        String existingBoundaries = null;
        try (PreparedStatement find =
                connection.prepareStatement(
                        """
                        SELECT revision, state_json
                        FROM relationship_state
                        WHERE companion_identity_id = ?
                        """)) {
            find.setString(1, companion);
            try (ResultSet rs = find.executeQuery()) {
                if (rs.next()) {
                    currentRevision = rs.getLong("revision");
                    // merge: null 字段保留旧值（由调用方 Draft 语义：null=不改）
                    var node = objectMapper.readTree(rs.getString("state_json"));
                    if (node.hasNonNull("preferredAddress")) {
                        existingAddress = node.get("preferredAddress").asText();
                    }
                    if (node.hasNonNull("boundaries")) {
                        existingBoundaries = node.get("boundaries").asText();
                    }
                }
            }
        }

        String mergedAddress =
                change.preferredAddress() != null ? change.preferredAddress() : existingAddress;
        String mergedBoundaries =
                change.boundaries() != null ? change.boundaries() : existingBoundaries;
        stateJson = stateEnvelope(mergedAddress, mergedBoundaries);

        if (currentRevision == null) {
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            """
                            INSERT INTO relationship_state (
                                companion_identity_id, state_json, reason_json,
                                source_turn_id, revision, updated_at
                            ) VALUES (?, ?, ?, ?, 1, ?)
                            """)) {
                insert.setString(1, companion);
                insert.setString(2, stateJson);
                insert.setString(3, reasonJson);
                insert.setString(4, turn);
                insert.setString(5, nowText);
                insert.executeUpdate();
            }
            return;
        }

        try (PreparedStatement update =
                connection.prepareStatement(
                        """
                        UPDATE relationship_state
                        SET state_json = ?, reason_json = ?, source_turn_id = ?,
                            revision = ?, updated_at = ?
                        WHERE companion_identity_id = ? AND revision = ?
                        """)) {
            update.setString(1, stateJson);
            update.setString(2, reasonJson);
            update.setString(3, turn);
            update.setLong(4, currentRevision + 1);
            update.setString(5, nowText);
            update.setString(6, companion);
            update.setLong(7, currentRevision);
            if (update.executeUpdate() != 1) {
                throw new SQLException("relationship CAS 失败: " + companion);
            }
        }
    }

    private String stateEnvelope(ApprovedRelationshipChange change) throws JsonProcessingException {
        return stateEnvelope(change.preferredAddress(), change.boundaries());
    }

    private String stateEnvelope(String preferredAddress, String boundaries)
            throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        if (preferredAddress != null) {
            root.put("preferredAddress", preferredAddress);
        } else {
            root.putNull("preferredAddress");
        }
        if (boundaries != null) {
            root.put("boundaries", boundaries);
        } else {
            root.putNull("boundaries");
        }
        return objectMapper.writeValueAsString(root);
    }

    private String reasonEnvelope(ApprovedRelationshipChange change) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("reason", change.reason());
        root.put("proposeId", change.proposeId());
        return objectMapper.writeValueAsString(root);
    }
}
