package com.wannian.server.kernel.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 0.2.4-P：硬安全 + 分层合并顺序。 */
class PromptComposerTest {

    @Test
    void compose_orderIsHardSafetyThenLayersThenSkillIndex() {
        PromptLayerTexts layers =
                new PromptLayerTexts("SOUL块", "VOICE块", "IDENTITY块", "USER块", "SAFETY块");
        String out = PromptComposer.compose(layers, "Skill索引块");
        assertThat(out)
                .isEqualTo(
                        PromptSkeleton.HARD_SAFETY.strip()
                                + "\n\nSOUL块\n\nVOICE块\n\nIDENTITY块\n\nUSER块\n\nSAFETY块\n\nSkill索引块");
    }

    @Test
    void compose_skipsBlankLayersButKeepsHardSafety() {
        String out = PromptComposer.compose(PromptLayerTexts.empty(), "  ");
        assertThat(out).isEqualTo(PromptSkeleton.HARD_SAFETY.strip());
    }
}
