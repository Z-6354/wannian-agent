package com.wannian.server.kernel.memory;

/**
 * 记忆 Review Worker Port（可替换：本批 InProcess；更后 Remote）。
 *
 * <p>单消费者消费 PENDING job；失败不挡聊天。
 */
public interface MemoryReviewWorkerPort {

    /**
     * 拉取并处理至多一条待办（或实现内循环由调度器驱动）。
     *
     * @return true 若实际处理了 job；false 若队列空
     */
    boolean pollOnce();
}
