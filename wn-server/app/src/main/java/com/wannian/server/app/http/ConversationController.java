package com.wannian.server.app.http;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.conversation.ConversationStatus;
import com.wannian.server.app.archive.ArchiveNoticeStore;
import com.wannian.server.app.persistence.SqliteOutboxQuery;
import com.wannian.server.kernel.conversation.ConversationHistoryQuery;
import com.wannian.server.kernel.conversation.ConversationHistoryResult;
import com.wannian.server.kernel.conversation.ConversationListQuery;
import com.wannian.server.kernel.conversation.ConversationListResult;
import com.wannian.server.kernel.conversation.ConversationMutationResult;
import com.wannian.server.kernel.conversation.ConversationSearchQuery;
import com.wannian.server.kernel.conversation.ConversationSearchResult;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.ConversationSummary;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.EmptyArchiveCommand;
import com.wannian.server.kernel.conversation.EmptyTrashCommand;
import com.wannian.server.kernel.conversation.EmptyTrashResult;
import com.wannian.server.kernel.conversation.RenameConversationCommand;
import com.wannian.server.kernel.error.ErrorCodes;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话创建与 0.2.4-B 读写 API（列表/历史/搜索/生命周期）。
 *
 * <p>不接收 Turn、不调用模型。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationStore conversationStore;
    private final ConversationHistoryAssembler historyAssembler;
    private final SqliteOutboxQuery outboxQuery;
    private final ArchiveNoticeStore archiveNotices;

    public ConversationController(
            ConversationStore conversationStore,
            ConversationHistoryAssembler historyAssembler,
            SqliteOutboxQuery outboxQuery,
            ArchiveNoticeStore archiveNotices) {
        this.conversationStore = conversationStore;
        this.historyAssembler = historyAssembler;
        this.outboxQuery = outboxQuery;
        this.archiveNotices = archiveNotices;
    }

    @PostMapping
    public ResponseEntity<CreateConversationResponse> create(
            @RequestBody(required = false) CreateConversationRequest request) {
        String rawId = request == null ? null : request.conversationId();
        ConversationId id;
        if (rawId == null || rawId.isBlank()) {
            id = ConversationId.generate();
        } else {
            Optional<ConversationId> parsed = HttpMapping.conversationId(rawId);
            if (parsed.isEmpty()) {
                return HttpMapping.rejectedConversation(
                        ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
            }
            id = parsed.get();
        }
        String title = request == null ? null : request.title();
        CreateConversationCommand command =
                new CreateConversationCommand(
                        id, title == null ? Optional.empty() : Optional.of(title));
        return HttpMapping.conversation(conversationStore.create(command));
    }

    @GetMapping
    public ResponseEntity<ConversationListResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        Optional<ConversationStatus> filter = parseStatus(status);
        if (status != null && !status.isBlank() && filter.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ConversationListResponse.rejected(
                            ErrorCodes.ILLEGAL_ARGUMENT, "status 无效"));
        }
        int capped = Math.min(100, Math.max(1, limit));
        ConversationListResult result =
                conversationStore.list(
                        new ConversationListQuery(
                                filter,
                                cursor == null || cursor.isBlank()
                                        ? Optional.empty()
                                        : Optional.of(cursor.trim()),
                                capped));
        return ResponseEntity.ok(ConversationListResponse.from(result));
    }

    @GetMapping("/recent")
    public ResponseEntity<ConversationDetailResponse> recent() {
        Optional<ConversationSummary> recent = conversationStore.findRecentActive();
        if (recent.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        ConversationSummary summary = recent.get();
        long outboxCursor = outboxQuery.latestSequence(summary.id().asString());
        return ResponseEntity.ok(ConversationDetailResponse.from(summary, outboxCursor));
    }

    @GetMapping("/search")
    public ResponseEntity<ConversationSearchResponse> search(
            @RequestParam String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ConversationSearchResponse.rejected(
                            ErrorCodes.ILLEGAL_ARGUMENT,
                            "q 不能为空；空查询请用 GET /api/conversations"));
        }
        Optional<ConversationStatus> filter = parseStatus(status);
        if (status != null && !status.isBlank() && filter.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ConversationSearchResponse.rejected(
                            ErrorCodes.ILLEGAL_ARGUMENT, "status 无效"));
        }
        int capped = Math.min(50, Math.max(1, limit));
        ConversationSearchResult result =
                conversationStore.search(
                        new ConversationSearchQuery(
                                q.trim(),
                                filter,
                                cursor == null || cursor.isBlank()
                                        ? Optional.empty()
                                        : Optional.of(cursor.trim()),
                                capped));
        return ResponseEntity.ok(ConversationSearchResponse.from(result));
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationDetailResponse> get(@PathVariable String conversationId) {
        Optional<ConversationId> id = HttpMapping.conversationId(conversationId);
        if (id.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ConversationDetailResponse.rejected(
                            ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID"));
        }
        Optional<ConversationSummary> found = conversationStore.find(id.get());
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ConversationDetailResponse.rejected(
                            ErrorCodes.CONVERSATION_NOT_FOUND, "会话不存在"));
        }
        ConversationSummary summary = found.get();
        long outboxCursor = outboxQuery.latestSequence(summary.id().asString());
        return ResponseEntity.ok(ConversationDetailResponse.from(summary, outboxCursor));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<ConversationMessagesResponse> messages(
            @PathVariable String conversationId,
            @RequestParam(required = false) Integer afterSeq,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "true") boolean includeToolCalls) {
        Optional<ConversationId> id = HttpMapping.conversationId(conversationId);
        if (id.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ConversationMessagesResponse.rejected(
                            ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID"));
        }
        int capped = Math.min(100, Math.max(1, limit));
        ConversationHistoryResult history =
                conversationStore.listMessages(
                        new ConversationHistoryQuery(
                                id.get(),
                                afterSeq == null ? Optional.empty() : Optional.of(afterSeq),
                                capped));
        return switch (history) {
            case ConversationHistoryResult.Rejected rejected ->
                    ResponseEntity.status(HttpMapping.statusForPublic(rejected.reasonCode()))
                            .body(ConversationMessagesResponse.rejected(
                                    rejected.reasonCode(), rejected.detail()));
            case ConversationHistoryResult.Ok ok ->
                    ResponseEntity.ok(historyAssembler.toResponse(ok, includeToolCalls));
        };
    }

    @PatchMapping("/{conversationId}")
    public ResponseEntity<ConversationDetailResponse> patch(
            @PathVariable String conversationId,
            @RequestBody(required = false) ConversationPatchRequest request) {
        Optional<ConversationId> id = HttpMapping.conversationId(conversationId);
        if (id.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ConversationDetailResponse.rejected(
                            ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID"));
        }
        if (request == null || request.op() == null || request.op().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ConversationDetailResponse.rejected(
                            ErrorCodes.ILLEGAL_ARGUMENT, "op 不能为空"));
        }
        if (request.expectedRevision() == null) {
            return ResponseEntity.badRequest()
                    .body(ConversationDetailResponse.rejected(
                            ErrorCodes.ILLEGAL_ARGUMENT, "expectedRevision 不能为空"));
        }
        long rev = request.expectedRevision();
        String op = request.op().trim().toLowerCase(Locale.ROOT);
        ConversationMutationResult result =
                switch (op) {
                    case "rename" ->
                            conversationStore.rename(
                                    new RenameConversationCommand(
                                            id.get(),
                                            rev,
                                            request.title() == null ? "" : request.title()));
                    case "archive" -> conversationStore.archive(id.get(), rev);
                    case "unarchive" -> conversationStore.unarchive(id.get(), rev);
                    case "trash" -> conversationStore.trash(id.get(), rev);
                    case "restore" -> conversationStore.restore(id.get(), rev);
                    case "pin" -> conversationStore.pin(id.get(), rev);
                    case "unpin" -> conversationStore.unpin(id.get(), rev);
                    default ->
                            new ConversationMutationResult.Rejected(
                                    ErrorCodes.ILLEGAL_ARGUMENT, "未知 op: " + op);
                };
        return HttpMapping.conversationMutation(result);
    }

    @PostMapping("/trash/empty")
    public ResponseEntity<EmptyTrashResponse> emptyTrash(
            @RequestBody(required = false) EmptyTrashRequest request) {
        if (request == null || request.confirm() == null) {
            return ResponseEntity.badRequest()
                    .body(EmptyTrashResponse.rejected(
                            ErrorCodes.ILLEGAL_ARGUMENT, "confirm 不能为空"));
        }
        int batch = request.batchLimit() == null ? 20 : request.batchLimit();
        EmptyTrashResult result;
        try {
            result =
                    conversationStore.emptyTrash(
                            new EmptyTrashCommand(request.confirm().trim(), batch));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(EmptyTrashResponse.rejected(ErrorCodes.ILLEGAL_ARGUMENT, ex.getMessage()));
        }
        return switch (result) {
            case EmptyTrashResult.Ok ok ->
                    ResponseEntity.ok(
                            new EmptyTrashResponse(
                                    "ok", ok.deletedCount(), ok.skippedBusy(), null, null));
            case EmptyTrashResult.Rejected rejected ->
                    ResponseEntity.status(HttpMapping.statusForPublic(rejected.reasonCode()))
                            .body(EmptyTrashResponse.rejected(rejected.reasonCode(), rejected.detail()));
        };
    }

    /**
     * 永久删除归档会话（消息 / turn / 搜索一并清掉），并关闭自动归档占位通知。
     */
    @PostMapping("/archive/empty")
    public ResponseEntity<EmptyTrashResponse> emptyArchive(
            @RequestBody(required = false) EmptyTrashRequest request) {
        if (request == null || request.confirm() == null) {
            return ResponseEntity.badRequest()
                    .body(EmptyTrashResponse.rejected(
                            ErrorCodes.ILLEGAL_ARGUMENT, "confirm 不能为空"));
        }
        int batch = request.batchLimit() == null ? 50 : request.batchLimit();
        EmptyTrashResult result;
        try {
            result =
                    conversationStore.emptyArchive(
                            new EmptyArchiveCommand(request.confirm().trim(), batch));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(EmptyTrashResponse.rejected(ErrorCodes.ILLEGAL_ARGUMENT, ex.getMessage()));
        }
        return switch (result) {
            case EmptyTrashResult.Ok ok -> {
                if (ok.deletedCount() > 0) {
                    archiveNotices.dismissAllOpen();
                }
                yield ResponseEntity.ok(
                        new EmptyTrashResponse(
                                "ok", ok.deletedCount(), ok.skippedBusy(), null, null));
            }
            case EmptyTrashResult.Rejected rejected ->
                    ResponseEntity.status(HttpMapping.statusForPublic(rejected.reasonCode()))
                            .body(EmptyTrashResponse.rejected(rejected.reasonCode(), rejected.detail()));
        };
    }

    private static Optional<ConversationStatus> parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ConversationStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
