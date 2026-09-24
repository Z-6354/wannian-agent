package com.wannian.server.app.journal;

import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.journal.RunJournalEntry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 将带 turnId 的条目写入 {@code turn_step}；进程级（无 turnId）跳过，交由 {@link ProcessEventWriter}。
 */
public final class SqliteTurnStepJournal implements RunJournal {

    private static final System.Logger LOG = System.getLogger(SqliteTurnStepJournal.class.getName());

    private final DataSource dataSource;

    public SqliteTurnStepJournal(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void append(RunJournalEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.turnId() == null) {
            return;
        }
        if (entry.kind() == JournalKind.PROCESS_START
                || entry.kind() == JournalKind.PROCESS_SHUTDOWN) {
            return;
        }
        String sql =
                """
                INSERT INTO turn_step (
                    id, turn_id, conversation_id, step_no, actor, kind,
                    request_json, result_json, status, error_code, started_at, finished_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entry.id());
            ps.setString(2, entry.turnId());
            ps.setString(3, entry.conversationId());
            ps.setInt(4, entry.stepNo());
            ps.setString(5, entry.actor().name());
            ps.setString(6, entry.kind().name());
            ps.setString(7, entry.requestJson());
            ps.setString(8, entry.resultJson());
            ps.setString(9, entry.status());
            ps.setString(10, entry.errorCode());
            ps.setString(11, entry.startedAt().toString());
            ps.setString(12, entry.finishedAt() == null ? null : entry.finishedAt().toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () ->
                            "turn_step 写入失败 turn="
                                    + entry.turnId()
                                    + " kind="
                                    + entry.kind()
                                    + ": "
                                    + ex.getMessage());
        }
    }
}
