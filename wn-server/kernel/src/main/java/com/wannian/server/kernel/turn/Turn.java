package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.turn.TurnStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * Turn 领域对象：状态只能通过本类方法迁移，禁止外部改字符串。
 *
 * <p>对照 docs/guide/29。{@code COMMITTING → COMPLETED} 由 {@link TurnCommitter#commit}
 * 在落库事务中完成，不在本类提供 {@code complete()}，以免绕过 Message/Outbox 原子提交。
 *
 * <p>每次成功迁移会递增 {@link #revision()}，供后续持久化 CAS 使用。
 */
public final class Turn {

    private final TurnId id;
    private final ConversationId conversationId;
    private final String clientRequestId;
    private final MessageId inputMessageId;

    private TurnStatus status;
    private long revision;
    private String executionId;
    private Instant claimExpiresAt;
    private String errorCode;
    private Instant updatedAt;

    private Turn(
            TurnId id,
            ConversationId conversationId,
            String clientRequestId,
            MessageId inputMessageId,
            TurnStatus status,
            long revision,
            String executionId,
            Instant claimExpiresAt,
            String errorCode,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.clientRequestId = Objects.requireNonNull(clientRequestId, "clientRequestId");
        this.inputMessageId = Objects.requireNonNull(inputMessageId, "inputMessageId");
        this.status = Objects.requireNonNull(status, "status");
        this.revision = revision;
        this.executionId = executionId;
        this.claimExpiresAt = claimExpiresAt;
        this.errorCode = errorCode;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** receive 成功后的初始形态：RECEIVED，revision=1。 */
    public static Turn received(
            TurnId id,
            ConversationId conversationId,
            String clientRequestId,
            MessageId inputMessageId,
            Instant now) {
        return new Turn(
                id,
                conversationId,
                clientRequestId,
                inputMessageId,
                TurnStatus.RECEIVED,
                1L,
                null,
                null,
                null,
                now);
    }

    /**
     * 从已持久化快照重建，只供加载使用。
     *
     * <p>这里会拒绝内部自相矛盾的快照，但不能代替 {@link TurnRepository#save} 的迁移检查。
     * 通用保存不能把回合写成 COMPLETED，也不能从终态复活。
     */
    public static Turn reconstitute(
            TurnId id,
            ConversationId conversationId,
            String clientRequestId,
            MessageId inputMessageId,
            TurnStatus status,
            long revision,
            String executionId,
            Instant claimExpiresAt,
            String errorCode,
            Instant updatedAt) {
        validateSnapshot(status, revision, executionId, claimExpiresAt, errorCode);
        return new Turn(
                id,
                conversationId,
                clientRequestId,
                inputMessageId,
                status,
                revision,
                executionId,
                claimExpiresAt,
                errorCode,
                updatedAt);
    }

    public TurnId id() {
        return id;
    }

    public ConversationId conversationId() {
        return conversationId;
    }

    public String clientRequestId() {
        return clientRequestId;
    }

    public MessageId inputMessageId() {
        return inputMessageId;
    }

    public TurnStatus status() {
        return status;
    }

    public long revision() {
        return revision;
    }

    public String executionId() {
        return executionId;
    }

    public Instant claimExpiresAt() {
        return claimExpiresAt;
    }

    public String errorCode() {
        return errorCode;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * RECEIVED → CLAIMED。
     *
     * @param expectedRevision 调用方读到的 revision，不匹配则拒绝（乐观锁）
     * @param claim 新的执行认领
     * @param now 当前时间
     */
    public void claim(long expectedRevision, ExecutionClaim claim, Instant now) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(now, "now");
        requireRevision(expectedRevision);
        requireStatus(TurnStatus.RECEIVED, "claim");
        if (!claim.expiresAt().isAfter(now)) {
            throw new TurnTransitionException(
                    "CLAIM_EXPIRED", "claim 的 expiresAt 必须晚于 now，不能保存已经过期的认领");
        }
        if (hasActiveOwner(now)) {
            throw new TurnTransitionException(
                    "OWNER_ACTIVE", "回合仍有未过期的执行 owner，不能重复 claim");
        }
        this.executionId = claim.executionId();
        this.claimExpiresAt = claim.expiresAt();
        advance(TurnStatus.CLAIMED, now);
    }

    /** CLAIMED → RUNNING；要求 claim 未过期。 */
    public void start(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatus(TurnStatus.CLAIMED, "start");
        if (!hasActiveOwner(now)) {
            throw new TurnTransitionException("CLAIM_EXPIRED", "claim 已过期，不能 start");
        }
        advance(TurnStatus.RUNNING, now);
    }

    /**
     * RUNNING → COMMITTING。
     *
     * <p>进入提交区时必须仍是这次 executionId 的有效 lease。过期后再进来会被拒绝。
     * 已经合法写入 COMMITTING 之后，时间继续走也不在这里回退；最终提交由
     * {@link TurnCommitter} 核对冻结的 executionId，不再用“lease 过了就拒绝”把回合卡死。
     *
     * <p>这只改内存里的状态机。把 {@code COMMITTING} 写进数据库必须走
     * {@link TurnCommitter#freezeCommit}，让最终计划与这次迁移落在同一事务。
     * {@link TurnRepository#save} 会拒绝 {@code RUNNING → COMMITTING}，不能用这次方法调用代替冻结。
     */
    public void beginCommit(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatus(TurnStatus.RUNNING, "beginCommit");
        if (!hasActiveOwner(now)) {
            throw new TurnTransitionException("CLAIM_EXPIRED", "claim 已过期，不能进入 COMMITTING");
        }
        advance(TurnStatus.COMMITTING, now);
    }

    /** RECEIVED / CLAIMED / RUNNING → CANCELLED；COMMITTING 及之后不可取消。 */
    public void cancel(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != TurnStatus.RECEIVED
                && status != TurnStatus.CLAIMED
                && status != TurnStatus.RUNNING) {
            throw new TurnTransitionException(
                    "ILLEGAL_TRANSITION",
                    "当前状态 " + status + " 不可 cancel（已进入不可取消提交或已终态）");
        }
        advance(TurnStatus.CANCELLED, now);
    }

    /** CLAIMED / RUNNING → FAILED。 */
    public void fail(String classifiedErrorCode, Instant now) {
        Objects.requireNonNull(classifiedErrorCode, "classifiedErrorCode");
        Objects.requireNonNull(now, "now");
        String code = classifiedErrorCode.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("errorCode 不能为空");
        }
        if (status != TurnStatus.CLAIMED && status != TurnStatus.RUNNING) {
            throw new TurnTransitionException(
                    "ILLEGAL_TRANSITION", "当前状态 " + status + " 不可 fail");
        }
        this.errorCode = code;
        advance(TurnStatus.FAILED, now);
    }

    private boolean hasActiveOwner(Instant now) {
        if (executionId == null || claimExpiresAt == null) {
            return false;
        }
        return claimExpiresAt.isAfter(now);
    }

    private void requireRevision(long expectedRevision) {
        if (this.revision != expectedRevision) {
            throw new TurnTransitionException(
                    "REVISION_CONFLICT",
                    "revision 不匹配，期望 " + expectedRevision + " 实际 " + this.revision);
        }
    }

    private void requireStatus(TurnStatus expected, String action) {
        if (status != expected) {
            throw new TurnTransitionException(
                    "ILLEGAL_TRANSITION",
                    "不能对状态 " + status + " 执行 " + action + "（需要 " + expected + "）");
        }
    }

    private static void validateSnapshot(
            TurnStatus status,
            long revision,
            String executionId,
            Instant claimExpiresAt,
            String errorCode) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision 必须从 1 起，实际为 " + revision);
        }
        boolean owned = executionId != null && !executionId.isBlank() && claimExpiresAt != null;
        switch (status) {
            case RECEIVED -> {
                if (executionId != null || claimExpiresAt != null || errorCode != null) {
                    throw new IllegalArgumentException("RECEIVED 快照不能携带执行身份或 errorCode");
                }
            }
            case CLAIMED, RUNNING, COMMITTING -> {
                if (!owned || errorCode != null) {
                    throw new IllegalArgumentException(status + " 快照必须有 executionId、未过期字段，且不能有 errorCode");
                }
            }
            case FAILED -> {
                if (errorCode == null || errorCode.isBlank()) {
                    throw new IllegalArgumentException("FAILED 快照必须有 errorCode");
                }
            }
            case CANCELLED, COMPLETED -> {
                if (errorCode != null) {
                    throw new IllegalArgumentException(status + " 快照不能携带 errorCode");
                }
            }
            default -> throw new IllegalArgumentException("无法识别的回合状态: " + status);
        }
    }

    private void advance(TurnStatus next, Instant now) {
        this.status = next;
        this.revision++;
        this.updatedAt = now;
    }
}
