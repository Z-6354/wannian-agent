package com.wannian.server.app.manage;

import com.wannian.server.app.manage.ManageBodies.AddListedRequest;
import com.wannian.server.app.manage.ManageBodies.EnableRequest;
import com.wannian.server.app.manage.ManageBodies.EnabledBody;
import com.wannian.server.app.manage.ManageBodies.ErrorBody;
import com.wannian.server.app.manage.ManageBodies.ListedBody;
import com.wannian.server.app.manage.ManageBodies.ModelListBody;
import com.wannian.server.app.manage.ManageBodies.ProbeRequest;
import com.wannian.server.app.manage.ManageBodies.ProbeResponse;
import com.wannian.server.app.manage.ManageBodies.UpsertVendorRequest;
import com.wannian.server.app.manage.ModelVendorStore.AddResult;
import com.wannian.server.app.manage.ModelVendorStore.DeleteResult;
import com.wannian.server.app.manage.ModelVendorStore.EnableResult;
import com.wannian.server.app.manage.ModelVendorStore.ListResult;
import com.wannian.server.app.manage.ModelVendorStore.RemoveResult;
import com.wannian.server.app.manage.ModelVendorStore.SaveResult;
import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.app.model.EnabledModelPortResolver.ResolveResult;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型供应商管理与探测。不改 Turn。probe 只用于验证已启用模型，不是对话入口。
 */
@RestController
@RequestMapping("/api/manage/model")
public class ModelManageController {

    private final ModelVendorStore store;
    private final EnabledModelPortResolver modelPorts;

    public ModelManageController(ModelVendorStore store, EnabledModelPortResolver modelPorts) {
        this.store = store;
        this.modelPorts = modelPorts;
    }

    @GetMapping("/vendors")
    public List<ManageBodies.VendorBody> listVendors() {
        return store.list();
    }

    @PutMapping("/vendors/{id}")
    public ResponseEntity<?> saveVendor(@PathVariable String id, @RequestBody(required = false) UpsertVendorRequest request) {
        return switch (store.save(id, request)) {
            case SaveResult.Saved saved ->
                    ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK).body(saved.vendor());
            case SaveResult.Rejected rejected -> error(rejected.code(), rejected.detail());
        };
    }

    @DeleteMapping("/vendors/{id}")
    public ResponseEntity<?> deleteVendor(@PathVariable String id) {
        return switch (store.delete(id)) {
            case DeleteResult.Deleted ignored -> ResponseEntity.noContent().build();
            case DeleteResult.Rejected rejected -> error(rejected.code(), rejected.detail());
        };
    }

    @PostMapping("/vendors/{id}/models:list")
    public ResponseEntity<?> listModels(@PathVariable String id) {
        return switch (store.listModels(id)) {
            case ListResult.Listed listed -> ResponseEntity.ok(new ModelListBody(listed.vendorId(), listed.entries()));
            case ListResult.Rejected rejected -> error(rejected.code(), rejected.detail());
        };
    }

    @GetMapping("/listed")
    public ListedBody listed() {
        return new ListedBody(store.listListed());
    }

    @PutMapping("/listed")
    public ResponseEntity<?> addListed(@RequestBody(required = false) AddListedRequest request) {
        if (request == null) {
            return error(ManageReason.ILLEGAL_ARGUMENT, "请求体不能为空");
        }
        return switch (store.addListed(request.vendorId(), request.modelId())) {
            case AddResult.Added added -> ResponseEntity.ok(added.model());
            case AddResult.Rejected rejected -> error(rejected.code(), rejected.detail());
        };
    }

    @DeleteMapping("/listed")
    public ResponseEntity<?> removeListed(
            @RequestParam(name = "vendorId", required = false) String vendorId,
            @RequestParam(name = "modelId", required = false) String modelId) {
        return switch (store.removeListed(vendorId, modelId)) {
            case RemoveResult.Removed ignored -> ResponseEntity.noContent().build();
            case RemoveResult.Rejected rejected -> error(rejected.code(), rejected.detail());
        };
    }

    @GetMapping("/enabled")
    public EnabledBody enabled() {
        return new EnabledBody(store.findEnabled());
    }

    @PutMapping("/enabled")
    public ResponseEntity<?> enable(@RequestBody(required = false) EnableRequest request) {
        if (request == null) {
            return error(ManageReason.ILLEGAL_ARGUMENT, "请求体不能为空");
        }
        return switch (store.enable(request.vendorId(), request.modelId())) {
            case EnableResult.Enabled enabled -> ResponseEntity.ok(new EnabledBody(enabled.selection()));
            case EnableResult.Rejected rejected -> error(rejected.code(), rejected.detail());
        };
    }

    @PostMapping("/probe")
    public ResponseEntity<?> probe(@RequestBody(required = false) ProbeRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return error(ManageReason.ILLEGAL_ARGUMENT, "text 不能为空");
        }
        return switch (modelPorts.resolve()) {
            case ResolveResult.Rejected rejected -> error(rejected.code(), rejected.detail());
            case ResolveResult.Resolved resolved -> {
                ModelOutcome outcome =
                        resolved
                                .port()
                                .decide(
                                        new ModelRequest(List.of(new ModelMessage("user", request.text()))),
                                        new ModelCallContext(
                                                "probe",
                                                1,
                                                Instant.now().plusSeconds(30),
                                                false,
                                                UUID.randomUUID().toString()));
                yield switch (outcome) {
                    case ModelOutcome.FinalAnswer answer ->
                            ResponseEntity.ok(
                                    new ProbeResponse(
                                            "final",
                                            answer.text(),
                                            resolved.vendorId(),
                                            resolved.modelId(),
                                            null,
                                            null));
                    case ModelOutcome.ModelRefusal refusal ->
                            ResponseEntity.ok(
                                    new ProbeResponse(
                                            "refusal",
                                            refusal.reason(),
                                            resolved.vendorId(),
                                            resolved.modelId(),
                                            null,
                                            null));
                    case ModelOutcome.ToolCalls ignored ->
                            error(ManageReason.DEPENDENCY_UNAVAILABLE, "探测不支持工具调用响应");
                    case ModelOutcome.Failure failure -> error(failure.code(), failure.detail());
                };
            }
        };
    }

    private static ResponseEntity<ErrorBody> error(String code, String detail) {
        return ResponseEntity.status(statusFor(code)).body(new ErrorBody(code, detail));
    }

    private static HttpStatus statusFor(String code) {
        return switch (code) {
            case ManageReason.VENDOR_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ManageReason.REVISION_CONFLICT -> HttpStatus.CONFLICT;
            case ManageReason.MANAGE_UNCONFIGURED,
                    ManageReason.DEPENDENCY_UNAVAILABLE,
                    ErrorCodes.MODEL_TIMEOUT,
                    ErrorCodes.MODEL_RATE_LIMITED ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case ManageReason.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case ManageReason.MODEL_NOT_ENABLED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
