package com.wannian.server.app.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 运行中临时事件总线：每会话有界环缓 + 订阅者扇出。
 * 进程重启不保留；去重键由订阅方用 (executionId, runSeq)。
 */
@Component
public class RunEventBus {

    private static final int DEFAULT_BUFFER = 256;
    private static final int MAX_SUBSCRIBERS_PER_CONVERSATION = 8;

    private final int bufferSize;
    private final ConcurrentHashMap<String, ConversationLane> lanes = new ConcurrentHashMap<>();

    public RunEventBus() {
        this(DEFAULT_BUFFER);
    }

    public RunEventBus(int bufferSize) {
        this.bufferSize = Math.max(32, bufferSize);
    }

    public void publish(RunEvent event) {
        Objects.requireNonNull(event, "event");
        ConversationLane lane =
                lanes.computeIfAbsent(event.conversationId(), id -> new ConversationLane(bufferSize));
        lane.publish(event);
    }

    /** @return 取消订阅的 Runnable */
    public Runnable subscribe(String conversationId, Consumer<RunEvent> consumer) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(consumer, "consumer");
        ConversationLane lane =
                lanes.computeIfAbsent(conversationId, id -> new ConversationLane(bufferSize));
        return lane.subscribe(consumer);
    }

    public List<RunEvent> recent(String conversationId) {
        ConversationLane lane = lanes.get(conversationId);
        return lane == null ? List.of() : lane.snapshot();
    }

    private static final class ConversationLane {
        private final int capacity;
        private final ArrayList<RunEvent> ring = new ArrayList<>();
        private final CopyOnWriteArrayList<Consumer<RunEvent>> subscribers = new CopyOnWriteArrayList<>();
        private final Object lock = new Object();

        ConversationLane(int capacity) {
            this.capacity = capacity;
        }

        void publish(RunEvent event) {
            List<Consumer<RunEvent>> targets;
            synchronized (lock) {
                ring.add(event);
                while (ring.size() > capacity) {
                    ring.remove(0);
                }
                targets = List.copyOf(subscribers);
            }
            for (Consumer<RunEvent> subscriber : targets) {
                try {
                    subscriber.accept(event);
                } catch (RuntimeException ignored) {
                }
            }
        }

        Runnable subscribe(Consumer<RunEvent> consumer) {
            synchronized (lock) {
                if (subscribers.size() >= MAX_SUBSCRIBERS_PER_CONVERSATION) {
                    throw new SubscriberLimitExceeded();
                }
                subscribers.add(consumer);
            }
            return () -> subscribers.remove(consumer);
        }

        List<RunEvent> snapshot() {
            synchronized (lock) {
                return List.copyOf(ring);
            }
        }
    }

    /** 同一会话临时事件订阅已达上限。 */
    public static final class SubscriberLimitExceeded extends IllegalStateException {
        public SubscriberLimitExceeded() {
            super("SSE 订阅数已达上限");
        }
    }
}
