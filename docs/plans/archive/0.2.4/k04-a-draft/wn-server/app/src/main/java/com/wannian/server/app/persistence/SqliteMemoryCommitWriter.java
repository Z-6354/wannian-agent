package com.wannian.server.app.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryLifecycle;
import com.wannian.server.kernel.memory.MemoryScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热/冷路径写入 {@code memory_record}（含 SUPERSEDE CAS）。
 *
 * <p>importance 落列；propose_id 取自变更；triage_id=validate；不改 claim 正文。
 * 冷路径（Review）{@code source_turn_id} 可空。
 *
 * <p>同 companion 写入串行（对齐 Hermes 单写者 / OpenClaw workspace lock），
 * 减少热路径与 Review/HTTP 交错。冻结计划里的 {@code expectedGeneration} 仍作 CAS 期望值：
 * 若 Freeze 之后已被 correct 等推进，则跳过本条记忆、不挡 Turn（避免覆盖更新的人工更正）。
 *
 * <p>0.2.4-A：{@link #applyAll} 返回实际落库结果，供同事务 {@code MEMORY_WRITE} 记账；
 * 跳过（代次过期 / 旧冻结计划无观察点）不进入列表，不伪造成功。
 */
final class SqliteMemoryCommitWriter {

    private static final String TRIAGE_VALIDATE = "validate";

    /** companionIdentityId → 监视器；按 id 排序加锁，避免多 companion 死锁。 */
    private static final ConcurrentHashMap<String, Object> COMPANION_WRITE_LOCKS =
            new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    SqliteMemoryCommitWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** 供冷路径与热路径共用的 companion 写锁。 */
    static Object writeLockFor(String companionIdentityId) {
        Objects.requireNonNull(companionIdentityId, "companionIdentityId");
        return COMPANION_WRITE_LOCKS.computeIfAbsent(companionIdentityId, id -> new Object());
    }

    /**
     * 热路径批量写入。仅包含真正 INSERT 成功的行；代次过期与不安全旧计划跳过项不出现。
     *
     * @return 不可变列表；无成功写入时为空列表（非 null）
     */
    List<AppliedMemoryWrite> applyAll(
            Connection connection, TurnId turnId, Instant now, List<ApprovedMemoryChange> changes)
            throws SQLException, JsonProcessingException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(changes, "changes");
        if (changes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> companions = new LinkedHashSet<>();
        for (ApprovedMemoryChange change : changes) {
            companions.add(change.companionIdentity().value());
        }
        List<String> lockOrder = new ArrayList<>(companions);
        lockOrder.sort(Comparator.naturalOrder());
        return lockCompanions(
                lockOrder, 0, () -> applyAllLocked(connection, turnId, now, changes));
    }

    private List<AppliedMemoryWrite> applyAllLocked(
            Connection connection, TurnId turnId, Instant now, List<ApprovedMemoryChange> changes)
            throws SQLException, JsonProcessingException {
        String nowText = now.toString();
        List<AppliedMemoryWrite> applied = new ArrayList<>(changes.size());
        for (ApprovedMemoryChange change : changes) {
            try {
                AppliedMemoryWrite one = applyOne(connection, turnId.asString(), nowText, change);
                if (one != null) {
                    applied.add(one);
                }
            } catch (StaleGenerationException ignored) {
                // Preserve the user-facing turn while dropping only its stale memory mutation.
            }
        }
        return List.copyOf(applied);
    }

    /** 冷路径单条 APPLY；返回新行 id。跳过时返回 null。 */
    String applyOne(
            Connection connection, Instant now, ApprovedMemoryChange change)
            throws SQLException, JsonProcessingException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(change, "change");
        Object lock = writeLockFor(change.companionIdentity().value());
        synchronized (lock) {
            AppliedMemoryWrite applied = applyOne(connection, null, now.toString(), change);
            return applied == null ? null : applied.memoryRecordId();
        }
    }

    private static <T> T lockCompanions(List<String> ordered, int index, SqlSupplier<T> action)
            throws SQLException, JsonProcessingException {
        if (index >= ordered.size()) {
            return action.get();
        }
        synchronized (writeLockFor(ordered.get(index))) {
            return lockCompanions(ordered, index + 1, action);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException, JsonProcessingException;
    }

    private AppliedMemoryWrite applyOne(
            Connection connection, String turnIdOrNull, String nowText, ApprovedMemoryChange change)
            throws SQLException, JsonProcessingException {
        String companion = change.companionIdentity().value();
        if (change.expectedGeneration() == null || change.expectedGeneration() < 0) {
            // Legacy frozen plans have no observation point; their memory mutations are unsafe.
            return null;
        }
        if (!advanceGeneration(
                connection, companion, change.subjectKey(), change.expectedGeneration())) {
            throw new StaleGenerationException(change.subjectKey());
        }
        String supersedesId = null;
        try (PreparedStatement find =
                connection.prepareStatement(
                        """
                        SELECT id, revision
                        FROM memory_record
                        WHERE companion_identity_id = ?
                          AND subject_key = ?
                          AND status = ?
                        """)) {
            find.setString(1, companion);
            find.setString(2, change.subjectKey());
            find.setString(3, MemoryLifecycle.ACTIVE.name());
            try (ResultSet rs = find.executeQuery()) {
                if (rs.next()) {
                    supersedesId = rs.getString("id");
                    long oldRevision = rs.getLong("revision");
                    try (PreparedStatement supersede =
                            connection.prepareStatement(
                                    """
                                    UPDATE memory_record
                                    SET status = ?, revision = ?, valid_until = ?
                                    WHERE id = ? AND revision = ? AND status = ?
                                    """)) {
                        supersede.setString(1, MemoryLifecycle.SUPERSEDED.name());
                        supersede.setLong(2, oldRevision + 1);
                        supersede.setString(3, nowText);
                        supersede.setString(4, supersedesId);
                        supersede.setLong(5, oldRevision);
                        supersede.setString(6, MemoryLifecycle.ACTIVE.name());
                        if (supersede.executeUpdate() != 1) {
                            throw new SQLException("memory SUPERSEDE CAS 失败: " + supersedesId);
                        }
                    }
                }
            }
        }

        String contentJson = contentEnvelope(change);
        String newId = UUID.randomUUID().toString();
        try (PreparedStatement insert =
                connection.prepareStatement(
                        """
                        INSERT INTO memory_record (
                            id, companion_identity_id, subject_key,
                            content_kind, source_kind, scope,
                            content_json, source_turn_id, source_message_id,
                            propose_id, triage_id, status,
                            valid_from, valid_until, supersedes_id,
                            revision, created_at, last_recalled_at, importance
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, NULL, ?, 1, ?, NULL, ?)
                        """)) {
            insert.setString(1, newId);
            insert.setString(2, companion);
            insert.setString(3, change.subjectKey());
            insert.setString(4, change.contentKind().name());
            insert.setString(5, change.sourceKind().name());
            insert.setString(6, change.scope().name());
            insert.setString(7, contentJson);
            insert.setString(8, turnIdOrNull);
            insert.setString(9, change.proposeId());
            insert.setString(10, TRIAGE_VALIDATE);
            insert.setString(11, MemoryLifecycle.ACTIVE.name());
            insert.setString(12, nowText);
            insert.setString(13, supersedesId);
            insert.setString(14, nowText);
            insert.setDouble(15, change.importance());
            insert.executeUpdate();
        }
        return new AppliedMemoryWrite(
                newId,
                change.proposeId(),
                change.contentKind(),
                change.scope(),
                supersedesId);
    }

    /** Compare and increment the subject token in the same transaction as the record mutation. */
    private static boolean advanceGeneration(
            Connection connection, String companion, String subject, long expected)
            throws SQLException {
        if (expected == 0) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO memory_subject_generation "
                            + "(companion_identity_id, subject_key, generation) VALUES (?, ?, 1) "
                            + "ON CONFLICT(companion_identity_id, subject_key) DO NOTHING")) {
                insert.setString(1, companion);
                insert.setString(2, subject);
                if (insert.executeUpdate() == 1) {
                    return true;
                }
            }
        } else {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE memory_subject_generation SET generation = generation + 1 "
                            + "WHERE companion_identity_id = ? AND subject_key = ? AND generation = ?")) {
                update.setString(1, companion);
                update.setString(2, subject);
                update.setLong(3, expected);
                if (update.executeUpdate() == 1) {
                    return true;
                }
            }
        }
        return false;
    }

    static final class StaleGenerationException extends SQLException {
        StaleGenerationException(String subjectKey) {
            super("记忆草案基于过时代次，已拒绝写入: " + subjectKey);
        }
    }

    /**
     * 一次实际落库成功的记忆写入摘要（无 claim / path / 原文）。
     *
     * <p>供 Committer 同事务写 {@code MEMORY_WRITE}；不进入冻结计划 JSON。
     */
    record AppliedMemoryWrite(
            String memoryRecordId,
            String proposeId,
            ContentKind contentKind,
            MemoryScope scope,
            String supersedesId) {

        AppliedMemoryWrite {
            Objects.requireNonNull(memoryRecordId, "memoryRecordId");
            Objects.requireNonNull(proposeId, "proposeId");
            Objects.requireNonNull(contentKind, "contentKind");
            Objects.requireNonNull(scope, "scope");
            memoryRecordId = memoryRecordId.trim();
            proposeId = proposeId.trim();
            if (memoryRecordId.isEmpty()) {
                throw new IllegalArgumentException("memoryRecordId 不能为空");
            }
            if (proposeId.isEmpty()) {
                throw new IllegalArgumentException("proposeId 不能为空");
            }
            if (supersedesId != null) {
                supersedesId = supersedesId.trim();
                if (supersedesId.isEmpty()) {
                    supersedesId = null;
                }
            }
        }
    }

    private String contentEnvelope(ApprovedMemoryChange change) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("v", 1);
        root.put("claim", change.claim());
        if (change.path() != null) {
            root.put("path", change.path());
        } else {
            root.putNull("path");
        }
        return objectMapper.writeValueAsString(root);
    }
}
