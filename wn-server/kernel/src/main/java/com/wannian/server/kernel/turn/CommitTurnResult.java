package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import java.util.Objects;

/**
 * {@link TurnCommitter#commit} 的封闭结果：成功或可分类失败，禁止返回 null。
 */
public sealed interface CommitTurnResult
        permits CommitTurnResult.Committed,
                CommitTurnResult.RevisionConflict,
                CommitTurnResult.Rejected {

    /** 事务已成功提交。 */
    record Committed(TurnId turnId, long newTurnRevision) implements CommitTurnResult {
        public Committed {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * 乐观锁冲突：库中 revision 与 {@link CommitTurnPlan#expectedTurnRevision()} 不一致。
     *
     * <p>此时不得有 Message / Outbox 的部分写入。
     */
    record RevisionConflict(TurnId turnId, long actualRevision) implements CommitTurnResult {
        public RevisionConflict {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * 计划被拒绝（非法状态、不支持的扩展字段、校验失败等）。
     *
     * @param reasonCode 稳定机器码，如 {@code UNSUPPORTED_EXTENSION}、{@code ILLEGAL_ARGUMENT}
     * @param detail 人类可读说明（可进日志，勿含密钥）
     */
    record Rejected(String reasonCode, String detail) implements CommitTurnResult {
        public Rejected {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
