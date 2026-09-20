package com.wannian.server.app.manage;

import java.util.List;

/** Fake 模式与协议常量的固定目录。live 模式由 {@link OpenAiCompatibleVendorAdapter} 拉真实目录。 */
public final class StubModelCatalog {

    public static final String PROTOCOL = "openai-compatible";

    public record Entry(String id, String displayName) {}

    private static final List<Entry> ENTRIES =
            List.of(new Entry("stub-chat", "桩对话模型"), new Entry("stub-reasoner", "桩推理模型"));

    private StubModelCatalog() {}

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static boolean contains(String modelId) {
        if (modelId == null) {
            return false;
        }
        for (Entry entry : ENTRIES) {
            if (entry.id().equals(modelId)) {
                return true;
            }
        }
        return false;
    }
}
