package com.wannian.server.kernel.memory;

/**
 * 记忆 Review 调度 Port：enqueue 后立即返回；不挡聊天。
 */
public interface MemoryReviewScheduler {

    /** 触发类型（落库 memory_review_job.trigger）。 */
    enum Trigger {
        INTERVAL,
        IDLE
    }

    /**
     * 入队一次 Review。同 conversation + trigger 已有 PENDING 时可合并/跳过（实现写死）。
     *
     * @param conversationId 会话 id
     * @param companionIdentity 伴身
     * @param trigger INTERVAL 或 IDLE
     */
    default void enqueue(
            String conversationId, CompanionIdentity companionIdentity, Trigger trigger) {
        enqueue(conversationId, companionIdentity, trigger, null);
    }

    /** 入队 IDLE Review 时携带扫描器捕获的活动水位，防止后续活动被错误地折叠进旧任务。 */
    void enqueue(
            String conversationId,
            CompanionIdentity companionIdentity,
            Trigger trigger,
            String activityWatermark);
}
