package com.wannian.server.kernel.tool;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 工具「使用 / 不使用 / 不能使用」策略：系统探测（HostCapability）优先于用户勾选。
 *
 * <p>{@code powershell_resolve_5} 与 {@code powershell_resolve_7} 在本机皆可用时互斥，默认保留 7。
 */
public final class ToolUsePolicy {

    /** 使用中（本机可用且已启用）。 */
    public static final String STATUS_IN_USE = "IN_USE";

    /** 不使用（本机可用但未启用）。 */
    public static final String STATUS_NOT_USING = "NOT_USING";

    /** 不能使用（本机不满足 requiredCapabilities）。 */
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    private ToolUsePolicy() {}

    public static boolean isAvailable(String toolName, HostCapabilitySet host) {
        Objects.requireNonNull(host, "host");
        return BuiltinToolPool.find(toolName)
                .map(spec -> host.containsAll(spec.requiredCapabilities()))
                .orElse(false);
    }

    public static String status(String toolName, HostCapabilitySet host, Set<String> enabled) {
        Objects.requireNonNull(enabled, "enabled");
        if (!isAvailable(toolName, host)) {
            return STATUS_UNAVAILABLE;
        }
        return enabled.contains(toolName) ? STATUS_IN_USE : STATUS_NOT_USING;
    }

    public static boolean selectable(String toolName, HostCapabilitySet host) {
        return isAvailable(toolName, host);
    }

    /**
     * 钳制用户启用名单：去掉不能使用的；PS 5/7 皆可用且皆勾选时只留 7（或按用户只勾一个保留）。
     *
     * <p>不自动把「可用但未勾选」的加回（允许全部不使用）。
     */
    public static List<String> clampEnabled(List<String> requested, HostCapabilitySet host) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(host, "host");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : requested) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("enabled 不得含空白名");
            }
            String name = raw.trim();
            if (!BuiltinToolPool.contains(name)) {
                throw new IllegalArgumentException("不在内置池: " + name);
            }
            if (!isAvailable(name, host)) {
                continue;
            }
            out.add(name);
        }
        applyPowershellMutex(out, host);
        return List.copyOf(out);
    }

    /**
     * 默认启用：池内本机可用者全开；PS 互斥时默认只开 7（若有），否则开 5。
     */
    public static List<String> defaultEnabled(HostCapabilitySet host) {
        Objects.requireNonNull(host, "host");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String name : BuiltinToolPool.allNames()) {
            if (isAvailable(name, host)) {
                out.add(name);
            }
        }
        applyPowershellMutexPreferSeven(out, host);
        return List.copyOf(out);
    }

    /** 模式名单钳制为 enabled 子集，并去掉不可用名。 */
    public static List<String> clampFacet(List<String> facet, Set<String> enabled, HostCapabilitySet host) {
        Objects.requireNonNull(facet, "facet");
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(host, "host");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : facet) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("模式名单不得含空白名");
            }
            String name = raw.trim();
            if (!enabled.contains(name)) {
                continue;
            }
            if (!isAvailable(name, host)) {
                continue;
            }
            out.add(name);
        }
        return List.copyOf(out);
    }

    private static void applyPowershellMutex(LinkedHashSet<String> enabled, HostCapabilitySet host) {
        boolean has5 = enabled.contains(BuiltinToolNames.POWERSHELL_RESOLVE_5);
        boolean has7 = enabled.contains(BuiltinToolNames.POWERSHELL_RESOLVE_7);
        if (!(has5 && has7)) {
            return;
        }
        // 皆勾选：优先保留 7（若本机可用），否则保留 5
        if (host.contains(HostCapabilities.SHELL_PS_FAMILY7)) {
            enabled.remove(BuiltinToolNames.POWERSHELL_RESOLVE_5);
        } else {
            enabled.remove(BuiltinToolNames.POWERSHELL_RESOLVE_7);
        }
    }

    private static void applyPowershellMutexPreferSeven(
            LinkedHashSet<String> enabled, HostCapabilitySet host) {
        boolean avail5 = host.contains(HostCapabilities.SHELL_PS_FAMILY5);
        boolean avail7 = host.contains(HostCapabilities.SHELL_PS_FAMILY7);
        if (avail5 && avail7) {
            enabled.remove(BuiltinToolNames.POWERSHELL_RESOLVE_5);
            enabled.add(BuiltinToolNames.POWERSHELL_RESOLVE_7);
        }
    }

    /** 默认模式名单：仅保留本机可用，且 PS 互斥时优先 7。 */
    public static List<String> defaultFacet(List<String> seed, HostCapabilitySet host) {
        Objects.requireNonNull(seed, "seed");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String name : seed) {
            if (isAvailable(name, host)) {
                out.add(name);
            }
        }
        applyPowershellMutexPreferSeven(out, host);
        return List.copyOf(out);
    }
}
