package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.app.stream.DurableTurnScheduler;
import com.wannian.server.app.stream.DurableTurnScheduler.ActiveTurnRegistry;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnRepository;
import com.wannian.server.kernel.turn.TurnTerminalWriter;
import com.wannian.server.kernel.turn.TurnTransitionException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Turn 状态查询与 Stop / 撤队。 */
@RestController
@RequestMapping("/api/conversations/{conversationId}/turns/{turnId}")
public class TurnControlController {

    private final TurnRepository turns;
    private final TurnTerminalWriter terminalWriter;
    private final DurableTurnScheduler scheduler;

    public TurnControlController(
            TurnRepository turns,
            TurnTerminalWriter terminalWriter,
            DurableTurnScheduler scheduler) {
        this.turns = turns;
        this.terminalWriter = terminalWriter;
        this.scheduler = scheduler;
    }

    @GetMapping
    public ResponseEntity<TurnStatusResponse> status(
            @PathVariable String conversationId, @PathVariable String turnId) {
        Optional<Turn> turn = loadOwned(conversationId, turnId);
        if (turn.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(
                            new TurnStatusResponse(
                                    "rejected",
                                    null,
                                    null,
                                    null,
                                    null,
                                    ErrorCodes.TURN_NOT_FOUND,
                                    "回合不存在或不属于该会话"));
        }
        Turn t = turn.get();
        return ResponseEntity.ok(
                new TurnStatusResponse(
                        "ok",
                        t.conversationId().asString(),
                        t.id().asString(),
                        t.status().name(),
                        t.executionId(),
                        t.errorCode(),
                        null));
    }

    @PostMapping("/stop")
    public ResponseEntity<StopTurnResponse> stop(
            @PathVariable String conversationId, @PathVariable String turnId) {
        Optional<Turn> found = loadOwned(conversationId, turnId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(
                            new StopTurnResponse(
                                    "rejected",
                                    null,
                                    null,
                                    null,
                                    ErrorCodes.TURN_NOT_FOUND,
                                    "回合不存在或不属于该会话"));
        }
        Turn turn = found.get();
        return switch (turn.status()) {
            case COMPLETED, FAILED, CANCELLED ->
                    ResponseEntity.ok(
                            new StopTurnResponse(
                                    "ok",
                                    turn.conversationId().asString(),
                                    turn.id().asString(),
                                    turn.status().name(),
                                    null,
                                    "已终态，停止幂等"));
            case COMMITTING ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    new StopTurnResponse(
                                            "rejected",
                                            turn.conversationId().asString(),
                                            turn.id().asString(),
                                            turn.status().name(),
                                            ErrorCodes.STOP_NOT_ALLOWED,
                                            "正在完成提交，无法停止"));
            case RECEIVED -> dequeue(turn);
            case CLAIMED, RUNNING -> cancelRunning(turn);
        };
    }

    private ResponseEntity<StopTurnResponse> dequeue(Turn turn) {
        Instant now = Instant.now();
        long revision = turn.revision();
        try {
            turn.cancel(now);
        } catch (TurnTransitionException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            new StopTurnResponse(
                                    "rejected",
                                    turn.conversationId().asString(),
                                    turn.id().asString(),
                                    turn.status().name(),
                                    ex.reasonCode(),
                                    ex.getMessage()));
        }
        SaveTurnResult saved = terminalWriter.saveCancelled(turn, revision, now);
        if (!(saved instanceof SaveTurnResult.Saved)) {
            Optional<Turn> again = turns.find(turn.id());
            if (again.isPresent() && again.get().status() == TurnStatus.CANCELLED) {
                return ResponseEntity.ok(
                        new StopTurnResponse(
                                "ok",
                                turn.conversationId().asString(),
                                turn.id().asString(),
                                TurnStatus.CANCELLED.name(),
                                null,
                                "排队项已取消"));
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(
                            new StopTurnResponse(
                                    "rejected",
                                    turn.conversationId().asString(),
                                    turn.id().asString(),
                                    null,
                                    ErrorCodes.CANCEL_FAILED,
                                    "撤队落库失败"));
        }
        scheduler.wake();
        return ResponseEntity.ok(
                new StopTurnResponse(
                        "ok",
                        turn.conversationId().asString(),
                        turn.id().asString(),
                        TurnStatus.CANCELLED.name(),
                        null,
                        "排队项已取消"));
    }

    private ResponseEntity<StopTurnResponse> cancelRunning(Turn turn) {
        Optional<ActiveTurnRegistry.Active> active = scheduler.findActive(turn.id());
        if (active.isPresent()) {
            active.get().cancelToken().cancel();
            active.get().worker().interrupt();
            // 终态由 TurnEngine.cancelAttempt 落库；此处返回“已请求停止”
            Optional<Turn> latest = turns.find(turn.id());
            String status = latest.map(t -> t.status().name()).orElse(turn.status().name());
            if (TurnStatus.COMMITTING.name().equals(status)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(
                                new StopTurnResponse(
                                        "rejected",
                                        turn.conversationId().asString(),
                                        turn.id().asString(),
                                        status,
                                        ErrorCodes.STOP_NOT_ALLOWED,
                                        "正在完成提交，无法停止"));
            }
            return ResponseEntity.ok(
                    new StopTurnResponse(
                            "ok",
                            turn.conversationId().asString(),
                            turn.id().asString(),
                            status,
                            null,
                            "已请求取消；终态以查询为准"));
        }
        // Worker 尚未登记（CLAIMED 竞态）或进程内无活动：直接领域取消 + Outbox 终态
        return dequeue(turn);
    }

    private Optional<Turn> loadOwned(String conversationId, String turnId) {
        Optional<ConversationId> cid = HttpMapping.conversationId(conversationId);
        Optional<TurnId> tid = HttpMapping.turnId(turnId);
        if (cid.isEmpty() || tid.isEmpty()) {
            return Optional.empty();
        }
        Optional<Turn> found = turns.find(tid.get());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (!found.get().conversationId().equals(cid.get())) {
            return Optional.empty();
        }
        return found;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TurnStatusResponse(
            String result,
            String conversationId,
            String turnId,
            String status,
            String executionId,
            String errorCode,
            String detail) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StopTurnResponse(
            String result,
            String conversationId,
            String turnId,
            String status,
            String reasonCode,
            String detail) {}
}
