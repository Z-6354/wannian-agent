package com.wannian.server.kernel.notice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 消息中心读投影（2.5.10）。
 *
 * @param payload kind 专用载荷（归档 items、scheduled 等）
 * @param actions 前端可渲染动作提示
 */
public record UserNotice(
        String noticeId,
        NoticeKind kind,
        Instant createdAt,
        boolean dismissed,
        String title,
        String body,
        String conversationId,
        String taskId,
        String errorCode,
        String terminalStatus,
        Map<String, Object> payload,
        List<String> actions) {

    public UserNotice {
        Objects.requireNonNull(noticeId, "noticeId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(title, "title");
        title = title.isBlank() ? kind.name() : title.trim();
        if (title.length() > 80) {
            title = title.substring(0, 80);
        }
        body = body == null || body.isBlank() ? null : clip(body.trim(), 200);
        conversationId = blankToNull(conversationId);
        taskId = blankToNull(taskId);
        errorCode = blankToNull(errorCode);
        terminalStatus = blankToNull(terminalStatus);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public UserNotice dismissedCopy() {
        return new UserNotice(
                noticeId,
                kind,
                createdAt,
                true,
                title,
                body,
                conversationId,
                taskId,
                errorCode,
                terminalStatus,
                payload,
                actions);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
