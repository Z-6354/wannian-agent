package com.wannian.server.app.manage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 内置预设。万年同一网关按协议拆成两条：Responses（Codex）与 Chat Completions（Gemini 等）。
 */
@Component
public final class BuiltinVendorPresetSource implements VendorPresetSource {

    /** 两个万年协议共用同一密钥文件 {@code secrets/wannian-ai.key}。 */
    public static final String WANNIAN_SECRET_ID = "wannian-ai";

    private static final List<VendorPreset> PRESETS =
            List.of(
                    new VendorPreset(
                            "wannian-ai",
                            "万年AI · Responses",
                            StubModelCatalog.PROTOCOL_RESPONSES,
                            "https://api.wannian.fun/v1",
                            "WANNIAN_AI_API_KEY",
                            WANNIAN_SECRET_ID),
                    new VendorPreset(
                            "wannian-openai",
                            "万年AI · Chat",
                            StubModelCatalog.PROTOCOL,
                            "https://api.wannian.fun/v1",
                            "WANNIAN_AI_API_KEY",
                            WANNIAN_SECRET_ID),
                    new VendorPreset(
                            "deepseek",
                            "DeepSeek",
                            StubModelCatalog.PROTOCOL,
                            "https://api.deepseek.com/v1",
                            "DEEPSEEK_API_KEY"),
                    new VendorPreset(
                            "zhipu",
                            "智谱",
                            StubModelCatalog.PROTOCOL,
                            "https://open.bigmodel.cn/api/paas/v4",
                            "ZHIPU_API_KEY"));

    private final Map<String, VendorPreset> byId =
            PRESETS.stream().collect(Collectors.toUnmodifiableMap(VendorPreset::id, Function.identity()));

    @Override
    public List<VendorPreset> list() {
        return PRESETS;
    }

    @Override
    public Optional<VendorPreset> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.trim()));
    }
}
