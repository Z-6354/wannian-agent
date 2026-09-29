package com.wannian.server.app.notice;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.kernel.notice.NoticeKind;
import com.wannian.server.kernel.notice.UserNotice;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 进程内有界消息中心（2.5.10 · M1=A）。
 *
 * <p>任务类通知按 taskId+kind+terminalStatus 幂等（M6=A）。
 */
@Component
public final class NoticeCenter {

    private final CopyOnWriteArrayList<UserNotice> notices = new CopyOnWriteArrayList<>();
    private final int max;

    public NoticeCenter(@Value("${wannian.notice.max:32}") int max) {
        this.max = Math.max(8, max);
    }

    public void publish(UserNotice notice) {
        Objects.requireNonNull(notice, "notice");
        if (notice.dismissed()) {
            return;
        }
        if (isTaskKind(notice.kind()) && notice.taskId() != null) {
            upsertTaskNotice(notice);
        } else {
            notices.add(0, notice);
        }
        trim();
    }

    public void publishArchive(String title, String body, List<Map<String, Object>> items, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items == null ? List.of() : List.copyOf(items));
        List<String> actions = List.of("dismiss", "undo_archive");
        publish(
                new UserNotice(
                        newId(),
                        NoticeKind.ARCHIVE,
                        at == null ? Instant.now() : at,
                        false,
                        title == null || title.isBlank() ? "已自动归档" : title,
                        body,
                        null,
                        null,
                        null,
                        null,
                        payload,
                        actions));
    }

    public void publishTaskTerminal(
            ConversationId conversationId,
            BackgroundTaskId taskId,
            BackgroundTaskStatus terminalStatus,
            String errorCode,
            String resultPreview,
            boolean scheduled) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (terminalStatus != BackgroundTaskStatus.SUCCEEDED
                && terminalStatus != BackgroundTaskStatus.FAILED
                && terminalStatus != BackgroundTaskStatus.CANCELLED) {
            return;
        }
        NoticeKind kind =
                terminalStatus == BackgroundTaskStatus.SUCCEEDED
                        ? NoticeKind.TASK_COMPLETED
                        : NoticeKind.TASK_FAILED;
        Map<String, Object> payload = new LinkedHashMap<>();
        if (scheduled) {
            payload.put("scheduled", true);
        }
        if (resultPreview != null && !resultPreview.isBlank()) {
            payload.put("resultPreview", clip(resultPreview, 160));
        }
        String title =
                switch (terminalStatus) {
                    case SUCCEEDED -> scheduled ? "定时任务已完成" : "后台任务已完成";
                    case FAILED -> "后台任务失败";
                    case CANCELLED -> "后台任务已取消";
                    default -> "后台任务";
                };
        String body =
                taskId.asString()
                        + (errorCode != null && !errorCode.isBlank() ? " · " + errorCode : "");
        List<String> actions = List.of("dismiss", "open_task");
        publish(
                new UserNotice(
                        newId(),
                        kind,
                        Instant.now(),
                        false,
                        title,
                        body,
                        conversationId.asString(),
                        taskId.asString(),
                        errorCode,
                        terminalStatus.name(),
                        payload,
                        actions));
    }

    /**
     * 2.5.12：定时提醒成功 — 标题/正文用提醒句（如「看微信」），非「定时任务已完成」+ taskId。
     */
    public void publishScheduledReminder(
            ConversationId conversationId,
            BackgroundTaskId taskId,
            String title,
            String body) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(taskId, "taskId");
        String remind = body == null ? "" : body.trim();
        if (remind.isBlank()) {
            return;
        }
        String shortTitle =
                title != null && !title.isBlank() ? clip(title.trim(), 40) : clip(remind, 40);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scheduled", true);
        payload.put("reminder", true);
        payload.put("resultPreview", clip(remind, 160));
        List<String> actions = List.of("dismiss", "open_task");
        publish(
                new UserNotice(
                        newId(),
                        NoticeKind.TASK_COMPLETED,
                        Instant.now(),
                        false,
                        shortTitle,
                        remind,
                        conversationId.asString(),
                        taskId.asString(),
                        null,
                        BackgroundTaskStatus.SUCCEEDED.name(),
                        payload,
                        actions));
    }

    public List<UserNotice> listOpen() {
        List<UserNotice> out = new ArrayList<>();
        for (UserNotice n : notices) {
            if (!n.dismissed()) {
                out.add(n);
            }
        }
        return List.copyOf(out);
    }

    public boolean dismiss(String noticeId) {
        if (noticeId == null || noticeId.isBlank()) {
            return false;
        }
        String id = noticeId.trim();
        for (int i = 0; i < notices.size(); i++) {
            UserNotice n = notices.get(i);
            if (n.noticeId().equals(id)) {
                if (!n.dismissed()) {
                    notices.set(i, n.dismissedCopy());
                }
                return true;
            }
        }
        return false;
    }

    public void dismissAllOpen() {
        for (int i = 0; i < notices.size(); i++) {
            UserNotice n = notices.get(i);
            if (!n.dismissed()) {
                notices.set(i, n.dismissedCopy());
            }
        }
    }

    /**
     * 撤销归档成功后：从 ARCHIVE 通知的 items 中移除该会话；空则 dismiss。
     *
     * @return 是否至少改动一条
     */
    public boolean removeArchiveConversation(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        String cid = conversationId.asString();
        boolean removed = false;
        for (int i = 0; i < notices.size(); i++) {
            UserNotice n = notices.get(i);
            if (n.dismissed() || n.kind() != NoticeKind.ARCHIVE) {
                continue;
            }
            Object raw = n.payload().get("items");
            if (!(raw instanceof List<?> items) || items.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> kept = new ArrayList<>();
            for (Object row : items) {
                if (!(row instanceof Map<?, ?> map)) {
                    continue;
                }
                Object rowCid = map.get("conversationId");
                if (rowCid != null && cid.equals(String.valueOf(rowCid))) {
                    removed = true;
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    kept.add(typed);
                }
            }
            if (kept.size() != items.size()) {
                if (kept.isEmpty()) {
                    notices.set(i, n.dismissedCopy());
                } else {
                    Map<String, Object> payload = new LinkedHashMap<>(n.payload());
                    payload.put("items", List.copyOf(kept));
                    notices.set(
                            i,
                            new UserNotice(
                                    n.noticeId(),
                                    n.kind(),
                                    n.createdAt(),
                                    false,
                                    "已自动归档 " + kept.size() + " 个会话",
                                    n.body(),
                                    n.conversationId(),
                                    n.taskId(),
                                    n.errorCode(),
                                    n.terminalStatus(),
                                    payload,
                                    n.actions()));
                }
            }
        }
        return removed;
    }

    private void upsertTaskNotice(UserNotice notice) {
        for (int i = 0; i < notices.size(); i++) {
            UserNotice existing = notices.get(i);
            if (existing.dismissed()) {
                continue;
            }
            if (existing.kind() == notice.kind()
                    && Objects.equals(existing.taskId(), notice.taskId())
                    && Objects.equals(existing.terminalStatus(), notice.terminalStatus())) {
                notices.set(i, notice);
                return;
            }
        }
        notices.add(0, notice);
    }

    private void trim() {
        while (notices.size() > max) {
            int removeAt = -1;
            for (int i = notices.size() - 1; i >= 0; i--) {
                if (notices.get(i).dismissed()) {
                    removeAt = i;
                    break;
                }
            }
            if (removeAt < 0) {
                removeAt = notices.size() - 1;
            }
            notices.remove(removeAt);
        }
    }

    private static boolean isTaskKind(NoticeKind kind) {
        return kind == NoticeKind.TASK_COMPLETED
                || kind == NoticeKind.TASK_FAILED
                || kind == NoticeKind.TASK_SCHEDULED;
    }

    private static String newId() {
        return Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
