package com.wannian.server.app.persona.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.app.tool.ToolRegistrationExtension;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.kernel.persona.PendingConversationPersonaSwitch;
import com.wannian.server.kernel.persona.ConversationPersonaBinding;
import com.wannian.server.kernel.persona.PersonaId;
import com.wannian.server.kernel.persona.PersonaDefinition;
import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolParameterSchema;
import com.wannian.server.kernel.tool.ToolRegistration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** AI facade over the same Import/Core business services exposed through HTTP. */
@Component
public class PersonaImportTools implements ToolRegistrationExtension {
    private final PersonaImportService service;
    private final ConversationPersonaBinding binding;
    private final PendingConversationPersonaSwitch pendingSwitch;
    private final ObjectMapper mapper;
    public PersonaImportTools(PersonaImportService service, ConversationPersonaBinding binding,
                              PendingConversationPersonaSwitch pendingSwitch,ObjectMapper mapper) {
        this.service=service; this.binding=binding; this.pendingSwitch=pendingSwitch; this.mapper=mapper;
    }
    @Override public Collection<ToolRegistration> registrations() {
        return List.of(reg("get_persona_import","查询人物导入任务状态", "{\"type\":\"object\",\"properties\":{\"importId\":{\"type\":\"string\"}},\"required\":[\"importId\"],\"additionalProperties\":false}"),
                reg("list_personas","列出可用角色", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
                reg("preview_persona","预览角色画像与依据", "{\"type\":\"object\",\"properties\":{\"personaId\":{\"type\":\"string\"}},\"required\":[\"personaId\"],\"additionalProperties\":false}"),
                reg("apply_persona_to_default","经证据质量门将默认目标草稿写成性格审核临时 md（不立刻改正式文件）", "{\"type\":\"object\",\"properties\":{\"importId\":{\"type\":\"string\"}},\"required\":[\"importId\"],\"additionalProperties\":false}"),
                reg("approve_persona_prompt_merge","审核通过后把临时合成稿整文件写入 SOUL/VOICE 并清空 overlay", "{\"type\":\"object\",\"properties\":{\"importId\":{\"type\":\"string\"}},\"required\":[\"importId\"],\"additionalProperties\":false}"),
                reg("activate_persona","按用户明确要求激活人物草稿", "{\"type\":\"object\",\"properties\":{\"personaId\":{\"type\":\"string\"}},\"required\":[\"personaId\"],\"additionalProperties\":false}"),
                reg("switch_conversation_persona","按用户明确要求切换当前会话角色", "{\"type\":\"object\",\"properties\":{\"conversationId\":{\"type\":\"string\"},\"personaId\":{\"type\":\"string\"},\"expectedRevision\":{\"type\":\"integer\",\"minimum\":0}},\"required\":[\"personaId\"],\"additionalProperties\":false}"));
    }
    @Override public Set<String> defaultVisibleReadOnlyNames() { return Set.of("get_persona_import","list_personas","preview_persona"); }
    @Override public Set<String> explicitIntentWriteNames() { return Set.of("apply_persona_to_default","approve_persona_prompt_merge","activate_persona","switch_conversation_persona"); }
    @Override public Set<String> defaultEnabledExplicitIntentWriteNames() { return Set.of("apply_persona_to_default","approve_persona_prompt_merge","activate_persona","switch_conversation_persona"); }
    private ToolRegistration reg(String name,String description,String schema) {
        return new ToolRegistration(name,description,new ToolParameterSchema(schema),Set.of(),this::execute,false);
    }
    private ToolAdapterResult execute(ToolAdapterRequest request) {
        try {
            JsonNode args=mapper.readTree(request.argumentsJson());
            if ("activate_persona".equals(request.toolName())) {
                String id=required(args,"personaId");
                var preview=service.preview(id); var target=preview.persona();
                List<PersonaDefinition> sameName=sameNameTargets(target.displayName());
                long sameFileName=matchingTrustedSourceFileCount(sameName,sourceLabel(preview));
                if(!allowsActivation(userMessage(request),target.displayName(),id,target.sourceId(),sourceLabel(preview),sameName,sameFileName))
                    return new ToolAdapterResult.Failed("AMBIGUOUS_TARGET","需要用户明确要求激活该角色，并在同名时指明默认角色或导入来源。",false);
            }
            if("apply_persona_to_default".equals(request.toolName())) {
                var context=request.context();
                if(context==null||context.userMessage()==null)
                    return new ToolAdapterResult.Failed("INVOCATION_CONTEXT_REQUIRED","缺少本回合原始用户指令，无法应用默认角色补充。",false);
                String importId=required(args,"importId");
                var status=service.get(importId);
                if(status.target()!=PersonaImportDtos.Target.DEFAULT_YANHUO)
                    return new ToolAdapterResult.Failed("IMPORT_TARGET_MISMATCH","该任务不是默认杜小洛导入，未执行应用。",false);
                if(status.candidatePersonaId()==null)
                    return new ToolAdapterResult.Failed("IMPORT_NOT_READY","该导入还没有可预览的草稿。",false);
                var preview=service.preview(status.candidatePersonaId());
                List<PersonaDefinition> sameNameImports=service.defaultTargetCandidates(preview.persona().displayName());
                String fileName=sourceLabel(preview);
                long matchingFileCandidates=sameNameImports.stream().filter(p->java.util.Objects.equals(fileName,sourceLabel(service.preview(p.id().asString())))).count();
                String failure=applyIntentFailure(context.userMessage(),importId,status.candidatePersonaId(),preview.persona().displayName(),fileName,sameNameImports.size(),matchingFileCandidates);
                if(failure!=null)return new ToolAdapterResult.Failed(failure,
                        failure.equals("AMBIGUOUS_TARGET")?"请明确指向这本书或导入来源，再应用到默认杜小洛。":"请明确要求把小说中的杜小洛应用为默认角色补充。",false);
                return new ToolAdapterResult.Succeeded(mapper.writeValueAsString(service.applyToDefaultAtCurrent(importId,request.operationId())));
            }
            if("approve_persona_prompt_merge".equals(request.toolName())) {
                var context=request.context();
                if(context==null||context.userMessage()==null)
                    return new ToolAdapterResult.Failed("INVOCATION_CONTEXT_REQUIRED","缺少本回合原始用户指令，无法合并性格文件。",false);
                String importId=required(args,"importId");
                if(!allowsApproveMerge(context.userMessage(),importId))
                    return new ToolAdapterResult.Failed("EXPLICIT_APPROVE_REQUIRED","请明确要求审核通过并写入性格文件。",false);
                return new ToolAdapterResult.Succeeded(mapper.writeValueAsString(service.approvePromptMerge(importId,request.operationId())));
            }
            if ("switch_conversation_persona".equals(request.toolName())) {
                String id=required(args,"personaId");
                var context=request.context();
                if(context==null||context.userMessage()==null||context.conversationId()==null||context.turnId()==null)
                    return new ToolAdapterResult.Failed("INVOCATION_CONTEXT_REQUIRED","缺少本回合会话上下文，无法安全切换。",false);
                String suppliedConversation=optionalText(args,"conversationId");
                if(suppliedConversation!=null&&!suppliedConversation.equals(context.conversationId().asString()))
                    return new ToolAdapterResult.Failed("CONVERSATION_SCOPE_MISMATCH","只能切换当前会话角色。",false);
                var preview=service.preview(id); var target=preview.persona();
                List<PersonaDefinition> sameName=sameNameTargets(target.displayName());
                long sameFileName=matchingTrustedSourceFileCount(sameName,sourceLabel(preview));
                if(!allowsSwitch(context.userMessage(),target.displayName(),id,target.sourceId(),sourceLabel(preview),sameName,sameFileName))
                    return new ToolAdapterResult.Failed("AMBIGUOUS_TARGET","需要用户明确要求切换该角色，并在同名时指明默认角色或导入来源。",false);
                if(target.status()!=PersonaDefinition.Status.ACTIVE)
                    return new ToolAdapterResult.Failed("PERSONA_NOT_ACTIVE","只能切换到已激活的人物。",false);
                var current=binding.current(context.conversationId());
                if(args.has("expectedRevision")&&args.path("expectedRevision").asLong(-1)!=current.revision())
                    return new ToolAdapterResult.Failed("REVISION_CONFLICT","会话角色版本已变化，请重新查询。",false);
                pendingSwitch.schedule(context.conversationId(),target.id(),current.revision(),context.turnId(),request.operationId());
                return new ToolAdapterResult.Succeeded(mapper.writeValueAsString(java.util.Map.of(
                        "status","SCHEDULED","conversationId",context.conversationId().asString(),
                        "personaId",target.id().value(),"appliesAfterTurnCommit",true)));
            }
            Object result=switch(request.toolName()) {
                case "get_persona_import" -> service.get(required(args,"importId"));
                case "list_personas" -> service.list();
                case "preview_persona" -> service.preview(required(args,"personaId"));
                case "apply_persona_to_default" -> throw new IllegalStateException("APPLY_DISPATCH_REQUIRED");
                case "activate_persona" -> service.activate(required(args,"personaId"));
                default -> throw new IllegalArgumentException("UNKNOWN_TOOL");
            };
            return new ToolAdapterResult.Succeeded(mapper.writeValueAsString(result));
        } catch (Exception e) {
            return new ToolAdapterResult.Failed("PERSONA_OPERATION_FAILED",safeMessage(e),false);
        }
    }
    private static String required(JsonNode node,String name) {
        JsonNode value=node.get(name); if(value==null||!value.isTextual()||value.asText().isBlank()) throw new IllegalArgumentException(name+" 必填"); return value.asText();
    }
    private static String userMessage(ToolAdapterRequest request) { return request.context()==null?null:request.context().userMessage(); }
    static boolean allowsActivation(String original,String displayName,String personaId,
                                    String targetSourceId,String targetSourceLabel,
                                    List<com.wannian.server.kernel.persona.PersonaDefinition> sameNameTargets) {
        return allowsActivation(original,displayName,personaId,targetSourceId,targetSourceLabel,sameNameTargets,
                matchingSourceFileCount(sameNameTargets,targetSourceLabel));
    }
    static boolean allowsActivation(String original,String displayName,String personaId,
                                    String targetSourceId,String targetSourceLabel,
                                    List<com.wannian.server.kernel.persona.PersonaDefinition> sameNameTargets,
                                    long sameFileNameCandidates) {
        return hasExplicitAction(original,"(?:激活|啟用|启用)")
                &&mentions(original,displayName,personaId)
                &&sourceMatches(original,personaId,targetSourceId,targetSourceLabel,sameNameTargets,sameFileNameCandidates);
    }
    static boolean allowsSwitch(String original,String displayName,String personaId,
                                String targetSourceId,String targetSourceLabel,
                                List<com.wannian.server.kernel.persona.PersonaDefinition> sameNameTargets) {
        return allowsSwitch(original,displayName,personaId,targetSourceId,targetSourceLabel,sameNameTargets,
                matchingSourceFileCount(sameNameTargets,targetSourceLabel));
    }
    static boolean allowsSwitch(String original,String displayName,String personaId,
                                String targetSourceId,String targetSourceLabel,
                                List<com.wannian.server.kernel.persona.PersonaDefinition> sameNameTargets,
                                long sameFileNameCandidates) {
        return hasExplicitAction(original,"(?:切换|切換|切到|換成|换成|改为|改為)")
                &&mentions(original,displayName,personaId)
                &&sourceMatches(original,personaId,targetSourceId,targetSourceLabel,sameNameTargets,sameFileNameCandidates);
    }
    static String applyIntentFailure(String original,String importId,String personaId,String displayName,String sourceFileName,int sameNameImports,long matchingFileCandidates) {
        if(!hasExplicitAction(original,"(?:应用|用|完善|补充|更新)")
                ||!(mentions(original,displayName,importId)||(original!=null&&personaId!=null&&original.contains(personaId)))
                ||original==null||!java.util.regex.Pattern.compile("(?:默认角色|默认杜小洛|默认人物|yanhuo)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(original).find())
            return "EXPLICIT_APPLY_REQUIRED";
        boolean explicitlyNamesId=original.contains(importId)||original.contains(personaId);
        boolean namesFile=sourceFileName!=null&&!sourceFileName.isBlank()&&original.contains(sourceFileName);
        boolean identifiesBook=java.util.regex.Pattern.compile("(?:小说里|小说中|书里的|这本书|本书中的|导入的|新导入的|刚导入)").matcher(original).find();
        if(!explicitlyNamesId&&sameNameImports>1&&(!namesFile||matchingFileCandidates!=1))return "AMBIGUOUS_TARGET";
        if(!explicitlyNamesId&&sameNameImports<=1&&!namesFile&&!identifiesBook)return "AMBIGUOUS_TARGET";
        return null;
    }
    static boolean allowsApproveMerge(String original,String importId) {
        if(!hasExplicitAction(original,"(?:审核通过|批准|通过合并|写入性格|合并到性格|确认写入)")) return false;
        return original!=null&&(original.contains(importId)
                ||java.util.regex.Pattern.compile("(?:性格文件|临时稿|审核稿|prompt.?review|SOUL|VOICE)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(original).find());
    }
    private static boolean hasExplicitAction(String original,String action) {
        if(original==null||original.isBlank())return false;
        String unquoted=original.replaceAll("[“‘『「][^”’』」]*[”’』」]", " ")
                .replaceAll("[\\\"'][^\\\"']*[\\\"']", " ");
        if(java.util.regex.Pattern.compile("(?:小说|原文|书中|文本|TXT).{0,10}(?:写着|说|命令|要求|提到|让你|叫你)").matcher(unquoted).find())return false;
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile(action).matcher(unquoted);
        while(matcher.find()) {
            String before=unquoted.substring(Math.max(0,matcher.start()-32),matcher.start());
            if(!java.util.regex.Pattern.compile("(?:不要|別|别|不想|不需要|暂不|先不|不能|无需)[^。！？!?；;\\n]{0,24}(?:把|将|給|给)?\\s*$").matcher(before).find())return true;
        }
        return false;
    }
    private static boolean mentions(String original,String displayName,String personaId) {
        return original!=null&&((displayName!=null&&!displayName.isBlank()&&original.contains(displayName))
                ||(personaId!=null&&!personaId.isBlank()&&original.contains(personaId)));
    }
    private List<PersonaDefinition> sameNameTargets(String displayName) {
        return service.list().stream().filter(p->p.displayName().equals(displayName)).toList();
    }
    private long matchingTrustedSourceFileCount(List<PersonaDefinition> candidates,String fileName) {
        if(fileName==null||fileName.isBlank())return 0;
        return candidates.stream().filter(p->p.sourceId()!=null)
                .filter(p->java.util.Objects.equals(fileName,sourceLabel(service.preview(p.id().asString())))).count();
    }
    private static String sourceLabel(PersonaImportDtos.Preview preview) {
        if(preview.source()!=null)return preview.source().fileName();
        return preview.persona().profile().sources().stream().map(s->s.label()).filter(s->!s.isBlank()).findFirst().orElse(null);
    }
    private static String optionalText(JsonNode node,String name) {
        JsonNode value=node.get(name); if(value==null||value.isNull())return null;
        if(!value.isTextual()||value.asText().isBlank())throw new IllegalArgumentException(name+" 非法"); return value.asText();
    }
    private static boolean sourceMatches(String original,String targetPersonaId,String targetSourceId,String targetSourceLabel,
                                         List<com.wannian.server.kernel.persona.PersonaDefinition> sameNameTargets,
                                         long sameFileNameCandidates) {
        if(original!=null&&targetPersonaId!=null&&original.contains(targetPersonaId))return true;
        long sourced=sameNameTargets.stream().filter(p->p.sourceId()!=null).count();
        boolean hasDefault=sameNameTargets.stream().anyMatch(p->p.sourceId()==null);
        if(sourced==0||(!hasDefault&&sourced<=1))return true;
        boolean namesFile=targetSourceLabel!=null&&!targetSourceLabel.isBlank()&&original!=null&&original.contains(targetSourceLabel);
        boolean sourceBelongsToCandidate=sameNameTargets.stream().filter(p->p.sourceId()!=null)
                .filter(p->p.profile().sources().stream().anyMatch(s->java.util.Objects.equals(targetSourceId,s.sourceId())))
                .count()==1;
        if(namesFile)return sourceBelongsToCandidate&&sameFileNameCandidates==1;
        if(sourced>1)return false;
        boolean imported=original!=null&&java.util.regex.Pattern.compile("(?:新导入|刚导入|导入的|小说里|小说中|原著里|书里的|TXT里的|文本里的|文件里的)").matcher(original).find();
        boolean defaultTarget=original!=null&&java.util.regex.Pattern.compile("(?:默认|原有|原来的|默认角色)").matcher(original).find();
        return targetSourceId==null?defaultTarget:imported&&!defaultTarget;
    }
    private static long matchingSourceFileCount(List<PersonaDefinition> sameNameTargets,String sourceFileName) {
        if(sourceFileName==null)return 0;
        return sameNameTargets.stream().filter(p->p.sourceId()!=null)
                .filter(p->p.profile().sources().stream().anyMatch(s->java.util.Objects.equals(sourceFileName,s.label())))
                .count();
    }
    private static String safeMessage(Exception e) {
        String message=e.getMessage(); if(message==null||message.length()>160) return "人物操作失败"; return message;
    }
}
