package com.wannian.server.app.journal;

import com.wannian.server.kernel.journal.JournalKind;
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
 * 运行观察条目的 step 序号装饰器。
 *
 * <p>0.2.4-A：
 * <ul>
 *   <li><b>SQLite 启用</b>（构造时传入 {@link DataSource}）：<strong>不</strong>分配 step_no；
 *       库内序号由 {@link SqliteTurnStepJournal} 在同连接事务中 {@code MAX+1} 决定。
 *       调用方自带的 stepNo（如 TurnEngine 本地计数）仅可能出现在 JSONL，不作 DB 权威。
 *   <li><b>仅 JSONL</b>（无 DataSource）：保留进程内单调编号，并从 0 起编。
 * </ul>
 */
public final class SequencingRunJournal implements RunJournal {

    private static final System.Logger LOG = System.getLogger(SequencingRunJournal.class.getName());

    private final RunJournal delegate;
    /** null = 仅 JSONL，本类负责编号；非 null = SQLite 在 sink 链中，DB 为编号权威。 */
    private final DataSource dataSource;
    private final boolean dbAuthoritative;
    private final ConcurrentHashMap<String, AtomicInteger> byTurn = new ConcurrentHashMap<>();

    /**
     * SQLite（可能叠加 JSONL）模式：不缓存、不改写 step_no。
     *
     * @param dataSource 非 null，仅作「DB 权威」标记；本类不再用它读 MAX
     */
    public SequencingRunJournal(RunJournal delegate, DataSource dataSource) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dbAuthoritative = true;
    }

    /** 无库时（仅 JSONL）从 0 起编。 */
    public SequencingRunJournal(RunJournal delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dataSource = null;
        this.dbAuthoritative = false;
    }

    @Override
    public void append(RunJournalEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.turnId() == null) {
            delegate.append(entry);
            return;
        }
        if (dbAuthoritative) {
            // SqliteTurnStepJournal 忽略入参 stepNo，在短事务内自分配。
            delegate.append(entry);
            return;
        }
        int step = byTurn.computeIfAbsent(entry.turnId(), this::seedCounter).incrementAndGet();
        if (entry.kind() == JournalKind.FINALIZE) {
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

    /** 仅 JSONL 模式使用；无 DataSource 时恒为 0。 */
    private int loadMaxStepNo(String turnId) {
        if (dataSource == null) {
            return 0;
        }
        // dbAuthoritative 构造不会走到编号分支；保留防御读取以免误配。
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
