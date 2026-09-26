package com.wannian.server.app.manage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 同一网关多协议时按目录标签分流：Codex / gpt-6-* → Responses；其余 → Chat Completions。
 *
 * <p>无 {@code owned_by} 标签的原生供应商（DeepSeek / 智谱）原样保留。
 */
public final class VendorCatalogProtocolFilter {

    private VendorCatalogProtocolFilter() {}

    public static List<ModelCatalogEntry> filter(String protocol, List<ModelCatalogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        boolean responses = StubModelCatalog.isResponsesProtocol(protocol);
        boolean compatible = StubModelCatalog.PROTOCOL.equals(protocol);
        if (!responses && !compatible) {
            return List.copyOf(entries);
        }
        boolean anyTagged = false;
        for (ModelCatalogEntry entry : entries) {
            if (ownedBy(entry) != null) {
                anyTagged = true;
                break;
            }
        }
        if (!anyTagged) {
            return List.copyOf(entries);
        }
        List<ModelCatalogEntry> out = new ArrayList<>(entries.size());
        for (ModelCatalogEntry entry : entries) {
            boolean family = isResponsesFamily(entry);
            if (responses ? family : !family) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    static boolean isResponsesFamily(ModelCatalogEntry entry) {
        if (entry == null || entry.id() == null) {
            return false;
        }
        String id = entry.id().trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("gpt-6") || id.startsWith("codex-")) {
            return true;
        }
        String owned = ownedBy(entry);
        return owned != null && owned.equals("codex");
    }

    /** 从 {@code id (owned_by)} 展示名解析 owned_by；无则 null。 */
    static String ownedBy(ModelCatalogEntry entry) {
        if (entry == null || entry.displayName() == null) {
            return null;
        }
        String display = entry.displayName().trim();
        int open = display.lastIndexOf('(');
        int close = display.lastIndexOf(')');
        if (open < 0 || close <= open + 1) {
            return null;
        }
        String owned = display.substring(open + 1, close).trim().toLowerCase(Locale.ROOT);
        return owned.isEmpty() ? null : owned;
    }
}
