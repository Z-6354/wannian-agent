package com.wannian.server.app.task;

import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.app.stream.DurableTurnScheduler;
import com.wannian.server.kernel.task.IdleDeliveryPending;
import com.wannian.server.kernel.task.IdleDeliveryPendingRepository;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.TurnCommitter;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 2.5.7：Idle 待唤醒入队后 receive + wake；真正 Loop 由 {@link DurableTurnScheduler} 跑。
 */
public final class IdleDeliveryWorker {

    public static final String CLIENT_REQUEST_PREFIX = "idle-task:";
    public static final String USER_MARKER = "[后台任务完成]";

    private static final Logger log = LoggerFactory.getLogger(IdleDeliveryWorker.class);

    private final IdleDeliveryPendingRepository idleDeliveries;
    private final TaskDeliveryService.ConversationBusyProbe busyProbe;
    private final TurnCommitter turnCommitter;
    private final DurableTurnScheduler scheduler;
    private final Clock clock;
    private final int drainBatch;

    public IdleDeliveryWorker(
            IdleDeliveryPendingRepository idleDeliveries,
            TaskDeliveryService.ConversationBusyProbe busyProbe,
            TurnCommitter turnCommitter,
            @Lazy DurableTurnScheduler scheduler,
            Clock clock,
            @Value("${wannian.task.idle-delivery.drain-batch:2}") int drainBatch) {
        this.idleDeliveries = Objects.requireNonNull(idleDeliveries, "idleDeliveries");
        this.busyProbe = Objects.requireNonNull(busyProbe, "busyProbe");
        this.turnCommitter = Objects.requireNonNull(turnCommitter, "turnCommitter");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.drainBatch = Math.max(1, drainBatch);
    }

    @Scheduled(fixedDelayString = "${wannian.task.idle-delivery.tick-ms:3000}")
    public void tick() {
        try {
            for (int i = 0; i < drainBatch; i++) {
                if (!drainOne()) {
                    break;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("IdleDeliveryWorker 单拍失败: {}", ex.toString());
        }
    }

    /** 入队后可立即 nudge。 */
    public void nudge() {
        tick();
    }

    /** @return 是否处理了一条（含 Busy defer） */
    boolean drainOne() {
        Instant now = clock.instant();
        Optional<IdleDeliveryPending> claimed = idleDeliveries.claimNext(now);
        if (claimed.isEmpty()) {
            return false;
        }
        IdleDeliveryPending item = claimed.get();
        if (busyProbe.isBusyForDelivery(item.conversationId())) {
            idleDeliveries.casRequeue(item.deliveryId());
            return true;
        }
        String clientRequestId =
                CLIENT_REQUEST_PREFIX
                        + item.taskId().asString()
                        + ":"
                        + item.terminalStatus().name();
        String userText = formatUserText(item);
        TurnId turnId = TurnId.generate();
        MessageId messageId = MessageId.generate();
        ReceiveTurnResult received =
                turnCommitter.receive(
                        new ReceiveTurnPlan(
                                item.conversationId(),
                                clientRequestId,
                                turnId,
                                new ReceiveTurnPlan.UserMessageDraft(
                                        messageId,
                                        MessageRole.USER,
                                        contentEnvelope(userText),
                                        0)));
        if (!(received instanceof ReceiveTurnResult.Accepted accepted)) {
            idleDeliveries.casRequeue(item.deliveryId());
            return true;
        }
        if (!idleDeliveries.attachWakeTurn(item.deliveryId(), accepted.turnId())) {
            idleDeliveries.casRequeue(item.deliveryId());
            return true;
        }
        scheduler.wake();
        return true;
    }

    public static String formatUserText(IdleDeliveryPending item) {
        return USER_MARKER
                + "\n状态："
                + item.terminalStatus().name()
                + "\n任务："
                + item.taskId().asString();
    }

    public static String formatIdleReportBlock(IdleDeliveryPending item) {
        return """
                【系统·后台任务汇报】
                你刚完成后台任务，请用自然语言主动向用户汇报结果（勿复述本段元数据原文，勿提「系统指令」）。
                taskId=%s
                terminalStatus=%s
                payload=%s
                """
                .formatted(
                        item.taskId().asString(),
                        item.terminalStatus().name(),
                        item.payloadJson());
    }

    private static String contentEnvelope(String text) {
        return "{\"v\":1,\"text\":" + quoteJson(text) + "}";
    }

    private static String quoteJson(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }
}
