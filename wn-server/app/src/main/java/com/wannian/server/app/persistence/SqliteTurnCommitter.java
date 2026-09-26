package com.wannian.server.app.persistence;

import com.wannian.server.kernel.error.ErrorCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.app.journal.SqliteTurnStepJournal;
import com.wannian.server.kernel.journal.JournalActor;
import com.wannian.server.kernel.journal.JournalJson;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.RunJournalEntry;
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.CommitTurnResult;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import com.wannian.server.kernel.turn.FreezeCommitResult;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.TurnCommitter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link TurnCommitter} 的 SQLite 实现（编排：消息 / Turn / Outbox / 冻结计划；memory/rel 委托 Writer）。
 *
 * <p>幂等键是全局的，绑定会话和原始正文。序号在本事务内分配，不采用调用方数字。
 * 进入 {@code COMMITTING} 必须经 {@link #freezeCommit}，与可恢复计划同事务写入。
 * 一次成功完成必有一条能定位该 Turn 与助手消息的 {@code TurnCompleted} 事件。
 * 已经进入 {@code COMMITTING} 后，lease 过期不再拒绝提交；错误的 executionId 则整笔拒绝。
 *
 * <p>0.2.4-A：实际 Memory 落库后，在同一事务内写入正式 {@code MEMORY_WRITE} 步骤；
 * 失败回滚整笔（含 Message / Turn / Outbox / Memory）。
 */
@Component
public class SqliteTurnCommitter implements TurnCommitter {

    static final String TURN_COMPLETED = "TurnCompleted";
    static final String MESSAGE_COMMITTED = "MessageCommitted";
    private static final String AGGREGATE_TURN = "turn";
    private static final String AGGREGATE_MESSAGE = "message";
    /** 唯一键竞争或 SQLITE_BUSY 的总等待上限。到点返回可重试忙，不假装成功。 */
    private static final long IDEMPOTENCY_WAIT_BUDGET_MS = 200L;
    private static final long RETRY_PAUSE_MS = 15L;
    private static final int ATTEMPT_BUSY_TIMEOUT_MS = 30;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final SqliteFrozenPlanStore frozenPlanStore;
    private final SqliteMemoryCommitWriter memoryWriter;
    private final SqliteRelationshipCommitWriter relationshipWriter;
    private final SqliteTurnStepJournal turnStepJournal;

    /** 测试 / 无 Spring 注入 journal 时：自建与 DataSource 绑定的 journal。 */
    public SqliteTurnCommitter(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, new SqliteTurnStepJournal(dataSource));
    }

    @Autowired
    public SqliteTurnCommitter(
            DataSource dataSource, ObjectMapper objectMapper, SqliteTurnStepJournal turnStepJournal) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.turnStepJournal = Objects.requireNonNull(turnStepJournal, "turnStepJournal");
        this.frozenPlanStore = new SqliteFrozenPlanStore(objectMapper);
        this.memoryWriter = new SqliteMemoryCommitWriter(objectMapper);
        this.relationshipWriter = new SqliteRelationshipCommitWriter(objectMapper);
    }

    @Override
    public ReceiveTurnResult receive(ReceiveTurnPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.userMessage().role() != MessageRole.USER) {
            return new ReceiveTurnResult.Rejected(ErrorCodes.ILLEGAL_ARGUMENT, "userMessage.role 必须为 USER");
        }

        long deadline = System.nanoTime() + IDEMPOTENCY_WAIT_BUDGET_MS * 1_000_000L;
        while (true) {
            try (Connection connection = dataSource.getConnection()) {
                applyAttemptBusyTimeout(connection);
                connection.setAutoCommit(false);
                try {
                    ReceiveTurnResult result = receiveInTransaction(connection, plan);
                    if (result instanceof ReceiveTurnResult.Accepted) {
                        connection.commit();
                    } else {
                        connection.rollback();
                    }
                    return result;
                } catch (SQLException ex) {
                    rollbackQuietly(connection);
                    if (SqliteErrors.isUniqueViolation(ex) || SqliteErrors.isBusy(ex)) {
                        ReceiveTurnResult settled = rereadRequest(plan);
                        if (settled != null) {
                            return settled;
                        }
                        if (System.nanoTime() >= deadline) {
                            return busy(plan.clientRequestId());
                        }
                        pause();
                        continue;
                    }
                    return new ReceiveTurnResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, "接收回合失败");
                } catch (RuntimeException ex) {
                    rollbackQuietly(connection);
                    throw ex;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException ex) {
                if (SqliteErrors.isBusy(ex) && System.nanoTime() < deadline) {
                    pause();
                    continue;
                }
                if (SqliteErrors.isBusy(ex)) {
                    return busy(plan.clientRequestId());
                }
                return new ReceiveTurnResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, "无法打开数据库连接以接收回合");
            }
        }
    }

    @Override
    public FreezeCommitResult freezeCommit(FreezeCommitPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.assistantMessage().role() != MessageRole.ASSISTANT) {
            return new FreezeCommitResult.Rejected(
                    ErrorCodes.ILLEGAL_ARGUMENT, "assistantMessage.role 必须为 ASSISTANT");
        }
        for (CommitTurnPlan.OutboxEventDraft event : plan.additionalOutboxEvents()) {
            if (TURN_COMPLETED.equals(event.eventType())
                    && (!AGGREGATE_TURN.equals(event.aggregateType())
                            || !plan.turnId().asString().equals(event.aggregateId()))) {
                return new FreezeCommitResult.Rejected(
                        ErrorCodes.ILLEGAL_ARGUMENT, "附加完成事件必须指向本次 Turn");
            }
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                FreezeCommitResult result = freezeInTransaction(connection, plan);
                if (result instanceof FreezeCommitResult.Frozen) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return result;
            } catch (SQLException | JsonProcessingException ex) {
                rollbackQuietly(connection);
                return new FreezeCommitResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, "冻结完成计划失败");
            } catch (RuntimeException ex) {
                rollbackQuietly(connection);
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            return new FreezeCommitResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, "无法打开数据库连接以冻结完成计划");
        }
    }

    @Override
    public Optional<CommitTurnPlan> frozenCommitPlan(TurnId turnId) {
        Objects.requireNonNull(turnId, "turnId");
        try (Connection connection = dataSource.getConnection()) {
            TurnRow turn = loadTurn(connection, turnId);
            if (turn == null || !TurnStatus.COMMITTING.name().equals(turn.status())) {
                return Optional.empty();
            }
            SqliteFrozenPlanStore.FrozenPlan frozen = frozenPlanStore.load(connection, turnId);
            if (frozen == null) {
                return Optional.empty();
            }
            return Optional.of(
                    CommitTurnPlan.completeTurn(
                            turnId,
                            turn.revision(),
                            frozen.executionId(),
                            frozen.assistantMessage(),
                            frozen.additionalEvents(),
                            frozen.approvedMemoryChanges(),
                            frozen.approvedRelationshipChange()));
        } catch (SQLException | JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    @Override
    public CommitTurnResult commit(CommitTurnPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.hasUnsupportedExtensions()) {
            return new CommitTurnResult.Rejected(
                    ErrorCodes.UNSUPPORTED_EXTENSION, "本批尚不支持 Task 变更");
        }
        if (plan.assistantMessage().role() != MessageRole.ASSISTANT) {
            return new CommitTurnResult.Rejected(
                    ErrorCodes.ILLEGAL_ARGUMENT, "assistantMessage.role 必须为 ASSISTANT");
        }
        CommitTurnResult mismatched = mismatchedCompletion(plan);
        if (mismatched != null) {
            return mismatched;
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                CommitTurnResult result = commitInTransaction(connection, plan);
                if (result instanceof CommitTurnResult.Committed) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return result;
            } catch (SQLException | JsonProcessingException ex) {
                rollbackQuietly(connection);
                return new CommitTurnResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, "完成回合提交失败");
            } catch (RuntimeException ex) {
                rollbackQuietly(connection);
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            return new CommitTurnResult.Rejected(ErrorCodes.PERSISTENCE_FAILED, "无法打开数据库连接以完成回合提交");
        }
    }

    private ReceiveTurnResult receiveInTransaction(Connection connection, ReceiveTurnPlan plan)
            throws SQLException {
        ConversationGate gate = loadConversationGate(connection, plan.conversationId().asString());
        if (gate == null) {
            return new ReceiveTurnResult.Rejected(
                    ErrorCodes.CONVERSATION_NOT_FOUND,
                    "会话不存在，请先创建会话: " + plan.conversationId().asString());
        }
        if (!"ACTIVE".equals(gate.status())) {
            return new ReceiveTurnResult.Rejected(
                    ErrorCodes.CONVERSATION_NOT_ACTIVE,
                    "会话状态为 " + gate.status() + "，不能接收新消息");
        }

        ExistingTurn existing = findByClientRequestId(connection, plan.clientRequestId());
        ReceiveTurnResult settled = settleExisting(plan, existing, connection);
        if (settled != null) {
            return settled;
        }

        String now = Instant.now().toString();
        int sequenceNo = allocateMessageSequence(connection, plan.conversationId().asString());
        existing = findByClientRequestId(connection, plan.clientRequestId());
        settled = settleExisting(plan, existing, connection);
        if (settled != null) {
            return settled;
        }
        insertUserMessage(connection, plan, sequenceNo, now, gate);
        insertReceivedTurn(connection, plan, now);
        return new ReceiveTurnResult.Accepted(plan.turnId(), false);
    }

    /**
     * 锁住会话序号之后再看一次幂等键。另一笔事务若已提交同一请求，这里回放或冲突，不再插入。
     * 序号增量会随回滚撤销；若已分配后才发现重复，留下空洞是允许的。
     */
    private ReceiveTurnResult settleExisting(
            ReceiveTurnPlan plan, ExistingTurn existing, Connection connection) throws SQLException {
        if (existing == null) {
            return null;
        }
        if (!existing.conversationId().equals(plan.conversationId().asString())) {
            return new ReceiveTurnResult.Conflict(
                    existing.turnId(),
                    "clientRequestId 已绑定其他会话: " + plan.clientRequestId());
        }
        String storedContent = loadMessageContent(connection, existing.inputMessageId());
        if (sameEnvelope(storedContent, plan.userMessage().contentJson())) {
            return new ReceiveTurnResult.Accepted(existing.turnId(), true);
        }
        return new ReceiveTurnResult.Conflict(
                existing.turnId(), "clientRequestId 已存在但正文不一致: " + plan.clientRequestId());
    }

    private ReceiveTurnResult rereadRequest(ReceiveTurnPlan plan) {
        try (Connection connection = dataSource.getConnection()) {
            ExistingTurn existing = findByClientRequestId(connection, plan.clientRequestId());
            ReceiveTurnResult settled = settleExisting(plan, existing, connection);
            return settled;
        } catch (SQLException ex) {
            return null;
        }
    }

    private FreezeCommitResult freezeInTransaction(Connection connection, FreezeCommitPlan plan)
            throws SQLException, JsonProcessingException {
        TurnId turnId = plan.turnId();
        TurnRow turn = loadTurn(connection, turnId);
        if (turn == null) {
            return new FreezeCommitResult.Rejected(ErrorCodes.TURN_NOT_FOUND, "回合不存在: " + turnId.asString());
        }
        if (!TurnStatus.RUNNING.name().equals(turn.status())) {
            return new FreezeCommitResult.Rejected(
                    ErrorCodes.ILLEGAL_STATUS, "冻结完成计划要求状态为 RUNNING，当前为 " + turn.status());
        }
        if (!plan.expectedExecutionId().equals(turn.executionId())) {
            return new FreezeCommitResult.Rejected(
                    ErrorCodes.OWNER_MISMATCH, "executionId 与库中冻结身份不一致: " + turnId.asString());
        }
        if (turn.revision() != plan.expectedTurnRevision()) {
            return new FreezeCommitResult.RevisionConflict(turnId, turn.revision());
        }
        Instant expiresAt = loadClaimExpiresAt(connection, turnId);
        if (expiresAt == null || !expiresAt.isAfter(plan.now())) {
            return new FreezeCommitResult.Rejected(ErrorCodes.CLAIM_EXPIRED, "lease 已过期，不能进入 COMMITTING");
        }
        if (frozenPlanStore.load(connection, turnId) != null) {
            return new FreezeCommitResult.Rejected(
                    "PLAN_ALREADY_FROZEN", "该回合已有完成计划，不能再次冻结");
        }

        frozenPlanStore.insert(connection, plan);
        long newRevision = turn.revision() + 1;
        int updated =
                advanceToCommitting(
                        connection,
                        turnId,
                        plan.expectedTurnRevision(),
                        plan.expectedExecutionId(),
                        plan.now(),
                        newRevision);
        if (updated != 1) {
            TurnRow again = loadTurn(connection, turnId);
            long actual = again == null ? -1L : again.revision();
            return new FreezeCommitResult.RevisionConflict(turnId, actual);
        }
        return new FreezeCommitResult.Frozen(turnId, newRevision);
    }

    private CommitTurnResult commitInTransaction(Connection connection, CommitTurnPlan plan)
            throws SQLException, JsonProcessingException {
        TurnId turnId = plan.turnId();
        TurnRow turn = loadTurn(connection, turnId);
        if (turn == null) {
            return new CommitTurnResult.Rejected(ErrorCodes.TURN_NOT_FOUND, "回合不存在: " + turnId.asString());
        }
        if (TurnStatus.COMPLETED.name().equals(turn.status())) {
            if (!plan.expectedExecutionId().equals(turn.executionId())) {
                return ownerMismatch(turnId);
            }
            return new CommitTurnResult.Committed(turnId, turn.revision());
        }
        if (!TurnStatus.COMMITTING.name().equals(turn.status())) {
            return new CommitTurnResult.Rejected(
                    ErrorCodes.ILLEGAL_STATUS, "完成提交要求状态为 COMMITTING，当前为 " + turn.status());
        }
        if (!plan.expectedExecutionId().equals(turn.executionId())) {
            return ownerMismatch(turnId);
        }
        if (turn.revision() != plan.expectedTurnRevision()) {
            return new CommitTurnResult.RevisionConflict(turnId, turn.revision());
        }
        SqliteFrozenPlanStore.FrozenPlan frozen = frozenPlanStore.load(connection, turnId);
        if (frozen == null) {
            return new CommitTurnResult.Rejected(
                    ErrorCodes.MISSING_COMMIT_PLAN, "COMMITTING 缺少可恢复完成计划，不能提交");
        }
        CommitTurnResult mismatch = mismatchedFrozenPlan(plan, frozen);
        if (mismatch != null) {
            return mismatch;
        }

        String now = Instant.now().toString();
        int sequenceNo = allocateMessageSequence(connection, turn.conversationId());
        ConversationGate gate = loadConversationGate(connection, turn.conversationId());
        insertAssistantMessage(
                connection,
                plan,
                turn.conversationId(),
                turnId,
                sequenceNo,
                now,
                gate == null
                        ? new ConversationGate("ACTIVE", "")
                        : gate);
        long newRevision = turn.revision() + 1;
        int updated =
                completeTurn(
                        connection,
                        turnId,
                        plan.expectedTurnRevision(),
                        plan.expectedExecutionId(),
                        plan.assistantMessage().messageId().asString(),
                        newRevision,
                        now);
        if (updated != 1) {
            TurnRow again = loadTurn(connection, turnId);
            long actual = again == null ? -1L : again.revision();
            return new CommitTurnResult.RevisionConflict(turnId, actual);
        }
        insertRequiredCompletion(connection, plan, turn.conversationId(), now);
        insertAdditionalEvents(connection, plan, now);
        Instant writeAt = Instant.parse(now);
        List<SqliteMemoryCommitWriter.AppliedMemoryWrite> appliedMemories =
                memoryWriter.applyAll(connection, turnId, writeAt, plan.approvedMemoryChanges());
        insertMemoryWriteSteps(
                connection, turnId, turn.conversationId(), writeAt, appliedMemories);
        relationshipWriter.apply(connection, turnId, writeAt, plan.approvedRelationshipChange());
        touchConversationActivity(connection, turn.conversationId(), now);
        frozenPlanStore.delete(connection, turnId);
        return new CommitTurnResult.Committed(turnId, newRevision);
    }

    /**
     * 正式 MEMORY_WRITE：仅对应实际落库结果；稳定 id={@code mw:{turnId}:{memoryRecordId}}。
     * 与 Message/Turn/Outbox/Memory 同事务；插入失败抛 SQLException 触发回滚。
     */
    private void insertMemoryWriteSteps(
            Connection connection,
            TurnId turnId,
            String conversationId,
            Instant writeAt,
            List<SqliteMemoryCommitWriter.AppliedMemoryWrite> applied)
            throws SQLException {
        if (applied.isEmpty()) {
            return;
        }
        String turnIdText = turnId.asString();
        for (SqliteMemoryCommitWriter.AppliedMemoryWrite one : applied) {
            String stepId = memoryWriteStepId(turnIdText, one.memoryRecordId());
            String requestJson =
                    JournalJson.object(
                            "v",
                            "1",
                            "proposeId",
                            one.proposeId(),
                            "contentKind",
                            one.contentKind().name(),
                            "scope",
                            one.scope().name());
            String resultJson =
                    one.supersedesId() == null
                            ? JournalJson.object(
                                    "v",
                                    "1",
                                    "memoryRecordId",
                                    one.memoryRecordId(),
                                    "status",
                                    "SUCCEEDED")
                            : JournalJson.object(
                                    "v",
                                    "1",
                                    "memoryRecordId",
                                    one.memoryRecordId(),
                                    "supersedesId",
                                    one.supersedesId(),
                                    "status",
                                    "SUCCEEDED");
            RunJournalEntry draft =
                    new RunJournalEntry(
                            stepId,
                            turnIdText,
                            conversationId,
                            0,
                            JournalActor.SYSTEM,
                            JournalKind.MEMORY_WRITE,
                            requestJson,
                            resultJson,
                            "SUCCEEDED",
                            null,
                            writeAt,
                            writeAt);
            turnStepJournal.insertInTransaction(connection, draft);
        }
    }

    /** 稳定幂等键：COMMITTING 重试不重复插行。 */
    static String memoryWriteStepId(String turnId, String memoryRecordId) {
        return "mw:" + turnId + ":" + memoryRecordId;
    }

    /** 完成回合同事务刷新会话活动时间（IdleScanner 用）。 */
    private static void touchConversationActivity(
            Connection connection, String conversationId, String now) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE conversation
                        SET last_activity_at = ?, updated_at = ?
                        WHERE id = ?
                        """)) {
            ps.setString(1, now);
            ps.setString(2, now);
            ps.setString(3, conversationId);
            ps.executeUpdate();
        }
    }

    private static CommitTurnResult mismatchedFrozenPlan(
            CommitTurnPlan plan, SqliteFrozenPlanStore.FrozenPlan frozen) {
        if (!frozen.executionId().equals(plan.expectedExecutionId())) {
            return new CommitTurnResult.Rejected(
                    ErrorCodes.PLAN_MISMATCH, "提交计划的 executionId 与冻结计划不一致");
        }
        var expected = frozen.assistantMessage();
        var actual = plan.assistantMessage();
        if (!expected.messageId().equals(actual.messageId())
                || !expected.contentJson().equals(actual.contentJson())
                || expected.role() != actual.role()) {
            return new CommitTurnResult.Rejected(
                    ErrorCodes.PLAN_MISMATCH, "提交计划的助手消息与冻结计划不一致");
        }
        if (frozen.additionalEvents().size() != plan.outboxEvents().size()) {
            return new CommitTurnResult.Rejected(
                    ErrorCodes.PLAN_MISMATCH, "提交计划的附加事件与冻结计划不一致");
        }
        for (int i = 0; i < frozen.additionalEvents().size(); i++) {
            var left = frozen.additionalEvents().get(i);
            var right = plan.outboxEvents().get(i);
            if (!left.eventId().equals(right.eventId())
                    || !left.aggregateType().equals(right.aggregateType())
                    || !left.aggregateId().equals(right.aggregateId())
                    || !left.eventType().equals(right.eventType())
                    || !left.payloadJson().equals(right.payloadJson())) {
                return new CommitTurnResult.Rejected(
                        ErrorCodes.PLAN_MISMATCH, "提交计划的附加事件与冻结计划不一致");
            }
        }
        if (!frozen.approvedMemoryChanges().equals(plan.approvedMemoryChanges())) {
            return new CommitTurnResult.Rejected(
                    ErrorCodes.PLAN_MISMATCH, "提交计划的记忆变更与冻结计划不一致");
        }
        if (!Objects.equals(
                frozen.approvedRelationshipChange(), plan.approvedRelationshipChange())) {
            return new CommitTurnResult.Rejected(
                    ErrorCodes.PLAN_MISMATCH, "提交计划的关系变更与冻结计划不一致");
        }
        return null;
    }

    private CommitTurnResult mismatchedCompletion(CommitTurnPlan plan) {
        for (CommitTurnPlan.OutboxEventDraft event : plan.outboxEvents()) {
            if (!TURN_COMPLETED.equals(event.eventType())) {
                continue;
            }
            if (!AGGREGATE_TURN.equals(event.aggregateType())
                    || !plan.turnId().asString().equals(event.aggregateId())) {
                return new CommitTurnResult.Rejected(
                        ErrorCodes.ILLEGAL_ARGUMENT, "完成事件必须指向本次 Turn，不能用错配事件代替");
            }
        }
        return null;
    }

    private void insertRequiredCompletion(
            Connection connection, CommitTurnPlan plan, String conversationId, String now)
            throws SQLException {
        insertMessageCommitted(connection, plan, conversationId, now);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("v", 1);
        payload.put("turnId", plan.turnId().asString());
        payload.put("conversationId", conversationId);
        payload.put("assistantMessageId", plan.assistantMessage().messageId().asString());
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new SQLException("无法序列化完成事件", ex);
        }
        insertOutbox(
                connection,
                UUID.randomUUID().toString(),
                AGGREGATE_TURN,
                plan.turnId().asString(),
                TURN_COMPLETED,
                payloadJson,
                allocateOutboxSequence(connection),
                now);
    }

    private void insertMessageCommitted(
            Connection connection, CommitTurnPlan plan, String conversationId, String now)
            throws SQLException {
        var assistant = plan.assistantMessage();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("v", 1);
        payload.put("conversationId", conversationId);
        payload.put("turnId", plan.turnId().asString());
        payload.put("messageId", assistant.messageId().asString());
        payload.put("role", assistant.role().name());
        payload.put("textPreview", previewText(assistant.contentJson()));
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new SQLException("无法序列化 MessageCommitted", ex);
        }
        insertOutbox(
                connection,
                UUID.randomUUID().toString(),
                AGGREGATE_MESSAGE,
                assistant.messageId().asString(),
                MESSAGE_COMMITTED,
                payloadJson,
                allocateOutboxSequence(connection),
                now);
    }

    private static String previewText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        int key = contentJson.indexOf("\"text\"");
        if (key < 0) {
            return clip(contentJson, 120);
        }
        int colon = contentJson.indexOf(':', key);
        int firstQuote = contentJson.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = firstQuote + 1; i < contentJson.length(); i++) {
            char c = contentJson.charAt(i);
            if (c == '\\' && i + 1 < contentJson.length()) {
                sb.append(contentJson.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return clip(sb.toString(), 120);
    }

    private static String clip(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }

    private void insertAdditionalEvents(Connection connection, CommitTurnPlan plan, String now)
            throws SQLException {
        for (CommitTurnPlan.OutboxEventDraft event : plan.outboxEvents()) {
            if (TURN_COMPLETED.equals(event.eventType())) {
                continue;
            }
            insertOutbox(
                    connection,
                    event.eventId(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.eventType(),
                    event.payloadJson(),
                    allocateOutboxSequence(connection),
                    now);
        }
    }

    private static CommitTurnResult ownerMismatch(TurnId turnId) {
        return new CommitTurnResult.Rejected(
                ErrorCodes.OWNER_MISMATCH, "executionId 与冻结的执行尝试不一致: " + turnId.asString());
    }

    private static ReceiveTurnResult busy(String clientRequestId) {
        return new ReceiveTurnResult.Rejected(
                ErrorCodes.RETRYABLE_BUSY, "幂等键竞争尚未落定，请重试: " + clientRequestId);
    }

    /** 信封按 HTTP 已确定的序列化文本比较，不另做通用 JSON 语义比较。 */
    private static boolean sameEnvelope(String stored, String incoming) {
        return Objects.equals(stored, incoming);
    }

    private static void applyAttemptBusyTimeout(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + ATTEMPT_BUSY_TIMEOUT_MS);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 调用方只看原失败对应的 Rejected，不把回滚失败再抛出去。
        }
    }

    private static ConversationGate loadConversationGate(Connection connection, String conversationId)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT status, title FROM conversation WHERE id = ?")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String title = rs.getString("title");
                return new ConversationGate(rs.getString("status"), title == null ? "" : title);
            }
        }
    }

    private static String extractMessageText(String contentJson) {
        return SqliteConversationStore.extractText(contentJson);
    }

    private record ConversationGate(String status, String title) {}

    private static ExistingTurn findByClientRequestId(Connection connection, String clientRequestId)
            throws SQLException {
        String sql =
                """
                SELECT id, conversation_id, input_message_id
                FROM turn
                WHERE client_request_id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, clientRequestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ExistingTurn(
                        new TurnId(UUID.fromString(rs.getString("id"))),
                        rs.getString("conversation_id"),
                        rs.getString("input_message_id"));
            }
        }
    }

    private static String loadMessageContent(Connection connection, String messageId)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT content_json FROM message WHERE id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        }
    }

    private static int allocateMessageSequence(Connection connection, String conversationId)
            throws SQLException {
        String sql =
                """
                UPDATE conversation
                SET next_message_seq = next_message_seq + 1
                WHERE id = ?
                RETURNING next_message_seq - 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("会话不存在，无法分配消息序号: " + conversationId);
                }
                return rs.getInt(1);
            }
        }
    }

    private static long allocateOutboxSequence(Connection connection) throws SQLException {
        String sql =
                """
                UPDATE sequence_counter
                SET next_value = next_value + 1
                WHERE name = 'outbox'
                RETURNING next_value - 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("outbox 序号计数器不存在");
            }
            return rs.getLong(1);
        }
    }

    private static void insertUserMessage(
            Connection connection,
            ReceiveTurnPlan plan,
            int sequenceNo,
            String now,
            ConversationGate gate)
            throws SQLException {
        var draft = plan.userMessage();
        String sql =
                """
                INSERT INTO message (
                    id, conversation_id, turn_id, role, content_json, sequence_no, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, draft.messageId().asString());
            ps.setString(2, plan.conversationId().asString());
            ps.setString(3, plan.turnId().asString());
            ps.setString(4, draft.role().name());
            ps.setString(5, draft.contentJson());
            ps.setInt(6, sequenceNo);
            ps.setString(7, now);
            ps.executeUpdate();
        }
        ConversationSearchSync.upsertMessageRow(
                connection,
                plan.conversationId().asString(),
                draft.messageId().asString(),
                gate.status(),
                gate.title(),
                extractMessageText(draft.contentJson()));
    }

    private static void insertReceivedTurn(Connection connection, ReceiveTurnPlan plan, String now)
            throws SQLException {
        String sql =
                """
                INSERT INTO turn (
                    id, conversation_id, client_request_id, status, input_message_id,
                    output_message_id, execution_id, claim_expires_at, revision, error_code,
                    created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, 1, NULL, ?, ?, NULL)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, plan.turnId().asString());
            ps.setString(2, plan.conversationId().asString());
            ps.setString(3, plan.clientRequestId());
            ps.setString(4, TurnStatus.RECEIVED.name());
            ps.setString(5, plan.userMessage().messageId().asString());
            ps.setString(6, now);
            ps.setString(7, now);
            ps.executeUpdate();
        }
    }

    private static TurnRow loadTurn(Connection connection, TurnId turnId) throws SQLException {
        String sql =
                """
                SELECT conversation_id, status, revision, execution_id
                FROM turn
                WHERE id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, turnId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new TurnRow(
                        rs.getString("conversation_id"),
                        rs.getString("status"),
                        rs.getLong("revision"),
                        rs.getString("execution_id"));
            }
        }
    }

    private static Instant loadClaimExpiresAt(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT claim_expires_at FROM turn WHERE id = ?")) {
            ps.setString(1, turnId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String raw = rs.getString(1);
                return raw == null || raw.isBlank() ? null : Instant.parse(raw);
            }
        }
    }

    private static int advanceToCommitting(
            Connection connection,
            TurnId turnId,
            long expectedRevision,
            String expectedExecutionId,
            Instant now,
            long newRevision)
            throws SQLException {
        String sql =
                """
                UPDATE turn
                SET status = ?,
                    revision = ?,
                    updated_at = ?
                WHERE id = ?
                  AND revision = ?
                  AND status = ?
                  AND execution_id = ?
                  AND claim_expires_at > ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TurnStatus.COMMITTING.name());
            ps.setLong(2, newRevision);
            ps.setString(3, now.toString());
            ps.setString(4, turnId.asString());
            ps.setLong(5, expectedRevision);
            ps.setString(6, TurnStatus.RUNNING.name());
            ps.setString(7, expectedExecutionId);
            ps.setString(8, now.toString());
            return ps.executeUpdate();
        }
    }

    private static void insertAssistantMessage(
            Connection connection,
            CommitTurnPlan plan,
            String conversationId,
            TurnId turnId,
            int sequenceNo,
            String now,
            ConversationGate gate)
            throws SQLException {
        var draft = plan.assistantMessage();
        String sql =
                """
                INSERT INTO message (
                    id, conversation_id, turn_id, role, content_json, sequence_no, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, draft.messageId().asString());
            ps.setString(2, conversationId);
            ps.setString(3, turnId.asString());
            ps.setString(4, draft.role().name());
            ps.setString(5, draft.contentJson());
            ps.setInt(6, sequenceNo);
            ps.setString(7, now);
            ps.executeUpdate();
        }
        ConversationSearchSync.upsertMessageRow(
                connection,
                conversationId,
                draft.messageId().asString(),
                gate.status(),
                gate.title(),
                extractMessageText(draft.contentJson()));
    }

    private static int completeTurn(
            Connection connection,
            TurnId turnId,
            long expectedRevision,
            String expectedExecutionId,
            String outputMessageId,
            long newRevision,
            String now)
            throws SQLException {
        String sql =
                """
                UPDATE turn
                SET status = ?,
                    output_message_id = ?,
                    revision = ?,
                    updated_at = ?,
                    completed_at = ?
                WHERE id = ?
                  AND revision = ?
                  AND status = ?
                  AND execution_id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TurnStatus.COMPLETED.name());
            ps.setString(2, outputMessageId);
            ps.setLong(3, newRevision);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.setString(6, turnId.asString());
            ps.setLong(7, expectedRevision);
            ps.setString(8, TurnStatus.COMMITTING.name());
            ps.setString(9, expectedExecutionId);
            return ps.executeUpdate();
        }
    }

    private static void insertOutbox(
            Connection connection,
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payloadJson,
            long sequenceNo,
            String now)
            throws SQLException {
        String sql =
                """
                INSERT INTO outbox_event (
                    id, aggregate_type, aggregate_id, event_type, payload_json, sequence_no, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventId);
            ps.setString(2, aggregateType);
            ps.setString(3, aggregateId);
            ps.setString(4, eventType);
            ps.setString(5, payloadJson);
            ps.setLong(6, sequenceNo);
            ps.setString(7, now);
            ps.executeUpdate();
        }
    }

    private record TurnRow(String conversationId, String status, long revision, String executionId) {}

    private record ExistingTurn(TurnId turnId, String conversationId, String inputMessageId) {}

}
