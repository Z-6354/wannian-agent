package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import java.util.Objects;

/**
 * {@link TurnRepository#save} 的封闭结果。
 *
 * <p>成功或可分类失败，禁止返回 null。状态是否合法由 {@link Turn} 在调用 save 之前判定。
 */
public sealed interface SaveTurnResult
        permits SaveTurnResult.Saved,
                SaveTurnResult.RevisionConflict,
                SaveTurnResult.NotFound,
                SaveTurnResult.Rejected {

    /** 这一步迁移已落库。 */
    record Saved(TurnId turnId, long newRevision) implements SaveTurnResult {
        public Saved {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /** 库中 revision 已不是调用方读到的值；本步未写入。 */
    record RevisionConflict(TurnId turnId, long actualRevision) implements SaveTurnResult {
        public RevisionConflict {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /** 目标回合不存在；本步未写入。 */
    record NotFound(TurnId turnId) implements SaveTurnResult {
        public NotFound {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * 数据库写入失败且已尝试回滚。
     *
     * <p>{@code detail} 是技术说明，不是最终用户文案。
     */
    record Rejected(TurnId turnId, String reasonCode, String detail) implements SaveTurnResult {
        public Rejected {
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
