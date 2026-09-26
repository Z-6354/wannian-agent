package com.wannian.server.app.http;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.kernel.persona.ConversationPersonaBinding;
import com.wannian.server.kernel.persona.PersonaId;
import com.wannian.server.kernel.persona.PersonaCatalog;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Per-conversation persona binding endpoints. */
@RestController
@RequestMapping("/api/conversations/{conversationId}/persona")
public final class ConversationPersonaController {
    private final ConversationPersonaBinding bindings;
    private final PersonaCatalog catalog;
    public ConversationPersonaController(ConversationPersonaBinding bindings, PersonaCatalog catalog) { this.bindings=bindings; this.catalog=catalog; }
    @GetMapping public ResponseEntity<PersonaBindingBody> get(@PathVariable String conversationId) {
        Optional<ConversationId> id=HttpMapping.conversationId(conversationId);
        if(id.isEmpty()) return ResponseEntity.badRequest().body(new PersonaBindingBody("rejected",null,null,null,"ILLEGAL_ARGUMENT","conversationId 不是合法 UUID"));
        try {
            // Read current binding via resolving a synthetic-free direct query is exposed by the binding service below.
            ConversationPersonaBinding.BindingSnapshot binding=bindings.current(id.get());
            return ResponseEntity.ok(new PersonaBindingBody("ok",binding.personaId().value(),binding.revision(),catalog.get(binding.personaId()).map(d->d.displayName()).orElse("杜小洛"),null,null));
        } catch(RuntimeException ex) { return rejected(ex); }
    }
    @PutMapping public ResponseEntity<PersonaBindingBody> put(@PathVariable String conversationId,@RequestBody BindPersonaBody body) {
        Optional<ConversationId> id=HttpMapping.conversationId(conversationId);
        if(id.isEmpty() || body==null || body.personaId()==null || body.expectedRevision()==null) return ResponseEntity.badRequest().body(new PersonaBindingBody("rejected",null,null,null,"ILLEGAL_ARGUMENT","请求字段不合法"));
        try {var result=bindings.bind(id.get(),new PersonaId(body.personaId()),body.expectedRevision());return ResponseEntity.ok(new PersonaBindingBody("ok",result.personaId().value(),result.revision(),catalog.get(result.personaId()).map(d->d.displayName()).orElse("杜小洛"),null,null));}
        catch(RuntimeException ex){return rejected(ex);}
    }
    private static ResponseEntity<PersonaBindingBody> rejected(RuntimeException ex) {
        String text=ex.getMessage()==null?"":ex.getMessage(); String code=text.startsWith("REVISION_CONFLICT")?"REVISION_CONFLICT":text.startsWith("TURN_ACTIVE")?"TURN_ACTIVE":text.startsWith("CONVERSATION_NOT_FOUND")?"CONVERSATION_NOT_FOUND":text.startsWith("CONVERSATION_NOT_ACTIVE")?"CONVERSATION_NOT_ACTIVE":text.startsWith("PERSONA_NOT_FOUND")?"PERSONA_NOT_FOUND":text.startsWith("PERSONA_NOT_ACTIVE")?"PERSONA_NOT_ACTIVE":"PERSONA_BINDING_FAILED";
        return ResponseEntity.status(code.equals("REVISION_CONFLICT")||code.equals("TURN_ACTIVE")?409:code.equals("CONVERSATION_NOT_FOUND")?404:400).body(new PersonaBindingBody("rejected",null,null,null,code,text));
    }
    public record BindPersonaBody(String personaId,Long expectedRevision){}
    public record PersonaBindingBody(String result,String personaId,Long revision,String displayName,String reasonCode,String detail){}
}
