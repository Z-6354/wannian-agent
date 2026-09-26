package com.wannian.server.app.http;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.app.archive.ArchiveNoticeStore;
import com.wannian.server.app.archive.ArchiveNoticeStore.ArchivedItem;
import com.wannian.server.app.archive.ArchiveNoticeStore.Notice;
import com.wannian.server.kernel.conversation.ConversationMutationResult;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.error.ErrorCodes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 0.2.4-G：空会话清理与自动归档通知占位 API（完整消息中心 → 0.2.5）。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationHygieneController {

    private final ConversationStore conversationStore;
    private final ArchiveNoticeStore notices;

    public ConversationHygieneController(
            ConversationStore conversationStore, ArchiveNoticeStore notices) {
        this.conversationStore = conversationStore;
        this.notices = notices;
    }

    @PostMapping("/empty/purge")
    public ResponseEntity<Map<String, Object>> purgeEmpty() {
        int deleted = conversationStore.purgeEmptyActiveConversations(100);
        return ResponseEntity.ok(Map.of("result", "ok", "deleted", deleted));
    }

    @GetMapping("/notices/archive")
    public ResponseEntity<Map<String, Object>> listArchiveNotices() {
        List<Map<String, Object>> body = new ArrayList<>();
        for (Notice n : notices.listOpen()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (ArchivedItem item : n.items()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("conversationId", item.conversationId().asString());
                row.put("title", item.title());
                row.put("revision", item.revision());
                row.put("reason", item.reason());
                items.add(row);
            }
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("id", n.id());
            notice.put("at", n.at().toString());
            notice.put("items", items);
            body.add(notice);
        }
        return ResponseEntity.ok(Map.of("result", "ok", "notices", body));
    }

    @PostMapping("/notices/archive/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(@RequestBody Map<String, String> body) {
        String id = body == null ? null : body.get("noticeId");
        if (id == null || id.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("result", "rejected", "reasonCode", ErrorCodes.ILLEGAL_ARGUMENT));
        }
        boolean ok = notices.dismiss(id.trim());
        return ResponseEntity.ok(Map.of("result", ok ? "ok" : "not_found"));
    }

    @PostMapping("/notices/archive/undo")
    public ResponseEntity<ConversationDetailResponse> undo(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest()
                    .body(
                            ConversationDetailResponse.rejected(
                                    ErrorCodes.ILLEGAL_ARGUMENT, "body 必填"));
        }
        Object rawId = body.get("conversationId");
        Object rawRev = body.get("expectedRevision");
        if (!(rawId instanceof String cid) || cid.isBlank() || rawRev == null) {
            return ResponseEntity.badRequest()
                    .body(
                            ConversationDetailResponse.rejected(
                                    ErrorCodes.ILLEGAL_ARGUMENT, "conversationId/expectedRevision 无效"));
        }
        Optional<ConversationId> parsed = HttpMapping.conversationId(cid);
        if (parsed.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            ConversationDetailResponse.rejected(
                                    ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID"));
        }
        long revision;
        try {
            revision = Long.parseLong(String.valueOf(rawRev));
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest()
                    .body(
                            ConversationDetailResponse.rejected(
                                    ErrorCodes.ILLEGAL_ARGUMENT, "expectedRevision 无效"));
        }
        ConversationMutationResult result = conversationStore.unarchive(parsed.get(), revision);
        if (result instanceof ConversationMutationResult.Ok) {
            notices.removeConversation(parsed.get());
        }
        return HttpMapping.conversationMutation(result);
    }
}
