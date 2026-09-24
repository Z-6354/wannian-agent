package com.wannian.server.app.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryLifecycle;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.SourceKind;
import com.wannian.server.kernel.memory.StoredMemoryRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** {@link MemoryStore} 的 SQLite 只读实现。 */
@Component
public class SqliteMemoryStore implements MemoryStore {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteMemoryStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Optional<StoredMemoryRecord> findById(String id) {
        Objects.requireNonNull(id, "id");
        String sql =
                """
                SELECT id, companion_identity_id, subject_key, content_kind, source_kind, scope,
                       content_json, propose_id, status, revision, created_at, last_recalled_at,
                       supersedes_id, importance
                FROM memory_record
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 memory_record 失败: " + id, ex);
        }
    }

    @Override
    public List<StoredMemoryRecord> listByCompanion(
            CompanionIdentity companionIdentity, MemoryLifecycle lifecycle) {
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        Objects.requireNonNull(lifecycle, "lifecycle");
        String sql =
                """
                SELECT id, companion_identity_id, subject_key, content_kind, source_kind, scope,
                       content_json, propose_id, status, revision, created_at, last_recalled_at,
                       supersedes_id, importance
                FROM memory_record
                WHERE companion_identity_id = ? AND status = ?
                ORDER BY created_at DESC
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, companionIdentity.value());
            ps.setString(2, lifecycle.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<StoredMemoryRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
                return List.copyOf(out);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("列出 memory_record 失败", ex);
        }
    }

    @Override
    public List<StoredMemoryRecord> listActive(CompanionIdentity companionIdentity) {
        return listByCompanion(companionIdentity, MemoryLifecycle.ACTIVE);
    }

    @Override
    public Map<String, Long> subjectGenerations(CompanionIdentity companionIdentity) {
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT subject_key, generation FROM memory_subject_generation "
                                + "WHERE companion_identity_id = ?")) {
            ps.setString(1, companionIdentity.value());
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Long> out = new LinkedHashMap<>();
                while (rs.next()) {
                    out.put(rs.getString("subject_key"), rs.getLong("generation"));
                }
                return Map.copyOf(out);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 memory subject generation 失败", ex);
        }
    }

    @Override
    public MemoryStore.SubjectSnapshot subjectSnapshot(CompanionIdentity companionIdentity) {
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            List<StoredMemoryRecord> active = listActive(connection, companionIdentity);
            Map<String, Long> generations;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT subject_key, generation FROM memory_subject_generation "
                            + "WHERE companion_identity_id = ?")) {
                ps.setString(1, companionIdentity.value());
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, Long> values = new LinkedHashMap<>();
                    while (rs.next()) {
                        values.put(rs.getString("subject_key"), rs.getLong("generation"));
                    }
                    generations = Map.copyOf(values);
                }
            }
            connection.commit();
            return new MemoryStore.SubjectSnapshot(active, generations);
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 memory subject snapshot 失败", ex);
        }
    }

    private List<StoredMemoryRecord> listActive(Connection connection, CompanionIdentity companionIdentity)
            throws SQLException {
        String sql = """
                SELECT id, companion_identity_id, subject_key, content_kind, source_kind, scope,
                       content_json, propose_id, status, revision, created_at, last_recalled_at,
                       supersedes_id, importance
                FROM memory_record
                WHERE companion_identity_id = ? AND status = ?
                ORDER BY created_at DESC
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, companionIdentity.value());
            ps.setString(2, MemoryLifecycle.ACTIVE.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<StoredMemoryRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
                return List.copyOf(out);
            }
        }
    }

    private StoredMemoryRecord mapRow(ResultSet rs) throws SQLException {
        String contentJson = rs.getString("content_json");
        String claim;
        String path = null;
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            claim = root.path("claim").asText("");
            if (root.has("path") && !root.get("path").isNull()) {
                path = root.get("path").asText(null);
            }
        } catch (Exception ex) {
            throw new SQLException("content_json 损坏: " + rs.getString("id"), ex);
        }
        String lastRecalled = rs.getString("last_recalled_at");
        return new StoredMemoryRecord(
                rs.getString("id"),
                new CompanionIdentity(rs.getString("companion_identity_id")),
                rs.getString("subject_key"),
                claim,
                ContentKind.valueOf(rs.getString("content_kind")),
                SourceKind.valueOf(rs.getString("source_kind")),
                MemoryScope.valueOf(rs.getString("scope")),
                rs.getDouble("importance"),
                path,
                MemoryLifecycle.valueOf(rs.getString("status")),
                rs.getString("propose_id"),
                rs.getLong("revision"),
                Instant.parse(rs.getString("created_at")),
                lastRecalled == null || lastRecalled.isBlank() ? null : Instant.parse(lastRecalled),
                rs.getString("supersedes_id"));
    }
}
