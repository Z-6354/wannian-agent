package com.wannian.server.app.http;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.app.task.BackgroundTaskQueryService;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.task.CancelTaskResult;
import com.wannian.server.kernel.task.TaskRuntime;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 2.5.9：后台 Task 读 API + 取消（对标 TaskReviewController 形态）。
 */
@RestController
@RequestMapping("/api/conversations/{conversationId}/background-tasks")
public class BackgroundTaskController {

    private final BackgroundTaskQueryService queryService;
    private final TaskRuntime taskRuntime;

    public BackgroundTaskController(
            BackgroundTaskQueryService queryService, TaskRuntime taskRuntime) {
        this.queryService = queryService;
        this.taskRuntime = taskRuntime;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @PathVariable String conversationId,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        Optional<ConversationId> cid = HttpMapping.conversationId(conversationId);
        if (cid.isEmpty()) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
        }
        return ResponseEntity.ok(Map.of("items", queryService.list(cid.get(), activeOnly)));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> get(
            @PathVariable String conversationId, @PathVariable String taskId) {
        Optional<ConversationId> cid = HttpMapping.conversationId(conversationId);
        if (cid.isEmpty()) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
        }
        BackgroundTaskId tid;
        try {
            tid = BackgroundTaskId.parse(taskId);
        } catch (RuntimeException ex) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "taskId 不是合法 UUID");
        }
        Optional<Map<String, Object>> detail = queryService.get(cid.get(), tid);
        if (detail.isEmpty()) {
            return error(ErrorCodes.TASK_NOT_FOUND, "任务不存在或不属于该会话");
        }
        return ResponseEntity.ok(detail.get());
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable String conversationId, @PathVariable String taskId) {
        Optional<ConversationId> cid = HttpMapping.conversationId(conversationId);
        if (cid.isEmpty()) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
        }
        BackgroundTaskId tid;
        try {
            tid = BackgroundTaskId.parse(taskId);
        } catch (RuntimeException ex) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "taskId 不是合法 UUID");
        }
        // 归属校验（取消前）
        if (queryService.get(cid.get(), tid).isEmpty()) {
            return error(ErrorCodes.TASK_NOT_FOUND, "任务不存在或不属于该会话");
        }
        CancelTaskResult result = taskRuntime.requestCancel(tid);
        if (result instanceof CancelTaskResult.Accepted accepted) {
            return ResponseEntity.ok(
                    Map.of(
                            "status",
                            accepted.status().name(),
                            "taskId",
                            accepted.taskId().asString()));
        }
        if (result instanceof CancelTaskResult.AlreadyTerminal already) {
            return error(
                    ErrorCodes.TASK_ALREADY_TERMINAL,
                    "任务已终态: " + already.status().name());
        }
        if (result instanceof CancelTaskResult.NotFound) {
            return error(ErrorCodes.TASK_NOT_FOUND, "任务不存在");
        }
        if (result instanceof CancelTaskResult.NotEnabled notEnabled) {
            return error(ErrorCodes.BACKGROUND_NOT_ENABLED, notEnabled.reason());
        }
        return error(ErrorCodes.INTERNAL_DEFECT, "未知取消结果");
    }

    /** 删除终态任务（从列表移除）；进行中请先 {@code /cancel}。 */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> delete(
            @PathVariable String conversationId, @PathVariable String taskId) {
        Optional<ConversationId> cid = HttpMapping.conversationId(conversationId);
        if (cid.isEmpty()) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
        }
        BackgroundTaskId tid;
        try {
            tid = BackgroundTaskId.parse(taskId);
        } catch (RuntimeException ex) {
            return error(ErrorCodes.ILLEGAL_ARGUMENT, "taskId 不是合法 UUID");
        }
        Optional<Map<String, Object>> detail = queryService.get(cid.get(), tid);
        if (detail.isEmpty()) {
            return error(ErrorCodes.TASK_NOT_FOUND, "任务不存在或不属于该会话");
        }
        Object statusObj = detail.get().get("status");
        String status = statusObj == null ? "" : String.valueOf(statusObj);
        if (!("SUCCEEDED".equals(status)
                || "FAILED".equals(status)
                || "CANCELLED".equals(status))) {
            return error(ErrorCodes.ILLEGAL_STATUS, "任务未结束，请先取消后再删除");
        }
        if (!queryService.deleteTerminal(cid.get(), tid)) {
            return error(ErrorCodes.TASK_NOT_FOUND, "删除失败：任务不存在或已变化");
        }
        return ResponseEntity.ok(Map.of("ok", true, "taskId", tid.asString()));
    }

    private static ResponseEntity<Map<String, String>> error(String code, String detail) {
        return ResponseEntity.status(HttpMapping.statusForPublic(code))
                .body(Map.of("code", code, "detail", detail));
    }
}
