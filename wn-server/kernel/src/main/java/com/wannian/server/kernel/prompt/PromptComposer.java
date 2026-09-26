package com.wannian.server.kernel.prompt;



import java.util.Objects;



/**

 * 纯函数：硬安全 + 分层 MD + 可选 Skill 索引 → 常驻 system 前缀。

 *

 * <p>观察日锚块仍由 {@code ContextAssembler} 追加，不在此合并。

 *

 * <p>分层顺序对齐 Hermes：身份／声线常驻；Skill 仅索引按需。

 */

public final class PromptComposer {



    private PromptComposer() {}



    public static String compose(PromptLayerTexts layers, String skillIndexText) {

        Objects.requireNonNull(layers, "layers");

        StringBuilder out = new StringBuilder();

        appendBlock(out, PromptSkeleton.HARD_SAFETY);

        appendBlock(out, layers.soul());
        appendBlock(out, layers.voice());
        appendBlock(out, layers.personaSupplement());
        appendBlock(out, layers.identity());
        appendBlock(out, layers.userNote());

        appendBlock(out, layers.safetySupplement());

        if (skillIndexText != null && !skillIndexText.isBlank()) {

            appendBlock(out, skillIndexText.strip());

        }

        return out.toString().strip();

    }



    private static void appendBlock(StringBuilder out, String block) {

        if (block == null || block.isBlank()) {

            return;

        }

        if (out.length() > 0) {

            out.append("\n\n");

        }

        out.append(block.strip());

    }

}


