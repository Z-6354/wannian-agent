package com.wannian.server.app.persona.importer;

import com.wannian.server.kernel.persona.PersonaDefinition;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class PersonaImportController {
    private final PersonaImportService service;
    public PersonaImportController(PersonaImportService service) { this.service=service; }

    @PostMapping(value="/personas/imports", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(value="characterHint",required=false) String characterHint,
            @RequestParam(value="target",required=false,defaultValue="NEW_PERSONA") String target,
            @RequestParam(value="requestKey",required=false) String requestKey) throws IOException {
        try { return ResponseEntity.accepted().body(service.upload(file,characterHint,PersonaImportDtos.Target.valueOf(target),requestKey)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(new ErrorBody(e.getMessage(), "TXT 上传参数无效")); }
    }
    @GetMapping("/personas/imports/{id}")
    public PersonaImportDtos.ImportStatus getImport(@PathVariable String id) { return service.get(id); }
    @GetMapping("/personas")
    public List<PersonaDefinition> list() { return service.list(); }
    @GetMapping("/personas/{id}")
    public PersonaImportDtos.Preview preview(@PathVariable String id) { return service.preview(id); }
    @PostMapping("/personas/{id}/activate")
    public PersonaDefinition activate(@PathVariable String id) { return service.activate(id); }
    @PostMapping("/personas/imports/{id}/apply-to-default")
    public ResponseEntity<?> applyToDefault(@PathVariable String id,@RequestBody PersonaImportDtos.ApplyToDefaultRequest request) {
        try {
            var staged=service.applyToDefault(id,request.expectedOverlayRevision(),request.operationId());
            return ResponseEntity.ok(staged);
        } catch(IllegalStateException ex) {
            String code=safeCode(ex);
            return ResponseEntity.status(code.equals("QUALITY_GATE_FAILED")?HttpStatus.UNPROCESSABLE_ENTITY:HttpStatus.CONFLICT)
                    .body(new ErrorBody(code,"默认角色审核稿未暂存（性格尚未写入正式 prompts）"));
        }
    }
    @GetMapping("/personas/imports/{id}/prompt-review")
    public PersonaImportDtos.PromptReviewStaging promptReview(@PathVariable String id) {
        return service.promptReviewStatus(id);
    }
    @PostMapping("/personas/imports/{id}/approve-prompt-merge")
    public ResponseEntity<?> approvePromptMerge(@PathVariable String id,@RequestBody PersonaImportDtos.ApprovePromptMergeRequest request) {
        try {
            return ResponseEntity.ok(service.approvePromptMerge(id,request.operationId()));
        } catch(IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody(safeCode(ex),"性格文件合并未通过"));
        } catch(IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorBody(ex.getMessage(),"性格文件合并参数无效"));
        }
    }
    @GetMapping("/personas/default/overlay")
    public PersonaImportDtos.DefaultOverlayState currentDefaultOverlay() {
        return service.currentDefaultOverlay().map(PersonaImportController::state)
                .orElseGet(()->new PersonaImportDtos.DefaultOverlayState(false,0,null,null,null,null,null));
    }
    @PostMapping("/personas/default/rollback")
    public ResponseEntity<?> rollbackDefault(@RequestBody PersonaImportDtos.RollbackDefaultRequest request) {
        try {
            var restored=service.rollbackDefaultOverlay(request.revision(),request.expectedCurrentRevision());
            return ResponseEntity.ok(restored.map(PersonaImportController::state)
                    .orElseGet(()->new PersonaImportDtos.DefaultOverlayState(false,0,null,null,null,null,null)));
        } catch(IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody(safeCode(ex),"默认角色补充回滚失败"));
        }
    }
    @PostMapping("/personas/{id}/archive")
    public PersonaDefinition archive(@PathVariable String id) { return service.archive(id); }
    @DeleteMapping("/personas/{id}/source")
    public ResponseEntity<Void> deleteSource(@PathVariable String id) { service.deleteSource(id); return ResponseEntity.noContent().build(); }
    @DeleteMapping("/personas/imports/{id}/source")
    public ResponseEntity<Void> deleteImportSource(@PathVariable String id) { service.deleteImportSource(id); return ResponseEntity.noContent().build(); }
    public record ErrorBody(String code,String message) {}
    private static PersonaImportDtos.DefaultOverlayState state(com.wannian.server.kernel.persona.DefaultPersonaOverlayRevision value) {
        return new PersonaImportDtos.DefaultOverlayState(true,value.revision(),value.sourcePersonaId().asString(),value.soul(),value.voice(),value.evidenceSummary(),value.createdAt());
    }
    private static String safeCode(IllegalStateException error) {
        String message=error.getMessage(); if(message==null)return "PERSONA_OPERATION_FAILED";
        int colon=message.indexOf(':'); String code=colon<0?message:message.substring(0,colon);
        return code.matches("[A-Z_]{2,64}")?code:"PERSONA_OPERATION_FAILED";
    }
}
