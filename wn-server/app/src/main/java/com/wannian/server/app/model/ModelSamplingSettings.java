package com.wannian.server.app.model;

/**
 * Chat Completions / Responses 采样参数（陪伴默认略高温度，减轻助手腔模板句）。
 *
 * @param temperature null 表示不传（跟供应商默认）
 * @param topP null 表示不传
 * @param presencePenalty null 表示不传
 */
public record ModelSamplingSettings(Double temperature, Double topP, Double presencePenalty) {

    public static ModelSamplingSettings unset() {
        return new ModelSamplingSettings(null, null, null);
    }

    /** 聊天陪伴默认：略升温 + 轻抗重复。 */
    public static ModelSamplingSettings chatDefaults() {
        return new ModelSamplingSettings(0.85, 0.95, 0.35);
    }

    public boolean anySet() {
        return temperature != null || topP != null || presencePenalty != null;
    }
}

