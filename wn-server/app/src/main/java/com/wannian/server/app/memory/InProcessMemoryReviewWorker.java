package com.wannian.server.app.memory;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.conversation.ConversationMessage;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.DefaultMemoryShape;
import com.wannian.server.kernel.memory.MemoryPolicy;
import com.wannian.server.kernel.memory.MemoryReviewConstants;
import com.wannian.server.kernel.memory.MemoryReviewBatchApplier;
import com.wannian.server.kernel.memory.MemoryReviewLlm;
import com.wannian.server.kernel.memory.MemoryReviewLease;
import com.wannian.server.kernel.memory.MemoryReviewWorkerPort;
import com.wannian.server.kernel.memory.MemoryShape;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.MemoryToolDraft;
import com.wannian.server.kernel.memory.SecretOnlyMemoryPolicy;
import com.wannian.server.kernel.memory.StoredMemoryRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 同 JVM 单消费者 Review Worker：claim → LLM → Shape → Policy → MemoryCommand；失败不挡聊天。
 */
public final class InProcessMemoryReviewWorker implements MemoryReviewWorkerPort {

    private static final System.Logger LOG =
            System.getLogger(InProcessMemoryReviewWorker.class.getName());
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final DataSource dataSource;
    private final ConversationStore conversations;
    private final MemoryStore memoryStore;
    private final MemoryReviewLlm reviewLlm;
    private final MemoryShape shape;
    private final MemoryPolicy policy;
    private final MemoryReviewBatchApplier batchApplier;
    private final Clock clock;

    public InProcessMemoryReviewWorker(
            DataSource dataSource,
            ConversationStore conversations,
            MemoryStore memoryStore,
            MemoryReviewLlm reviewLlm,
            MemoryReviewBatchApplier batchApplier) {
        this(
                dataSource,
                conversations,
                memoryStore,
                reviewLlm,
                new DefaultMemoryShape(),
                new SecretOnlyMemoryPolicy(),
                batchApplier,
                Clock.system(ZONE));
    }

    public InProcessMemoryReviewWorker(
            DataSource dataSource,
            ConversationStore conversations,
            MemoryStore memoryStore,
            MemoryReviewLlm reviewLlm,
            MemoryShape shape,
            MemoryPolicy policy,
            MemoryReviewBatchApplier batchApplier,
            Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
        this.reviewLlm = Objects.requireNonNull(reviewLlm, "reviewLlm");
        this.shape = Objects.requireNonNull(shape, "shape");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.batchApplier = Objects.requireNonNull(batchApplier, "batchApplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean pollOnce() {
        ClaimedJob job = claimNext();
        if (job == null) {
            return false;
        }
        try {
            List<ApprovedMemoryChange> changes = process(job);
            MemoryReviewBatchApplier.ApplyResult result =
                    batchApplier.applyAndComplete(
                            new MemoryReviewLease(job.id(), job.leaseOwner()), changes);
            if (result instanceof MemoryReviewBatchApplier.ApplyResult.LeaseLost) {
                LOG.log(
                        System.Logger.Level.INFO,
                        () -> "Review job lease 已被回收，丢弃迟到草案 id=" + job.id());
            }
        } catch (RuntimeException ex) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () ->
                            "Review job 失败 id="
                                    + job.id()
                                    + " conversation="
                                    + job.conversationId()
                                    + " msg="
                                    + ex.getMessage());
            markFailed(job, ex.getMessage());
        }
        return true;
    }

    private List<ApprovedMemoryChange> process(ClaimedJob job) {
        CompanionIdentity companion = new CompanionIdentity(job.companionId());
        ConversationId conversationId = new ConversationId(UUID.fromString(job.conversationId()));
        List<ConversationMessage> recent =
                conversations.listRecentMessages(
                        conversationId, MemoryReviewConstants.RECENT_MESSAGE_LIMIT);
        String transcript = formatTranscript(recent);
        MemoryStore.SubjectSnapshot memorySnapshot = memoryStore.subjectSnapshot(companion);
        List<StoredMemoryRecord> active = memorySnapshot.active();
        java.util.Map<String, Long> generations = memorySnapshot.generations();
        String memorySummary = formatActiveSummary(active);

        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZONE));
        MemoryReviewLlm.ReviewRequest request =
                new MemoryReviewLlm.ReviewRequest(
                        job.conversationId(),
                        companion,
                        DATE.format(now),
                        MemoryReviewConstants.PLACE_ANCHOR_UNSPECIFIED,
                        transcript,
                        memorySummary);

        List<MemoryToolDraft> drafts = reviewLlm.propose(request);
        List<ApprovedMemoryChange> changes = new java.util.ArrayList<>();
        for (MemoryToolDraft draft : drafts) {
            ApprovedMemoryChange change = approvedChange(draft, active, generations);
            if (change != null) {
                changes.add(change);
            }
        }
        return List.copyOf(changes);
    }

    private ApprovedMemoryChange approvedChange(
            MemoryToolDraft draft,
            List<StoredMemoryRecord> activeSnapshot,
            java.util.Map<String, Long> generationSnapshot) {
        if (isDuplicate(draft, activeSnapshot)) {
            return null;
        }
        MemoryShape.ShapeResult shaped = shape.shape(draft);
        if (shaped instanceof MemoryShape.ShapeResult.Rejected) {
            return null;
        }
        MemoryToolDraft accepted = ((MemoryShape.ShapeResult.Accepted) shaped).shaped();
        MemoryPolicy.PolicyResult decided = policy.evaluate(accepted);
        if (decided instanceof MemoryPolicy.PolicyResult.Rejected) {
            return null;
        }
        return ApprovedMemoryChange.fromReviewDraft(accepted)
                .withExpectedGeneration(generationSnapshot.getOrDefault(accepted.subjectKey(), 0L));
    }

    /** 同 subject_key 且 claim 规范化相等 → 跳过（Review 与既有 ACTIVE 去重）。 */
    private static boolean isDuplicate(MemoryToolDraft draft, List<StoredMemoryRecord> active) {
        for (StoredMemoryRecord row : active) {
            if (row.subjectKey().equals(draft.subjectKey()) && row.claim().equals(draft.claim())) {
                return true;
            }
        }
        return false;
    }

    private ClaimedJob claimNext() {
        Instant now = Instant.now(clock);
        String leaseUntil = now.plus(MemoryReviewConstants.LEASE_DURATION).toString();
        String nowText = now.toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                reclaimExpiredLeases(connection, nowText);
                ClaimedJob pending = selectOldestPending(connection);
                if (pending == null) {
                    connection.commit();
                    return null;
                }
                String leaseOwner = UUID.randomUUID().toString();
                try (PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                UPDATE memory_review_job
                                SET status = 'RUNNING', attempt = attempt + 1,
                                    lease_owner = ?, lease_until = ?, updated_at = ?, last_error = NULL,
                                    activity_watermark = CASE
                                        WHEN trigger = 'IDLE' THEN COALESCE(activity_watermark, (
                                            SELECT last_activity_at FROM conversation
                                            WHERE conversation.id = memory_review_job.conversation_id
                                        ))
                                        ELSE activity_watermark
                                    END,
                                    reviewed_activity_watermark = (
                                        SELECT last_activity_at FROM conversation
                                        WHERE conversation.id = memory_review_job.conversation_id
                                    )
                                WHERE id = ? AND status = 'PENDING' AND attempt = ?
                                """)) {
                    ps.setString(1, leaseOwner);
                    ps.setString(2, leaseUntil);
                    ps.setString(3, nowText);
                    ps.setString(4, pending.id());
                    ps.setInt(5, pending.attempt());
                    if (ps.executeUpdate() != 1) {
                        connection.rollback();
                        return null;
                    }
                }
                connection.commit();
                return new ClaimedJob(
                        pending.id(),
                        pending.conversationId(),
                        pending.companionId(),
                        pending.attempt() + 1,
                        leaseOwner);
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                throw new IllegalStateException("claim Review job 失败", ex);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("无法打开数据库连接以 claim Review", ex);
        }
    }

    /** Recover expired attempts while holding the same write transaction used for claiming. */
    private static void reclaimExpiredLeases(Connection connection, String nowText)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE memory_review_job
                        SET status = CASE WHEN attempt >= ? THEN 'DEAD' ELSE 'PENDING' END,
                            last_error = CASE
                                WHEN attempt >= ? THEN 'worker lease expired at attempt limit'
                                ELSE last_error
                            END,
                            lease_owner = NULL, lease_until = NULL, updated_at = ?
                        WHERE status = 'RUNNING'
                          AND lease_until IS NOT NULL
                          AND julianday(lease_until) <= julianday(?)
                        """)) {
            ps.setInt(1, MemoryReviewConstants.MAX_ATTEMPTS);
            ps.setInt(2, MemoryReviewConstants.MAX_ATTEMPTS);
            ps.setString(3, nowText);
            ps.setString(4, nowText);
            ps.executeUpdate();
        }
    }

    private static ClaimedJob selectOldestPending(Connection connection) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT id, conversation_id, companion_id, attempt
                        FROM memory_review_job
                        WHERE status = 'PENDING'
                        ORDER BY created_at ASC
                        LIMIT 1
                        """)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ClaimedJob(
                        rs.getString("id"),
                        rs.getString("conversation_id"),
                        rs.getString("companion_id"),
                        rs.getInt("attempt"),
                        null);
            }
        }
    }

    private void markFailed(ClaimedJob job, String error) {
        String status =
                job.attempt() >= MemoryReviewConstants.MAX_ATTEMPTS ? "DEAD" : "PENDING";
        updateStatus(job, status, truncate(error), Instant.now(clock));
    }

    private boolean updateStatus(ClaimedJob job, String status, String lastError, Instant now) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                UPDATE memory_review_job
                                SET status = ?, last_error = ?, lease_owner = NULL, lease_until = NULL,
                                    updated_at = ?
                                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                                  AND lease_until IS NOT NULL
                                  AND julianday(lease_until) > julianday(?)
                                """)) {
            ps.setString(1, status);
            ps.setString(2, lastError);
            ps.setString(3, now.toString());
            ps.setString(4, job.id());
            ps.setString(5, job.leaseOwner());
            ps.setString(6, now.toString());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            LOG.log(System.Logger.Level.WARNING, () -> "更新 Review job 状态失败 id=" + job.id(), ex);
            return false;
        }
    }

    private static String formatTranscript(List<ConversationMessage> recent) {
        StringBuilder out = new StringBuilder();
        for (ConversationMessage message : recent) {
            if (out.length() > 0) {
                out.append('\n');
            }
            String label =
                    switch (message.role()) {
                        case USER -> "用户: ";
                        case ASSISTANT -> "助手: ";
                    };
            try {
                out.append(label).append(ContextAssembler.textOf(message.contentJson()));
            } catch (RuntimeException ex) {
                out.append(label).append("(无法解析正文)");
            }
        }
        return out.toString();
    }

    private static String formatActiveSummary(List<StoredMemoryRecord> active) {
        StringBuilder out = new StringBuilder();
        int limit = Math.min(active.size(), MemoryReviewConstants.ACTIVE_MEMORY_SUMMARY_LIMIT);
        for (int i = 0; i < limit; i++) {
            StoredMemoryRecord row = active.get(i);
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(row.subjectKey())
                    .append(": ")
                    .append(row.claim())
                    .append(" (importance=")
                    .append(row.importance())
                    .append(')');
        }
        return out.toString();
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 调用方看主失败
        }
    }

    private record ClaimedJob(
            String id, String conversationId, String companionId, int attempt, String leaseOwner) {}
}
