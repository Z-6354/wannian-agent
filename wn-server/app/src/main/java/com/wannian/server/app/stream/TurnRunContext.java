package com.wannian.server.app.stream;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.agent.AgentBudget;
import com.wannian.server.kernel.model.ModelStreamObserver;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前 Worker 线程上的 Turn 运行上下文：CancelToken、流式观察、executionId。
 * 仅进程内；重启不保留。
 */
public final class TurnRunContext {

    private static final ThreadLocal<Handle> CURRENT = new ThreadLocal<>();

    private TurnRunContext() {}

    public static void enter(Handle handle) {
        CURRENT.set(Objects.requireNonNull(handle, "handle"));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Handle> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static ModelStreamObserver streamObserverOrNoop() {
        Handle handle = CURRENT.get();
        return handle == null ? ModelStreamObserver.NOOP : handle.streamObserver();
    }

    public static final class Handle {
        private final ConversationId conversationId;
        private final TurnId turnId;
        private final AtomicReference<String> executionId;
        private final AgentBudget.CancelToken cancelToken;
        private final RunEventBus eventBus;
        private final AtomicLong runSeq;

        public Handle(
                ConversationId conversationId,
                TurnId turnId,
                AgentBudget.CancelToken cancelToken,
                RunEventBus eventBus) {
            this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
            this.turnId = Objects.requireNonNull(turnId, "turnId");
            this.cancelToken = Objects.requireNonNull(cancelToken, "cancelToken");
            this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
            this.executionId = new AtomicReference<>("");
            this.runSeq = new AtomicLong(0);
        }

        public ConversationId conversationId() {
            return conversationId;
        }

        public TurnId turnId() {
            return turnId;
        }

        public String executionId() {
            return executionId.get();
        }

        public void updateExecutionId(String id) {
            if (id != null && !id.isBlank()) {
                executionId.set(id);
            }
        }

        public AgentBudget.CancelToken cancelToken() {
            return cancelToken;
        }

        public ModelStreamObserver streamObserver() {
            return delta -> {
                if (delta == null || delta.isEmpty()) {
                    return;
                }
                long seq = runSeq.incrementAndGet();
                eventBus.publish(
                        RunEvent.replyDelta(
                                conversationId.asString(),
                                turnId.asString(),
                                executionId.get(),
                                seq,
                                delta));
            };
        }

        public void publishTurnStarted() {
            long seq = runSeq.incrementAndGet();
            eventBus.publish(
                    RunEvent.turnStarted(
                            conversationId.asString(),
                            turnId.asString(),
                            executionId.get(),
                            seq));
        }

        public void publishToolStarted(
                String callId, String operationId, String name, String argumentsSummary) {
            long seq = runSeq.incrementAndGet();
            eventBus.publish(
                    RunEvent.toolStarted(
                            conversationId.asString(),
                            turnId.asString(),
                            executionId.get(),
                            seq,
                            callId,
                            operationId,
                            name,
                            argumentsSummary));
        }

        public void publishToolUpdated(
                String callId,
                String operationId,
                String name,
                String status,
                String errorCode,
                String resultSummary) {
            long seq = runSeq.incrementAndGet();
            eventBus.publish(
                    RunEvent.toolUpdated(
                            conversationId.asString(),
                            turnId.asString(),
                            executionId.get(),
                            seq,
                            callId,
                            operationId,
                            name,
                            status,
                            errorCode,
                            resultSummary));
        }
    }
}
