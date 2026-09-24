package com.wannian.server.app.journal;

import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.journal.RunJournalEntry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * 按 turnId 单调分配 step_no。
 *
 * <p>首次见到某 turnId 时从 {@code turn_step} 读 {@code MAX(step_no)}，避免进程重启后
 * 与已有行撞 UNIQUE(turn_id, step_no) 导致 SQLite 静默丢步。
 */
public final class SequencingRunJournal implements RunJournal {

    private static final System.Logger LOG = System.getLogger(SequencingRunJournal.class.getName());

    private final RunJournal delegate;
    private final DataSource dataSource;
    private final ConcurrentHashMap<String, AtomicInteger> byTurn = new ConcurrentHashMap<>();

    public SequencingRunJournal(RunJournal delegate, DataSource dataSource) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** 无库时（仅 JSONL）从 0 起编。 */
    public SequencingRunJournal(RunJournal delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dataSource = null;
    }

    @Override
    public void append(RunJournalEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.turnId() == null) {
            delegate.append(entry);
            return;
        }
        int step =
                byTurn.computeIfAbsent(entry.turnId(), this::seedCounter).incrementAndGet();
        if (entry.kind() == com.wannian.server.kernel.journal.JournalKind.FINALIZE) {
            // 回合结束：释放计数器，避免长驻进程无限增长
            byTurn.remove(entry.turnId());
        }
        delegate.append(
                new RunJournalEntry(
                        entry.id(),
                        entry.turnId(),
                        entry.conversationId(),
                        step,
                        entry.actor(),
                        entry.kind(),
                        entry.requestJson(),
                        entry.resultJson(),
                        entry.status(),
                        entry.errorCode(),
                        entry.startedAt(),
                        entry.finishedAt()));
    }

    private AtomicInteger seedCounter(String turnId) {
        return new AtomicInteger(loadMaxStepNo(turnId));
    }

    private int loadMaxStepNo(String turnId) {
        if (dataSource == null) {
            return 0;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT COALESCE(MAX(step_no), 0) FROM turn_step WHERE turn_id = ?")) {
            ps.setString(1, turnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException ex) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () -> "读取 turn_step max(step_no) 失败 turn=" + turnId + ": " + ex.getMessage());
        }
        return 0;
    }
}
