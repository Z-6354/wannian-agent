package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import java.time.Instant;

/**
 * 将 Turn 终态（CANCELLED / FAILED）与对应 Outbox 事件落在同一数据库事务。
 *
 * <p>成功完成路径仍走 {@link TurnCommitter#commit}。本端口只覆盖取消与失败，
 * 避免页面永远停在「生成中」。
 */
public interface TurnTerminalWriter {

    /**
     * 持久化已调用 {@link Turn#cancel} 的对象，并写入 {@code TurnCancelled} Outbox。
     *
     * @param turn 内存状态已为 CANCELLED，revision 已 +1
     * @param expectedRevision 迁移前 revision
     */
    SaveTurnResult saveCancelled(Turn turn, long expectedRevision, Instant now);

    /**
     * 持久化已调用 {@link Turn#fail} 的对象，并写入 {@code TurnFailed} Outbox。
     *
     * @param turn 内存状态已为 FAILED，revision 已 +1
     * @param expectedRevision 迁移前 revision
     */
    SaveTurnResult saveFailed(Turn turn, long expectedRevision, Instant now);

    /** 仅走 {@link TurnRepository#save}，不写 Outbox（测试与无持久交付场景）。 */
    static TurnTerminalWriter viaRepository(TurnRepository turns) {
        return new TurnTerminalWriter() {
            @Override
            public SaveTurnResult saveCancelled(Turn turn, long expectedRevision, Instant now) {
                return turns.save(turn, expectedRevision, now);
            }

            @Override
            public SaveTurnResult saveFailed(Turn turn, long expectedRevision, Instant now) {
                return turns.save(turn, expectedRevision, now);
            }
        };
    }
}
