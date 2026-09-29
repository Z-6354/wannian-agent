package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.IdleDeliveryId;
import com.wannian.server.api.common.TurnId;
import java.time.Instant;
import java.util.Optional;

/** Idle 待唤醒队列（V027）。 */
public interface IdleDeliveryPendingRepository {

    void insert(IdleDeliveryPending pending);

    Optional<IdleDeliveryPending> findByTaskTerminal(
            BackgroundTaskId taskId, BackgroundTaskStatus terminalStatus);

    /** 取最旧 QUEUED 并 CAS→DISPATCHING；无则 empty。 */
    Optional<IdleDeliveryPending> claimNext(Instant now);

    boolean casRequeue(IdleDeliveryId id);

    /** DISPATCHING 时绑定唤模 Turn。 */
    boolean attachWakeTurn(IdleDeliveryId id, TurnId wakeTurnId);

    Optional<IdleDeliveryPending> findByWakeTurnId(TurnId wakeTurnId);

    boolean casDelivered(IdleDeliveryId id, TurnId wakeTurnId, Instant deliveredAt);

    /** Turn 仍可重试时把绑定行退回 QUEUED（清 wake_turn）。 */
    boolean casRequeueByWakeTurn(TurnId wakeTurnId);

    /** Turn 已终态失败时停止唤模重试。 */
    boolean casCancelledByWakeTurn(TurnId wakeTurnId);
}
