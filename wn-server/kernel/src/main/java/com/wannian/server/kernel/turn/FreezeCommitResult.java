package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import java.util.Objects;

/**
 * {@link TurnCommitter#freezeCommit} 的封闭结果。
 */
public sealed interface FreezeCommitResult
        permits FreezeCommitResult.Frozen,
                FreezeCommitResult.RevisionConflict,
                FreezeCommitResult.Rejected {

    /** 计划已与 COMMITTING 同事务落库。 */
    record Frozen(TurnId turnId, long committingRevision) implements FreezeCommitResult {
        public Frozen {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /** 库中 revision 已变；本步未写入计划。 */
    record RevisionConflict(TurnId turnId, long actualRevision) implements FreezeCommitResult {
        public RevisionConflict {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /** 非法状态、owner/lease 不匹配或持久化失败。 */
    record Rejected(String reasonCode, String detail) implements FreezeCommitResult {
        public Rejected {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
