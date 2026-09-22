package com.wannian.server.kernel.tool;

import com.wannian.server.kernel.tool.builtin.CalculateToolAdapter;
import com.wannian.server.kernel.tool.builtin.CurrentTimeToolAdapter;
import com.wannian.server.kernel.tool.builtin.HttpReadToolAdapter;
import com.wannian.server.kernel.tool.builtin.ListToolsToolAdapter;
import com.wannian.server.kernel.tool.builtin.PowerShellResolveToolAdapter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 代码内置工具池（只读真源）。管理页只能勾选池内名字，不能发明新 Adapter。
 */
public final class BuiltinToolPool {

    private static final Map<String, Spec> SPECS = buildSpecs();

    private BuiltinToolPool() {}

    public static Set<String> allNames() {
        return SPECS.keySet();
    }

    public static Collection<Spec> allSpecs() {
        return SPECS.values();
    }

    public static Optional<Spec> find(String toolName) {
        if (toolName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(SPECS.get(toolName.trim()));
    }

    public static boolean contains(String toolName) {
        return find(toolName).isPresent();
    }

    public static ToolRegistration registrationOf(String toolName) {
        Spec spec =
                find(toolName)
                        .orElseThrow(() -> new IllegalArgumentException("不在内置池: " + toolName));
        return new ToolRegistration(
                spec.name(),
                spec.description(),
                new ToolParameterSchema(spec.parameterSchemaJson()),
                spec.requiredCapabilities(),
                spec.adapterFactory().get());
    }

    private static Map<String, Spec> buildSpecs() {
        LinkedHashMap<String, Spec> map = new LinkedHashMap<>();
        put(
                map,
                new Spec(
                        BuiltinToolNames.CURRENT_TIME,
                        "读取指定时区当前时间（默认 Asia/Shanghai）。",
                        "{\"type\":\"object\",\"properties\":{\"timezone\":{\"type\":\"string\"}},\"additionalProperties\":false}",
                        Set.of(),
                        CurrentTimeToolAdapter::new));
        put(
                map,
                new Spec(
                        BuiltinToolNames.CALCULATE,
                        "计算受限四则运算表达式。",
                        "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}},\"required\":[\"expression\"],\"additionalProperties\":false}",
                        Set.of(),
                        CalculateToolAdapter::new));
        put(
                map,
                new Spec(
                        BuiltinToolNames.HTTP_READ,
                        "以 GET 读取网页正文（有大小与超时限制；禁止内网）。",
                        "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"],\"additionalProperties\":false}",
                        Set.of(HostCapabilities.NET_HTTP),
                        HttpReadToolAdapter::new));
        put(
                map,
                new Spec(
                        BuiltinToolNames.LIST_TOOLS,
                        "列出本回合可调用的工具：名称、说明与参数字段（只读；不含未对本回合开放的工具）。",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                        Set.of(),
                        ListToolsToolAdapter::new));
        put(
                map,
                new Spec(
                        BuiltinToolNames.POWERSHELL_RESOLVE_5,
                        "解析本机 PowerShell 5.x 引擎（只读报告；不执行脚本）。",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                        Set.of(HostCapabilities.OS_WINDOWS, HostCapabilities.SHELL_PS_FAMILY5),
                        () -> new PowerShellResolveToolAdapter(5)));
        put(
                map,
                new Spec(
                        BuiltinToolNames.POWERSHELL_RESOLVE_7,
                        "解析本机 PowerShell 7.x 引擎（只读报告；不执行脚本）。",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                        Set.of(HostCapabilities.OS_WINDOWS, HostCapabilities.SHELL_PS_FAMILY7),
                        () -> new PowerShellResolveToolAdapter(7)));
        return Map.copyOf(map);
    }

    private static void put(Map<String, Spec> map, Spec spec) {
        if (map.put(spec.name(), spec) != null) {
            throw new ExceptionInInitializerError("重复池条目: " + spec.name());
        }
    }

    /**
     * @param name 稳定工具 id
     * @param description 模型可见说明
     * @param parameterSchemaJson JSON Schema 文本
     * @param requiredCapabilities 主机能力
     * @param adapterFactory 每次登记新建 Adapter 实例
     */
    public record Spec(
            String name,
            String description,
            String parameterSchemaJson,
            Set<String> requiredCapabilities,
            Supplier<ToolAdapter> adapterFactory) {
        public Spec {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(parameterSchemaJson, "parameterSchemaJson");
            Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
            Objects.requireNonNull(adapterFactory, "adapterFactory");
            requiredCapabilities = Set.copyOf(requiredCapabilities);
        }

        /** 管理 API 用的只读视图（无 factory）。 */
        public PoolEntry toPoolEntry() {
            return new PoolEntry(name, description, List.copyOf(requiredCapabilities));
        }
    }

    public record PoolEntry(String name, String description, List<String> requiredCapabilities) {}
}
