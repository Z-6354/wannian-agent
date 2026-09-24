package com.wannian.server.app.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.MemoryCommand;
import com.wannian.server.kernel.memory.MemoryLifecycle;
import com.wannian.server.kernel.memory.MemoryReviewBatchApplier;
import com.wannian.server.kernel.memory.MemoryReviewLease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 记忆<strong>短事务</strong>写缝（冷路径 / S11 HTTP / 弱 B）。
 *
 * <p><b>职责边界</b>
 *
 * <ul>
 *   <li>唯一允许的冷路径 JDBC 写入口之一；Controller / Scanner / Worker 不得绕过本类直写表。
 *   <li>热路径（Turn Freeze 同事务）仍走 {@link SqliteMemoryCommitWriter}（经 TurnCommitter），
 *       本类 {@link #applyReview} 复用 Writer 的 {@code applyOne}；{@link #correct} 用
 *       <strong>按旧 id CAS SUPERSEDE</strong>，禁止按 subject 盲替。
 *   <li>{@link #forget} 与 {@link #tombstone} 共用清空策略：status=FORGOTTEN +
 *       {@code content_json={"v":1,"claim":"","forgotten":true}} + revision CAS；
 *       不改 propose_id 列（墓碑是状态迁移，不是新 claim）。
 *   <li>失败返回 {@link CommandResult.Rejected} + 稳定 {@link ErrorCodes}；禁止假 Applied。
 * </ul>
 */
@Component
public class SqliteMemoryCommand implements MemoryCommand, MemoryReviewBatchApplier {

    /** 与热路径 Writer 一致的 triage 标记。 */
    private static final String TRIAGE_VALIDATE = "validate";

    /**
     * forget / tombstone 清空正文。claim 置空串；{@code forgotten:true} 供排查。
     * 注意：ACTIVE 行的 StoredMemoryRecord 不允许空 claim，故清空后必须已非 ACTIVE。
     */
    private static final String FORGOTTEN_JSON = "{\"v\":1,\"claim\":\"\",\"forgotten\":true}";

    private final DataSource dataSource;
    private final SqliteMemoryCommitWriter writer;
    private final ObjectMapper objectMapper;

    public SqliteMemoryCommand(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.writer = new SqliteMemoryCommitWriter(objectMapper);
    }

    /**
     * HTTP correct：旧 ACTIVE 行按 id+revision CAS → SUPERSEDED；再 INSERT 新 ACTIVE。
     *
     * <p>调用前 HTTP 层须已 Shape+Policy；本方法再校验 {@code proposeId=http_correct}。
     * 新行 {@code source_turn_id=NULL}、{@code supersedes_id=oldId}、{@code revision=1}。
     */
    @Override
    public CommandResult correct(
            String oldMemoryId, long expectedRevision, ApprovedMemoryChange replacement) {
        Objects.requireNonNull(oldMemoryId, "oldMemoryId");
        Objects.requireNonNull(replacement, "replacement");
        if (!ApprovedMemoryChange.PROPOSE_HTTP_CORRECT.equals(replacement.proposeId())) {
            return new CommandResult.Rejected(
                    ErrorCodes.ILLEGAL_ARGUMENT, "correct 要求 proposeId=http_correct");
        }
        String oldId = oldMemoryId.trim();
        if (oldId.isEmpty()) {
            return new CommandResult.Rejected(ErrorCodes.ILLEGAL_ARGUMENT, "oldMemoryId 不得空白");
        }
        Instant now = Instant.now();
        String nowText = now.toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ActiveRow old = loadActive(connection, oldId);
                if (old == null) {
                    connection.rollback();
                    return new CommandResult.Rejected(
                            ErrorCodes.MEMORY_NOT_FOUND, "记忆不存在或非 ACTIVE: " + oldId);
                }
                if (old.revision() != expectedRevision) {
                    connection.rollback();
                    return new CommandResult.Rejected(
                            ErrorCodes.REVISION_CONFLICT,
                            "revision 冲突，实际 " + old.revision());
                }
                if (hasOtherActiveSubject(
                        connection,
                        replacement.companionIdentity().value(),
                        replacement.subjectKey(),
                        oldId)) {
                    connection.rollback();
                    return new CommandResult.Rejected(
                            ErrorCodes.MEMORY_SUBJECT_CONFLICT,
                            "目标 subjectKey 已有 ACTIVE 记忆: " + replacement.subjectKey());
                }
                try (PreparedStatement supersede =
                        connection.prepareStatement(
                                """
                                UPDATE memory_record
                                SET status = ?, revision = ?, valid_until = ?
                                WHERE id = ? AND revision = ? AND status = ?
                                """)) {
                    supersede.setString(1, MemoryLifecycle.SUPERSEDED.name());
                    supersede.setLong(2, old.revision() + 1);
                    supersede.setString(3, nowText);
                    supersede.setString(4, oldId);
                    supersede.setLong(5, expectedRevision);
                    supersede.setString(6, MemoryLifecycle.ACTIVE.name());
                    if (supersede.executeUpdate() != 1) {
                        connection.rollback();
                        return new CommandResult.Rejected(
                                ErrorCodes.REVISION_CONFLICT, "SUPERSEDE CAS 失败");
                    }
                }
                bumpGeneration(connection, old.companionId(), old.subjectKey());
                if (!old.subjectKey().equals(replacement.subjectKey())
                        || !old.companionId().equals(replacement.companionIdentity().value())) {
                    bumpGeneration(connection, replacement.companionIdentity().value(), replacement.subjectKey());
                }
                String newId = insertReplacement(connection, replacement, oldId, nowText);
                connection.commit();
                return new CommandResult.Applied(newId, 1L);
            } catch (SQLException | JsonProcessingException ex) {
                rollbackQuietly(connection);
                if (ex instanceof SQLException sqlException
                        && isActiveSubjectConstraint(sqlException)) {
                    return new CommandResult.Rejected(
                            ErrorCodes.MEMORY_SUBJECT_CONFLICT,
                            "目标 subjectKey 已有 ACTIVE 记忆");
                }
                return new CommandResult.Rejected(
                        ErrorCodes.PERSISTENCE_FAILED, "correct 失败: " + ex.getMessage());
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            return new CommandResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, "无法打开数据库连接");
        }
    }

    /** 人主动遗忘（S11）；清空策略见 {@link #markForgotten}。 */
    @Override
    public CommandResult forget(String memoryId, long expectedRevision) {
        return markForgotten(memoryId, expectedRevision, "forget");
    }

    /**
     * Review Worker APPLY：短事务内 {@link SqliteMemoryCommitWriter#applyOne}。
     *
     * <p>要求 {@code proposeId=llm_review}。Writer 可按 subject 对既有 ACTIVE 做 SUPERSEDE。
     */
    @Override
    public CommandResult applyReview(ApprovedMemoryChange change) {
        Objects.requireNonNull(change, "change");
        if (!ApprovedMemoryChange.PROPOSE_LLM_REVIEW.equals(change.proposeId())) {
            return new CommandResult.Rejected(
                    ErrorCodes.ILLEGAL_ARGUMENT, "applyReview 要求 proposeId=llm_review");
        }
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String newId = writer.applyOne(connection, now, change);
                if (newId == null) {
                    connection.rollback();
                    return new CommandResult.Rejected(
                            ErrorCodes.REVISION_CONFLICT, "Review 草案缺少观察代次或已过期");
                }
                connection.commit();
                return new CommandResult.Applied(newId, 1L);
            } catch (SQLException | JsonProcessingException ex) {
                rollbackQuietly(connection);
                if (ex instanceof SqliteMemoryCommitWriter.StaleGenerationException) {
                    return new CommandResult.Rejected(ErrorCodes.REVISION_CONFLICT, ex.getMessage());
                }
                return new CommandResult.Rejected(
                        ErrorCodes.PERSISTENCE_FAILED, "Review APPLY 失败: " + ex.getMessage());
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            return new CommandResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, "无法打开数据库连接");
        }
    }

    /**
     * 弱 B 内部墓碑；与 {@link #forget} 同清空策略，通道标签仅用于日志 {@code op}。
     *
     * <p>不强制改 propose_id（实施单：状态迁移非新 claim）。
     */
    @Override
    public CommandResult tombstone(String memoryId, long expectedRevision) {
        return markForgotten(memoryId, expectedRevision, "tombstone");
    }

    /**
     * ACTIVE + revision CAS → FORGOTTEN，并写入 {@link #FORGOTTEN_JSON}。
     *
     * @param op 日志用操作名（forget / tombstone）
     */
    private CommandResult markForgotten(String memoryId, long expectedRevision, String op) {
        Objects.requireNonNull(memoryId, "memoryId");
        String id = memoryId.trim();
        if (id.isEmpty()) {
            return new CommandResult.Rejected(ErrorCodes.ILLEGAL_ARGUMENT, "memoryId 不得空白");
        }
        Instant now = Instant.now();
        String nowText = now.toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ActiveRow row = loadActive(connection, id);
                if (row == null) {
                    connection.rollback();
                    return new CommandResult.Rejected(
                            ErrorCodes.MEMORY_NOT_FOUND, "记忆不存在或非 ACTIVE: " + id);
                }
                if (row.revision() != expectedRevision) {
                    connection.rollback();
                    return new CommandResult.Rejected(
                            ErrorCodes.REVISION_CONFLICT,
                            "revision 冲突，实际 " + row.revision());
                }
                long newRevision = row.revision() + 1;
                try (PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                UPDATE memory_record
                                SET status = ?, revision = ?, content_json = ?, valid_until = ?
                                WHERE id = ? AND revision = ? AND status = ?
                                """)) {
                    ps.setString(1, MemoryLifecycle.FORGOTTEN.name());
                    ps.setLong(2, newRevision);
                    ps.setString(3, FORGOTTEN_JSON);
                    ps.setString(4, nowText);
                    ps.setString(5, id);
                    ps.setLong(6, expectedRevision);
                    ps.setString(7, MemoryLifecycle.ACTIVE.name());
                    if (ps.executeUpdate() != 1) {
                        connection.rollback();
                        return new CommandResult.Rejected(
                                ErrorCodes.REVISION_CONFLICT, op + " CAS 失败");
                    }
                }
                bumpGeneration(connection, row.companionId(), row.subjectKey());
                connection.commit();
                return new CommandResult.Applied(id, newRevision);
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                return new CommandResult.Rejected(
                        ErrorCodes.PERSISTENCE_FAILED, op + " 失败: " + ex.getMessage());
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            return new CommandResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, "无法打开数据库连接");
        }
    }

    /**
     * correct 专用 INSERT：悬空 source_turn；{@code supersedes_id} 指旧行；revision 固定从 1。
     *
     * <p>不用 {@link SqliteMemoryCommitWriter#applyOne}，避免按 subject 再盲替其它 ACTIVE。
     */
    private String insertReplacement(
            Connection connection,
            ApprovedMemoryChange change,
            String supersedesId,
            String nowText)
            throws SQLException, JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("v", 1);
        root.put("claim", change.claim());
        if (change.path() != null) {
            root.put("path", change.path());
        } else {
            root.putNull("path");
        }
        String contentJson = objectMapper.writeValueAsString(root);
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
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, NULL, ?, 1, ?, NULL, ?)
                        """)) {
            insert.setString(1, newId);
            insert.setString(2, change.companionIdentity().value());
            insert.setString(3, change.subjectKey());
            insert.setString(4, change.contentKind().name());
            insert.setString(5, change.sourceKind().name());
            insert.setString(6, change.scope().name());
            insert.setString(7, contentJson);
            insert.setString(8, change.proposeId());
            insert.setString(9, TRIAGE_VALIDATE);
            insert.setString(10, MemoryLifecycle.ACTIVE.name());
            insert.setString(11, nowText);
            insert.setString(12, supersedesId);
            insert.setString(13, nowText);
            insert.setDouble(14, change.importance());
            insert.executeUpdate();
        }
        return newId;
    }

    /** 仅当 id 存在且 ACTIVE 时返回当前 revision；否则 null → MEMORY_NOT_FOUND。 */
    private static ActiveRow loadActive(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT revision, companion_identity_id, subject_key FROM memory_record
                        WHERE id = ? AND status = ?
                        """)) {
            ps.setString(1, id);
            ps.setString(2, MemoryLifecycle.ACTIVE.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ActiveRow(
                        rs.getLong("revision"),
                        rs.getString("companion_identity_id"),
                        rs.getString("subject_key"));
            }
        }
    }

    /**
     * Review batch/APPLY/job completion form one fenced transaction. If any write or completion
     * CAS fails, every memory change and its idempotency ledger row rolls back together.
     */
    @Override
    public ApplyResult applyAndComplete(
            MemoryReviewLease lease, List<ApprovedMemoryChange> changes) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(changes, "changes");
        Map<String, ApprovedMemoryChange> uniqueDrafts = new LinkedHashMap<>();
        for (ApprovedMemoryChange change : changes) {
            Objects.requireNonNull(change, "change");
            if (!ApprovedMemoryChange.PROPOSE_LLM_REVIEW.equals(change.proposeId())) {
                throw new IllegalArgumentException("Review batch requires proposeId=llm_review");
            }
            uniqueDrafts.putIfAbsent(reviewDraftKey(change), change);
        }
        Map<ReviewSubject, Integer> subjectCounts = new HashMap<>();
        for (ApprovedMemoryChange change : uniqueDrafts.values()) {
            subjectCounts.merge(
                    new ReviewSubject(
                            change.companionIdentity().value(), change.subjectKey()),
                    1,
                    Integer::sum);
        }
        Set<ReviewSubject> conflictingSubjects = new HashSet<>();
        subjectCounts.forEach(
                (subject, count) -> {
                    if (count > 1) {
                        conflictingSubjects.add(subject);
                    }
                });
        // Competing claims for one subject have no defensible winner; skip all of them.
        List<ApprovedMemoryChange> ordered = new ArrayList<>();
        for (ApprovedMemoryChange change : uniqueDrafts.values()) {
            if (!conflictingSubjects.contains(
                    new ReviewSubject(
                            change.companionIdentity().value(), change.subjectKey()))) {
                ordered.add(change);
            }
        }
        ordered.sort(Comparator.comparing(SqliteMemoryCommand::reviewDraftKey));

        Instant now = Instant.now();
        String nowText = now.toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!fenceReviewLease(connection, lease, nowText)) {
                    if ("SUCCEEDED".equals(loadReviewJobStatus(connection, lease.jobId()))) {
                        connection.commit();
                        return new ApplyResult.Completed(true);
                    }
                    connection.rollback();
                    return new ApplyResult.LeaseLost();
                }

                for (ApprovedMemoryChange change : ordered) {
                    String draftKey = reviewDraftKey(change);
                    if (findAppliedDraft(connection, lease.jobId(), draftKey) != null) {
                        continue;
                    }
                    try {
                        String memoryRecordId = writer.applyOne(connection, now, change);
                        if (memoryRecordId != null) {
                            recordAppliedDraft(
                                    connection, lease.jobId(), draftKey, memoryRecordId, nowText);
                        }
                    } catch (SqliteMemoryCommitWriter.StaleGenerationException ignored) {
                        // This draft was based on pre-correction/forget state; complete the job safely.
                    }
                }

                if (!completeReviewJob(connection, lease, nowText)) {
                    connection.rollback();
                    return new ApplyResult.LeaseLost();
                }
                connection.commit();
                return new ApplyResult.Completed(false);
            } catch (SQLException | JsonProcessingException ex) {
                rollbackQuietly(connection);
                throw new IllegalStateException("Review batch atomic apply failed", ex);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("无法打开数据库连接以应用 Review batch", ex);
        }
    }

    private static String loadReviewJobStatus(Connection connection, String jobId)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT status FROM memory_review_job WHERE id = ?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** The conditional write obtains SQLite's writer lock before any memory mutation. */
    private static boolean fenceReviewLease(
            Connection connection, MemoryReviewLease lease, String nowText) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE memory_review_job SET updated_at = updated_at
                        WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                          AND lease_until IS NOT NULL
                          AND julianday(lease_until) > julianday(?)
                        """)) {
            ps.setString(1, lease.jobId());
            ps.setString(2, lease.ownerToken());
            ps.setString(3, nowText);
            return ps.executeUpdate() == 1;
        }
    }

    private static boolean completeReviewJob(
            Connection connection, MemoryReviewLease lease, String nowText) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE memory_review_job
                        SET status = 'SUCCEEDED', lease_owner = NULL, lease_until = NULL,
                            last_error = NULL, updated_at = ?
                        WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                          AND lease_until IS NOT NULL
                          AND julianday(lease_until) > julianday(?)
                        """)) {
            ps.setString(1, nowText);
            ps.setString(2, lease.jobId());
            ps.setString(3, lease.ownerToken());
            ps.setString(4, nowText);
            return ps.executeUpdate() == 1;
        }
    }

    private static String findAppliedDraft(Connection connection, String jobId, String draftKey)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT memory_record_id FROM memory_review_job_apply WHERE job_id = ? AND draft_key = ?")) {
            ps.setString(1, jobId);
            ps.setString(2, draftKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void recordAppliedDraft(
            Connection connection,
            String jobId,
            String draftKey,
            String memoryRecordId,
            String nowText)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        INSERT INTO memory_review_job_apply
                            (job_id, draft_key, memory_record_id, created_at)
                        VALUES (?, ?, ?, ?)
                        """)) {
            ps.setString(1, jobId);
            ps.setString(2, draftKey);
            ps.setString(3, memoryRecordId);
            ps.setString(4, nowText);
            ps.executeUpdate();
        }
    }

    /** Semantic fingerprint independent of model output ordering. */
    private static String reviewDraftKey(ApprovedMemoryChange change) {
        StringBuilder canonical = new StringBuilder();
        appendFingerprintField(canonical, change.companionIdentity().value());
        appendFingerprintField(canonical, change.subjectKey());
        appendFingerprintField(canonical, change.claim());
        appendFingerprintField(canonical, change.contentKind().name());
        appendFingerprintField(canonical, change.sourceKind().name());
        appendFingerprintField(canonical, change.scope().name());
        appendFingerprintField(canonical, Double.toHexString(change.importance()));
        appendFingerprintField(canonical, change.path());
        appendFingerprintField(canonical, change.proposeId());
        appendFingerprintField(
                canonical,
                change.expectedGeneration() == null ? null : change.expectedGeneration().toString());
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void appendFingerprintField(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
    }

    /** correct 可保留旧行自己的 subject，但不得与另一条 ACTIVE 记忆形成重复键。 */
    private static boolean hasOtherActiveSubject(
            Connection connection, String companionId, String subjectKey, String oldId)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT 1 FROM memory_record
                        WHERE companion_identity_id = ? AND subject_key = ?
                          AND status = ? AND id <> ?
                        LIMIT 1
                        """)) {
            ps.setString(1, companionId);
            ps.setString(2, subjectKey);
            ps.setString(3, MemoryLifecycle.ACTIVE.name());
            ps.setString(4, oldId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Human-initiated lifecycle changes invalidate every in-flight draft for that subject. */
    private static void bumpGeneration(Connection connection, String companionId, String subjectKey)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO memory_subject_generation "
                        + "(companion_identity_id, subject_key, generation) VALUES (?, ?, 1) "
                        + "ON CONFLICT(companion_identity_id, subject_key) "
                        + "DO UPDATE SET generation = generation + 1")) {
            ps.setString(1, companionId);
            ps.setString(2, subjectKey);
            ps.executeUpdate();
        }
    }

    /** 将部分唯一索引兜住的并发冲突映射为稳定业务结果。 */
    private static boolean isActiveSubjectConstraint(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String message = current.getMessage();
            if ((current.getErrorCode() & 0xff) == 19
                    && message != null
                    && message.toLowerCase(java.util.Locale.ROOT)
                            .contains("memory_record.companion_identity_id, memory_record.subject_key")) {
                return true;
            }
        }
        return false;
    }

    /** 测试/诊断：查 ACTIVE 行 revision（CAS 辅助）。 */
    long readRevision(String memoryId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT revision FROM memory_record WHERE id = ? AND status = ?")) {
            ps.setString(1, memoryId);
            ps.setString(2, MemoryLifecycle.ACTIVE.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("memory 不存在或非 ACTIVE: " + memoryId);
                }
                return rs.getLong(1);
            }
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 调用方看主失败码
        }
    }

    private record ActiveRow(long revision, String companionId, String subjectKey) {}

    private record ReviewSubject(String companionIdentityId, String subjectKey) {}
}
