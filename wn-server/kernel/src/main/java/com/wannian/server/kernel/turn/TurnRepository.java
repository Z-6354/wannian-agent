package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import java.time.Instant;
import java.util.Optional;

/**
 * 已有 Turn 的加载与单步迁移落库。
 *
 * <p>不负责 receive / commit：那两条路径在 {@link TurnCommitter}。
 * 状态迁移必须先调用 {@link Turn} 的领域方法，再 {@link #save}；禁止用 SQL 改 {@code status}。
 *
 * <p>{@code save} 只接受恰好前进 1 的 revision（{@code turn.revision() == expectedRevision + 1}），
 * 并用 {@code WHERE revision = expectedRevision} 做比较并交换。更新行数为 0 时不得假装成功。
 */
public interface TurnRepository {

    /** 按主键加载；不存在则 empty。 */
    Optional<Turn> find(TurnId id);

    /**
     * 把刚刚发生的一次领域迁移写回。
     *
     * @param turn 已执行一次 {@code claim}/{@code start}/{@code cancel}/{@code fail} 的对象。
     *     {@code beginCommit} 只改内存；进入 {@code COMMITTING} 必须经 {@link TurnCommitter#freezeCommit}
     * @param expectedRevision 迁移前读到的 revision
     * @param now 这次迁移的判定时间。lease 用库里的 {@code claim_expires_at} 和它比较，不用对象自己的 {@code updatedAt}
     * @return 已保存、冲突或不存在；不得为 null
     * @throws IllegalArgumentException 当 {@code turn.revision()} 不是 {@code expectedRevision + 1}
     */
    SaveTurnResult save(Turn turn, long expectedRevision, Instant now);
}
