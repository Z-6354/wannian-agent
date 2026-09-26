package com.wannian.server.app.persona.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wannian.server.kernel.persona.DefaultPersonaOverlayPort;
import com.wannian.server.kernel.persona.DefaultPersonaOverlayRevision;
import com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits;
import com.wannian.server.kernel.persona.PersonaDefinition;
import com.wannian.server.kernel.persona.PersonaId;
import com.wannian.server.kernel.persona.PersonaProfileV1;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonaPromptReviewServiceTest {
    @TempDir Path data;

    @Test
    void cleanExtractedLayerKeepsShortValidAndDropsInsufficient() {
        assertThat(PersonaPromptReviewService.cleanExtractedLayer("说话俏皮"))
                .contains("说话俏皮")
                .startsWith("- ");
        String raw = "样本1：活泼亲近，愿意玩笑。；样本2：当前片段没有足够内容判断其性格。；样本3：说话俏皮。；样本4：证据不足。";
        String cleaned = PersonaPromptReviewService.cleanExtractedLayer(raw);
        assertThat(cleaned)
                .contains("活泼亲近")
                .contains("说话俏皮")
                .doesNotContain("没有足够")
                .doesNotContain("证据不足");
        assertThat(PersonaPromptReviewService.countObservationBullets(cleaned)).isEqualTo(2);
    }

    @Test
    void rewriterIntegratesIntoBodyWithoutObservationAppendix() {
        String soulBase = "# 杜小洛的性格\n\n你是杜小洛，万年里的 AI 助手。偶尔有一点平静的幽默。不要向用户背诵这些性格词。\n\n## 稳定的底色\n\n- 先看用户现在需要什么。\n";
        String voiceBase = "# 杜小洛的声音\n\n自然简体中文。\n\n语气词、幽默与感叹号顺着情境偶尔出现，没有固定口癖。严肃时收住。\n\n## 多轮校准\n\n下列是行为示例。\n";
        String soulFree = "样本1：活泼亲近，愿意玩笑撒娇。；样本2：证据不足。；样本3：叙述者推测其内心。";
        String voiceFree = "样本1：说话俏皮直率。；样本2：称呼对方为「流氓大叔」。；样本3：无法判断。";
        var result = PersonaPromptRewriter.rewrite(soulBase, voiceBase, soulFree, voiceFree, "imp-1", "persona_1");
        assertThat(result.soul())
                .contains("活泼")
                .contains("人物气质")
                .contains("不要向用户背诵这些性格词。")
                .doesNotContain("## 小说人物补充")
                .doesNotContain("小说观察")
                .doesNotContain("叙述者")
                .contains("persona-import integrated");
        assertThat(result.voice())
                .contains("俏皮")
                .contains("说话气质")
                .doesNotContain("流氓大叔")
                .doesNotContain("## 小说人物补充")
                .doesNotContain("小说观察");
        assertThat(result.soulTraitCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.voiceTraitCount()).isGreaterThanOrEqualTo(1);
        // 再 stage 一次不叠加重复气质条 / 开篇织入
        var again = PersonaPromptRewriter.rewrite(result.soul(), result.voice(), soulFree, voiceFree, "imp-1", "persona_1");
        assertThat(again.soul().split("人物气质", -1).length - 1).isEqualTo(1);
        assertThat(again.soul().split("亲近时可更活泼", -1).length - 1).isEqualTo(2); // 开篇一次 + 底色条一次
        assertThat(again.voice().split("语气可俏皮", -1).length - 1).isEqualTo(2); // 语气段一次 + 说话气质条一次
    }

    @Test
    void stripNovelSectionRemovesLegacyAppendix() {
        String withAppendix = "# 原性格\n\n正文。\n\n## 小说人物补充\n\n旧附录。\n";
        assertThat(PersonaPromptReviewService.stripNovelSection(withAppendix)).isEqualTo("# 原性格\n\n正文。");
        assertThat(PersonaPromptRewriter.stripLegacy(withAppendix + "\n<!-- persona-import integrated importId=x -->\n"))
                .isEqualTo("# 原性格\n\n正文。");
    }

    @Test
    void stageThenApproveReplacesPromptsWithIntegratedRewrite() throws Exception {
        Path prompts = Files.createDirectories(data.resolve("prompts"));
        Files.writeString(
                prompts.resolve("SOUL.md"),
                "# 原SOUL\n\n你是杜小洛。偶尔有一点平静的幽默。不要向用户背诵这些性格词。\n\n## 稳定的底色\n\n- 底色。\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                prompts.resolve("VOICE.md"),
                "# 原VOICE\n\n节奏。\n\n语气词、幽默与感叹号顺着情境偶尔出现，没有固定口癖。\n",
                StandardCharsets.UTF_8);
        var overlay = mock(DefaultPersonaOverlayPort.class);
        when(overlay.current())
                .thenReturn(Optional.of(new DefaultPersonaOverlayRevision(
                        3, new PersonaId("persona_x"), "o-soul", "o-voice", "sum", Instant.EPOCH)));
        when(overlay.rollback(0, 3)).thenReturn(Optional.empty());
        var service = new PersonaPromptReviewService(overlay, data.toString());
        var traits = new DefaultPersonaOverlayTraits(
                DefaultPersonaOverlayTraits.InteractionStyle.THOUGHTFUL,
                DefaultPersonaOverlayTraits.ResponsePace.BALANCED,
                DefaultPersonaOverlayTraits.Initiative.HIGH,
                DefaultPersonaOverlayTraits.Humor.LIGHT);
        var profile = new PersonaProfileV1(
                1,
                "杜小洛",
                "样本1：活泼亲近，愿意玩笑撒娇。；样本2：证据不足。",
                "样本1：说话俏皮。；样本2：无法判断。",
                "id",
                List.of(new PersonaProfileV1.SourceReference("s1", "t")),
                List.of(
                        new PersonaProfileV1.Evidence("s1", 0, 3, "杜小洛", "[SOUL] x [STYLE:THOUGHTFUL]", false),
                        new PersonaProfileV1.Evidence("s1", 4, 7, "杜小洛", "[VOICE] y [PACE:BALANCED]", false)),
                traits);
        var draft = new PersonaDefinition(
                new PersonaId("persona_x"), "杜小洛", PersonaDefinition.Status.DRAFT, 1, profile, "s1", Instant.EPOCH);
        var staged = service.stage("imp-review-1", draft, "op-stage-1");
        assertThat(staged.status()).isEqualTo("STAGED");
        assertThat(staged.personalityEffective()).isFalse();
        assertThat(staged.soulObservationCount()).isGreaterThanOrEqualTo(1);
        assertThat(staged.voiceObservationCount()).isGreaterThanOrEqualTo(1);
        String stagedSoul = Files.readString(Path.of(staged.soulFile()));
        assertThat(stagedSoul)
                .contains("人物气质")
                .contains("活泼")
                .doesNotContain("## 小说人物补充")
                .doesNotContain("小说观察")
                .doesNotContain("先整理对话重点");
        assertThat(Files.readString(Path.of(staged.metaFile())))
                .contains("integrated-rewrite")
                .contains("改写摘要")
                .contains("personalityEffective: `false`");
        assertThat(Files.readString(prompts.resolve("SOUL.md"))).doesNotContain("人物气质");

        var merged = service.approve("imp-review-1", "op-approve-1");
        assertThat(merged.overlayCleared()).isTrue();
        assertThat(merged.soulSnippet()).contains("人物气质");
        String liveSoul = Files.readString(prompts.resolve("SOUL.md"));
        String liveVoice = Files.readString(prompts.resolve("VOICE.md"));
        assertThat(liveSoul).contains("原SOUL").contains("人物气质").contains("活泼").doesNotContain("小说观察");
        assertThat(liveVoice).contains("原VOICE").contains("俏皮").doesNotContain("小说观察");
        verify(overlay).rollback(0, 3);

        var approvedStatus = service.status("imp-review-1");
        assertThat(approvedStatus.status()).isEqualTo("APPROVED");
        assertThat(approvedStatus.personalityEffective()).isTrue();

        var again = service.approve("imp-review-1", "op-approve-1");
        assertThat(again.soulSha256()).isEqualTo(merged.soulSha256());
    }
}
