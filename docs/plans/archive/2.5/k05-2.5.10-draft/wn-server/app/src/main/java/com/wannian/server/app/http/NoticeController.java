package com.wannian.server.app.http;

import com.wannian.server.app.notice.NoticeCenter;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.notice.UserNotice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 2.5.10：统一消息中心读/关 API。 */
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

    private final NoticeCenter noticeCenter;

    public NoticeController(NoticeCenter noticeCenter) {
        this.noticeCenter = noticeCenter;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> body = new ArrayList<>();
        for (UserNotice n : noticeCenter.listOpen()) {
            body.add(toJson(n));
        }
        return ResponseEntity.ok(Map.of("result", "ok", "notices", body));
    }

    @PostMapping("/{noticeId}/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(@PathVariable String noticeId) {
        if (noticeId == null || noticeId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("result", "rejected", "reasonCode", ErrorCodes.ILLEGAL_ARGUMENT));
        }
        boolean ok = noticeCenter.dismiss(noticeId.trim());
        return ResponseEntity.ok(Map.of("result", ok ? "ok" : "not_found"));
    }

    static Map<String, Object> toJson(UserNotice n) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("noticeId", n.noticeId());
        row.put("kind", n.kind().name());
        row.put("createdAt", n.createdAt().toString());
        row.put("dismissed", n.dismissed());
        row.put("title", n.title());
        if (n.body() != null) {
            row.put("body", n.body());
        }
        if (n.conversationId() != null) {
            row.put("conversationId", n.conversationId());
        }
        if (n.taskId() != null) {
            row.put("taskId", n.taskId());
        }
        if (n.errorCode() != null) {
            row.put("errorCode", n.errorCode());
        }
        if (n.terminalStatus() != null) {
            row.put("terminalStatus", n.terminalStatus());
        }
        if (!n.payload().isEmpty()) {
            row.put("payload", n.payload());
        }
        if (!n.actions().isEmpty()) {
            row.put("actions", n.actions());
        }
        return row;
    }
}
