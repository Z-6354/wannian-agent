package com.wannian.server.app.archive;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.app.notice.NoticeCenter;
import com.wannian.server.kernel.notice.NoticeKind;
import com.wannian.server.kernel.notice.UserNotice;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 自动归档通知适配器（2.4.7 占位 → 2.5.10 委托 {@link NoticeCenter}）。
 *
 * <p>保留 ArchivedItem / Notice 形状供 hygiene API 兼容；真实存储在消息中心。
 */
@Component
public class ArchiveNoticeStore {

    private final NoticeCenter noticeCenter;

    public ArchiveNoticeStore(NoticeCenter noticeCenter) {
        this.noticeCenter = Objects.requireNonNull(noticeCenter, "noticeCenter");
    }

    public void publishBatch(List<ArchivedItem> items, Instant at) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ArchivedItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("conversationId", item.conversationId().asString());
            row.put("title", item.title());
            row.put("revision", item.revision());
            row.put("reason", item.reason());
            rows.add(row);
        }
        noticeCenter.publishArchive(
                "已自动归档 " + items.size() + " 个会话", null, rows, at);
    }

    public List<Notice> listOpen() {
        List<Notice> out = new ArrayList<>();
        for (UserNotice n : noticeCenter.listOpen()) {
            if (n.kind() != NoticeKind.ARCHIVE) {
                continue;
            }
            out.add(toLegacy(n));
        }
        return List.copyOf(out);
    }

    public boolean dismiss(String noticeId) {
        return noticeCenter.dismiss(noticeId);
    }

    public boolean removeConversation(ConversationId conversationId) {
        return noticeCenter.removeArchiveConversation(conversationId);
    }

    public void dismissAllOpen() {
        // 清空归档动作：只关 ARCHIVE，不动任务通知
        for (UserNotice n : noticeCenter.listOpen()) {
            if (n.kind() == NoticeKind.ARCHIVE) {
                noticeCenter.dismiss(n.noticeId());
            }
        }
    }

    private static Notice toLegacy(UserNotice n) {
        List<ArchivedItem> items = new ArrayList<>();
        Object raw = n.payload().get("items");
        if (raw instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> map)) {
                    continue;
                }
                Object cid = map.get("conversationId");
                if (cid == null) {
                    continue;
                }
                long revision = 0L;
                Object rev = map.get("revision");
                if (rev instanceof Number num) {
                    revision = num.longValue();
                } else if (rev != null) {
                    try {
                        revision = Long.parseLong(String.valueOf(rev));
                    } catch (NumberFormatException ignored) {
                        revision = 0L;
                    }
                }
                items.add(
                        new ArchivedItem(
                                new ConversationId(UUID.fromString(String.valueOf(cid))),
                                map.get("title") == null ? "" : String.valueOf(map.get("title")),
                                revision,
                                map.get("reason") == null ? "" : String.valueOf(map.get("reason"))));
            }
        }
        return new Notice(n.noticeId(), n.createdAt(), items, n.dismissed());
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
