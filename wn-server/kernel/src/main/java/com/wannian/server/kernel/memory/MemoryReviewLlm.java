package com.wannian.server.kernel.memory;

import java.util.List;

/**
 * Review 结构化 LLM Port（与回话 ModelPort 可分 bean；stream=false）。
 *
 * <p>同一次输出须含规范化 claim + importance + 轴；禁止本口写库。
 */
public interface MemoryReviewLlm {

    /**
     * @param request 近讯摘要 + ACTIVE 摘要 + 时间/地点锚
     * @return 待 Shape 的草案列表（可空）
     */
    List<MemoryToolDraft> propose(ReviewRequest request);

    /**
     * Review 输入（不可变载体；实现可再扩字段）。
     *
     * @param conversationId 会话
     * @param companionIdentity 伴身
     * @param wallClockDate 绝对时间锚（如 2026-09-23）
     * @param placeAnchor 地点锚；未知可为 null
     * @param recentTranscript 近讯摘要文本
     * @param activeMemorySummary ACTIVE 摘要文本
     */
    record ReviewRequest(
            String conversationId,
            CompanionIdentity companionIdentity,
            String wallClockDate,
            String placeAnchor,
            String recentTranscript,
            String activeMemorySummary) {}
}
