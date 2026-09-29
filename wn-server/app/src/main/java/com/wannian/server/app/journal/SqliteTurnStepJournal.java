package com.wannian.server.app.journal;

import com.wannian.server.kernel.journal.JournalActor;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.journal.RunJournalEntry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 将带 turnId 的条目写入 {@code turn_step}；进程级（无 turnId）跳过，交由 {@link ProcessEventWriter}。
 *
 * <p>2.4.1：{@code step_no} 仅在<strong>同一 JDBC 连接/事务</strong>内通过 {@code MAX(step_no)+1} 分配；
 * 不得先在连接 A 读 MAX、再在连接 B INSERT。
 *
 * <ul>
 *   <li><b>弱路径</b> {@link #append}：自开短事务；失败只警告，不抛回打断 Turn（运行观察）。
 *   <li><b>强路径</b> {@link #insertInTransaction}：使用调用方事务；失败抛 {@link SQLException}
 *       （正式 {@code MEMORY_WRITE}，由 Committer 回滚整笔）。
 * </ul>
 *
 * <p>写入时忽略 {@link RunJournalEntry#stepNo()} 入参，以库内分配为准（A4 将撤掉
 * {@code SequencingRunJournal} 对 SQLite 的缓存权威；JSONL 仍可能暂时带着旧序号）。
 */
public final class SqliteTurnStepJournal implements RunJournal {

    private static final System.Logger LOG = System.getLogger(SqliteTurnStepJournal.class.getName());

    private static final String INSERT_SQL =
            """
            INSERT INTO turn_step (
                id, turn_id, conversation_id, step_no, actor, kind,
                request_json, result_json, status, error_code, started_at, finished_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

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
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertInTransaction(connection, entry);
                connection.commit();
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                LOG.log(
                        System.Logger.Level.WARNING,
                        () ->
                                "turn_step 写入失败 turn="
                                        + entry.turnId()
                                        + " kind="
                                        + entry.kind()
                                        + ": "
                                        + ex.getMessage());
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () ->
                            "turn_step 无法打开连接 turn="
                                    + entry.turnId()
                                    + " kind="
                                    + entry.kind()
                                    + ": "
                                    + ex.getMessage());
        }
    }

    /**
     * 在调用方已有写事务中分配 {@code step_no} 并 INSERT。
     *
     * <p>用于正式 {@code MEMORY_WRITE}：失败必须抛出以便 Committer 回滚。
     * 幂等：若 {@code id} 主键已存在（COMMITTING 重试），视为成功并返回已有语义下的条目
     * （不二次分配 step_no）；其它 SQL 错误原样抛出。
     *
     * @param draft {@code stepNo} 被忽略；{@code id}/{@code turnId}/{@code kind} 等其余字段写入
     * @return 带库内分配 {@code stepNo} 的条目
     */
    public RunJournalEntry insertInTransaction(Connection connection, RunJournalEntry draft)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(draft, "draft");
        if (draft.turnId() == null || draft.turnId().isBlank()) {
            throw new SQLException("turn_step 强写入要求非空 turnId");
        }
        if (draft.kind() == JournalKind.PROCESS_START
                || draft.kind() == JournalKind.PROCESS_SHUTDOWN) {
            throw new SQLException("进程级 kind 不得写入 turn_step: " + draft.kind());
        }
        int stepNo = allocateStepNo(connection, draft.turnId());
        RunJournalEntry stored =
                new RunJournalEntry(
                        draft.id(),
                        draft.turnId(),
                        draft.conversationId(),
                        stepNo,
                        draft.actor(),
                        draft.kind(),
                        draft.requestJson(),
                        draft.resultJson(),
                        draft.status(),
                        draft.errorCode(),
                        draft.startedAt(),
                        draft.finishedAt());
        try {
            insertRow(connection, stored);
            return stored;
        } catch (SQLException ex) {
            if (isPrimaryKeyConflict(ex)) {
                // COMMITTING 恢复：同稳定 id 已存在；MAX 未因失败 INSERT 增加，可安全重读
                RunJournalEntry existing = loadById(connection, draft.id());
                if (existing != null) {
                    return existing;
                }
            }
            throw ex;
        }
    }

    private static RunJournalEntry loadById(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT id, turn_id, conversation_id, step_no, actor, kind,
                               request_json, result_json, status, error_code, started_at, finished_at
                        FROM turn_step WHERE id = ?
                        """)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String finished = rs.getString("finished_at");
                return new RunJournalEntry(
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
                        finished == null ? null : Instant.parse(finished));
            }
        }
    }

    private static int allocateStepNo(Connection connection, String turnId) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT COALESCE(MAX(step_no), 0) FROM turn_step WHERE turn_id = ?")) {
            ps.setString(1, turnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) + 1;
                }
            }
        }
        return 1;
    }

    private static void insertRow(Connection connection, RunJournalEntry entry) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
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
        }
    }

    private static boolean isPrimaryKeyConflict(SQLException ex) {
        String msg = ex.getMessage();
        if (msg != null && msg.toLowerCase().contains("unique")) {
            return true;
        }
        // SQLite SQLITE_CONSTRAINT_PRIMARYKEY / UNIQUE
        return "23000".equals(ex.getSQLState()) || ex.getErrorCode() == 1555 || ex.getErrorCode() == 2067;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort
        }
    }
}
