package com.wannian.server.app.http;

import com.wannian.server.app.http.MemoryHttpBodies.CorrectMemoryRequest;
import com.wannian.server.app.http.MemoryHttpBodies.ForgetMemoryRequest;
import com.wannian.server.app.http.MemoryHttpBodies.MemoryEntryBody;
import com.wannian.server.app.http.MemoryHttpBodies.MemoryErrorBody;
import com.wannian.server.app.http.MemoryHttpBodies.MemoryListBody;
import com.wannian.server.app.http.MemoryHttpBodies.MemoryWriteBody;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.DefaultMemoryShape;
import com.wannian.server.kernel.memory.MemoryCommand;
import com.wannian.server.kernel.memory.MemoryLifecycle;
import com.wannian.server.kernel.memory.MemoryPolicy;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.MemoryShape;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.MemoryToolDraft;
import com.wannian.server.kernel.memory.SecretOnlyMemoryPolicy;
import com.wannian.server.kernel.memory.SourceKind;
import com.wannian.server.kernel.memory.StoredMemoryRecord;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S11-a 记忆 HTTP：列表 ACTIVE、纠正 SUPERSEDE、遗忘 FORGOTTEN。
 *
 * <p><b>硬约束</b>
 *
 * <ul>
 *   <li>禁止假 200：写路径只在 {@link MemoryCommand.CommandResult.Applied} 时 200。
 *   <li>写库只经 {@link MemoryCommand}；本类不做 JDBC。
 *   <li>correct 前必须 Shape + Policy；{@code proposeId} 服务端写死 {@code http_correct}。
 *   <li>companion 本批仅 yanhuo；GET <strong>须</strong>显式 {@code ?companion=yanhuo}（缺省/其它 → 400）。
 *   <li>错误映射：{@code MEMORY_NOT_FOUND}→404；{@code REVISION_CONFLICT}→409；其余校验类→400。
 * </ul>
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryHttpController {

    private final MemoryStore memoryStore;
    private final MemoryCommand memoryCommand;
    private final MemoryShape shape;
    private final MemoryPolicy policy;

    public MemoryHttpController(MemoryStore memoryStore, MemoryCommand memoryCommand) {
        this.memoryStore = memoryStore;
        this.memoryCommand = memoryCommand;
        this.shape = new DefaultMemoryShape();
        this.policy = new SecretOnlyMemoryPolicy();
    }

    /** GET：仅 ACTIVE；不含 FORGOTTEN / SUPERSEDED。 */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String companion) {
        CompanionIdentity identity = parseCompanion(companion);
        if (identity == null) {
            return error(HttpStatus.BAD_REQUEST, ErrorCodes.ILLEGAL_ARGUMENT, "须提供 companion=yanhuo");
        }
        List<StoredMemoryRecord> active =
                memoryStore.listByCompanion(identity, MemoryLifecycle.ACTIVE);
        List<MemoryEntryBody> entries = new ArrayList<>(active.size());
        for (StoredMemoryRecord row : active) {
            entries.add(
                            new MemoryEntryBody(
                                    row.id(),
                                    row.subjectKey(),
                                    row.claim(),
                                    row.contentKind().name(),
                                    row.sourceKind().name(),
                                    row.scope().name(),
                                    row.importance(),
                                    row.path(),
                                    row.revision(),
                                    row.createdAt().toString()));
        }
        return ResponseEntity.ok(new MemoryListBody(identity.value(), List.copyOf(entries)));
    }

    /**
     * POST correct：请求体 → Draft → Shape → Policy → {@link MemoryCommand#correct}。
     *
     * <p>Policy REJECT（如密钥）→ 400，不写库。
     */
    @PostMapping("/correct")
    public ResponseEntity<?> correct(@RequestBody(required = false) CorrectMemoryRequest request) {
        if (request == null
                || request.id() == null
                || request.id().isBlank()
                || request.expectedRevision() == null
                || request.subjectKey() == null
                || request.claim() == null
                || request.contentKind() == null
                || request.sourceKind() == null
                || request.scope() == null
                || request.importance() == null) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.ILLEGAL_ARGUMENT,
                    "id/expectedRevision/subjectKey/claim/contentKind/sourceKind/scope/importance 均必填");
        }
        MemoryToolDraft draft;
        try {
            draft =
                    new MemoryToolDraft(
                            CompanionIdentity.YANHUO,
                            request.subjectKey(),
                            request.claim(),
                            ContentKind.valueOf(request.contentKind().trim()),
                            SourceKind.valueOf(request.sourceKind().trim()),
                            MemoryScope.valueOf(request.scope().trim()),
                            request.importance(),
                            request.path());
        } catch (RuntimeException ex) {
            return error(HttpStatus.BAD_REQUEST, ErrorCodes.ILLEGAL_ARGUMENT, ex.getMessage());
        }
        MemoryShape.ShapeResult shaped = shape.shape(draft);
        if (shaped instanceof MemoryShape.ShapeResult.Rejected rejected) {
            return error(HttpStatus.BAD_REQUEST, rejected.code(), rejected.message());
        }
        MemoryToolDraft accepted = ((MemoryShape.ShapeResult.Accepted) shaped).shaped();
        MemoryPolicy.PolicyResult decided = policy.evaluate(accepted);
        if (decided instanceof MemoryPolicy.PolicyResult.Rejected rejected) {
            return error(HttpStatus.BAD_REQUEST, rejected.code(), rejected.message());
        }
        ApprovedMemoryChange change =
                new ApprovedMemoryChange(
                        accepted.companionIdentity(),
                        accepted.subjectKey(),
                        accepted.claim(),
                        accepted.contentKind(),
                        accepted.sourceKind(),
                        accepted.scope(),
                        accepted.importance(),
                        accepted.path(),
                        ApprovedMemoryChange.PROPOSE_HTTP_CORRECT);
        return mapWrite(memoryCommand.correct(request.id().trim(), request.expectedRevision(), change));
    }

    /** POST forget：人主动遗忘；清空策略在 Command。 */
    @PostMapping("/forget")
    public ResponseEntity<?> forget(@RequestBody(required = false) ForgetMemoryRequest request) {
        if (request == null
                || request.id() == null
                || request.id().isBlank()
                || request.expectedRevision() == null) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.ILLEGAL_ARGUMENT,
                    "id / expectedRevision 均必填");
        }
        return mapWrite(memoryCommand.forget(request.id().trim(), request.expectedRevision()));
    }

    /** 仅接受显式 {@code yanhuo}；缺省或其它 → null（由调用方转 400）。 */
    private static CompanionIdentity parseCompanion(String companion) {
        if (companion == null || companion.isBlank()) {
            return null;
        }
        if (CompanionIdentity.YANHUO.value().equals(companion.trim())) {
            return CompanionIdentity.YANHUO;
        }
        return null;
    }

    private static ResponseEntity<?> mapWrite(MemoryCommand.CommandResult result) {
        return switch (result) {
            case MemoryCommand.CommandResult.Applied applied ->
                    ResponseEntity.ok(new MemoryWriteBody(applied.memoryId(), applied.newRevision()));
            case MemoryCommand.CommandResult.Rejected rejected ->
                    error(statusFor(rejected.code()), rejected.code(), rejected.message());
        };
    }

    private static ResponseEntity<MemoryErrorBody> error(
            HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).body(new MemoryErrorBody(code, detail));
    }

    private static HttpStatus statusFor(String code) {
        return switch (code) {
            case ErrorCodes.MEMORY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.REVISION_CONFLICT, ErrorCodes.MEMORY_SUBJECT_CONFLICT ->
                    HttpStatus.CONFLICT;
            case ErrorCodes.PERSISTENCE_FAILED, ErrorCodes.RETRYABLE_BUSY ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
