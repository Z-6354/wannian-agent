package com.wannian.server.kernel.tool;

import com.wannian.server.kernel.tool.builtin.CalculateToolAdapter;
import com.wannian.server.kernel.tool.builtin.CurrentTimeToolAdapter;
import com.wannian.server.kernel.tool.builtin.HttpReadToolAdapter;
import com.wannian.server.kernel.tool.builtin.ListToolsToolAdapter;
import com.wannian.server.kernel.tool.builtin.PowerShellResolveToolAdapter;
import com.wannian.server.kernel.tool.builtin.RememberFactToolAdapter;
import com.wannian.server.kernel.tool.builtin.SearchMemoryToolAdapter;
import com.wannian.server.kernel.tool.builtin.UpdateRelationshipToolAdapter;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.DefaultMemoryShape;
import com.wannian.server.kernel.memory.SecretOnlyMemoryPolicy;
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
        return registrationOf(spec, null);
    }

    /** 按装配时提供的专用 Adapter 覆盖某个工具的默认工厂。 */
    public static ToolRegistration registrationOf(String toolName, ToolAdapter adapterOverride) {
        Spec spec =
                find(toolName)
                        .orElseThrow(() -> new IllegalArgumentException("不在内置池: " + toolName));
        return registrationOf(spec, adapterOverride);
    }

    private static ToolRegistration registrationOf(Spec spec, ToolAdapter adapterOverride) {
        return new ToolRegistration(
                spec.name(),
                spec.description(),
                new ToolParameterSchema(spec.parameterSchemaJson()),
                spec.requiredCapabilities(),
                adapterOverride == null ? spec.adapterFactory().get() : adapterOverride,
                spec.countsTowardDecisionBudget());
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
                        true,
                        CurrentTimeToolAdapter::new));
        put(
                map,
                new Spec(
                        BuiltinToolNames.CALCULATE,
                        "计算受限四则运算表达式。",
                        "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}},\"required\":[\"expression\"],\"additionalProperties\":false}",
                        Set.of(),
                        true,
                        CalculateToolAdapter::new));
        put(
                map,
                new Spec(
                        BuiltinToolNames.HTTP_READ,
                        "以 GET 读取网页正文（有大小与超时限制；禁止内网）。",
                        "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"],\"additionalProperties\":false}",
                        Set.of(HostCapabilities.NET_HTTP),
                        true,
                        HttpReadToolAdapter::new));
        put(
                map,
                new Spec(
                        BuiltinToolNames.LIST_TOOLS,
                        "列出本回合可调用的工具：名称、说明与参数字段（只读；不含未对本回合开放的工具）。",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                        Set.of(),
                        true,
                        ListToolsToolAdapter::new));
        put(
                map,
                new Spec(
                        BuiltinToolNames.POWERSHELL_RESOLVE_5,
                        "解析本机 PowerShell 5.x 引擎（只读报告；不执行脚本）。",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                        Set.of(HostCapabilities.OS_WINDOWS, HostCapabilities.SHELL_PS_FAMILY5),
                        true,
                        () -> new PowerShellResolveToolAdapter(5)));
        put(
                map,
                new Spec(
                        BuiltinToolNames.POWERSHELL_RESOLVE_7,
                        "解析本机 PowerShell 7.x 引擎（只读报告；不执行脚本）。",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                        Set.of(HostCapabilities.OS_WINDOWS, HostCapabilities.SHELL_PS_FAMILY7),
                        true,
                        () -> new PowerShellResolveToolAdapter(7)));
        put(
                map,
                new Spec(
                        BuiltinToolNames.REMEMBER_FACT,
                        "记住一条可跨会话复用的事实。"
                                + " claim 须含绝对时间/具体地点；importance 为 0~1。"
                                + " contentKind 必须是 USER_FACT|USER_PREFERENCE|SHARED_HISTORY|TASK_CONTEXT；"
                                + " sourceKind 可选 EXPLICIT|OBSERVED|INFERRED|REFLECTION（默认 EXPLICIT）；"
                                + " scope 可选 COMPANION|CONVERSATION|TASK（默认 COMPANION）。",
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"claim\":{\"type\":\"string\"},"
                                + "\"subjectKey\":{\"type\":\"string\"},"
                                + "\"importance\":{\"type\":\"string\"},"
                                + "\"contentKind\":{\"type\":\"string\",\"enum\":[\"USER_FACT\",\"USER_PREFERENCE\",\"SHARED_HISTORY\",\"TASK_CONTEXT\"]},"
                                + "\"sourceKind\":{\"type\":\"string\",\"enum\":[\"EXPLICIT\",\"OBSERVED\",\"INFERRED\",\"REFLECTION\"]},"
                                + "\"scope\":{\"type\":\"string\",\"enum\":[\"COMPANION\",\"CONVERSATION\",\"TASK\"]},"
                                + "\"path\":{\"type\":\"string\"}"
                                + "},\"required\":[\"claim\",\"subjectKey\",\"importance\",\"contentKind\"],\"additionalProperties\":false}",
                        Set.of(),
                        false,
                        () ->
                                new RememberFactToolAdapter(
                                        new DefaultMemoryShape(),
                                        new SecretOnlyMemoryPolicy(),
                                        CompanionIdentity.YANHUO)));
        put(
                map,
                new Spec(
                        BuiltinToolNames.UPDATE_RELATIONSHIP,
                        "更新伴身关系称呼或边界（须给 reason；至少改称呼或边界之一）。",
                        "{\"type\":\"object\",\"properties\":{\"preferredAddress\":{\"type\":\"string\"},\"boundaries\":{\"type\":\"string\"},\"reason\":{\"type\":\"string\"}},\"required\":[\"reason\"],\"additionalProperties\":false}",
                        Set.of(),
                        false,
                        () ->
                                new UpdateRelationshipToolAdapter(
                                        new SecretOnlyMemoryPolicy(), CompanionIdentity.YANHUO)));
        put(
                map,
                new Spec(
                        BuiltinToolNames.SEARCH_MEMORY,
                        "按关键词搜索已存 ACTIVE 记忆（claim/subjectKey 子串；无向量）。缺事实时再调，勿替代自动注入。",
                        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"string\"}},\"required\":[\"query\"],\"additionalProperties\":false}",
                        Set.of(),
                        false,
                        SearchMemoryToolAdapter::unavailable));
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
     * @param countsTowardDecisionBudget 是否计入决策次数；记忆/关系系统工具为 false
     * @param adapterFactory 每次登记新建 Adapter 实例
     */
    public record Spec(
            String name,
            String description,
            String parameterSchemaJson,
            Set<String> requiredCapabilities,
            boolean countsTowardDecisionBudget,
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
            return new PoolEntry(
                    name, description, List.copyOf(requiredCapabilities), countsTowardDecisionBudget);
        }
    }

    public record PoolEntry(
            String name,
            String description,
            List<String> requiredCapabilities,
            boolean countsTowardDecisionBudget) {

        public PoolEntry(String name, String description, List<String> requiredCapabilities) {
            this(name, description, requiredCapabilities, true);
        }
    }
}
