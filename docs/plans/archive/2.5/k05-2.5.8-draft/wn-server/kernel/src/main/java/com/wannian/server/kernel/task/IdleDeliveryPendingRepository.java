package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.IdleDeliveryId;
import com.wannian.server.api.common.TurnId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Idle 待唤醒队列（V027）。 */
public interface IdleDeliveryPendingRepository {

    void insert(IdleDeliveryPending pending);

    Optional<IdleDeliveryPending> findByTaskTerminal(
            BackgroundTaskId taskId, BackgroundTaskStatus terminalStatus);

    /** 2.5.8：QUEUED|DISPATCHING（多次开火幂等）。 */
    Optional<IdleDeliveryPending> findActiveQueued(
            BackgroundTaskId taskId, BackgroundTaskStatus terminalStatus);

    /** 取最旧 QUEUED 并 CAS→DISPATCHING；无则 empty。 */
    Optional<IdleDeliveryPending> claimNext(Instant now);

    /** 崩溃收口扫描：全部 DISPATCHING 行。 */
    List<IdleDeliveryPending> listDispatching();

    boolean casRequeue(IdleDeliveryId id);

    /** DISPATCHING 时绑定唤模 Turn。 */
    boolean attachWakeTurn(IdleDeliveryId id, TurnId wakeTurnId);

    Optional<IdleDeliveryPending> findByWakeTurnId(TurnId wakeTurnId);

    boolean casDelivered(IdleDeliveryId id, TurnId wakeTurnId, Instant deliveredAt);

    /** Turn 仍可重试时把绑定行退回 QUEUED（清 wake_turn）。 */
    boolean casRequeueByWakeTurn(TurnId wakeTurnId);

    /**
     * wake Turn 已 FAILED/CANCELLED：清 wake、递增 {@code _wakeAttempt}、回 QUEUED，
     * 以便下次用新 {@code clientRequestId} 再建 Turn（同键会幂等回放到已失败 Turn）。
     */
    boolean resetAfterWakeFailed(TurnId wakeTurnId, String newPayloadJson);

    /** Turn 已终态失败且不可再唤模时停止。 */
    boolean casCancelledByWakeTurn(TurnId wakeTurnId);

    /** 无 wake_turn 时按 id 取消（会话不可用等）。 */
    boolean casCancelled(IdleDeliveryId id);
}
