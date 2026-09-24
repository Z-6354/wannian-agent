package com.wannian.server.kernel.tool;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具配置三态与启用策略：系统探测（HostCapability）优先于用户勾选。
 *
 * <p>存盘态仅 {@code locked}/{@code on}/{@code off}。锁死四名不可关；
 * {@code powershell_resolve_5} 与 {@code powershell_resolve_7} 本机皆可用时互斥，默认保留 7。
 */
public final class ToolUsePolicy {

    /** 使用中（本机可用且已启用）。 */
    public static final String STATUS_IN_USE = "IN_USE";

    /** 不使用（本机可用但未启用）。 */
    public static final String STATUS_NOT_USING = "NOT_USING";

    /** 不能使用（本机不满足 requiredCapabilities）。 */
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    /** 锁死顺序（与 Yanhuo 种子前缀对齐）。 */
    private static final List<String> LOCKED_ORDER =
            List.of(
                    BuiltinToolNames.LIST_TOOLS,
                    BuiltinToolNames.CURRENT_TIME,
                    BuiltinToolNames.REMEMBER_FACT,
                    BuiltinToolNames.UPDATE_RELATIONSHIP,
                    BuiltinToolNames.SEARCH_MEMORY);

    private static final Set<String> LOCKED_NAMES = Set.copyOf(LOCKED_ORDER);

    private ToolUsePolicy() {}

    /** 存盘 / API 配置态。 */
    public enum ConfigState {
        LOCKED("locked"),
        ON("on"),
        OFF("off");

        private final String wire;

        ConfigState(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** 解析存盘小写态；非法返回 empty。 */
        public static java.util.Optional<ConfigState> tryParse(String raw) {
            if (raw == null || raw.isBlank()) {
                return java.util.Optional.empty();
            }
            String key = raw.trim().toLowerCase(Locale.ROOT);
            for (ConfigState state : values()) {
                if (state.wire.equals(key)) {
                    return java.util.Optional.of(state);
                }
            }
            return java.util.Optional.empty();
        }
    }

    public static boolean isLocked(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        return LOCKED_NAMES.contains(toolName.trim());
    }

    /** 锁死名有序列表（本机可用性由调用方再滤）。 */
    public static List<String> lockedNames() {
        return LOCKED_ORDER;
    }

    /**
     * 出厂默认：锁死→{@link ConfigState#LOCKED}；池内其余可选→{@link ConfigState#ON}；未知名非法。
     */
    public static ConfigState productDefault(String toolName) {
        String name = requirePoolName(toolName);
        return isLocked(name) ? ConfigState.LOCKED : ConfigState.ON;
    }

    /**
     * 规范化客户端/磁盘态：锁死强制 {@code LOCKED}；可选非法态→{@code ON}。
     */
    public static ConfigState coerce(String toolName, String raw) {
        String name = requirePoolName(toolName);
        if (isLocked(name)) {
            return ConfigState.LOCKED;
        }
        return ConfigState.tryParse(raw)
                .filter(state -> state == ConfigState.ON || state == ConfigState.OFF)
                .orElse(ConfigState.ON);
    }

    /** {@code locked}/{@code on} 且本机可用。 */
    public static boolean isEffectivelyEnabled(
            ConfigState state, HostCapabilitySet host, String toolName) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(host, "host");
        if (state != ConfigState.LOCKED && state != ConfigState.ON) {
            return false;
        }
        return isAvailable(toolName, host);
    }

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

    /** 锁死或本机不可用 → 不可勾选。 */
    public static boolean selectable(String toolName, HostCapabilitySet host) {
        if (isLocked(toolName)) {
            return false;
        }
        return isAvailable(toolName, host);
    }

    /**
     * 钳制用户启用名单：去掉不能使用的；PS 5/7 皆可用且皆勾选时只留 7（或按用户只勾一个保留）；
     * 末尾强制并入所有锁死且本机可用名。
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
        for (String locked : LOCKED_ORDER) {
            if (isAvailable(locked, host)) {
                out.add(locked);
            }
        }
        return List.copyOf(out);
    }

    /** 出厂 byName：池内每名 {@link #productDefault}。 */
    public static Map<String, String> defaultByName() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String name : BuiltinToolPool.allNames()) {
            out.put(name, productDefault(name).wire());
        }
        return Map.copyOf(out);
    }

    /**
     * 默认启用：池内本机可用且出厂非 OFF 者全开；PS 互斥时默认只开 7；再并入锁死可用名。
     */
    public static List<String> defaultEnabled(HostCapabilitySet host) {
        Objects.requireNonNull(host, "host");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String name : BuiltinToolPool.allNames()) {
            ConfigState state = productDefault(name);
            if (isEffectivelyEnabled(state, host, name)) {
                out.add(name);
            }
        }
        applyPowershellMutexPreferSeven(out, host);
        for (String locked : LOCKED_ORDER) {
            if (isAvailable(locked, host)) {
                out.add(locked);
            }
        }
        return List.copyOf(out);
    }

    /** 由 byName 派生有效启用名（含 PS 互斥与锁死并入）。 */
    public static List<String> enabledFromByName(
            Map<String, String> byName, HostCapabilitySet host) {
        Objects.requireNonNull(byName, "byName");
        Objects.requireNonNull(host, "host");
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (String name : BuiltinToolPool.allNames()) {
            ConfigState state = coerce(name, byName.get(name));
            if (state == ConfigState.LOCKED || state == ConfigState.ON) {
                requested.add(name);
            }
        }
        return clampEnabled(List.copyOf(requested), host);
    }

    /**
     * 规范化 byName：未知键非法；每池名经 {@link #coerce}；PS 互斥把落选侧写成 {@code off}；
     * 锁死强制 {@code locked}。
     */
    public static Map<String, String> normalizeByName(
            Map<String, String> rawByName, HostCapabilitySet host) {
        Objects.requireNonNull(rawByName, "byName");
        Objects.requireNonNull(host, "host");
        for (String key : rawByName.keySet()) {
            if (key == null || key.isBlank() || !BuiltinToolPool.contains(key.trim())) {
                throw new IllegalArgumentException("不在内置池: " + key);
            }
        }
        LinkedHashMap<String, ConfigState> coerced = new LinkedHashMap<>();
        for (String name : BuiltinToolPool.allNames()) {
            coerced.put(name, coerce(name, rawByName.get(name)));
        }
        List<String> enabled = enabledFromByName(wireMap(coerced), host);
        Set<String> enabledSet = Set.copyOf(enabled);
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String name : BuiltinToolPool.allNames()) {
            if (isLocked(name)) {
                out.put(name, ConfigState.LOCKED.wire());
                continue;
            }
            ConfigState state = coerced.get(name);
            if (state == ConfigState.ON && !enabledSet.contains(name) && isAvailable(name, host)) {
                // PS 互斥等钳制导致落选
                out.put(name, ConfigState.OFF.wire());
            } else {
                out.put(name, state.wire());
            }
        }
        return Map.copyOf(out);
    }

    /** 模式名单钳制为 enabled 子集，并去掉不可用名；再强制并入锁死且已启用名。 */
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
        for (String locked : LOCKED_ORDER) {
            if (enabled.contains(locked) && isAvailable(locked, host)) {
                out.add(locked);
            }
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

    /** 默认模式名单：仅保留本机可用，且 PS 互斥时优先 7；再并入锁死可用名。 */
    public static List<String> defaultFacet(List<String> seed, HostCapabilitySet host) {
        Objects.requireNonNull(seed, "seed");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String name : seed) {
            if (isAvailable(name, host)) {
                out.add(name);
            }
        }
        applyPowershellMutexPreferSeven(out, host);
        for (String locked : LOCKED_ORDER) {
            if (isAvailable(locked, host)) {
                out.add(locked);
            }
        }
        return List.copyOf(out);
    }

    private static String requirePoolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("工具名不得空白");
        }
        String name = toolName.trim();
        if (!BuiltinToolPool.contains(name)) {
            throw new IllegalArgumentException("不在内置池: " + name);
        }
        return name;
    }

    private static Map<String, String> wireMap(Map<String, ConfigState> states) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigState> entry : states.entrySet()) {
            out.put(entry.getKey(), entry.getValue().wire());
        }
        return out;
    }
}
