package com.wannian.server.app.journal;

import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.journal.RunJournalEntry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** 进程启停写入 {@code process_event}，并扇出到 JSONL（经 RunJournal）。 */
public final class ProcessEventWriter {

    private static final System.Logger LOG = System.getLogger(ProcessEventWriter.class.getName());

    private final DataSource dataSource;
    private final RunJournal journal;

    public ProcessEventWriter(DataSource dataSource, RunJournal journal) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    public void writeStart(String payloadJson) {
        write(JournalKind.PROCESS_START, payloadJson);
    }

    public void writeShutdown(String payloadJson) {
        write(JournalKind.PROCESS_SHUTDOWN, payloadJson);
    }

    private void write(JournalKind kind, String payloadJson) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                INSERT INTO process_event (id, kind, payload_json, created_at)
                                VALUES (?, ?, ?, ?)
                                """)) {
            ps.setString(1, id);
            ps.setString(2, kind.name());
            ps.setString(3, payloadJson);
            ps.setString(4, now.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () -> "process_event 写入失败 kind=" + kind + ": " + ex.getMessage());
        }
        try {
            journal.append(
                    new RunJournalEntry(
                            id,
                            null,
                            null,
                            0,
                            com.wannian.server.kernel.journal.JournalActor.SYSTEM,
                            kind,
                            payloadJson,
                            null,
                            "SUCCEEDED",
                            null,
                            now,
                            now));
        } catch (RuntimeException ignored) {
            // JSONL 失败已在 sink 内记录
        }
    }
}
