package com.wannian.server.kernel.task;

/** 用户待审后台提案状态（2.5.5 · V025；V029 增 CLAIMED）。 */
public enum TaskReviewStatus {
    PENDING,
    /** 确认占用中：Turn/Task 提交前；崩溃后对账完成或回 PENDING。 */
    CLAIMED,
    CONFIRMED,
    REJECTED,
    EXPIRED
}
