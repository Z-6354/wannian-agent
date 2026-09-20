package com.wannian.server.app.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.app.model.EnabledModelPortResolver.ResolveResult;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.CommitTurnResult;
import com.wannian.server.kernel.turn.ExecutionClaim;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import com.wannian.server.kernel.turn.FreezeCommitResult;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 初步对话：在已启用模型时，把 RECEIVED 回合认领并完成一次直接回答。
 *
 * <p>不是 Agent Loop。不执行工具。未启用模型时保持 RECEIVED，由调用方把原因写进响应。
 */
@Component
public class TurnDialogue {

    private final TurnRepository turns;
    private final TurnCommitter turnCommitter;
    private final EnabledModelPortResolver modelPorts;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    public TurnDialogue(
            TurnRepository turns,
            TurnCommitter turnCommitter,
            EnabledModelPortResolver modelPorts,
            ObjectMapper objectMapper,
            DataSource dataSource) {
        this.turns = Objects.requireNonNull(turns, "turns");
        this.turnCommitter = Objects.requireNonNull(turnCommitter, "turnCommitter");
        this.modelPorts = Objects.requireNonNull(modelPorts, "modelPorts");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public DialogueResult complete(TurnId turnId) {
        Objects.requireNonNull(turnId, "turnId");
        Optional<Turn> found = turns.find(turnId);
        if (found.isEmpty()) {
            return new DialogueResult.Held("TURN_NOT_FOUND", "回合不存在");
        }
        Turn turn = found.get();
        return switch (turn.status()) {
            case COMPLETED -> replayCompleted(turnId);
            case COMMITTING -> finishFrozen(turnId);
            case RECEIVED -> answerReceived(turn);
            case CLAIMED, RUNNING, FAILED, CANCELLED ->
                    new DialogueResult.Held(
                            "ILLEGAL_STATUS", "回合状态为 " + turn.status() + "，不能完成直接回答");
        };
    }

    private DialogueResult answerReceived(Turn turn) {
        ResolveResult resolved = modelPorts.resolve();
        if (resolved instanceof ResolveResult.Rejected rejected) {
            return new DialogueResult.Held(rejected.code(), rejected.detail());
        }
        ResolveResult.Resolved model = (ResolveResult.Resolved) resolved;

        String userText = textFromEnvelope(loadMessageContent(turn.inputMessageId().asString()));
        if (userText == null) {
            return new DialogueResult.Held("INPUT_MISSING", "找不到用户消息正文");
        }

        Instant now = Instant.now();
        ExecutionClaim claim = ExecutionClaim.attempt(now.plusSeconds(60));
        long revision = turn.revision();
        try {
            turn.claim(revision, claim, now);
        } catch (RuntimeException ex) {
            return new DialogueResult.Held("CLAIM_FAILED", ex.getMessage() == null ? "认领失败" : ex.getMessage());
        }
        if (!(turns.save(turn, revision, now) instanceof SaveTurnResult.Saved)) {
            return new DialogueResult.Held("CLAIM_FAILED", "认领未能保存");
        }

        turn = turns.find(turn.id()).orElse(turn);
        revision = turn.revision();
        now = Instant.now();
        try {
            turn.start(now);
        } catch (RuntimeException ex) {
            markFailed(turn.id(), "START_FAILED", now);
            return new DialogueResult.Held("START_FAILED", ex.getMessage() == null ? "无法开始执行" : ex.getMessage());
        }
        if (!(turns.save(turn, revision, now) instanceof SaveTurnResult.Saved)) {
            return new DialogueResult.Held("START_FAILED", "开始执行未能保存");
        }

        turn = turns.find(turn.id()).orElse(turn);
        revision = turn.revision();
        Instant deadline = Instant.now().plusSeconds(30);
        ModelOutcome outcome =
                model.port()
                        .decide(
                                new ModelRequest(List.of(new ModelMessage("user", userText))),
                                new ModelCallContext(
                                        turn.id().asString(),
                                        1,
                                        deadline,
                                        false,
                                        UUID.randomUUID().toString()));

        String replyText =
                switch (outcome) {
                    case ModelOutcome.FinalAnswer answer -> answer.text();
                    case ModelOutcome.ModelRefusal refusal -> refusal.reason();
                    case ModelOutcome.ToolCalls ignored -> null;
                    case ModelOutcome.Failure failure -> {
                        markFailed(turn.id(), failure.code(), Instant.now());
                        yield null;
                    }
                };
        if (replyText == null) {
            if (outcome instanceof ModelOutcome.ToolCalls) {
                markFailed(turn.id(), "TOOL_CALLS_UNSUPPORTED", Instant.now());
                return new DialogueResult.Held("TOOL_CALLS_UNSUPPORTED", "直接回答不支持工具调用响应");
            }
            if (outcome instanceof ModelOutcome.Failure failure) {
                return new DialogueResult.Held(failure.code(), failure.detail());
            }
            return new DialogueResult.Held("MODEL_EMPTY", "模型没有返回正文");
        }

        MessageId assistantId = MessageId.generate();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        assistantId, MessageRole.ASSISTANT, contentJson(replyText), 0);
        now = Instant.now();
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(turn.id(), revision, claim.executionId(), now, assistant));
        if (!(frozen instanceof FreezeCommitResult.Frozen frozenOk)) {
            String detail =
                    switch (frozen) {
                        case FreezeCommitResult.Rejected rejected -> rejected.detail();
                        case FreezeCommitResult.RevisionConflict conflict ->
                                "revision 冲突，实际 " + conflict.actualRevision();
                        case FreezeCommitResult.Frozen ignored -> "unexpected";
                    };
            String code =
                    frozen instanceof FreezeCommitResult.Rejected rejected
                            ? rejected.reasonCode()
                            : "FREEZE_FAILED";
            markFailed(turn.id(), code, Instant.now());
            return new DialogueResult.Held(code, detail);
        }

        TurnId answeredTurnId = turn.id();
        CommitTurnPlan plan =
                turnCommitter
                        .frozenCommitPlan(answeredTurnId)
                        .orElseGet(
                                () ->
                                        CommitTurnPlan.completeTurn(
                                                answeredTurnId,
                                                frozenOk.committingRevision(),
                                                claim.executionId(),
                                                assistant,
                                                List.of()));
        CommitTurnResult committed = turnCommitter.commit(plan);
        if (!(committed instanceof CommitTurnResult.Committed)) {
            String detail =
                    switch (committed) {
                        case CommitTurnResult.Rejected rejected -> rejected.detail();
                        case CommitTurnResult.RevisionConflict conflict ->
                                "revision 冲突，实际 " + conflict.actualRevision();
                        case CommitTurnResult.Committed ignored -> "unexpected";
                    };
            return new DialogueResult.Held("COMMIT_FAILED", detail);
        }
        return new DialogueResult.Replied(replyText);
    }

    private DialogueResult replayCompleted(TurnId turnId) {
        String content = loadOutputContent(turnId.asString());
        String text = textFromEnvelope(content);
        if (text == null) {
            return new DialogueResult.Held("REPLY_MISSING", "已完成回合缺少助手正文");
        }
        return new DialogueResult.Replied(text);
    }

    private DialogueResult finishFrozen(TurnId turnId) {
        Optional<CommitTurnPlan> frozen = turnCommitter.frozenCommitPlan(turnId);
        if (frozen.isEmpty()) {
            return new DialogueResult.Held("MISSING_COMMIT_PLAN", "COMMITTING 缺少可恢复完成计划");
        }
        CommitTurnPlan plan = frozen.get();
        CommitTurnResult committed = turnCommitter.commit(plan);
        if (!(committed instanceof CommitTurnResult.Committed)) {
            String detail =
                    committed instanceof CommitTurnResult.Rejected rejected
                            ? rejected.detail()
                            : "提交冻结计划失败";
            return new DialogueResult.Held("COMMIT_FAILED", detail);
        }
        String text = textFromEnvelope(plan.assistantMessage().contentJson());
        if (text == null) {
            return new DialogueResult.Held("REPLY_MISSING", "冻结计划缺少助手正文");
        }
        return new DialogueResult.Replied(text);
    }

    private void markFailed(TurnId turnId, String code, Instant now) {
        Optional<Turn> latest = turns.find(turnId);
        if (latest.isEmpty()) {
            return;
        }
        Turn current = latest.get();
        if (current.status() != TurnStatus.CLAIMED && current.status() != TurnStatus.RUNNING) {
            return;
        }
        long revision = current.revision();
        try {
            current.fail(code, now);
            turns.save(current, revision, now);
        } catch (RuntimeException ignored) {
            // 对话失败路径以 Held 为准；落 FAILED 是尽力而为。
        }
    }

    private String contentJson(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("v", 1);
        node.put("text", text);
        return node.toString();
    }

    private String textFromEnvelope(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(contentJson);
            JsonNode text = node.get("text");
            if (text == null || !text.isTextual()) {
                return null;
            }
            return text.asText();
        } catch (Exception ex) {
            return null;
        }
    }

    private String loadMessageContent(String messageId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT content_json FROM message WHERE id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (SQLException ex) {
            return null;
        }
    }

    private String loadOutputContent(String turnId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT m.content_json
                                FROM turn t
                                JOIN message m ON m.id = t.output_message_id
                                WHERE t.id = ?
                                """)) {
            ps.setString(1, turnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (SQLException ex) {
            return null;
        }
    }

    public sealed interface DialogueResult {
        record Replied(String text) implements DialogueResult {
            public Replied {
                Objects.requireNonNull(text, "text");
            }
        }

        record Held(String code, String detail) implements DialogueResult {
            public Held {
                Objects.requireNonNull(code, "code");
                Objects.requireNonNull(detail, "detail");
            }
        }
    }
}
