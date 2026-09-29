package com.wannian.server.app.http;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TaskReviewId;
import com.wannian.server.app.task.BackgroundTaskQueryService;
import com.wannian.server.app.task.TaskReviewService;
import com.wannian.server.app.task.TaskReviewService.ReviewActionResult;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.task.TaskReviewPending;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 2.5.5：后台任务用户审核 HTTP（列表 / 确认 / 驳回）。
 */
@RestController
@RequestMapping("/api/conversations/{conversationId}/task-reviews")
public class TaskReviewController {

    private final TaskReviewService taskReviewService;
    private final BackgroundTaskQueryService taskQuery;

    public TaskReviewController(
            TaskReviewService taskReviewService, BackgroundTaskQueryService taskQuery) {
        this.taskReviewService = taskReviewService;
        this.taskQuery = taskQuery;
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable String conversationId) {
        Optional<ConversationId> cid = HttpMapping.conversationId(conversationId);
        if (cid.isEmpty()) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
        }
        List<TaskReviewPending> pending = taskReviewService.listPending(cid.get());
        List<Map<String, Object>> items = new ArrayList<>(pending.size());
        for (TaskReviewPending row : pending) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reviewId", row.reviewId().asString());
            item.put("turnId", row.turnId().asString());
            item.put("taskType", row.proposal().taskType().name());
            item.put(
                    "inputPreview",
                    taskQuery.humanInputPreview(row.proposal().inputJson(), 160));
            item.put("acknowledgementText", row.acknowledgementText());
            item.put("scheduled", row.proposal().scheduleSpec() != null);
            item.put("expiresAt", row.expiresAt().toString());
            item.put("status", row.status().name());
            items.add(item);
        }
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PostMapping("/{reviewId}/confirm")
    public ResponseEntity<?> confirm(
            @PathVariable String conversationId, @PathVariable String reviewId) {
        Optional<ConversationId> cid = HttpMapping.conversationId(conversationId);
        if (cid.isEmpty()) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
        }
        TaskReviewId rid;
        try {
            rid = TaskReviewId.parse(reviewId);
        } catch (RuntimeException ex) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "reviewId 不是合法 UUID");
        }
        ReviewActionResult result = taskReviewService.confirm(cid.get(), rid);
        if (!result.success()) {
            return error(result.code(), result.detail());
        }
        return ResponseEntity.ok(
                Map.of(
                        "ok",
                        true,
                        "message",
                        result.detail() == null ? "" : result.detail(),
                        "taskId",
                        result.taskId() == null ? "" : result.taskId()));
    }

    @PostMapping("/{reviewId}/reject")
    public ResponseEntity<?> reject(
            @PathVariable String conversationId,
            @PathVariable String reviewId,
            @RequestBody(required = false) Map<String, String> body) {
        Optional<ConversationId> cid = HttpMapping.conversationId(conversationId);
        if (cid.isEmpty()) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
        }
        TaskReviewId rid;
        try {
            rid = TaskReviewId.parse(reviewId);
        } catch (RuntimeException ex) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "reviewId 不是合法 UUID");
        }
        String reason = body == null ? null : body.get("reason");
        ReviewActionResult result = taskReviewService.reject(cid.get(), rid, reason);
        if (!result.success()) {
            return error(result.code(), result.detail());
        }
        return ResponseEntity.ok(
                Map.of("ok", true, "message", result.detail() == null ? "" : result.detail()));
    }

    private static ResponseEntity<?> error(String code, String detail) {
        return ResponseEntity.status(HttpMapping.statusForPublic(code))
                .body(Map.of("code", code, "detail", detail == null ? "" : detail));
    }
}
