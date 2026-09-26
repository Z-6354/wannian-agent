package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.conversation.TitleSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 自动归档评估输入（调度门闩已筛过闲置天数；本对象供评估器读内容）。
 *
 * <p>与具体算法无关：LLM / 启发式均消费同一形状。
 */
public record ArchiveCandidate(
        ConversationId conversationId,
        String title,
        TitleSource titleSource,
        long revision,
        Instant lastActivityAt,
        long idleDays,
        List<TurnSnippet> recentTurns) {

    public ArchiveCandidate {
        Objects.requireNonNull(conversationId, "conversationId");
        title = title == null ? "" : title;
        Objects.requireNonNull(titleSource, "titleSource");
        Objects.requireNonNull(lastActivityAt, "lastActivityAt");
        Objects.requireNonNull(recentTurns, "recentTurns");
        recentTurns = List.copyOf(recentTurns);
    }

    /** 一轮用户/助手摘要（已截断）。 */
    public record TurnSnippet(String role, String text) {
        public TurnSnippet {
            Objects.requireNonNull(role, "role");
            text = text == null ? "" : text;
        }
    }
}
