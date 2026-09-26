package com.wannian.server.kernel.prompt;

import java.util.Objects;

/**
 * 已由 app 读盘并截断后的可编辑提示词分层（kernel 不读文件）。
 *
 * @param soul SOUL.md
 * @param voice VOICE.md（常驻声线／示例；可空）
 * @param identity IDENTITY.md（可空）
 * @param userNote USER.md（可空）
 * @param safetySupplement SAFETY.md 运营补充（可空；不得当硬安全）
 * @param personaSupplement 可选低信任角色补充，位于原 SOUL/VOICE 后、IDENTITY 前
 */
public record PromptLayerTexts(
        String soul,
        String voice,
        String identity,
        String userNote,
        String safetySupplement,
        String personaSupplement) {

    public PromptLayerTexts {
        soul = soul == null ? "" : soul;
        voice = voice == null ? "" : voice;
        identity = identity == null ? "" : identity;
        userNote = userNote == null ? "" : userNote;
        safetySupplement = safetySupplement == null ? "" : safetySupplement;
        personaSupplement = personaSupplement == null ? "" : personaSupplement;
        Objects.requireNonNull(soul, "soul");
        Objects.requireNonNull(voice, "voice");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(userNote, "userNote");
        Objects.requireNonNull(safetySupplement, "safetySupplement");
    }

    /** Compatibility constructor without the optional persona supplement. */
    public PromptLayerTexts(String soul, String voice, String identity, String userNote, String safetySupplement) {
        this(soul, voice, identity, userNote, safetySupplement, "");
    }

    /** 兼容旧四层构造（无 VOICE）。 */
    public PromptLayerTexts(String soul, String identity, String userNote, String safetySupplement) {
        this(soul, "", identity, userNote, safetySupplement, "");
    }

    public static PromptLayerTexts empty() {
        return new PromptLayerTexts("", "", "", "", "", "");
    }
}
