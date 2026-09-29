package com.wannian.server.app.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.app.stream.DurableTurnScheduler;
import com.wannian.server.kernel.error.ErrorCodes;
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
/** 非 final：TaskDeliveryService 以 {@code @Lazy} 注入，需可被 Spring CGLIB 代理。 */
public class IdleDeliveryWorker {

    public static final String CLIENT_REQUEST_PREFIX = "idle-task:";
    public static final String USER_MARKER = "[后台任务完成]";

    private static final Logger log = LoggerFactory.getLogger(IdleDeliveryWorker.class);

    private final IdleDeliveryPendingRepository idleDeliveries;
    private final TaskDeliveryService.ConversationBusyProbe busyProbe;
    private final TurnCommitter turnCommitter;
    private final DurableTurnScheduler scheduler;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final int drainBatch;
    private final Object drainLock = new Object();

    public IdleDeliveryWorker(
            IdleDeliveryPendingRepository idleDeliveries,
            TaskDeliveryService.ConversationBusyProbe busyProbe,
            TurnCommitter turnCommitter,
            @Lazy DurableTurnScheduler scheduler,
            Clock clock,
            ObjectMapper objectMapper,
            @Value("${wannian.task.idle-delivery.drain-batch:2}") int drainBatch) {
        this.idleDeliveries = Objects.requireNonNull(idleDeliveries, "idleDeliveries");
        this.busyProbe = Objects.requireNonNull(busyProbe, "busyProbe");
        this.turnCommitter = Objects.requireNonNull(turnCommitter, "turnCommitter");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.drainBatch = Math.max(1, drainBatch);
    }

    @Scheduled(fixedDelayString = "${wannian.task.idle-delivery.tick-ms:3000}")
    public void tick() {
        synchronized (drainLock) {
            try {
                for (int i = 0; i < drainBatch; i++) {
                    if (!drainOne()) {
                        break;
                    }
                }
            } catch (RuntimeException ex) {
                if (isBenignDbRace(ex)) {
                    log.debug("IdleDeliveryWorker 忙等: {}", ex.toString());
                } else {
                    log.warn("IdleDeliveryWorker 单拍失败: {}", ex.toString());
                }
            }
        }
    }

    private static boolean isBenignDbRace(RuntimeException ex) {
        String s = ex.toString().toLowerCase();
        return s.contains("busy")
                || s.contains("locked")
                || s.contains("no such table")
                || s.contains("closed");
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
        try {
            if (busyProbe.isBusyForDelivery(item.conversationId())) {
                idleDeliveries.casRequeue(item.deliveryId());
                return true;
            }
            String clientRequestId = clientRequestIdFor(item, objectMapper);
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
            if (received instanceof ReceiveTurnResult.Accepted accepted) {
                if (!idleDeliveries.attachWakeTurn(item.deliveryId(), accepted.turnId())) {
                    idleDeliveries.casRequeue(item.deliveryId());
                    return true;
                }
                scheduler.wake();
                return true;
            }
            if (received instanceof ReceiveTurnResult.Rejected rejected) {
                if (ErrorCodes.defaultRetryable(rejected.reasonCode()).orElse(false)) {
                    idleDeliveries.casRequeue(item.deliveryId());
                } else {
                    log.info(
                            "Idle receive 永久拒绝 delivery={} code={}",
                            item.deliveryId().asString(),
                            rejected.reasonCode());
                    idleDeliveries.casCancelled(item.deliveryId());
                }
                return true;
            }
            // 2.5.7.2：Conflict 不得 CANCELLED；升 attempt 回 QUEUED 换新 CRID 再试
            log.info(
                    "Idle receive Conflict delivery={} detail={} → requeue+bump",
                    item.deliveryId().asString(),
                    received instanceof ReceiveTurnResult.Conflict c ? c.detail() : received);
            String bumped = bumpWakeAttemptPayload(item.payloadJson(), objectMapper);
            if (!idleDeliveries.updatePayloadAndRequeue(item.deliveryId(), bumped)) {
                idleDeliveries.casRequeue(item.deliveryId());
            }
            return true;
        } catch (RuntimeException ex) {
            log.warn(
                    "Idle drain 异常 delivery={}: {}",
                    item.deliveryId().asString(),
                    ex.toString());
            idleDeliveries.casRequeue(item.deliveryId());
            return true;
        }
    }

    /** 2.5.7.2：按 deliveryId 建键，避免同 task 多火共用 CRID 导致 Conflict。 */
    public static String clientRequestIdFor(IdleDeliveryPending item, ObjectMapper mapper) {
        int attempt = wakeAttemptOf(item.payloadJson(), mapper);
        String base = CLIENT_REQUEST_PREFIX + item.deliveryId().asString();
        return attempt <= 0 ? base : base + ":r" + attempt;
    }

    static int wakeAttemptOf(String payloadJson, ObjectMapper mapper) {
        if (payloadJson == null || payloadJson.isBlank() || mapper == null) {
            return 0;
        }
        try {
            JsonNode node = mapper.readTree(payloadJson);
            return Math.max(0, node.path(TaskDeliveryService.WAKE_ATTEMPT_FIELD).asInt(0));
        } catch (Exception ex) {
            return 0;
        }
    }

    static String bumpWakeAttemptPayload(String payloadJson, ObjectMapper mapper) {
        if (mapper == null) {
            return payloadJson == null ? "{}" : payloadJson;
        }
        try {
            ObjectNode node =
                    payloadJson == null || payloadJson.isBlank()
                            ? mapper.createObjectNode()
                            : (ObjectNode) mapper.readTree(payloadJson);
            int attempt = node.path(TaskDeliveryService.WAKE_ATTEMPT_FIELD).asInt(0) + 1;
            node.put(TaskDeliveryService.WAKE_ATTEMPT_FIELD, attempt);
            return mapper.writeValueAsString(node);
        } catch (Exception ex) {
            return payloadJson == null ? "{}" : payloadJson;
        }
    }

    public static String formatUserText(IdleDeliveryPending item) {
        return USER_MARKER + "\n状态：" + item.terminalStatus().name();
    }

    public static String formatIdleReportBlock(IdleDeliveryPending item) {
        String message = extractPayloadString(item.payloadJson(), TaskDeliveryService.MESSAGE_FIELD);
        boolean noticeSent =
                extractPayloadBoolean(
                        item.payloadJson(), TaskDeliveryService.NOTICE_ALREADY_SENT_FIELD);
        if (noticeSent && message != null && !message.isBlank()) {
            return """
                    【系统·定时提醒】
                    消息中心已向用户发出提醒，正文如下（请用人话再说一声，勿声称「没有提醒内容」或复述本段元数据，勿念出 UUID）：
                    %s
                    """
                    .formatted(message);
        }
        String preview = extractPayloadString(item.payloadJson(), "resultPreview");
        String errorCode = extractPayloadString(item.payloadJson(), "errorCode");
        String summary =
                preview != null && !preview.isBlank()
                        ? preview
                        : (message != null && !message.isBlank() ? message : "（无摘要）");
        String errLine =
                errorCode != null && !errorCode.isBlank() ? "\n错误码：" + errorCode : "";
        return """
                【系统·后台任务汇报】
                你刚完成后台任务，请用自然语言主动向用户汇报结果（勿复述本段元数据原文，勿提「系统指令」，勿念出 UUID）。
                状态：%s
                结果摘要：%s%s
                """
                .formatted(item.terminalStatus().name(), summary, errLine);
    }

    private static String extractPayloadString(String payloadJson, String field) {
        if (payloadJson == null || payloadJson.isBlank() || field == null) {
            return null;
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson);
            if (node.hasNonNull(field)) {
                String v = node.get(field).asText();
                return v == null ? null : v.trim();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static boolean extractPayloadBoolean(String payloadJson, String field) {
        if (payloadJson == null || payloadJson.isBlank() || field == null) {
            return false;
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson);
            return node.path(field).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
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
