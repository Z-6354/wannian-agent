package com.wannian.server.app.memory;

import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryReviewConstants;
import com.wannian.server.kernel.memory.MemoryReviewScheduler;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 空闲扫：{@code last_activity_at} 超过 30min，且晚于最近 Review 实际读取水位 → enqueue IDLE。
 */
@Component
public class MemoryIdleScanner {

    private final DataSource dataSource;
    private final MemoryReviewScheduler scheduler;

    public MemoryIdleScanner(DataSource dataSource, MemoryReviewScheduler scheduler) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** @return 本轮新入队的 IDLE job 数 */
    public int scanOnce(Instant now) {
        Objects.requireNonNull(now, "now");
        Instant threshold = now.minus(MemoryReviewConstants.IDLE_THRESHOLD);
        List<IdleCandidate> candidates = loadCandidates(threshold);
        int enqueued = 0;
        for (IdleCandidate candidate : candidates) {
            if (!hasNewContentSinceLastReview(candidate)) {
                continue;
            }
            scheduler.enqueue(
                    candidate.conversationId(),
                    new CompanionIdentity(candidate.companionHint()),
                    MemoryReviewScheduler.Trigger.IDLE,
                    candidate.activityWatermark());
            enqueued++;
        }
        return enqueued;
    }

    private List<IdleCandidate> loadCandidates(Instant threshold) {
        String sql =
                """
                SELECT c.id, c.last_activity_at, COALESCE(tp.persona_id,'yanhuo') AS companion_id,
                       COALESCE(MAX(t.updated_at),c.last_activity_at) AS persona_activity_at
                FROM conversation c
                LEFT JOIN turn t ON t.conversation_id=c.id AND t.status='COMPLETED'
                LEFT JOIN turn_persona tp ON tp.turn_id=t.id
                WHERE c.last_activity_at IS NOT NULL AND c.last_activity_at <= ?
                GROUP BY c.id, c.last_activity_at, COALESCE(tp.persona_id,'yanhuo')
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, threshold.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<IdleCandidate> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(
                            new IdleCandidate(
                                    rs.getString("id"),
                                    Instant.parse(rs.getString("last_activity_at")),
                                    rs.getString("last_activity_at"),
                                    rs.getString("companion_id"),
                                    Instant.parse(rs.getString("persona_activity_at"))));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("IdleScanner 查询会话失败", ex);
        }
    }

    private boolean hasNewContentSinceLastReview(IdleCandidate candidate) {
        String reviewedWatermark = latestSucceededReviewWatermark(candidate.conversationId(),candidate.companionHint());
        if (reviewedWatermark == null) {
            return true;
        }
        return candidate.personaLastActivityAt().isAfter(Instant.parse(reviewedWatermark));
    }

    private String latestSucceededReviewWatermark(String conversationId,String companionId) {
        String sql =
                """
                SELECT reviewed_activity_watermark
                FROM memory_review_job
                WHERE conversation_id = ? AND companion_id = ? AND status = 'SUCCEEDED'
                ORDER BY julianday(updated_at) DESC
                LIMIT 1
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, companionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("查询上次 Review 失败", ex);
        }
    }

    private record IdleCandidate(
            String conversationId,
            Instant lastActivityAt,
            String activityWatermark,
            String companionHint,
            Instant personaLastActivityAt) {}
}
