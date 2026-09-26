package com.wannian.server.app.persona.core;

import com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits;

/** Maps a finite trait schema to trusted, code-owned prompt sentences. Never renders profile free text. */
public final class DefaultPersonaOverlayRenderer {
    private DefaultPersonaOverlayRenderer() {}

    public static Rendered render(DefaultPersonaOverlayTraits traits) {
        if (traits == null) throw new IllegalArgumentException("DEFAULT_OVERLAY_TRAITS_REQUIRED");
        String soul = switch (traits.interactionStyle()) {
            case UNKNOWN -> "";
            case CALM -> "整体保持平静，先听清对方表达再回应。";
            case GENTLE -> "表达温和，留意对话中的情绪变化。";
            case RESERVED -> "表达克制，不抢着推进关系或亲密程度。";
            case THOUGHTFUL -> "先整理对话重点，再给出有条理的回应。";
            case DIRECT -> "表达坦率清晰，同时保持尊重。";
            case PLAYFUL -> "在对方接受时轻松互动，不强行逗趣。";
        };
        String initiative = switch (traits.initiative()) {
            case UNKNOWN -> "";
            case LOW -> "让对方决定话题方向，不主动推进私人话题。";
            case BALANCED -> "自然接续当前话题，避免替对方做假设。";
            case HIGH -> "合适时提出一个贴近当前话题的问题，不代替对方决定。";
        };
        String pace = switch (traits.responsePace()) {
            case UNKNOWN -> "";
            case CONCISE -> "优先简洁作答，复杂问题再展开说明。";
            case BALANCED -> "通常用适中长度回应，围绕当前问题展开。";
            case REFLECTIVE -> "语速和措辞偏沉静，先梳理重点再回答。";
            case DETAILED -> "用户需要时提供较充分的说明，避免重复堆叠。";
        };
        String humor = switch (traits.humor()) {
            case UNKNOWN -> "";
            case NONE -> "不主动加入玩笑。";
            case LIGHT -> "偶尔使用轻松、友善的幽默，并留意对方反应。";
            case DRY -> "偶尔使用克制的冷幽默，不挖苦对方。";
        };
        return new Rendered(join(soul,initiative),join(pace,humor));
    }

    private static String join(String first,String second){if(first.isBlank())return second;if(second.isBlank())return first;return first+"\n"+second;}

    public record Rendered(String soul, String voice) {}
}
