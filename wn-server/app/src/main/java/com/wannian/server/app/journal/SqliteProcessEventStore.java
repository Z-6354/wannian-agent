package com.wannian.server.app.journal;

import com.wannian.server.kernel.journal.JournalActor;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.ProcessEventStore;
import com.wannian.server.kernel.journal.RunJournalEntry;
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

/** SQLite {@code process_event} 只读查询。 */
@Component
public final class SqliteProcessEventStore implements ProcessEventStore {

    private final DataSource dataSource;

    public SqliteProcessEventStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<RunJournalEntry> listRecent(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 须为正");
        }
        String sql =
                """
                SELECT id, kind, payload_json, created_at
                FROM process_event
                ORDER BY created_at DESC
                LIMIT ?
                """;
        List<RunJournalEntry> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Instant at = Instant.parse(rs.getString("created_at"));
                    out.add(
                            new RunJournalEntry(
                                    rs.getString("id"),
                                    null,
                                    null,
                                    0,
                                    JournalActor.SYSTEM,
                                    JournalKind.valueOf(rs.getString("kind")),
                                    rs.getString("payload_json"),
                                    null,
                                    "SUCCEEDED",
                                    null,
                                    at,
                                    at));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 process_event 失败: " + ex.getMessage(), ex);
        }
        return List.copyOf(out);
    }
}
