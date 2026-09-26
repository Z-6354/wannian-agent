package com.wannian.server.app.archive;

import com.wannian.server.api.common.ConversationId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * 自动归档结果占位通知（0.2.4-G）。
 *
 * <p>进程内有界缓存；完整消息系统在 0.2.5。前端轮询拉取，可撤销 / 关闭。
 */
@Component
public class ArchiveNoticeStore {

    private static final int MAX = 32;

    private final CopyOnWriteArrayList<Notice> notices = new CopyOnWriteArrayList<>();

    public void publishBatch(List<ArchivedItem> items, Instant at) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Notice notice =
                new Notice(
                        Instant.now().toEpochMilli() + "-" + items.size(),
                        at == null ? Instant.now() : at,
                        List.copyOf(items),
                        false);
        notices.add(0, notice);
        while (notices.size() > MAX) {
            notices.remove(notices.size() - 1);
        }
    }

    public List<Notice> listOpen() {
        List<Notice> out = new ArrayList<>();
        for (Notice n : notices) {
            if (!n.dismissed()) {
                out.add(n);
            }
        }
        return List.copyOf(out);
    }

    public boolean dismiss(String noticeId) {
        for (int i = 0; i < notices.size(); i++) {
            Notice n = notices.get(i);
            if (n.id().equals(noticeId)) {
                notices.set(i, n.dismissedCopy());
                return true;
            }
        }
        return false;
    }

    /**
     * 撤销归档成功后从未关闭通知中移除该会话；若某 notice 已无条目则标为 dismissed。
     *
     * @return 是否至少移除了一条
     */
    public boolean removeConversation(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        boolean removed = false;
        for (int i = 0; i < notices.size(); i++) {
            Notice n = notices.get(i);
            if (n.dismissed()) {
                continue;
            }
            List<ArchivedItem> kept = new ArrayList<>();
            for (ArchivedItem item : n.items()) {
                if (item.conversationId().equals(conversationId)) {
                    removed = true;
                } else {
                    kept.add(item);
                }
            }
            if (kept.size() != n.items().size()) {
                if (kept.isEmpty()) {
                    notices.set(i, n.dismissedCopy());
                } else {
                    notices.set(i, new Notice(n.id(), n.at(), kept, false));
                }
            }
        }
        return removed;
    }

    /** 关闭全部未关闭的自动归档通知（清空归档后避免占位条污染）。 */
    public void dismissAllOpen() {
        for (int i = 0; i < notices.size(); i++) {
            Notice n = notices.get(i);
            if (!n.dismissed()) {
                notices.set(i, n.dismissedCopy());
            }
        }
    }

    public record ArchivedItem(
            ConversationId conversationId, String title, long revision, String reason) {
        public ArchivedItem {
            Objects.requireNonNull(conversationId, "conversationId");
            title = title == null ? "" : title;
            reason = reason == null ? "" : reason;
        }
    }

    public record Notice(String id, Instant at, List<ArchivedItem> items, boolean dismissed) {
        public Notice {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(items, "items");
            items = List.copyOf(items);
        }

        Notice dismissedCopy() {
            return new Notice(id, at, items, true);
        }
    }
}
