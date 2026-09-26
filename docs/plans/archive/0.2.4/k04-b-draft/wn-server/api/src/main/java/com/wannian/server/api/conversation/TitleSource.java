package com.wannian.server.api.conversation;

/**
 * 会话标题来源（{@code conversation.title_source}）。
 *
 * <p>手动改名后必须为 {@link #MANUAL}；自动标题（E 段）不得覆盖 MANUAL。
 */
public enum TitleSource {

    /** 默认「会话 N」或后续 AI 标题。 */
    AUTO,

    /** 用户手动改名。 */
    MANUAL
}
