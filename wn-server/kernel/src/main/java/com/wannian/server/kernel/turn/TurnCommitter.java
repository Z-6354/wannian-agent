package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import java.util.Optional;

/**
 * 回合持久化入口：接收用户 Turn，以及完成回合时的最终提交。
 *
 * <p>调用方不得自行开启事务或拼装多 Repository 提交顺序。
 *
 * <p>SQLite 实现在 app；本接口位于 kernel，禁止依赖 JDBC/Spring。
 *
 * <p>开聊顺序：先 {@code ConversationStore#create}，再 {@link #receive}，引擎跑完后
 * {@link #freezeCommit}，再 {@link #commit}。会话不存在时 {@code receive} 必须拒绝，不得隐式建会话。
 * 进入 {@code COMMITTING} 必须经 {@link #freezeCommit}；通用 {@link TurnRepository#save} 不能写该状态。
 */
public interface TurnCommitter {

    /**
     * 同事务接收用户消息与 Turn(RECEIVED)；按 {@code clientRequestId} 幂等。
     *
     * @param plan 不可变接收计划
     * @return 接受（含是否回放）、冲突或拒绝；不得为 null
     */
    ReceiveTurnResult receive(ReceiveTurnPlan plan);

    /**
     * 同事务校验 owner/revision/lease，写入可恢复完成计划，并将 Turn 迁到 {@code COMMITTING}。
     *
     * <p>成功后即使进程退出，也只能提交这份计划，不能重跑模型或换一份答案。
     */
    FreezeCommitResult freezeCommit(FreezeCommitPlan plan);

    /**
     * 读取已冻结、可供 {@link #commit} 使用的完成计划。
     *
     * <p>仅当回合处于 {@code COMMITTING} 且计划行仍在时返回。重加载后丢弃内存对象用这一条恢复。
     */
    Optional<CommitTurnPlan> frozenCommitPlan(TurnId turnId);

    /**
     * 在单一业务事务中校验并提交完成计划中的全部事实。
     *
     * <p>{@code COMMITTING} 时必须与已冻结计划一致；任一前置条件失败或写入失败时整体回滚。
     *
     * @param plan 不可变提交计划，不得为 null
     * @return 成功、revision 冲突或拒绝等原因；不得返回 null
     */
    CommitTurnResult commit(CommitTurnPlan plan);
}
