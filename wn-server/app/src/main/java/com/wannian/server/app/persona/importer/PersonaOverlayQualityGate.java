package com.wannian.server.app.persona.importer;

import com.wannian.server.app.persona.core.DefaultPersonaOverlayRenderer;
import com.wannian.server.kernel.persona.PersonaProfileV1;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits;

/** Import 质量门：枚举作稳定倾向；自由文本经清洗后由 prompt-review 写入正式 SOUL/VOICE.md。 */
final class PersonaOverlayQualityGate {

    private PersonaOverlayQualityGate() {}

    static PersonaImportDtos.QualityReport evaluate(PersonaProfileV1 profile,String expectedSourceId,
                                                     PersonaImportDtos.Coverage coverage) {
        return evaluate(profile, expectedSourceId, coverage, com.wannian.server.app.persona.PersonaOverlayLimits.DEFAULT);
    }

    static PersonaImportDtos.QualityReport evaluate(PersonaProfileV1 profile,String expectedSourceId,
                                                     PersonaImportDtos.Coverage coverage,
                                                     com.wannian.server.app.persona.PersonaOverlayLimits limits) {
        int maxLayer = limits.layerCharBudget();
        int maxTotal = limits.totalCharBudget();
        List<String> reasons=new ArrayList<>(), uncertainties=new ArrayList<>();
        Set<Integer> soulChapters=new HashSet<>(),voiceChapters=new HashSet<>();
        Map<String,Set<Integer>> traitChapters=new LinkedHashMap<>();
        if(!"杜小洛".equals(profile.displayName())) reasons.add("默认杜小洛目标人物名称不匹配");
        // 枚举 → 稳定倾向；自由文本经清洗后写入正式 SOUL/VOICE.md（approve-prompt-merge）。
        // DB overlay  alone 对对话影响弱，不得宣称「仅枚举 / 仅 overlay = 性格已生效」。
        if(unsafeOverlayText(profile.soul())||unsafeOverlayText(profile.voice()))
            uncertainties.add("自由文本含需清洗的不安全表述；审核合并前不得原样写入正式性格文件");
        if(profile.soul().length()>maxLayer||profile.voice().length()>maxLayer
                ||profile.soul().length()+profile.voice().length()>maxTotal)
            uncertainties.add("草稿自由文本较长，审核合并时会截断清洗；枚举仅作稳定倾向，不能单独冒充人物性格");
        DefaultPersonaOverlayTraits traits=profile.defaultOverlayTraits();
        if(traits==null) reasons.add("缺少可安全渲染的默认角色枚举画像");
        else {
            DefaultPersonaOverlayRenderer.Rendered rendered=DefaultPersonaOverlayRenderer.render(traits);
            if(rendered.soul().length()>maxLayer||rendered.voice().length()>maxLayer
                    ||rendered.soul().length()+rendered.voice().length()>maxTotal)
                reasons.add("画像补充超过默认角色长度限制");
            traitChapters.put("STYLE",new HashSet<>());traitChapters.put("PACE",new HashSet<>());
            traitChapters.put("INITIATIVE",new HashSet<>());traitChapters.put("HUMOR",new HashSet<>());
        }
        if(coverage==null||coverage.windows()==null||coverage.windows().isEmpty()) {
            reasons.add("缺少模型取样覆盖记录");
        } else {
            for(PersonaProfileV1.Evidence evidence:profile.evidence()) {
                if(!expectedSourceId.equals(evidence.sourceId())) continue;
                Integer chapter=coverage.windows().stream()
                        .filter(w->w.startOffset()<=evidence.startOffset()&&w.endOffset()>=evidence.endOffset())
                        .map(PersonaImportDtos.WindowOffset::chapter).findFirst().orElse(null);
                if(chapter==null)continue;
                if(evidence.uncertain()) { uncertainties.add("存在需谨慎使用的证据"); continue; }
                String inference=evidence.inference().stripLeading().toUpperCase(java.util.Locale.ROOT);
                if(inference.startsWith("[SOUL]")||inference.startsWith("SOUL:"))soulChapters.add(chapter);
                if(inference.startsWith("[VOICE]")||inference.startsWith("VOICE:"))voiceChapters.add(chapter);
                if(traits!=null) {
                    countMarker(traitChapters,"STYLE",traits.interactionStyle().name(),inference,chapter);
                    countMarker(traitChapters,"PACE",traits.responsePace().name(),inference,chapter);
                    countMarker(traitChapters,"INITIATIVE",traits.initiative().name(),inference,chapter);
                    countMarker(traitChapters,"HUMOR",traits.humor().name(),inference,chapter);
                }
                if(inference.contains("矛盾")||inference.contains("不同情境")||inference.contains("不确定"))uncertainties.add("证据存在情境差异或矛盾，需保留克制表述");
            }
            if(coverage.unmodeledChapters()>0) uncertainties.add("还有"+coverage.unmodeledChapters()+"个命中章节未进入模型取样");
            if(coverage.modelChapters()<coverage.matchedChapters()) uncertainties.add("模型取样未覆盖全部本地命中章节");
        }
        if(soulChapters.size()<2) reasons.add("性格倾向缺少至少两个章节的可回溯证据");
        if(voiceChapters.size()<2) reasons.add("说话方式缺少至少两个章节的可回溯证据");
        if(traits!=null) {
            int selected=0;
            selected+=checkTrait(reasons,"STYLE",traits.interactionStyle().name(),traitChapters);
            selected+=checkTrait(reasons,"PACE",traits.responsePace().name(),traitChapters);
            selected+=checkTrait(reasons,"INITIATIVE",traits.initiative().name(),traitChapters);
            selected+=checkTrait(reasons,"HUMOR",traits.humor().name(),traitChapters);
            if(selected<2)reasons.add("至少两个默认角色枚举维度需要有跨章节证据");
        }
        return new PersonaImportDtos.QualityReport(reasons.isEmpty(),List.copyOf(reasons),soulChapters.size(),voiceChapters.size(),
                traitChapters.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,e->e.getValue().size())),
                uncertainties.stream().distinct().toList());
    }
    private static void countMarker(Map<String,Set<Integer>> chapters,String dimension,String value,String inference,int chapter) {
        if(!"UNKNOWN".equals(value)&&inference.contains("["+dimension+":"+value+"]"))chapters.get(dimension).add(chapter);
    }
    private static int checkTrait(List<String> reasons,String dimension,String value,Map<String,Set<Integer>> chapters) {
        if("UNKNOWN".equals(value))return 0;
        if(chapters.get(dimension).size()<2)reasons.add("默认角色"+dimension+"枚举缺少至少两个章节的可回溯证据");
        return 1;
    }

    private static boolean unsafeOverlayText(String value) {
        if(value==null||value.isBlank())return false;
        String text=value.toLowerCase(java.util.Locale.ROOT);
        return java.util.regex.Pattern.compile(
                "(?:忽略|无视|覆盖|绕过).{0,12}(?:系统|安全|此前|之前|规则|指令|提示词|prompt)"
                +"|(?:system\\s*prompt|developer\\s*instruction|ignore\\s+(?:all\\s+)?(?:previous|prior|safety))"
                +"|(?:调用|执行|使用|启用).{0,8}(?:工具|tool|命令|脚本)"
                +"|(?:拥有|获取|授予|提升|绕过|解除).{0,8}(?:权限|授权|访问权|管理员|admin)"
                +"|(?:自杀|自残|伤害自己|伤害他人|结束生命|危机指令)"
                +"|(?:我们(?:曾经|以前|已经|一起)|你还记得我们|共同经历|共同回忆|我们的回忆).{0,16}(?:见过|约定|经历|记得|发生|回忆)?"
                +"|(?:记得|回忆起|记住).{0,12}(?:我们一起|你和我|我们的)"
        ).matcher(text).find();
    }
}
