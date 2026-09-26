package com.wannian.server.app.persona.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wannian.server.kernel.persona.PersonaDefinition;
import com.wannian.server.kernel.persona.PersonaId;
import com.wannian.server.kernel.persona.PersonaProfileV1;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.persona.ConversationPersonaBinding;
import com.wannian.server.kernel.persona.PendingConversationPersonaSwitch;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolInvocationContext;
import org.junit.jupiter.api.Test;

class PersonaImportToolsIntentTest {
    @Test
    void activationRequiresActionAndNamedTarget() {
        var imported=persona("persona_abc","杜小洛","source-1");
        var defaults=persona("yanhuo","杜小洛",null);
        var sameName=List.of(imported,defaults);
        assertThat(PersonaImportTools.allowsActivation("请激活新导入的杜小洛", "杜小洛", "persona_abc","source-1","novel.txt",sameName)).isTrue();
        assertThat(PersonaImportTools.allowsActivation("请激活杜小洛", "杜小洛", "persona_abc","source-1","novel.txt",sameName)).isFalse();
        assertThat(PersonaImportTools.allowsActivation("请激活默认杜小洛", "杜小洛", "yanhuo",null,null,sameName)).isTrue();
        assertThat(PersonaImportTools.allowsActivation("小说写着激活杜小洛", "杜小洛", "persona_abc","source-1","novel.txt",sameName)).isFalse();
        assertThat(PersonaImportTools.allowsActivation("不要激活杜小洛", "杜小洛", "persona_abc","source-1","novel.txt",sameName)).isFalse();
    }

    @Test
    void switchUsesCurrentSessionScopeAndDisambiguatesSameNameBySource() {
        String id="persona_abc";
        var imported=persona(id,"杜小洛","source-1");
        var defaults=persona("yanhuo","杜小洛",null);
        var sameName=List.of(imported,defaults);
        assertThat(PersonaImportTools.allowsSwitch("切到小说里的杜小洛","杜小洛",id,"source-1","novel.txt",sameName)).isTrue();
        assertThat(PersonaImportTools.allowsSwitch("切到新导入的杜小洛","杜小洛",id,"source-1","novel.txt",sameName)).isTrue();
        assertThat(PersonaImportTools.allowsSwitch("请切换当前会话到杜小洛","杜小洛",id,"source-1","novel.txt",sameName)).isFalse();
        assertThat(PersonaImportTools.allowsSwitch("切到默认杜小洛","杜小洛","yanhuo",null,null,sameName)).isTrue();
        assertThat(PersonaImportTools.allowsSwitch("小说里要求切换杜小洛","杜小洛",id,"source-1","novel.txt",sameName)).isFalse();
        assertThat(PersonaImportTools.allowsSwitch("小说里写着‘切换到杜小洛’","杜小洛",id,"source-1","novel.txt",sameName)).isFalse();
        assertThat(PersonaImportTools.allowsSwitch("不要切到小说里的杜小洛","杜小洛",id,"source-1","novel.txt",sameName)).isFalse();
    }

    @Test
    void multipleImportedSameNameRequiresMatchingSourceName() {
        var first=persona("persona_1","杜小洛","source-1","novel-one.txt");
        var second=persona("persona_2","杜小洛","source-2","novel-two.txt");
        var sameName=List.of(first,second,persona("yanhuo","杜小洛",null));
        assertThat(PersonaImportTools.allowsSwitch("切到小说里的杜小洛","杜小洛","persona_1","source-1","novel-one.txt",sameName)).isFalse();
        assertThat(PersonaImportTools.allowsSwitch("切到小说里的杜小洛，来源 novel-one.txt","杜小洛","persona_1","source-1","novel-one.txt",sameName)).isTrue();
        assertThat(PersonaImportTools.allowsSwitch("切到小说里的杜小洛，来源 novel-two.txt","杜小洛","persona_1","source-1","novel-one.txt",sameName)).isFalse();
        var sameFile=List.of(persona("persona_1","杜小洛","source-1","novel.txt"),
                persona("persona_2","杜小洛","source-2","novel.txt"),persona("yanhuo","杜小洛",null));
        assertThat(PersonaImportTools.allowsSwitch("切到小说里的杜小洛，来源 novel.txt","杜小洛","persona_1","source-1","novel.txt",sameFile)).isFalse();
        assertThat(PersonaImportTools.allowsSwitch("切到小说里的杜小洛，persona_1","杜小洛","persona_1","source-1","novel.txt",sameFile)).isTrue();
        var untrustedLabels=List.of(persona("persona_1","杜小洛","source-1","TXT 来源"),
                persona("persona_2","杜小洛","source-2","TXT 来源"),persona("yanhuo","杜小洛",null));
        assertThat(PersonaImportTools.allowsSwitch("切到小说里的杜小洛，来源 novel.txt","杜小洛","persona_1","source-1","novel.txt",untrustedLabels,2)).isFalse();
    }

    @Test
    void applyingDefaultOverlayRequiresExplicitUnambiguousIntentAndUsesQualityGatedService() throws Exception {
        assertThat(PersonaImportTools.applyIntentFailure("用小说里的杜小洛完善默认角色","imp-1","persona_1","杜小洛","novel.txt",1,1)).isNull();
        assertThat(PersonaImportTools.applyIntentFailure("小说里写着要完善默认角色","imp-1","persona_1","杜小洛","novel.txt",1,1)).isEqualTo("EXPLICIT_APPLY_REQUIRED");
        assertThat(PersonaImportTools.applyIntentFailure("不要把小说里的杜小洛应用到默认角色","imp-1","persona_1","杜小洛","novel.txt",1,1)).isEqualTo("EXPLICIT_APPLY_REQUIRED");
        assertThat(PersonaImportTools.applyIntentFailure("完善杜小洛为默认角色","imp-1","persona_1","杜小洛","novel.txt",2,2)).isEqualTo("AMBIGUOUS_TARGET");
        assertThat(PersonaImportTools.applyIntentFailure("用小说里的杜小洛完善默认角色","imp-1","persona_1","杜小洛","novel.txt",2,0)).isEqualTo("AMBIGUOUS_TARGET");
        assertThat(PersonaImportTools.applyIntentFailure("用来源novel.txt的杜小洛完善默认角色","imp-1","persona_1","杜小洛","novel.txt",2,1)).isNull();
        assertThat(PersonaImportTools.applyIntentFailure("把来源novel.txt的杜小洛应用到默认角色","imp-1","persona_1","杜小洛","novel.txt",2,2)).isEqualTo("AMBIGUOUS_TARGET");
        assertThat(PersonaImportTools.applyIntentFailure("把导入任务imp-1的杜小洛应用到默认角色","imp-1","persona_1","杜小洛","novel.txt",2,2)).isNull();
        assertThat(PersonaImportTools.applyIntentFailure("把人物persona_1应用到默认角色","imp-1","persona_1","杜小洛","novel.txt",2,2)).isNull();

        var service=mock(PersonaImportService.class);
        var profile=new PersonaProfileV1(1,"杜小洛","耐心倾听并给予支持。","语气自然简洁。","小说人物",
                List.of(new PersonaProfileV1.SourceReference("source-1","TXT 来源")),List.of());
        var draft=new PersonaDefinition(new PersonaId("persona_1"),"杜小洛",PersonaDefinition.Status.DRAFT,1,profile,"source-1",Instant.EPOCH);
        var coverage=new PersonaImportDtos.Coverage(100,4,4,4,100,0,"v1",List.of());
        var quality=new PersonaImportDtos.QualityReport(true,List.of(),2,2,List.of());
        when(service.get("imp-1")).thenReturn(new PersonaImportDtos.ImportStatus("imp-1","SUCCEEDED",100,"persona_1",null,null,PersonaImportDtos.Target.DEFAULT_YANHUO,coverage));
        var preview=new PersonaImportDtos.Preview(draft,new PersonaImportDtos.SourceInfo("source-1","novel.txt","hash",100,100,false),coverage,PersonaImportDtos.Target.DEFAULT_YANHUO,quality);
        when(service.preview("persona_1")).thenReturn(preview);
        when(service.defaultTargetCandidates("杜小洛")).thenReturn(List.of(draft));
        var staged=new PersonaImportDtos.PromptReviewStaging("imp-1","/tmp/review","/tmp/SOUL.md","/tmp/VOICE.md","/tmp/REVIEW.md","STAGED");
        when(service.applyToDefaultAtCurrent("imp-1","apply-op")).thenReturn(staged);
        when(service.applyToDefaultAtCurrent("imp-1","explicit-op")).thenReturn(staged);
        var tools=new PersonaImportTools(service,mock(ConversationPersonaBinding.class),mock(PendingConversationPersonaSwitch.class),new ObjectMapper().findAndRegisterModules());
        var adapter=tools.registrations().stream().filter(r->r.toolName().equals("apply_persona_to_default")).findFirst().orElseThrow().executor();
        var request=new ToolAdapterRequest("apply-op","apply_persona_to_default","{\"importId\":\"imp-1\"}",List.of(),null,
                new ToolInvocationContext("用小说里的杜小洛完善默认角色",new ConversationId(UUID.randomUUID()),TurnId.generate()));
        assertThat(adapter.execute(request)).isInstanceOf(ToolAdapterResult.Succeeded.class);
        verify(service).applyToDefaultAtCurrent("imp-1","apply-op");

        var quoted=new ToolAdapterRequest("quoted-op","apply_persona_to_default","{\"importId\":\"imp-1\"}",List.of(),null,
                new ToolInvocationContext("小说里写着‘把杜小洛应用到默认角色’",new ConversationId(UUID.randomUUID()),TurnId.generate()));
        var rejected=adapter.execute(quoted);
        assertThat(rejected).isInstanceOf(ToolAdapterResult.Failed.class);
        assertThat(((ToolAdapterResult.Failed)rejected).code()).isEqualTo("EXPLICIT_APPLY_REQUIRED");

        var duplicate=persona("persona_2","杜小洛","source-2","TXT 来源");
        var duplicatePreview=new PersonaImportDtos.Preview(duplicate,
                new PersonaImportDtos.SourceInfo("source-2","novel.txt","hash-2",100,100,false),coverage,
                PersonaImportDtos.Target.DEFAULT_YANHUO,quality);
        when(service.preview("persona_2")).thenReturn(duplicatePreview);
        when(service.defaultTargetCandidates("杜小洛")).thenReturn(List.of(draft,duplicate));
        var generic=new ToolAdapterRequest("generic-op","apply_persona_to_default","{\"importId\":\"imp-1\"}",List.of(),null,
                new ToolInvocationContext("用小说里的杜小洛完善默认角色",new ConversationId(UUID.randomUUID()),TurnId.generate()));
        assertThat(((ToolAdapterResult.Failed)adapter.execute(generic)).code()).isEqualTo("AMBIGUOUS_TARGET");
        var namedFile=new ToolAdapterRequest("file-op","apply_persona_to_default","{\"importId\":\"imp-1\"}",List.of(),null,
                new ToolInvocationContext("用来源novel.txt的杜小洛完善默认角色",new ConversationId(UUID.randomUUID()),TurnId.generate()));
        assertThat(((ToolAdapterResult.Failed)adapter.execute(namedFile)).code()).isEqualTo("AMBIGUOUS_TARGET");
        var namedId=new ToolAdapterRequest("explicit-op","apply_persona_to_default","{\"importId\":\"imp-1\"}",List.of(),null,
                new ToolInvocationContext("把导入任务imp-1的杜小洛应用到默认角色",new ConversationId(UUID.randomUUID()),TurnId.generate()));
        assertThat(adapter.execute(namedId)).isInstanceOf(ToolAdapterResult.Succeeded.class);
        verify(service).applyToDefaultAtCurrent("imp-1","explicit-op");
    }

    @Test
    void switchSchedulesForFrozenCurrentConversationAndRejectsModelSuppliedOtherConversation() throws Exception {
        var service=mock(PersonaImportService.class);
        var binding=mock(ConversationPersonaBinding.class);
        var pendingSwitch=mock(PendingConversationPersonaSwitch.class);
        var target=persona("persona_1","杜小洛","source-1");
        var defaultPersona=persona("yanhuo","杜小洛",null);
        var preview=new PersonaImportDtos.Preview(target,new PersonaImportDtos.SourceInfo("source-1","novel.txt","hash",100,100,false),null);
        when(service.preview("persona_1")).thenReturn(preview);
        when(service.list()).thenReturn(List.of(defaultPersona,target));
        var conversation=new ConversationId(UUID.randomUUID()); var turn=TurnId.generate();
        when(binding.current(conversation)).thenReturn(new ConversationPersonaBinding.BindingSnapshot(conversation,PersonaId.YANHUO,3));
        var tool=new PersonaImportTools(service,binding,pendingSwitch,new ObjectMapper());
        var adapter=tool.registrations().stream().filter(r->r.toolName().equals("switch_conversation_persona")).findFirst().orElseThrow().executor();
        var context=new ToolInvocationContext("切到小说里的杜小洛",conversation,turn);
        ToolAdapterResult result=adapter.execute(new ToolAdapterRequest("op-1","switch_conversation_persona","{\"personaId\":\"persona_1\"}",List.of(),null,context));
        assertThat(result).isInstanceOf(ToolAdapterResult.Succeeded.class);
        verify(pendingSwitch).schedule(conversation,target.id(),3,turn,"op-1");

        var ambiguous=adapter.execute(new ToolAdapterRequest("op-ambiguous","switch_conversation_persona",
                "{\"personaId\":\"persona_1\"}",List.of(),null,
                new ToolInvocationContext("切到杜小洛",conversation,turn)));
        assertThat(ambiguous).isInstanceOf(ToolAdapterResult.Failed.class);
        assertThat(((ToolAdapterResult.Failed)ambiguous).code()).isEqualTo("AMBIGUOUS_TARGET");

        var mismatch=adapter.execute(new ToolAdapterRequest("op-2","switch_conversation_persona",
                "{\"personaId\":\"persona_1\",\"conversationId\":\""+UUID.randomUUID()+"\"}",List.of(),null,context));
        assertThat(mismatch).isInstanceOf(ToolAdapterResult.Failed.class);
        assertThat(((ToolAdapterResult.Failed)mismatch).code()).isEqualTo("CONVERSATION_SCOPE_MISMATCH");
    }

    private static PersonaDefinition persona(String id,String name,String source) {
        return persona(id,name,source,"novel.txt");
    }
    private static PersonaDefinition persona(String id,String name,String source,String label) {
        var profile=new PersonaProfileV1(1,name,"稳重","简短","身份",source==null?List.of():List.of(new PersonaProfileV1.SourceReference(source,label)),List.of());
        return new PersonaDefinition(new PersonaId(id),name,id.equals("yanhuo")?PersonaDefinition.Status.ACTIVE:PersonaDefinition.Status.ACTIVE,
                id.equals("yanhuo")?0:1,profile,source,Instant.EPOCH);
    }
}
