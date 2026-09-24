package com.wannian.server.app.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.relationship.RelationshipStore;
import com.wannian.server.kernel.relationship.StoredRelationshipState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * {@link RelationshipStore} 的 SQLite 只读实现。
 *
 * <p><b>职责边界</b>
 *
 * <ul>
 *   <li>只读 {@code relationship_state}；正式写入只经 {@link SqliteRelationshipCommitWriter} /
 *       TurnCommitter 热路径或短事务 Command，本类禁止 INSERT/UPDATE。
 *   <li>JSON 键与 Writer 对齐：{@code state_json.preferredAddress} / {@code boundaries}；
 *       {@code reason_json.reason}（另有 {@code proposeId}，Assembler 不用则忽略）。
 *   <li>无行 → {@link Optional#empty()}；损坏 JSON / JDBC 失败 → 抛 {@link IllegalStateException}，
 *       禁止假成功或吞掉后返回空（空只表示「尚无关系快照」）。
 * </ul>
 *
 * <p>由 Spring {@code @Component} 注册；{@link com.wannian.server.app.chat.TurnEngineConfig}
 * 只注入本 bean，勿再手写第二份构造。
 */
@Component
public class SqliteRelationshipStore implements RelationshipStore {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteRelationshipStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 按伴身主键取当前关系快照。
     *
     * @param companionIdentity 伴身；本批生产路径为 {@link CompanionIdentity#YANHUO}
     * @return 有行则映射为 {@link StoredRelationshipState}；无行 empty
     */
    @Override
    public Optional<StoredRelationshipState> findByCompanion(CompanionIdentity companionIdentity) {
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        String sql =
                """
                SELECT companion_identity_id, state_json, reason_json,
                       source_turn_id, revision, updated_at
                FROM relationship_state
                WHERE companion_identity_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, companionIdentity.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(companionIdentity, rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "读取 relationship_state 失败: " + companionIdentity.value(), ex);
        }
    }

    /**
     * 将一行映射为只读投影。
     *
     * <p>使用查询入参的 {@code companionIdentity}（与 PK 一致），避免再 new 一份字符串包装。
     * {@code preferredAddress}/{@code boundaries} 缺省或 JSON null → Java null（Assembler
     * {@link StoredRelationshipState#toPromptText()} 会跳过）。
     * {@code reason} 缺省时用 {@code "unknown"}，满足 record 非空约束。
     */
    private StoredRelationshipState mapRow(CompanionIdentity companionIdentity, ResultSet rs)
            throws SQLException {
        String stateJson = rs.getString("state_json");
        String reasonJson = rs.getString("reason_json");
        String preferredAddress;
        String boundaries;
        String reason;
        try {
            JsonNode state = objectMapper.readTree(stateJson);
            preferredAddress = textOrNull(state, "preferredAddress");
            boundaries = textOrNull(state, "boundaries");

            JsonNode reasonNode = objectMapper.readTree(reasonJson);
            String rawReason = textOrNull(reasonNode, "reason");
            reason = rawReason != null ? rawReason : "unknown";
        } catch (JsonProcessingException ex) {
            throw new SQLException(
                    "relationship_state JSON 损坏: " + companionIdentity.value(), ex);
        }

        String updatedAtRaw = rs.getString("updated_at");
        Instant updatedAt;
        try {
            updatedAt = Instant.parse(updatedAtRaw);
        } catch (RuntimeException ex) {
            throw new SQLException(
                    "relationship_state.updated_at 非法: " + updatedAtRaw, ex);
        }

        String sourceTurnId = rs.getString("source_turn_id");
        if (sourceTurnId != null && sourceTurnId.isBlank()) {
            sourceTurnId = null;
        }

        return new StoredRelationshipState(
                companionIdentity,
                preferredAddress,
                boundaries,
                reason,
                rs.getLong("revision"),
                updatedAt,
                sourceTurnId);
    }

    /** 节点缺键、JSON null、或空白字符串 → null；否则 trim 后文本。 */
    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null || !parent.hasNonNull(field)) {
            return null;
        }
        String text = parent.get(field).asText();
        if (text == null) {
            return null;
        }
        text = text.trim();
        return text.isEmpty() ? null : text;
    }
}
