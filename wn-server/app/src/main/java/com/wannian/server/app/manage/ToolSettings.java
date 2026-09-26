package com.wannian.server.app.manage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.app.persistence.SqliteConfig;
import com.wannian.server.app.tool.LocalHostCapabilityDetector;
import com.wannian.server.kernel.tool.BuiltinToolPool;
import com.wannian.server.kernel.tool.BuiltinToolRegistrar;
import com.wannian.server.app.tool.ToolRegistrationExtension;
import com.wannian.server.kernel.tool.FacetId;
import com.wannian.server.kernel.tool.HostCapabilitySet;
import com.wannian.server.kernel.tool.RoleId;
import com.wannian.server.kernel.tool.ToolBindingTable;
import com.wannian.server.kernel.tool.ToolCatalog;
import com.wannian.server.kernel.tool.ToolUsePolicy;
import com.wannian.server.kernel.tool.YanhuoToolBindings;
import com.wannian.server.kernel.tool.builtin.LoadSkillToolAdapter;
import com.wannian.server.kernel.tool.builtin.SearchMemoryToolAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据目录 {@code wannian.json} 的 {@code tools} 段：{@code byName} 三态 + 烟火三模式可见集。
 *
 * <p>系统 HostCapability 优先于用户勾选：不可用工具不能有效启用；PS 5/7 互斥。
 * 无 {@code byName} 视为无配置（不兼容旧 {@code enabled[]}）。
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
    private final List<ToolRegistrationExtension> registrationExtensions;
    private final HostCapabilitySet hostCapabilities;
    private final SearchMemoryToolAdapter searchMemoryAdapter;
    private final LoadSkillToolAdapter loadSkillAdapter;

    private Snapshot current;

    /** 测试与手动装配。 */
    public ToolSettings(String dataDir, ToolCatalog catalog, ToolBindingTable bindingTable)
            throws IOException {
        this(
                dataDir,
                catalog,
                bindingTable,
                "wannian-agent",
                LocalHostCapabilityDetector.detect(),
                SearchMemoryToolAdapter.unavailable(),
                LoadSkillToolAdapter.unavailable());
    }

    /** 测试可注入假主机能力。 */
    public ToolSettings(
            String dataDir,
            ToolCatalog catalog,
            ToolBindingTable bindingTable,
            HostCapabilitySet hostCapabilities)
            throws IOException {
        this(
                dataDir,
                catalog,
                bindingTable,
                "wannian-agent",
                hostCapabilities,
                SearchMemoryToolAdapter.unavailable(),
                LoadSkillToolAdapter.unavailable());
    }

    /** Spring 装配与测试共用完整构造。 */
    public ToolSettings(
            @Value("${wannian.data-dir:data}") String dataDir,
            ToolCatalog catalog,
            ToolBindingTable bindingTable,
            @Value("${wannian.http-read.user-agent:wannian-agent}") String httpReadUserAgent,
            HostCapabilitySet hostCapabilities,
            SearchMemoryToolAdapter searchMemoryAdapter,
            LoadSkillToolAdapter loadSkillAdapter)
            throws IOException {
        this(dataDir,catalog,bindingTable,httpReadUserAgent,hostCapabilities,searchMemoryAdapter,loadSkillAdapter,List.of());
    }

    @Autowired
    public ToolSettings(
            @Value("${wannian.data-dir:data}") String dataDir,
            ToolCatalog catalog,
            ToolBindingTable bindingTable,
            @Value("${wannian.http-read.user-agent:wannian-agent}") String httpReadUserAgent,
            HostCapabilitySet hostCapabilities,
            SearchMemoryToolAdapter searchMemoryAdapter,
            LoadSkillToolAdapter loadSkillAdapter,
            List<ToolRegistrationExtension> registrationExtensions)
            throws IOException {
        Path dir = SqliteConfig.resolveDataDir(dataDir);
        Files.createDirectories(dir);
        this.file = dir.resolve(FILE_NAME);
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.bindingTable = Objects.requireNonNull(bindingTable, "bindingTable");
        this.hostCapabilities = Objects.requireNonNull(hostCapabilities, "hostCapabilities");
        this.searchMemoryAdapter = Objects.requireNonNull(searchMemoryAdapter, "searchMemoryAdapter");
        this.loadSkillAdapter = Objects.requireNonNull(loadSkillAdapter, "loadSkillAdapter");
        this.registrationExtensions=registrationExtensions==null?List.of():List.copyOf(registrationExtensions);
        String ua = httpReadUserAgent == null ? "" : httpReadUserAgent.trim();
        this.httpReadUserAgent = ua.isEmpty() ? "wannian-agent" : ua;
        Snapshot seed = defaultsWithExtensions(hostCapabilities);
        Snapshot loaded = loadOrCreateConfigured(file, seed, hostCapabilities);
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

    public Snapshot update(Map<String, String> byName, FacetLists yanhuo) throws IOException {
        Snapshot next = validateConfigured(byName, yanhuo, false);
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
                catalog,
                snapshot.enabled().stream().filter(BuiltinToolPool::contains).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                httpReadUserAgent,
                searchMemoryAdapter,
                loadSkillAdapter);
        YanhuoToolBindings.applyTo(
                bindingTable, snapshot.yanhuo().chat(), snapshot.yanhuo().work(), snapshot.yanhuo().research());
        Set<String> writeNames = new HashSet<>();
        for (ToolRegistrationExtension extension : registrationExtensions) {
            writeNames.addAll(extension.explicitIntentWriteNames());
            for (var registration : extension.registrations()) {
                var effective = writeNames.contains(registration.toolName())
                        ? new com.wannian.server.kernel.tool.ToolRegistration(
                                registration.toolName(), registration.description(), registration.parameters(),
                                registration.requiredCapabilities(), request -> {
                                    if (request.pending() == null || !com.wannian.server.app.tool.ExplicitUserIntent.allowsWrite(request.pending().userMessage())) {
                                        return new com.wannian.server.kernel.tool.ToolAdapterResult.Failed(
                                                com.wannian.server.kernel.error.ErrorCodes.UNSUPPORTED_EXTENSION,
                                                "写入工具需要用户在本轮明确要求导入、激活或切换。", false);
                                    }
                                    return registration.executor().execute(request);
                                }, registration.countsTowardDecisionBudget())
                        : registration;
                var result = catalog.register(effective);
                if (result instanceof com.wannian.server.kernel.tool.RegisterToolResult.Rejected rejected) {
                    throw new IllegalStateException("扩展工具登记失败: " + rejected.code() + " " + rejected.message());
                }
            }
        }
        // Extension visibility comes only from explicit facet configuration in the snapshot.
    }

    public List<com.wannian.server.kernel.tool.ToolRegistration> extensionToolRegistrations() {
        return registrationExtensions.stream().flatMap(extension->extension.registrations().stream()).toList();
    }

    private Snapshot defaultsWithExtensions(HostCapabilitySet host) {
        Snapshot base=Snapshot.defaults(host);
        LinkedHashMap<String,String> states=new LinkedHashMap<>(base.byName());
        LinkedHashSet<String> readOnly=extensionReadOnlyNames();
        LinkedHashSet<String> defaultWrites=extensionDefaultEnabledWriteNames();
        LinkedHashSet<String> enabled=new LinkedHashSet<>(base.enabled());
        for(var registration:extensionToolRegistrations()) {
            String name=registration.toolName();
            boolean visibleByDefault=(readOnly.contains(name)||defaultWrites.contains(name))&&host.containsAll(registration.requiredCapabilities());
            states.put(name,visibleByDefault?"on":"off");
            if(visibleByDefault) enabled.add(name);
        }
        readOnly.addAll(defaultWrites);
        FacetLists facets=new FacetLists(append(base.yanhuo().chat(),enabled,readOnly),append(base.yanhuo().work(),enabled,readOnly),append(base.yanhuo().research(),enabled,readOnly));
        return new Snapshot(states,List.copyOf(enabled),facets);
    }

    private Snapshot validateConfigured(Map<String,String> raw,FacetLists facets,boolean lenient) {
        Set<String> extensionNames=extensionRegistrations().keySet();
        LinkedHashMap<String,String> builtins=new LinkedHashMap<>();
        LinkedHashMap<String,String> extensions=new LinkedHashMap<>();
        for(var entry:raw.entrySet()) {
            if(BuiltinToolPool.contains(entry.getKey())) builtins.put(entry.getKey(),entry.getValue());
            else if(extensionNames.contains(entry.getKey())) extensions.put(entry.getKey(),entry.getValue());
            else throw new IllegalArgumentException("不在内置池或扩展目录: "+entry.getKey());
        }
        Map<String,String> defaults=defaultsWithExtensions(hostCapabilities).byName();
        for(String name:extensionNames) {
            String state=extensions.getOrDefault(name,defaults.get(name));
            if(state==null)state="off";
            state=state.trim().toLowerCase(java.util.Locale.ROOT);
            if(!state.equals("on")&&!state.equals("off")) {
                if(!lenient)throw new IllegalArgumentException("扩展工具状态仅支持 on/off: "+name);
                state=defaults.getOrDefault(name,"off");
            }
            extensions.put(name,state);
        }
        FacetLists builtinFacets=new FacetLists(onlyBuiltins(facets.chat()),onlyBuiltins(facets.work()),onlyBuiltins(facets.research()));
        Snapshot base=Snapshot.validate(builtins,builtinFacets,hostCapabilities,lenient);
        Map<String,com.wannian.server.kernel.tool.ToolRegistration> registrations=extensionRegistrations();
        LinkedHashSet<String> enabled=new LinkedHashSet<>(base.enabled());
        for(var entry:extensions.entrySet()) if("on".equals(entry.getValue())&&hostCapabilities.containsAll(registrations.get(entry.getKey()).requiredCapabilities())) enabled.add(entry.getKey());
        // Older files predate extension names entirely. Seed only their declared read-only defaults;
        // explicit extension states/facets in newer files remain user-controlled.
        LinkedHashSet<String> legacyReadOnlyDefaults=new LinkedHashSet<>();
        if(lenient) {
            LinkedHashSet<String> defaultFacetExtensions=extensionReadOnlyNames();
            defaultFacetExtensions.addAll(extensionDefaultEnabledWriteNames());
            for(String name:defaultFacetExtensions)if(!raw.containsKey(name)&&enabled.contains(name))legacyReadOnlyDefaults.add(name);
        }
        FacetLists merged=new FacetLists(mergeExtensions(base.yanhuo().chat(),facets.chat(),enabled,extensionNames,legacyReadOnlyDefaults,lenient),
                mergeExtensions(base.yanhuo().work(),facets.work(),enabled,extensionNames,legacyReadOnlyDefaults,lenient),
                mergeExtensions(base.yanhuo().research(),facets.research(),enabled,extensionNames,legacyReadOnlyDefaults,lenient));
        LinkedHashMap<String,String> all=new LinkedHashMap<>(base.byName());all.putAll(extensions);
        return new Snapshot(all,List.copyOf(enabled),merged);
    }

    private List<String> mergeExtensions(List<String> base,List<String> requested,Set<String> enabled,Set<String> extensionNames,Set<String> legacyReadOnlyDefaults,boolean lenient) {
        LinkedHashSet<String> out=new LinkedHashSet<>(base);
        for(String name:legacyReadOnlyDefaults)if(enabled.contains(name))out.add(name);
        for(String name:requested) if(extensionNames.contains(name)) {
            if(enabled.contains(name)) out.add(name);
            else if(!lenient)throw new IllegalArgumentException("扩展工具未启用或主机能力不可用: "+name);
        }
        return List.copyOf(out);
    }
    private static List<String> onlyBuiltins(List<String> names){return names.stream().filter(BuiltinToolPool::contains).toList();}
    private static List<String> append(List<String> base,Set<String> enabled,Set<String> extras){LinkedHashSet<String> out=new LinkedHashSet<>(base);for(String n:extras)if(enabled.contains(n))out.add(n);return List.copyOf(out);}
    private LinkedHashSet<String> extensionReadOnlyNames(){
        LinkedHashSet<String> names=new LinkedHashSet<>();Map<String,com.wannian.server.kernel.tool.ToolRegistration> registrations=extensionRegistrations();
        for(var extension:registrationExtensions)for(String name:extension.defaultVisibleReadOnlyNames()) {
            if(!registrations.containsKey(name))throw new IllegalStateException("默认可见扩展工具未注册: "+name);
            if(extension.explicitIntentWriteNames().contains(name))throw new IllegalStateException("写工具不能声明为只读默认可见: "+name);
            names.add(name);
        }
        return names;
    }
    private LinkedHashSet<String> extensionDefaultEnabledWriteNames(){
        LinkedHashSet<String> names=new LinkedHashSet<>();
        for(var extension:registrationExtensions) {
            Set<String> writes=extension.explicitIntentWriteNames();
            for(String name:extension.defaultEnabledExplicitIntentWriteNames()) {
                if(!writes.contains(name))throw new IllegalStateException("默认开启的扩展写工具必须声明 explicitIntentWriteNames: "+name);
                if(!extensionRegistrations().containsKey(name))throw new IllegalStateException("默认开启的扩展写工具未注册: "+name);
                names.add(name);
            }
        }
        return names;
    }
    private Map<String,com.wannian.server.kernel.tool.ToolRegistration> extensionRegistrations(){
        LinkedHashMap<String,com.wannian.server.kernel.tool.ToolRegistration> out=new LinkedHashMap<>();
        for(var extension:registrationExtensions)for(var registration:extension.registrations())if(out.put(registration.toolName(),registration)!=null)throw new IllegalStateException("重复扩展工具名: "+registration.toolName());
        return Map.copyOf(out);
    }
    private Snapshot loadOrCreateConfigured(Path path,Snapshot seed,HostCapabilitySet host)throws IOException{
        if(Files.isRegularFile(path)){LoadOutcome outcome=readConfiguredOutcome(path,host);if(outcome!=null){if(outcome.dirty())writeTools(path,outcome.snapshot());return outcome.snapshot();}}
        writeTools(path,seed);return seed;
    }
    private LoadOutcome readConfiguredOutcome(Path path,HostCapabilitySet host)throws IOException{
        JsonNode root;
        try{root=MAPPER.readTree(Files.readString(path,StandardCharsets.UTF_8));}catch(JsonProcessingException ex){throw new IOException("wannian.json 损坏，拒绝覆盖: "+path,ex);}
        if(root==null||!root.isObject())throw new IOException("wannian.json 根节点不是对象，拒绝覆盖: "+path);
        JsonNode tools=root.get(TOOLS_KEY);if(tools==null||!tools.isObject())return null;
        JsonNode by=tools.get("byName"), y=tools.get("yanhuo");if(by==null||!by.isObject()||y==null||!y.isObject())return null;
        try{
            Map<String,String> raw=readByName(by);FacetLists facets=new FacetLists(readStringList(y.get("chat")),readStringList(y.get("work")),readStringList(y.get("research")));
            Snapshot normalized=validateConfigured(raw,facets,true);
            return new LoadOutcome(normalized,diskLagsNormalized(raw,facets,normalized));
        }catch(IllegalArgumentException ex){return null;}
    }

    static Snapshot loadOrCreate(Path file, Snapshot seed, HostCapabilitySet host) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(host, "host");
        if (Files.isRegularFile(file)) {
            LoadOutcome outcome = readToolsOutcome(file, host);
            if (outcome != null) {
                if (outcome.dirty()) {
                    writeTools(file, outcome.snapshot());
                }
                return outcome.snapshot();
            }
        }
        writeTools(file, seed);
        return seed;
    }

    /**
     * 读取 tools 段。无 {@code byName} 对象 → 返回 null（走 defaults 重写；不读旧 {@code enabled[]}）。
     */
    static Snapshot readTools(Path file, HostCapabilitySet host) throws IOException {
        LoadOutcome outcome = readToolsOutcome(file, host);
        return outcome == null ? null : outcome.snapshot();
    }

    /**
     * 读盘并规范化；若磁盘缺新锁死工具或 facet 未并入锁死名，标记 dirty 供启动写回。
     */
    static LoadOutcome readToolsOutcome(Path file, HostCapabilitySet host) throws IOException {
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
        JsonNode byNameNode = tools.get("byName");
        if (byNameNode == null || !byNameNode.isObject()) {
            return null;
        }
        try {
            Map<String, String> byNameRaw = readByName(byNameNode);
            JsonNode yanhuo = tools.get("yanhuo");
            if (yanhuo == null || !yanhuo.isObject()) {
                return null;
            }
            FacetLists facetsRaw =
                    new FacetLists(
                            readStringList(yanhuo.get("chat")),
                            readStringList(yanhuo.get("work")),
                            readStringList(yanhuo.get("research")));
            Snapshot normalized = Snapshot.validate(byNameRaw, facetsRaw, host, true);
            boolean dirty =
                    diskLagsNormalized(byNameRaw, facetsRaw, normalized);
            return new LoadOutcome(normalized, dirty);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 磁盘相对规范化快照落后：缺池内新键、锁死态未写回、或三面未含已启用锁死名。
     */
    static boolean diskLagsNormalized(
            Map<String, String> byNameRaw, FacetLists facetsRaw, Snapshot normalized) {
        Objects.requireNonNull(byNameRaw, "byNameRaw");
        Objects.requireNonNull(facetsRaw, "facetsRaw");
        Objects.requireNonNull(normalized, "normalized");
        for (Map.Entry<String, String> entry : normalized.byName().entrySet()) {
            String disk = byNameRaw.get(entry.getKey());
            if (disk == null || !disk.equals(entry.getValue())) {
                return true;
            }
        }
        return !facetsRaw.chat().equals(normalized.yanhuo().chat())
                || !facetsRaw.work().equals(normalized.yanhuo().work())
                || !facetsRaw.research().equals(normalized.yanhuo().research());
    }

    record LoadOutcome(Snapshot snapshot, boolean dirty) {
        LoadOutcome {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    static void writeTools(Path file, Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        ObjectNode root = objectRoot(file);
        ObjectNode tools = root.putObject(TOOLS_KEY);
        ObjectNode byName = tools.putObject("byName");
        for (Map.Entry<String, String> entry : snapshot.byName().entrySet()) {
            byName.put(entry.getKey(), entry.getValue());
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

    private static Map<String, String> readByName(JsonNode node) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode value = entry.getValue();
            if (value == null || !value.isTextual()) {
                throw new IllegalArgumentException("byName 值须为字符串");
            }
            out.put(entry.getKey(), value.asText());
        }
        return Map.copyOf(out);
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

    /**
     * @param byName 池内每名的存盘态（{@code locked}/{@code on}/{@code off}）
     * @param enabled 本机有效开启名（由 byName + host 派生，供 {@code registerEnabled}）
     * @param yanhuo 三模式可见集（须含锁死名）
     */
    public record Snapshot(Map<String, String> byName, List<String> enabled, FacetLists yanhuo) {
        public Snapshot {
            Objects.requireNonNull(byName, "byName");
            Objects.requireNonNull(enabled, "enabled");
            Objects.requireNonNull(yanhuo, "yanhuo");
            byName = Map.copyOf(byName);
            enabled = List.copyOf(enabled);
        }

        static Snapshot defaults(HostCapabilitySet host) {
            Objects.requireNonNull(host, "host");
            Map<String, String> byName =
                    ToolUsePolicy.normalizeByName(ToolUsePolicy.defaultByName(), host);
            List<String> enabled = ToolUsePolicy.enabledFromByName(byName, host);
            ToolBindingTable seed = YanhuoToolBindings.create();
            return new Snapshot(
                    byName,
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
                Map<String, String> byNameRaw, FacetLists yanhuoRaw, HostCapabilitySet host) {
            return validate(byNameRaw, yanhuoRaw, host, false);
        }

        static Snapshot validate(
                Map<String, String> byNameRaw,
                FacetLists yanhuoRaw,
                HostCapabilitySet host,
                boolean lenientFacets) {
            Objects.requireNonNull(byNameRaw, "byName");
            Objects.requireNonNull(yanhuoRaw, "yanhuo");
            Objects.requireNonNull(host, "host");
            Map<String, String> byName = ToolUsePolicy.normalizeByName(byNameRaw, host);
            List<String> enabled = ToolUsePolicy.enabledFromByName(byName, host);
            Set<String> enabledSet = Set.copyOf(enabled);
            FacetLists yanhuo =
                    new FacetLists(
                            normalizeFacet("chat", yanhuoRaw.chat(), enabledSet, host, lenientFacets),
                            normalizeFacet("work", yanhuoRaw.work(), enabledSet, host, lenientFacets),
                            normalizeFacet(
                                    "research", yanhuoRaw.research(), enabledSet, host, lenientFacets));
            return new Snapshot(byName, enabled, yanhuo);
        }

        private static List<String> normalizeFacet(
                String facet,
                List<String> names,
                Set<String> enabled,
                HostCapabilitySet host,
                boolean lenient) {
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
            // 锁死名强制并入（本机可用且已在 enabled）
            return ToolUsePolicy.clampFacet(List.copyOf(out), enabled, host);
        }
    }
}
