package com.wannian.server.app.task;

import com.wannian.server.kernel.task.BackgroundConcurrencyGate;
import com.wannian.server.kernel.task.SubAgentRunRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * bg Run（LEASED|RUNNING）∪ Memory Review 活跃 job 合计 ≤1（2.5.4）。
 */
public final class DefaultBackgroundConcurrencyGate implements BackgroundConcurrencyGate {

    private final SubAgentRunRepository subAgentRunRepository;
    private final DataSource dataSource;
    private final Clock clock;

    public DefaultBackgroundConcurrencyGate(
            SubAgentRunRepository subAgentRunRepository, DataSource dataSource, Clock clock) {
        this.subAgentRunRepository =
                Objects.requireNonNull(subAgentRunRepository, "subAgentRunRepository");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean canStartBackgroundRun() {
        int bg = subAgentRunRepository.countActive();
        int review = countActiveReviewWorkers();
        return bg + review < 1;
    }

    private int countActiveReviewWorkers() {
        Instant now = clock.instant();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT COUNT(*) FROM memory_review_job
                                WHERE status = 'RUNNING'
                                  AND lease_until IS NOT NULL
                                  AND julianday(lease_until) > julianday(?)
                                """)) {
            ps.setString(1, now.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("查询活跃 Memory Review 失败", ex);
        }
    }
}
