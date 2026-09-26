package com.wannian.server.app.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.conversation.ConversationStatus;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.conversation.TitleSource;
import com.wannian.server.kernel.conversation.ConversationHistoryQuery;
import com.wannian.server.kernel.conversation.ConversationHistoryResult;
import com.wannian.server.kernel.conversation.ConversationListQuery;
import com.wannian.server.kernel.conversation.ConversationListResult;
import com.wannian.server.kernel.conversation.ConversationMessage;
import com.wannian.server.kernel.conversation.ConversationMutationResult;
import com.wannian.server.kernel.conversation.ConversationSearchQuery;
import com.wannian.server.kernel.conversation.ConversationSearchResult;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.ConversationSummary;
import com.wannian.server.kernel.conversation.ConversationTitlePolicy;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.conversation.EmptyArchiveCommand;
import com.wannian.server.kernel.conversation.EmptyTrashCommand;
import com.wannian.server.kernel.conversation.EmptyTrashResult;
import com.wannian.server.kernel.conversation.RenameConversationCommand;
import com.wannian.server.kernel.error.ErrorCodes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * {@link ConversationStore} SQLite 实现（0.2.4-B 草稿 · 核心审阅）。
 *
 * <p>列表稳定分页；生命周期 CAS；搜索走 FTS5；清空回收站有界批次且不删 Memory。
 */
@Component
public class SqliteConversationStore implements ConversationStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TITLE_MAX = 80;

    private final DataSource dataSource;
    private final ConversationTitlePolicy titlePolicy;

    public SqliteConversationStore(DataSource dataSource, ConversationTitlePolicy titlePolicy) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.titlePolicy = Objects.requireNonNull(titlePolicy, "titlePolicy");
    }

    @Override
    public CreateConversationResult create(CreateConversationCommand command) {
        Objects.requireNonNull(command, "command");
        ConversationId id = command.conversationId();
        // 重复 id：必须先判定 AlreadyExists。若先 purge，空会话会被清掉再插入，错误变成 Created。
        try {
            if (conversationExists(id)) {
                return new CreateConversationResult.AlreadyExists(id);
            }
        } catch (SQLException ex) {
            return new CreateConversationResult.Rejected(
                    ErrorCodes.PERSISTENCE_FAILED, "无法检查会话是否已存在，id=" + id.asString());
        }
        // 新建前清掉其它空 ACTIVE，避免「点新会话堆空壳」（0.2.4-G）
        try {
            purgeEmptyActiveConversations(50);
        } catch (RuntimeException ignored) {
            // 清理失败不挡创建；启动任务会再扫
        }
        String now = Instant.now().toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String title =
                        command.title().isPresent()
                                ? command.title().get()
                                : titlePolicy.nextDefaultTitle(countConversations(connection));
                insert(connection, id, title, now);
                ConversationSearchSync.upsertMessageRow(
                        connection, id.asString(), "", ConversationStatus.ACTIVE.name(), title, "");
                connection.commit();
                return new CreateConversationResult.Created(id);
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                if (SqliteErrors.isUniqueViolation(ex)) {
                    return new CreateConversationResult.AlreadyExists(id);
                }
                return new CreateConversationResult.Rejected(
                        ErrorCodes.PERSISTENCE_FAILED, "创建会话失败，id=" + id.asString());
            } catch (RuntimeException ex) {
                rollbackQuietly(connection);
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            return new CreateConversationResult.Rejected(
                    ErrorCodes.PERSISTENCE_FAILED, "无法打开数据库连接以创建会话");
        }
    }

    @Override
    public List<ConversationMessage> listRecentMessages(ConversationId conversationId, int limit) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 须为正");
        }
        String sql =
                """
                SELECT id, role, content_json, sequence_no
                FROM message
                WHERE conversation_id = ?
                ORDER BY sequence_no DESC
                LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId.asString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<ConversationMessage> newestFirst = new ArrayList<>();
                while (rs.next()) {
                    newestFirst.add(
                            new ConversationMessage(
                                    new MessageId(UUID.fromString(rs.getString("id"))),
                                    MessageRole.valueOf(rs.getString("role")),
                                    rs.getString("content_json"),
                                    rs.getInt("sequence_no")));
                }
                Collections.reverse(newestFirst);
                return List.copyOf(newestFirst);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "读取近讯失败，conversationId=" + conversationId.asString(), ex);
        }
    }

    @Override
    public ConversationListResult list(ConversationListQuery query) {
        ConversationStatus status = query.statusFilter().orElse(ConversationStatus.ACTIVE);
        boolean active = status == ConversationStatus.ACTIVE;
        String sql = active
                ? """
                  SELECT id, title, status, title_source, revision, created_at, updated_at,
                         last_activity_at, trashed_at, pinned
                  FROM conversation
                  WHERE status = ?
                    AND (? IS NULL OR pinned < ?
                         OR (pinned = ? AND (COALESCE(last_activity_at, created_at) < ?
                         OR (COALESCE(last_activity_at, created_at) = ? AND id < ?))))
                  ORDER BY pinned DESC, COALESCE(last_activity_at, created_at) DESC, id DESC
                  LIMIT ?
                  """
                : """
                  SELECT id, title, status, title_source, revision, created_at, updated_at,
                         last_activity_at, trashed_at, pinned
                  FROM conversation
                  WHERE status = ?
                    AND (? IS NULL OR COALESCE(last_activity_at, created_at) < ?
                         OR (COALESCE(last_activity_at, created_at) = ? AND id < ?))
                  ORDER BY COALESCE(last_activity_at, created_at) DESC, id DESC
                  LIMIT ?
                  """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status.name());
            Cursor cur = Cursor.parse(query.cursor().orElse(null));
            if (cur == null) {
                ps.setNull(2, java.sql.Types.VARCHAR);
                ps.setNull(3, java.sql.Types.VARCHAR);
                ps.setNull(4, java.sql.Types.VARCHAR);
                ps.setNull(5, java.sql.Types.VARCHAR);
                if (active) {
                    ps.setNull(6, java.sql.Types.VARCHAR);
                    ps.setNull(7, java.sql.Types.VARCHAR);
                }
            } else {
                ps.setString(2, "x");
                if (active) {
                    ps.setInt(3, cur.pinned());
                    ps.setInt(4, cur.pinned());
                    ps.setString(5, cur.activityAt());
                    ps.setString(6, cur.activityAt());
                    ps.setString(7, cur.id());
                } else {
                    ps.setString(3, cur.activityAt());
                    ps.setString(4, cur.activityAt());
                    ps.setString(5, cur.id());
                }
            }
            ps.setInt(active ? 8 : 6, query.limit() + 1);
            List<ConversationSummary> rows = readSummaries(ps);
            return page(rows, query.limit());
        } catch (SQLException ex) {
            throw new IllegalStateException("列会话失败", ex);
        }
    }

    @Override
    public Optional<ConversationSummary> findRecentActive() {
        String sql = """
                SELECT id, title, status, title_source, revision, created_at, updated_at,
                       last_activity_at, trashed_at, pinned
                FROM conversation WHERE status = 'ACTIVE'
                ORDER BY COALESCE(last_activity_at, created_at) DESC, id DESC LIMIT 1
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            return readSummaries(ps).stream().findFirst();
        } catch (SQLException ex) {
            throw new IllegalStateException("读取最近活动会话失败", ex);
        }
    }

    @Override
    public Optional<ConversationSummary> find(ConversationId conversationId) {
        String sql =
                """
                SELECT id, title, status, title_source, revision, created_at, updated_at,
                       last_activity_at, trashed_at, pinned
                FROM conversation WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId.asString());
            List<ConversationSummary> rows = readSummaries(ps);
            return rows.stream().findFirst();
        } catch (SQLException ex) {
            throw new IllegalStateException("读会话失败", ex);
        }
    }

    @Override
    public ConversationHistoryResult listMessages(ConversationHistoryQuery query) {
        Optional<ConversationSummary> head = find(query.conversationId());
        if (head.isEmpty()) {
            return new ConversationHistoryResult.Rejected(
                    ErrorCodes.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        int after = query.afterSeq().orElse(0);
        String sql =
                """
                SELECT id, role, content_json, sequence_no, turn_id, created_at
                FROM message
                WHERE conversation_id = ? AND sequence_no > ?
                ORDER BY sequence_no ASC
                LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, query.conversationId().asString());
            ps.setInt(2, after);
            ps.setInt(3, query.limit() + 1);
            try (ResultSet rs = ps.executeQuery()) {
                List<ConversationHistoryResult.HistoryMessage> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(
                            new ConversationHistoryResult.HistoryMessage(
                                    new MessageId(UUID.fromString(rs.getString("id"))),
                                    MessageRole.valueOf(rs.getString("role")),
                                    extractText(rs.getString("content_json")),
                                    rs.getInt("sequence_no"),
                                    rs.getString("turn_id"),
                                    rs.getString("created_at")));
                }
                Optional<Integer> next = Optional.empty();
                if (rows.size() > query.limit()) {
                    rows = rows.subList(0, query.limit());
                    next = Optional.of(rows.get(rows.size() - 1).sequenceNo());
                }
                return new ConversationHistoryResult.Ok(List.copyOf(rows), next);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读历史失败", ex);
        }
    }

    @Override
    public ConversationMutationResult rename(RenameConversationCommand command) {
        String cleaned = cleanTitle(command.title());
        if (cleaned.isEmpty()) {
            return new ConversationMutationResult.Rejected(
                    ErrorCodes.INVALID_TITLE, "标题不能为空");
        }
        return mutate(
                command.conversationId(),
                command.expectedRevision(),
                (connection, row) -> {
                    if (row.status() != ConversationStatus.ACTIVE
                            && row.status() != ConversationStatus.ARCHIVED) {
                        return "会话状态不允许改名: " + row.status();
                    }
                    String now = Instant.now().toString();
                    try (PreparedStatement ps =
                            connection.prepareStatement(
                                    """
                                    UPDATE conversation
                                    SET title = ?, title_source = ?, revision = ?, updated_at = ?
                                    WHERE id = ? AND revision = ?
                                    """)) {
                        ps.setString(1, cleaned);
                        ps.setString(2, TitleSource.MANUAL.name());
                        ps.setLong(3, row.revision() + 1);
                        ps.setString(4, now);
                        ps.setString(5, command.conversationId().asString());
                        ps.setLong(6, command.expectedRevision());
                        if (ps.executeUpdate() != 1) {
                            return ErrorCodes.REVISION_CONFLICT;
                        }
                    }
                    ConversationSearchSync.refreshConversationMeta(
                            connection,
                            command.conversationId().asString(),
                            row.status().name(),
                            cleaned);
                    return null;
                });
    }

    /**
     * 自动标题 CAS + {@code TitleChanged} Outbox（同事务）。仅 AUTO；MANUAL 胜出。
     */
    @Override
    public ConversationMutationResult applyAutoTitle(
            ConversationId id, long expectedRevision, String title, String sourceTurnId) {
        String cleaned = cleanTitle(title);
        if (cleaned.isEmpty()) {
            return new ConversationMutationResult.Rejected(
                    ErrorCodes.INVALID_TITLE, "自动标题为空");
        }
        return mutate(
                id,
                expectedRevision,
                (connection, row) -> {
                    if (row.titleSource() != TitleSource.AUTO) {
                        return ErrorCodes.REVISION_CONFLICT;
                    }
                    if (row.status() != ConversationStatus.ACTIVE
                            && row.status() != ConversationStatus.ARCHIVED) {
                        return "会话状态不允许自动标题: " + row.status();
                    }
                    String now = Instant.now().toString();
                    long newRevision = row.revision() + 1;
                    try (PreparedStatement ps =
                            connection.prepareStatement(
                                    """
                                    UPDATE conversation
                                    SET title = ?, title_source = ?, revision = ?, updated_at = ?
                                    WHERE id = ? AND revision = ? AND title_source = ?
                                    """)) {
                        ps.setString(1, cleaned);
                        ps.setString(2, TitleSource.AUTO.name());
                        ps.setLong(3, newRevision);
                        ps.setString(4, now);
                        ps.setString(5, id.asString());
                        ps.setLong(6, expectedRevision);
                        ps.setString(7, TitleSource.AUTO.name());
                        if (ps.executeUpdate() != 1) {
                            return ErrorCodes.REVISION_CONFLICT;
                        }
                    }
                    insertTitleChangedOutbox(
                            connection,
                            id.asString(),
                            cleaned,
                            newRevision,
                            sourceTurnId,
                            now);
                    ConversationSearchSync.refreshConversationMeta(
                            connection, id.asString(), row.status().name(), cleaned);
                    return null;
                });
    }

    private static void insertTitleChangedOutbox(
            Connection connection,
            String conversationId,
            String title,
            long revision,
            String sourceTurnId,
            String now)
            throws SQLException {
        var payload = JSON.createObjectNode();
        payload.put("v", 1);
        payload.put("conversationId", conversationId);
        payload.put("title", title);
        payload.put("revision", revision);
        payload.put("titleSource", TitleSource.AUTO.name());
        if (sourceTurnId != null && !sourceTurnId.isBlank()) {
            payload.put("turnId", sourceTurnId);
        }
        String payloadJson;
        try {
            payloadJson = JSON.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new SQLException("无法序列化 TitleChanged Outbox", ex);
        }
        long sequence;
        try (PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                UPDATE sequence_counter
                                SET next_value = next_value + 1
                                WHERE name = 'outbox'
                                RETURNING next_value - 1
                                """);
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("outbox 序号计数器不存在");
            }
            sequence = rs.getLong(1);
        }
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        INSERT INTO outbox_event (
                            id, aggregate_type, aggregate_id, event_type, payload_json, sequence_no, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, "conversation");
            ps.setString(3, conversationId);
            ps.setString(4, "TitleChanged");
            ps.setString(5, payloadJson);
            ps.setLong(6, sequence);
            ps.setString(7, now);
            ps.executeUpdate();
        }
    }

    @Override
    public ConversationMutationResult archive(ConversationId id, long expectedRevision) {
        return transition(
                id,
                expectedRevision,
                ConversationStatus.ACTIVE,
                ConversationStatus.ARCHIVED,
                true);
    }

    @Override
    public ConversationMutationResult unarchive(ConversationId id, long expectedRevision) {
        return transition(
                id,
                expectedRevision,
                ConversationStatus.ARCHIVED,
                ConversationStatus.ACTIVE,
                false);
    }

    @Override
    public ConversationMutationResult trash(ConversationId id, long expectedRevision) {
        return mutate(
                id,
                expectedRevision,
                (connection, row) -> {
                    if (row.status() != ConversationStatus.ACTIVE
                            && row.status() != ConversationStatus.ARCHIVED) {
                        return "只有 ACTIVE/ARCHIVED 可移入回收站";
                    }
                    String now = Instant.now().toString();
                    try (PreparedStatement ps =
                            connection.prepareStatement(
                                    """
                                    UPDATE conversation
                                    SET status = ?, pinned = 0, pre_trash_status = ?, trashed_at = ?,
                                        revision = ?, updated_at = ?
                                    WHERE id = ? AND revision = ?
                                      AND NOT EXISTS (
                                        SELECT 1 FROM turn
                                        WHERE conversation_id = conversation.id
                                          AND status IN ('RECEIVED','CLAIMED','RUNNING','COMMITTING')
                                      )
                                    """)) {
                        ps.setString(1, ConversationStatus.TRASHED.name());
                        ps.setString(2, row.status().name());
                        ps.setString(3, now);
                        ps.setLong(4, row.revision() + 1);
                        ps.setString(5, now);
                        ps.setString(6, id.asString());
                        ps.setLong(7, expectedRevision);
                        if (ps.executeUpdate() != 1) {
                            if (SqliteConversationActivity.hasActiveTurn(
                                    connection, id.asString())) {
                                return ErrorCodes.CONVERSATION_BUSY;
                            }
                            return ErrorCodes.REVISION_CONFLICT;
                        }
                    }
                    ConversationSearchSync.refreshConversationMeta(
                            connection, id.asString(), ConversationStatus.TRASHED.name(), row.title());
                    return null;
                });
    }

    @Override
    public ConversationMutationResult restore(ConversationId id, long expectedRevision) {
        return mutate(
                id,
                expectedRevision,
                (connection, row) -> {
                    if (row.status() != ConversationStatus.TRASHED) {
                        return ErrorCodes.NOT_TRASHED;
                    }
                    ConversationStatus back =
                            row.preTrashStatus() == null
                                    ? ConversationStatus.ACTIVE
                                    : row.preTrashStatus();
                    String now = Instant.now().toString();
                    try (PreparedStatement ps =
                            connection.prepareStatement(
                                    """
                                    UPDATE conversation
                                    SET status = ?, pinned = 0, pre_trash_status = NULL, trashed_at = NULL,
                                        revision = ?, updated_at = ?
                                    WHERE id = ? AND revision = ?
                                    """)) {
                        ps.setString(1, back.name());
                        ps.setLong(2, row.revision() + 1);
                        ps.setString(3, now);
                        ps.setString(4, id.asString());
                        ps.setLong(5, expectedRevision);
                        if (ps.executeUpdate() != 1) {
                            return ErrorCodes.REVISION_CONFLICT;
                        }
                    }
                    ConversationSearchSync.refreshConversationMeta(
                            connection, id.asString(), back.name(), row.title());
                    return null;
                });
    }

    @Override
    public ConversationMutationResult pin(ConversationId id, long expectedRevision) {
        return setPinned(id, expectedRevision, true);
    }

    @Override
    public ConversationMutationResult unpin(ConversationId id, long expectedRevision) {
        return setPinned(id, expectedRevision, false);
    }

    private ConversationMutationResult setPinned(ConversationId id, long expectedRevision, boolean pinned) {
        return mutate(id, expectedRevision, (connection, row) -> {
            if (row.status() != ConversationStatus.ACTIVE) {
                return "只有 ACTIVE 会话可置顶";
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE conversation SET pinned = ?, revision = ?, updated_at = ? "
                            + "WHERE id = ? AND revision = ? AND status = 'ACTIVE'")) {
                ps.setInt(1, pinned ? 1 : 0);
                ps.setLong(2, row.revision() + 1);
                ps.setString(3, Instant.now().toString());
                ps.setString(4, id.asString());
                ps.setLong(5, expectedRevision);
                if (ps.executeUpdate() != 1) return ErrorCodes.REVISION_CONFLICT;
            }
            return null;
        });
    }

    @Override
    public ConversationSearchResult search(ConversationSearchQuery query) {
        String q = query.query().trim();
        if (q.isEmpty()) {
            return new ConversationSearchResult(List.of(), Optional.empty());
        }
        // FTS：简单转义双引号；短语用引号包裹降低噪声
        String fts = "\"" + q.replace("\"", "\"\"") + "\"";
        String statusClause;
        ConversationStatus filter = query.statusFilter().orElse(null);
        if (filter == null) {
            statusClause = "s.status IN ('ACTIVE','ARCHIVED')";
        } else {
            statusClause = "s.status = ?";
        }
        String sql =
                """
                SELECT s.conversation_id, s.message_id, s.status, s.title, s.body,
                       snippet(conversation_search, 4, '', '', '…', 12) AS snip
                FROM conversation_search s
                WHERE conversation_search MATCH ?
                  AND %s
                ORDER BY s.conversation_id, s.message_id
                LIMIT ?
                """
                        .formatted(statusClause);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, fts);
            if (filter != null) {
                ps.setString(i++, filter.name());
            }
            ps.setInt(i, query.limit());
            List<ConversationSearchResult.Hit> hits = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String mid = rs.getString("message_id");
                    Optional<MessageId> messageId =
                            mid == null || mid.isBlank()
                                    ? Optional.empty()
                                    : Optional.of(new MessageId(UUID.fromString(mid)));
                    String body = rs.getString("body");
                    String matchSource =
                            (body != null && body.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
                                    ? "body"
                                    : "title";
                    hits.add(
                            new ConversationSearchResult.Hit(
                                    new ConversationId(UUID.fromString(rs.getString("conversation_id"))),
                                    rs.getString("title"),
                                    ConversationStatus.valueOf(rs.getString("status")),
                                    matchSource,
                                    rs.getString("snip"),
                                    messageId));
                }
            }
            return new ConversationSearchResult(hits, Optional.empty());
        } catch (SQLException ex) {
            throw new IllegalStateException("搜索失败", ex);
        }
    }

    @Override
    public EmptyTrashResult emptyTrash(EmptyTrashCommand command) {
        if (!EmptyTrashCommand.CONFIRM_TOKEN.equals(command.confirmToken())) {
            return new EmptyTrashResult.Rejected(
                    ErrorCodes.ILLEGAL_ARGUMENT, "confirmToken 必须为 EMPTY_TRASH");
        }
        return purgeByStatus(ConversationStatus.TRASHED, command.batchLimit(), "清空回收站失败");
    }

    @Override
    public EmptyTrashResult emptyArchive(EmptyArchiveCommand command) {
        if (!EmptyArchiveCommand.CONFIRM_TOKEN.equals(command.confirmToken())) {
            return new EmptyTrashResult.Rejected(
                    ErrorCodes.ILLEGAL_ARGUMENT, "confirmToken 必须为 EMPTY_ARCHIVE");
        }
        return purgeByStatus(ConversationStatus.ARCHIVED, command.batchLimit(), "清空归档失败");
    }

    private EmptyTrashResult purgeByStatus(
            ConversationStatus status, int batchLimit, String failDetail) {
        int deleted = 0;
        int skipped = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<String> ids = listIdsByStatus(connection, status, batchLimit);
                for (String cid : ids) {
                    if (SqliteConversationActivity.hasActiveTurn(connection, cid)) {
                        skipped++;
                        continue;
                    }
                    purgeOne(connection, cid);
                    deleted++;
                }
                connection.commit();
                return new EmptyTrashResult.Ok(deleted, skipped);
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                return new EmptyTrashResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, failDetail);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            return new EmptyTrashResult.Rejected(
                    ErrorCodes.PERSISTENCE_FAILED, "无法打开数据库连接");
        }
    }

    @Override
    public int purgeEmptyActiveConversations(int limit) {
        int capped = Math.min(Math.max(limit, 1), 100);
        int deleted = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<String> ids = listEmptyActiveIds(connection, capped);
                for (String cid : ids) {
                    if (SqliteConversationActivity.hasActiveTurn(connection, cid)) {
                        continue;
                    }
                    purgeOne(connection, cid);
                    deleted++;
                }
                connection.commit();
                return deleted;
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                throw new IllegalStateException("清理空会话失败", ex);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("无法打开数据库连接以清理空会话", ex);
        }
    }

    /**
     * ACTIVE 且闲置早于 cutoff 的会话（有至少一条 message）。供归档调度列候选。
     */
    public List<ConversationSummary> listIdleActiveWithMessages(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        int capped = Math.min(Math.max(limit, 1), 50);
        String sql =
                """
                SELECT c.id, c.title, c.status, c.title_source, c.revision, c.created_at, c.updated_at,
                       c.last_activity_at, c.trashed_at, c.pinned
                FROM conversation c
                WHERE c.status = 'ACTIVE'
                  AND c.pinned = 0
                  AND COALESCE(c.last_activity_at, c.created_at) < ?
                  AND EXISTS (SELECT 1 FROM message m WHERE m.conversation_id = c.id)
                ORDER BY COALESCE(c.last_activity_at, c.created_at) ASC, c.id ASC
                LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, cutoff.toString());
            ps.setInt(2, capped);
            return List.copyOf(readSummaries(ps));
        } catch (SQLException ex) {
            throw new IllegalStateException("列出闲置会话失败", ex);
        }
    }

    private static List<String> listEmptyActiveIds(Connection connection, int limit)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT c.id FROM conversation c
                        WHERE c.status = 'ACTIVE'
                          AND NOT EXISTS (
                            SELECT 1 FROM message m WHERE m.conversation_id = c.id
                          )
                        ORDER BY c.created_at ASC, c.id ASC
                        LIMIT ?
                        """)) {
            ps.setInt(1, limit);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    private static void purgeOne(Connection connection, String conversationId) throws SQLException {
        ConversationSearchSync.deleteConversation(connection, conversationId);
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "DELETE FROM turn_step WHERE conversation_id = ?")) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        DELETE FROM turn_commit_plan
                        WHERE turn_id IN (SELECT id FROM turn WHERE conversation_id = ?)
                        """)) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                connection.prepareStatement("DELETE FROM turn WHERE conversation_id = ?")) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                connection.prepareStatement("DELETE FROM message WHERE conversation_id = ?")) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                connection.prepareStatement("DELETE FROM conversation WHERE id = ?")) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        }
        // 有意不删 memory_record / outbox 全表扫描
    }

    private static List<String> listIdsByStatus(
            Connection connection, ConversationStatus status, int limit) throws SQLException {
        String order =
                status == ConversationStatus.TRASHED
                        ? "ORDER BY trashed_at ASC, id ASC"
                        : "ORDER BY COALESCE(last_activity_at, updated_at, created_at) ASC, id ASC";
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT id FROM conversation WHERE status = ? " + order + " LIMIT ?")) {
            ps.setString(1, status.name());
            ps.setInt(2, limit);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    private ConversationMutationResult transition(
            ConversationId id,
            long expectedRevision,
            ConversationStatus from,
            ConversationStatus to,
            boolean requireIdle) {
        return mutate(
                id,
                expectedRevision,
                (connection, row) -> {
                    if (row.status() != from) {
                        return "期望状态 " + from + "，实际 " + row.status();
                    }
                    String now = Instant.now().toString();
                    String idleClause =
                            requireIdle
                                    ? """
                                       AND NOT EXISTS (
                                         SELECT 1 FROM turn
                                         WHERE conversation_id = conversation.id
                                           AND status IN ('RECEIVED','CLAIMED','RUNNING','COMMITTING')
                                       )
                                      """
                                    : "";
                    try (PreparedStatement ps =
                            connection.prepareStatement(
                                    """
                                    UPDATE conversation
                                    SET status = ?, pinned = 0, revision = ?, updated_at = ?
                                    WHERE id = ? AND revision = ?
                                    """
                                            + idleClause)) {
                        ps.setString(1, to.name());
                        ps.setLong(2, row.revision() + 1);
                        ps.setString(3, now);
                        ps.setString(4, id.asString());
                        ps.setLong(5, expectedRevision);
                        if (ps.executeUpdate() != 1) {
                            if (requireIdle
                                    && SqliteConversationActivity.hasActiveTurn(
                                            connection, id.asString())) {
                                return ErrorCodes.CONVERSATION_BUSY;
                            }
                            return ErrorCodes.REVISION_CONFLICT;
                        }
                    }
                    ConversationSearchSync.refreshConversationMeta(
                            connection, id.asString(), to.name(), row.title());
                    return null;
                });
    }

    @FunctionalInterface
    private interface Mutator {
        /** @return null 成功；否则 reasonCode 或可读 detail（含 ErrorCodes） */
        String apply(Connection connection, Row row) throws SQLException;
    }

    private ConversationMutationResult mutate(
            ConversationId id, long expectedRevision, Mutator mutator) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Row row = lockRow(connection, id.asString());
                if (row == null) {
                    connection.rollback();
                    return new ConversationMutationResult.Rejected(
                            ErrorCodes.CONVERSATION_NOT_FOUND, "会话不存在");
                }
                if (row.revision() != expectedRevision) {
                    connection.rollback();
                    return new ConversationMutationResult.Rejected(
                            ErrorCodes.REVISION_CONFLICT, "revision 不匹配");
                }
                String err = mutator.apply(connection, row);
                if (err != null) {
                    connection.rollback();
                    String code =
                            err.startsWith("CONVERSATION_")
                                            || err.startsWith("REVISION_")
                                            || err.startsWith("INVALID_")
                                            || err.startsWith("NOT_")
                                    ? err
                                    : ErrorCodes.ILLEGAL_STATUS;
                    String detail = code.equals(err) ? defaultDetail(code) : err;
                    return new ConversationMutationResult.Rejected(code, detail);
                }
                connection.commit();
                return find(id)
                        .<ConversationMutationResult>map(ConversationMutationResult.Ok::new)
                        .orElseGet(
                                () ->
                                        new ConversationMutationResult.Rejected(
                                                ErrorCodes.PERSISTENCE_FAILED, "变更后读回失败"));
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                return new ConversationMutationResult.Rejected(
                        ErrorCodes.PERSISTENCE_FAILED, "会话变更失败");
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            return new ConversationMutationResult.Rejected(
                    ErrorCodes.PERSISTENCE_FAILED, "无法打开数据库连接");
        }
    }

    private static String defaultDetail(String code) {
        return switch (code) {
            case ErrorCodes.CONVERSATION_BUSY -> "会话仍有进行中或排队的回合，请先完成或停止";
            case ErrorCodes.REVISION_CONFLICT -> "revision 冲突，请刷新后重试";
            case ErrorCodes.NOT_TRASHED -> "会话不在回收站";
            case ErrorCodes.INVALID_TITLE -> "标题不合法";
            default -> code;
        };
    }

    private static Row lockRow(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT id, title, status, title_source, revision, pre_trash_status, pinned
                        FROM conversation WHERE id = ?
                        """)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String pre = rs.getString("pre_trash_status");
                return new Row(
                        rs.getString("id"),
                        rs.getString("title"),
                        ConversationStatus.valueOf(rs.getString("status")),
                        TitleSource.valueOf(
                                Optional.ofNullable(rs.getString("title_source")).orElse("AUTO")),
                        rs.getLong("revision"),
                        pre == null || pre.isBlank()
                                ? null
                                : ConversationStatus.valueOf(pre),
                        rs.getInt("pinned") != 0);
            }
        }
    }

    private static List<ConversationSummary> readSummaries(PreparedStatement ps)
            throws SQLException {
        List<ConversationSummary> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(
                        new ConversationSummary(
                                new ConversationId(UUID.fromString(rs.getString("id"))),
                                rs.getString("title"),
                                ConversationStatus.valueOf(rs.getString("status")),
                                TitleSource.valueOf(
                                        Optional.ofNullable(rs.getString("title_source"))
                                                .orElse("AUTO")),
                                rs.getLong("revision"),
                                rs.getString("created_at"),
                                rs.getString("updated_at"),
                                rs.getString("last_activity_at"),
                                rs.getString("trashed_at"),
                                rs.getInt("pinned") != 0));
            }
        }
        return out;
    }

    private static ConversationListResult page(List<ConversationSummary> rows, int limit) {
        if (rows.size() <= limit) {
            return new ConversationListResult(rows, Optional.empty());
        }
        List<ConversationSummary> page = List.copyOf(rows.subList(0, limit));
        ConversationSummary last = page.get(page.size() - 1);
        String activity =
                last.lastActivityAt().isBlank() ? last.createdAt() : last.lastActivityAt();
        return new ConversationListResult(
                page, Optional.of(Cursor.encode(last.pinned() ? 1 : 0, activity, last.id().asString())));
    }

    private boolean conversationExists(ConversationId id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT 1 FROM conversation WHERE id = ? LIMIT 1")) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void insert(Connection connection, ConversationId id, String title, String now)
            throws SQLException {
        String sql =
                """
                INSERT INTO conversation
                  (id, title, status, revision, created_at, updated_at, title_source, last_activity_at)
                VALUES (?, ?, ?, 1, ?, ?, 'AUTO', ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.asString());
            ps.setString(2, title);
            ps.setString(3, ConversationStatus.ACTIVE.name());
            ps.setString(4, now);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
        }
    }

    private static long countConversations(Connection connection) throws SQLException {
        try (ResultSet rs =
                connection.createStatement().executeQuery("SELECT COUNT(*) FROM conversation")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String extractText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = JSON.readTree(contentJson);
            JsonNode text = node.get("text");
            return text != null && text.isTextual() ? text.asText() : "";
        } catch (Exception ex) {
            return "";
        }
    }

    public static String cleanTitle(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (c >= 32) {
                out.append(c);
            }
        }
        String t = out.toString().strip().replaceAll(" +", " ");
        if (t.length() > TITLE_MAX) {
            t = t.substring(0, TITLE_MAX).strip();
        }
        return t;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原失败
        }
    }

    private record Row(
            String id,
            String title,
            ConversationStatus status,
            TitleSource titleSource,
            long revision,
            ConversationStatus preTrashStatus,
            boolean pinned) {}

    private record Cursor(int pinned, String activityAt, String id) {
        static Cursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int bar = raw.lastIndexOf('|');
            if (bar <= 0 || bar >= raw.length() - 1) {
                return null;
            }
            int first = raw.indexOf('|');
            if (first == bar) return new Cursor(0, raw.substring(0, bar), raw.substring(bar + 1));
            try {
                return new Cursor(Integer.parseInt(raw.substring(0, first)),
                        raw.substring(first + 1, bar), raw.substring(bar + 1));
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        static String encode(int pinned, String activityAt, String id) {
            return pinned + "|" + activityAt + "|" + id;
        }
    }
}
