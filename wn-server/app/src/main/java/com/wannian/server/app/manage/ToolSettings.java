package com.wannian.server.app.manage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.app.persistence.SqliteConfig;
import com.wannian.server.app.tool.LocalHostCapabilityDetector;
import com.wannian.server.kernel.tool.BuiltinToolNames;
import com.wannian.server.kernel.tool.BuiltinToolPool;
import com.wannian.server.kernel.tool.BuiltinToolRegistrar;
import com.wannian.server.kernel.tool.FacetId;
import com.wannian.server.kernel.tool.HostCapabilitySet;
import com.wannian.server.kernel.tool.RoleId;
import com.wannian.server.kernel.tool.ToolBindingTable;
import com.wannian.server.kernel.tool.ToolCatalog;
import com.wannian.server.kernel.tool.ToolUsePolicy;
import com.wannian.server.kernel.tool.YanhuoToolBindings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据目录 {@code wannian.json} 的 {@code tools} 段：启用进目录名单 + 烟火三模式可见集。
 *
 * <p>系统 HostCapability 优先于用户勾选：不可用工具不能启用；PS 5/7 互斥。
 */
@Component
public class ToolSettings {

    static final String FILE_NAME = AgentBudgetSettings.FILE_NAME;
    private static final String TOOLS_KEY = "tools";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();
    private final ToolCatalog catalog;
    private final ToolBindingTable bindingTable;
    private final String httpReadUserAgent;
    private final HostCapabilitySet hostCapabilities;

    private Snapshot current;

    /** 测试与手动装配。 */
    public ToolSettings(String dataDir, ToolCatalog catalog, ToolBindingTable bindingTable)
            throws IOException {
        this(dataDir, catalog, bindingTable, "wannian-agent", LocalHostCapabilityDetector.detect());
    }

    /** 测试可注入假主机能力。 */
    public ToolSettings(
            String dataDir,
            ToolCatalog catalog,
            ToolBindingTable bindingTable,
            HostCapabilitySet hostCapabilities)
            throws IOException {
        this(dataDir, catalog, bindingTable, "wannian-agent", hostCapabilities);
    }

    @Autowired
    public ToolSettings(
            @Value("${wannian.data-dir:data}") String dataDir,
            ToolCatalog catalog,
            ToolBindingTable bindingTable,
            @Value("${wannian.http-read.user-agent:wannian-agent}") String httpReadUserAgent,
            HostCapabilitySet hostCapabilitySet)
            throws IOException {
        Path dir = SqliteConfig.resolveDataDir(dataDir);
        Files.createDirectories(dir);
        this.file = dir.resolve(FILE_NAME);
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.bindingTable = Objects.requireNonNull(bindingTable, "bindingTable");
        this.hostCapabilities = Objects.requireNonNull(hostCapabilitySet, "hostCapabilitySet");
        String ua = httpReadUserAgent == null ? "" : httpReadUserAgent.trim();
        this.httpReadUserAgent = ua.isEmpty() ? "wannian-agent" : ua;
        Snapshot seed = Snapshot.defaults(hostCapabilities);
        Snapshot loaded = loadOrCreate(file, seed, hostCapabilities);
        applyInMemory(loaded);
        applyToRuntime(loaded);
    }

    public HostCapabilitySet hostCapabilities() {
        return hostCapabilities;
    }

    public Snapshot snapshot() {
        lock.lock();
        try {
            return current;
        } finally {
            lock.unlock();
        }
    }

    public Snapshot update(List<String> enabled, FacetLists yanhuo) throws IOException {
        Snapshot next = Snapshot.validate(enabled, yanhuo, hostCapabilities, false);
        lock.lock();
        try {
            writeTools(file, next);
            applyInMemory(next);
            applyToRuntime(next);
            return next;
        } finally {
            lock.unlock();
        }
    }

    private void applyInMemory(Snapshot snapshot) {
        this.current = snapshot;
    }

    private void applyToRuntime(Snapshot snapshot) {
        BuiltinToolRegistrar.registerEnabled(
                catalog, new LinkedHashSet<>(snapshot.enabled()), httpReadUserAgent);
        YanhuoToolBindings.applyTo(
                bindingTable, snapshot.yanhuo().chat(), snapshot.yanhuo().work(), snapshot.yanhuo().research());
    }

    static Snapshot loadOrCreate(Path file, Snapshot seed, HostCapabilitySet host) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(host, "host");
        if (Files.isRegularFile(file)) {
            Snapshot existing = readTools(file, host);
            if (existing != null) {
                return existing;
            }
        }
        writeTools(file, seed);
        return seed;
    }

    static Snapshot readTools(Path file, HostCapabilitySet host) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JsonProcessingException ex) {
            throw new IOException("wannian.json 损坏，拒绝覆盖: " + file, ex);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("wannian.json 根节点不是对象，拒绝覆盖: " + file);
        }
        JsonNode tools = root.get(TOOLS_KEY);
        if (tools == null || !tools.isObject()) {
            return null;
        }
        try {
            List<String> enabled =
                    ensureListTools(migrateLegacyPowershellNames(readStringList(tools.get("enabled"))));
            JsonNode yanhuo = tools.get("yanhuo");
            if (yanhuo == null || !yanhuo.isObject()) {
                return null;
            }
            FacetLists facets =
                    new FacetLists(
                            ensureListTools(
                                    migrateLegacyPowershellNames(readStringList(yanhuo.get("chat")))),
                            ensureListTools(
                                    migrateLegacyPowershellNames(readStringList(yanhuo.get("work")))),
                            ensureListTools(
                                    migrateLegacyPowershellNames(
                                            readStringList(yanhuo.get("research")))));
            return Snapshot.validate(enabled, facets, host, true);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 旧单一 id → 展开为 _5+_7，再由 {@link ToolUsePolicy} 按本机互斥钳制。 */
    static List<String> migrateLegacyPowershellNames(List<String> names) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        boolean sawLegacy = false;
        for (String name : names) {
            if (BuiltinToolNames.POWERSHELL_RESOLVE_LEGACY.equals(name)) {
                sawLegacy = true;
                continue;
            }
            out.add(name);
        }
        if (sawLegacy) {
            out.add(BuiltinToolNames.POWERSHELL_RESOLVE_5);
            out.add(BuiltinToolNames.POWERSHELL_RESOLVE_7);
        }
        return List.copyOf(out);
    }

    /** 配置缺 list_tools 时补进名单头部（加载路径；用户仍可之后关掉）。 */
    static List<String> ensureListTools(List<String> names) {
        if (names.contains(BuiltinToolNames.LIST_TOOLS)) {
            return names;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(BuiltinToolNames.LIST_TOOLS);
        out.addAll(names);
        return List.copyOf(out);
    }

    static void writeTools(Path file, Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        ObjectNode root = objectRoot(file);
        ObjectNode tools = root.putObject(TOOLS_KEY);
        ArrayNode enabled = tools.putArray("enabled");
        for (String name : snapshot.enabled()) {
            enabled.add(name);
        }
        ObjectNode yanhuo = tools.putObject("yanhuo");
        writeStringList(yanhuo, "chat", snapshot.yanhuo().chat());
        writeStringList(yanhuo, "work", snapshot.yanhuo().work());
        writeStringList(yanhuo, "research", snapshot.yanhuo().research());
        Files.writeString(
                file,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static void writeStringList(ObjectNode parent, String key, List<String> values) {
        ArrayNode array = parent.putArray(key);
        for (String value : values) {
            array.add(value);
        }
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("须为字符串数组");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) {
                throw new IllegalArgumentException("数组元素须为字符串");
            }
            out.add(item.asText());
        }
        return List.copyOf(out);
    }

    private static ObjectNode objectRoot(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode existing = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (existing instanceof ObjectNode objectNode) {
                return objectNode;
            }
            throw new IOException("wannian.json 根节点不是对象，拒绝覆盖: " + file);
        } catch (JsonProcessingException ex) {
            throw new IOException("wannian.json 损坏，拒绝覆盖写入: " + file, ex);
        }
    }

    public record FacetLists(List<String> chat, List<String> work, List<String> research) {
        public FacetLists {
            Objects.requireNonNull(chat, "chat");
            Objects.requireNonNull(work, "work");
            Objects.requireNonNull(research, "research");
            chat = List.copyOf(chat);
            work = List.copyOf(work);
            research = List.copyOf(research);
        }
    }

    public record Snapshot(List<String> enabled, FacetLists yanhuo) {
        public Snapshot {
            Objects.requireNonNull(enabled, "enabled");
            Objects.requireNonNull(yanhuo, "yanhuo");
            enabled = List.copyOf(enabled);
        }

        static Snapshot defaults(HostCapabilitySet host) {
            Objects.requireNonNull(host, "host");
            List<String> enabled = ToolUsePolicy.defaultEnabled(host);
            ToolBindingTable seed = YanhuoToolBindings.create();
            return new Snapshot(
                    enabled,
                    new FacetLists(
                            ToolUsePolicy.defaultFacet(
                                    seed.find(RoleId.YANHUO, FacetId.CHAT).orElseThrow().toolNames(),
                                    host),
                            ToolUsePolicy.defaultFacet(
                                    seed.find(RoleId.YANHUO, FacetId.WORK).orElseThrow().toolNames(),
                                    host),
                            ToolUsePolicy.defaultFacet(
                                    seed.find(RoleId.YANHUO, FacetId.RESEARCH).orElseThrow().toolNames(),
                                    host)));
        }

        static Snapshot validate(
                List<String> enabledRaw, FacetLists yanhuoRaw, HostCapabilitySet host) {
            return validate(enabledRaw, yanhuoRaw, host, false);
        }

        static Snapshot validate(
                List<String> enabledRaw,
                FacetLists yanhuoRaw,
                HostCapabilitySet host,
                boolean lenientFacets) {
            Objects.requireNonNull(enabledRaw, "enabled");
            Objects.requireNonNull(yanhuoRaw, "yanhuo");
            Objects.requireNonNull(host, "host");
            for (String raw : enabledRaw) {
                if (raw == null || raw.isBlank()) {
                    throw new IllegalArgumentException("enabled 不得含空白名");
                }
                if (!BuiltinToolPool.contains(raw.trim())) {
                    throw new IllegalArgumentException("不在内置池: " + raw.trim());
                }
            }
            List<String> enabled = ToolUsePolicy.clampEnabled(enabledRaw, host);
            Set<String> enabledSet = Set.copyOf(enabled);
            FacetLists yanhuo =
                    new FacetLists(
                            normalizeFacet("chat", yanhuoRaw.chat(), enabledSet, lenientFacets),
                            normalizeFacet("work", yanhuoRaw.work(), enabledSet, lenientFacets),
                            normalizeFacet(
                                    "research", yanhuoRaw.research(), enabledSet, lenientFacets));
            return new Snapshot(enabled, yanhuo);
        }

        private static List<String> normalizeFacet(
                String facet, List<String> names, Set<String> enabled, boolean lenient) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (String raw : names) {
                if (raw == null || raw.isBlank()) {
                    throw new IllegalArgumentException(facet + " 不得含空白名");
                }
                String name = raw.trim();
                if (!BuiltinToolPool.contains(name)) {
                    throw new IllegalArgumentException(facet + " 含未知工具: " + name);
                }
                if (!enabled.contains(name)) {
                    if (lenient) {
                        continue;
                    }
                    throw new IllegalArgumentException(facet + " 含未启用工具: " + name);
                }
                out.add(name);
            }
            return List.copyOf(out);
        }
    }
}
