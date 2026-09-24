package com.wannian.server.app.persistence;

import com.wannian.server.kernel.memory.MemoryRecallTouch;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * {@link MemoryRecallTouch} 的 SQLite 实现：注入成功后 bump {@code last_recalled_at}。
 *
 * <p><b>语义（实施单写死）</b>
 *
 * <ul>
 *   <li><b>best-effort</b>：任一条或整批 JDBC 失败只打 WARNING 日志，<strong>不向</strong>
 *       Assembler / Loop 抛异常（不得挡 assemble / 聊天）。
 *   <li>只更新 {@code status = 'ACTIVE'} 的行；已 FORGOTTEN/SUPERSEDED 静默 0 行，不算错误。
 *   <li>本类不是正式记忆写缝：不改 claim、revision、lifecycle；正式写入仍经 Committer /
 *       {@link SqliteMemoryCommand}。
 *   <li>null / 空白 id 跳过；空列表 no-op。
 * </ul>
 *
 * <p>由 {@code @Component} 注册；TurnEngineConfig 只注入，勿双 bean。
 */
@Component
public class SqliteMemoryRecallTouch implements MemoryRecallTouch {

    private static final int SQLITE_BIND_PARAMETER_LIMIT = 900;

    private static final System.Logger LOG =
            System.getLogger(SqliteMemoryRecallTouch.class.getName());

    private final DataSource dataSource;

    public SqliteMemoryRecallTouch(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 对本轮实际注入的记忆 id 写入 {@code last_recalled_at = now}。
     *
     * <p>每批以一条 UPDATE 写入；按 SQLite 参数上限分批，保证锁等待次数不随
     * 记忆条数线性增长。失败时整批放弃并打一条日志。
     */
    @Override
    public void touchRecalled(List<String> ids, Instant now) {
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(now, "now");
        Set<String> uniqueIds = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                uniqueIds.add(id.trim());
            }
        }
        if (uniqueIds.isEmpty()) {
            return;
        }
        String nowText = now.toString();
        List<String> normalizedIds = new ArrayList<>(uniqueIds);
        for (int start = 0;
                start < normalizedIds.size();
                start += SQLITE_BIND_PARAMETER_LIMIT - 1) {
            List<String> batch = normalizedIds.subList(
                    start,
                    Math.min(start + SQLITE_BIND_PARAMETER_LIMIT - 1, normalizedIds.size()));
            String placeholders =
                    String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            String sql = "UPDATE memory_record SET last_recalled_at = ? "
                    + "WHERE status = 'ACTIVE' AND id IN (" + placeholders + ")";
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, nowText);
                for (int i = 0; i < batch.size(); i++) {
                    ps.setString(i + 2, batch.get(i));
                }
                ps.executeUpdate();
            } catch (SQLException ex) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () -> "bump last_recalled_at 批量失败: " + ex.getMessage());
            }
        }
    }
}
