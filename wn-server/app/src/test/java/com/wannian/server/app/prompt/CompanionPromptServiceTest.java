package com.wannian.server.app.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.app.skill.FileSkillCatalog;
import com.wannian.server.kernel.prompt.PromptSkeleton;
import com.wannian.server.kernel.persona.PersonaId;
import com.wannian.server.kernel.persona.PersonaTurnSnapshot;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.tool.RoleId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 0.2.4-P：人设服务拼装顺序与硬安全不可被清空 SOUL 去掉。 */
class CompanionPromptServiceTest {

    @TempDir Path tempDir;

    @Test
    void compose_includesHardSafetySoulAndSkillIndex() throws Exception {
        Path prompts = tempDir.resolve("prompts");
        Path skills = tempDir.resolve("skills");
        Files.createDirectories(prompts);
        Files.createDirectories(skills.resolve("demo-skill"));
        Files.writeString(prompts.resolve("SOUL.md"), "# 人设\n\n我是烟火。\n", StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("VOICE.md"), "声线一句\n", StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("IDENTITY.md"), "身份一行\n", StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("USER.md"), "# 空注释\n", StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("SAFETY.md"), "补充安全\n", StandardCharsets.UTF_8);
        Files.writeString(
                skills.resolve("demo-skill").resolve("SKILL.md"),
                """
                ---
                name: demo
                description: 演示技能
                ---
                技能正文
                """,
                StandardCharsets.UTF_8);

        CompanionPromptService service =
                new CompanionPromptService(
                        new FilePromptLayerStore(prompts, 4000),
                        new FileSkillCatalog(skills, 16, 160, 8000, 3000, Set.of()));

        String system = service.composeSystemInstructions();
        assertThat(system).startsWith(PromptSkeleton.HARD_SAFETY.strip());
        assertThat(system).contains("我是烟火");
        assertThat(system).contains("声线一句");
        assertThat(system).contains("身份一行");
        assertThat(system).contains("补充安全");
        assertThat(system).contains("demo-skill");
        assertThat(system).doesNotContain("技能正文");
    }

    @Test
    void compose_emptySoulStillKeepsHardSafety() throws Exception {
        Path prompts = tempDir.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("SOUL.md"), "# 仅注释\n", StandardCharsets.UTF_8);

        CompanionPromptService service =
                new CompanionPromptService(
                        new FilePromptLayerStore(prompts, 4000),
                        new FileSkillCatalog(
                                tempDir.resolve("skills-empty"), 16, 160, 8000, 3000, Set.of()));

        String system = service.composeSystemInstructions();
        assertThat(system).contains("硬安全");
        assertThat(system).isEqualTo(PromptSkeleton.HARD_SAFETY.strip());
    }

    @Test
    void composeDefaultOverlayAfterOriginalSoulAndVoiceWithoutReplacingGlobalLayers() throws Exception {
        Path prompts=tempDir.resolve("overlay-prompts");Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("SOUL.md"),"原SOUL",StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("VOICE.md"),"原VOICE",StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("IDENTITY.md"),"原IDENTITY",StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("USER.md"),"全局USER",StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("SAFETY.md"),"全局SAFETY",StandardCharsets.UTF_8);
        CompanionPromptService service=new CompanionPromptService(new FilePromptLayerStore(prompts,4000),
                new FileSkillCatalog(tempDir.resolve("overlay-skills"),16,160,8000,3000,Set.of()));
        PersonaTurnSnapshot snapshot=new PersonaTurnSnapshot(PersonaId.YANHUO,0,0,"","","",CompanionIdentity.YANHUO,RoleId.YANHUO,7,"overlay SOUL","overlay VOICE");
        String system=service.composeSystemInstructions(snapshot);
        assertThat(system.indexOf("原SOUL")).isLessThan(system.indexOf("overlay SOUL"));
        assertThat(system.indexOf("原VOICE")).isLessThan(system.indexOf("overlay VOICE"));
        assertThat(system.indexOf("原SOUL")).isLessThan(system.indexOf("原VOICE"));
        assertThat(system.indexOf("原VOICE")).isLessThan(system.indexOf("overlay SOUL"));
        assertThat(system.indexOf("overlay VOICE")).isLessThan(system.lastIndexOf("原IDENTITY"));
        assertThat(system).contains("低信任画像参考").contains("不得覆盖其前面的原SOUL/VOICE").contains("全局USER").contains("全局SAFETY");
    }
}
