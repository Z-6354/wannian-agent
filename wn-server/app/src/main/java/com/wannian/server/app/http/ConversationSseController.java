package com.wannian.server.app.http;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.app.persistence.SqliteOutboxQuery;
import com.wannian.server.app.persistence.SqliteOutboxQuery.OutboxRow;
import com.wannian.server.app.stream.RunEvent;
import com.wannian.server.app.stream.RunEventBus;
import com.wannian.server.app.stream.RunEventBus.SubscriberLimitExceeded;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.turn.TurnRepository;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 按会话订阅：先补发 Outbox（持久 cursor），回放运行环缓，再推临时事件；
 * 连接存续期间有界轮询新 Outbox，保证已连接客户端收到 commit/fail/cancel。
 * 临时事件不用作 Outbox cursor（不设 SSE id）。
 */
@RestController
@RequestMapping("/api/conversations/{conversationId}")
public class ConversationSseController {

    private static final int MAX_CONNECTIONS = 64;
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long OUTBOX_POLL_MS = 400L;
    private static final long HEARTBEAT_MS = 5_000L;

    private final SqliteOutboxQuery outboxQuery;
    private final RunEventBus eventBus;
    private final TurnRepository turnRepository;
    private final AtomicInteger openConnections = new AtomicInteger();
    private final ScheduledExecutorService poller =
            Executors.newScheduledThreadPool(
                    4,
                    r -> {
                        Thread t = new Thread(r, "sse-outbox-poll");
                        t.setDaemon(true);
                        return t;
                    });

    public ConversationSseController(
            SqliteOutboxQuery outboxQuery, RunEventBus eventBus, TurnRepository turnRepository) {
        this.outboxQuery = outboxQuery;
        this.eventBus = eventBus;
        this.turnRepository = turnRepository;
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object subscribe(
            HttpServletRequest request,
            @PathVariable String conversationId,
            @RequestParam(name = "afterSequence", defaultValue = "0") long afterSequence) {
        // 客户端断线后 Spring 可能用 ASYNC/ERROR 重派同一 GET；再开 SseEmitter 会
        //「Cannot start async: [ERROR]」且泄漏 openConnections，最终全员 503 重连。
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return ResponseEntity.noContent().build();
        }
        Optional<ConversationId> parsed = HttpMapping.conversationId(conversationId);
        if (parsed.isEmpty()) {
            return jsonRejected(HttpStatus.BAD_REQUEST, ErrorCodes.ILLEGAL_ARGUMENT, null);
        }
        // 先占槽再校验，避免 check-then-act 竞态；超限立刻归还
        int held = openConnections.incrementAndGet();
        if (held > MAX_CONNECTIONS) {
            openConnections.decrementAndGet();
            return jsonRejected(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.RETRYABLE_BUSY, null);
        }

        AtomicBoolean slotHeld = new AtomicBoolean(true);
        Runnable releaseSlot =
                () -> {
                    if (slotHeld.compareAndSet(true, false)) {
                        openConnections.decrementAndGet();
                    }
                };

        AtomicBoolean cleaned = new AtomicBoolean(false);
        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>(() -> {});
        AtomicReference<ScheduledFuture<?>> pollRef = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> beatRef = new AtomicReference<>();

        Runnable cleanup =
                () -> {
                    if (!cleaned.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        unsubscribeRef.get().run();
                    } finally {
                        cancelQuietly(pollRef.get());
                        cancelQuietly(beatRef.get());
                        releaseSlot.run();
                    }
                };

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        String conversationKey = parsed.get().asString();
        AtomicLong cursor = new AtomicLong(Math.max(0L, afterSequence));
        ConcurrentHashMap<String, Boolean> seenRunKeys = new ConcurrentHashMap<>();

        try {
            // 先挂回调再 schedule，避免极窄窗口内 complete 却无 cleanup
            emitter.onCompletion(cleanup);
            emitter.onTimeout(cleanup);
            emitter.onError(ex -> cleanup.run());

            List<OutboxRow> backlog = outboxQuery.listAfter(conversationKey, cursor.get(), 200);
            for (OutboxRow row : backlog) {
                sendOutbox(emitter, row);
                cursor.set(Math.max(cursor.get(), row.sequenceNo()));
            }

            for (RunEvent recent : replayableRecent(conversationKey)) {
                sendRun(emitter, recent, seenRunKeys);
            }

            unsubscribeRef.set(
                    eventBus.subscribe(
                            conversationKey,
                            event -> {
                                try {
                                    sendRun(emitter, event, seenRunKeys);
                                } catch (IOException ex) {
                                    emitter.completeWithError(ex);
                                }
                            }));

            pollRef.set(
                    poller.scheduleWithFixedDelay(
                            () -> pollOutbox(emitter, conversationKey, cursor),
                            OUTBOX_POLL_MS,
                            OUTBOX_POLL_MS,
                            TimeUnit.MILLISECONDS));

            beatRef.set(
                    poller.scheduleWithFixedDelay(
                            () -> {
                                try {
                                    emitter.send(SseEmitter.event().comment("heartbeat"));
                                } catch (IOException ex) {
                                    emitter.completeWithError(ex);
                                }
                            },
                            HEARTBEAT_MS,
                            HEARTBEAT_MS,
                            TimeUnit.MILLISECONDS));

            emitter.send(SseEmitter.event().comment("connected"));
            return emitter;
        } catch (SubscriberLimitExceeded ex) {
            cleanup.run();
            return jsonRejected(
                    HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.SSE_SUBSCRIBER_LIMIT, ex.getMessage());
        } catch (IllegalStateException ex) {
            cleanup.run();
            // Outbox/持久化包装的 ISE → 依赖不可用，勿当 busy 狂重试
            return jsonRejected(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCodes.DEPENDENCY_UNAVAILABLE,
                    ex.getMessage());
        } catch (IOException ex) {
            cleanup.run();
            return jsonRejected(
                    HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.DEPENDENCY_UNAVAILABLE, "SSE 初始化失败");
        } catch (RuntimeException ex) {
            cleanup.run();
            throw ex;
        }
    }

    /** produces=text/event-stream 时必须显式 JSON，否则 Map 体写入失败变 500。 */
    private static ResponseEntity<Map<String, String>> jsonRejected(
            HttpStatus status, String reasonCode, String detail) {
        if (detail == null || detail.isBlank()) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("result", "rejected", "reasonCode", reasonCode));
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("result", "rejected", "reasonCode", reasonCode, "detail", detail));
    }

    private void pollOutbox(SseEmitter emitter, String conversationKey, AtomicLong cursor) {
        try {
            List<OutboxRow> newer = outboxQuery.listAfter(conversationKey, cursor.get(), 100);
            for (OutboxRow row : newer) {
                sendOutbox(emitter, row);
                cursor.updateAndGet(prev -> Math.max(prev, row.sequenceNo()));
            }
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        } catch (RuntimeException ex) {
            // 单次轮询失败不关连接；下次再试
        }
    }

    private void sendOutbox(SseEmitter emitter, OutboxRow row) throws IOException {
        String type = mapOutboxType(row.eventType());
        emitter.send(
                SseEmitter.event()
                        .id(Long.toString(row.sequenceNo()))
                        .name(type)
                        .data(row.payloadJson()));
    }

    private void sendRun(
            SseEmitter emitter, RunEvent event, ConcurrentHashMap<String, Boolean> seenRunKeys)
            throws IOException {
        String dedupe = event.executionId() + ":" + event.runSeq();
        if (seenRunKeys.putIfAbsent(dedupe, Boolean.TRUE) != null) {
            return;
        }
        if (seenRunKeys.size() > 4000) {
            seenRunKeys.keySet().removeIf(k -> !k.equals(dedupe));
        }
        emitter.send(SseEmitter.event().name(event.type()).data(event.payloadJson()));
    }

    List<RunEvent> replayableRecent(String conversationId) {
        return eventBus.recent(conversationId).stream()
                .filter(event -> isReplayableRecent(conversationId, event))
                .toList();
    }

    private boolean isReplayableRecent(String conversationId, RunEvent event) {
        if (!conversationId.equals(event.conversationId())) {
            return false;
        }
        TurnId turnId;
        try {
            turnId = new TurnId(UUID.fromString(event.turnId()));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
        return turnRepository.find(turnId)
                .filter(turn -> conversationId.equals(turn.conversationId().asString()))
                .filter(
                        turn ->
                                turn.status() != TurnStatus.COMPLETED
                                        && turn.status() != TurnStatus.FAILED
                                        && turn.status() != TurnStatus.CANCELLED)
                .isPresent();
    }

    private static String mapOutboxType(String eventType) {
        return switch (eventType) {
            case "TurnCompleted" -> "turn.completed";
            case "TurnFailed" -> "turn.failed";
            case "TurnCancelled" -> "turn.cancelled";
            case "MessageCommitted" -> "message.committed";
            case "TitleChanged" -> "conversation.titleChanged";
            default -> eventType;
        };
    }

    private static void cancelQuietly(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }
}
