package com.wannian.server.app.journal;

import com.wannian.server.kernel.journal.JournalActor;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.RunJournalEntry;
import com.wannian.server.kernel.journal.TurnStepStore;
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

/** SQLite {@code turn_step} 只读查询。 */
@Component
public final class SqliteTurnStepStore implements TurnStepStore {

    private final DataSource dataSource;

    public SqliteTurnStepStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<RunJournalEntry> listByTurnId(String turnId) {
        Objects.requireNonNull(turnId, "turnId");
        String sql =
                """
                SELECT id, turn_id, conversation_id, step_no, actor, kind,
                       request_json, result_json, status, error_code, started_at, finished_at
                FROM turn_step
                WHERE turn_id = ?
                ORDER BY step_no ASC
                """;
        List<RunJournalEntry> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, turnId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String finished = rs.getString("finished_at");
                    out.add(
                            new RunJournalEntry(
                                    rs.getString("id"),
                                    rs.getString("turn_id"),
                                    rs.getString("conversation_id"),
                                    rs.getInt("step_no"),
                                    JournalActor.valueOf(rs.getString("actor")),
                                    JournalKind.valueOf(rs.getString("kind")),
                                    rs.getString("request_json"),
                                    rs.getString("result_json"),
                                    rs.getString("status"),
                                    rs.getString("error_code"),
                                    Instant.parse(rs.getString("started_at")),
                                    finished == null ? null : Instant.parse(finished)));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 turn_step 失败: " + ex.getMessage(), ex);
        }
        return List.copyOf(out);
    }
}
