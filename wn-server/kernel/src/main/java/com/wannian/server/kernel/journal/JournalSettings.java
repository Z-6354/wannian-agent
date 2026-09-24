package com.wannian.server.kernel.journal;

/**
 * 行为账本写入选项（由 app 从配置注入）。
 *
 * @param includeFullMessages true 时 MODEL_CALL 存脱敏全文；false 时只存长度与 digest
 * @param maxPayloadChars 单字段最大字符；须为正
 */
public record JournalSettings(boolean includeFullMessages, int maxPayloadChars) {

    public static final JournalSettings DEFAULT = new JournalSettings(true, 65_536);

    public JournalSettings {
        if (maxPayloadChars <= 0) {
            throw new IllegalArgumentException("maxPayloadChars 须为正");
        }
    }
}
