package com.wannian.server.app.manage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.app.persistence.SqliteConfig;
import com.wannian.server.kernel.agent.AgentBudget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据目录 {@code wannian.json} 里的 Agent 预算段。
 *
 * <p>同一 JSON 供后续管理页可改项共用；本类只读写 {@code agentBudget}，保留其它键。
 * {@code application.yml} / 环境变量只提供首次建文件时的种子。
 * kernel 不读配置；装配侧通过 {@link #createBudget(Instant)} 注入变量值。
 */
@Component
public class AgentBudgetSettings {

    static final String FILE_NAME = "wannian.json";
    static final String LEGACY_FILE_NAME = "agent-budget.properties";
    private static final String BUDGET_KEY = "agentBudget";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();

    private Snapshot current;

    public AgentBudgetSettings(
            @Value("${wannian.data-dir:data}") String dataDir,
            @Value("${wannian.agent.budget.max-model-decisions:3}") int seedMaxModelDecisions,
            @Value("${wannian.agent.budget.soft-deadline-seconds:15}") int seedSoftDeadlineSeconds,
            @Value("${wannian.agent.budget.hard-deadline-seconds:30}") int seedHardDeadlineSeconds)
            throws IOException {
        Path dir = SqliteConfig.resolveDataDir(dataDir);
        Files.createDirectories(dir);
        this.file = dir.resolve(FILE_NAME);
        Snapshot seed = Snapshot.validate(seedMaxModelDecisions, seedSoftDeadlineSeconds, seedHardDeadlineSeconds);
        Snapshot loaded = loadOrCreate(file, seed);
        apply(loaded);
    }

    /** 当前内存快照（供管理 API）。一次加锁读出三个预算字段。 */
    public Snapshot snapshot() {
        lock.lock();
        try {
            return current;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 用当前配置变量构造本轮 {@link AgentBudget}（新取消令牌；截止相对 {@code now}）。
     */
    public AgentBudget createBudget(Instant now) {
        Objects.requireNonNull(now, "now");
        Snapshot snap = snapshot();
        return AgentBudget.of(
                snap.maxModelDecisions(), snap.softDeadlineSeconds(), snap.hardDeadlineSeconds(), now);
    }

    /**
     * 校验并写入 {@code agentBudget}，同时更新内存变量。其它 JSON 键保持不动。
     *
     * @return 写入后的快照
     */
    public Snapshot update(int maxModelDecisions, int softDeadlineSeconds, int hardDeadlineSeconds)
            throws IOException {
        Snapshot next = Snapshot.validate(maxModelDecisions, softDeadlineSeconds, hardDeadlineSeconds);
        lock.lock();
        try {
            writeBudget(file, next);
            apply(next);
            return next;
        } finally {
            lock.unlock();
        }
    }

    static Snapshot loadOrCreate(Path file, Snapshot seed) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(seed, "seed");
        if (Files.isRegularFile(file)) {
            try {
                Snapshot existing = readBudget(file);
                if (existing != null) {
                    return existing;
                }
            } catch (IOException ex) {
                // 损坏 JSON：拒绝覆盖，向上抛出受控 IO。
                throw ex;
            }
            // 文件存在且可解析但缺/非法 agentBudget：走 seed/legacy，objectRoot 保留其它键。
        }
        Path legacy = file.resolveSibling(LEGACY_FILE_NAME);
        Snapshot fromLegacy = readLegacyProperties(legacy);
        Snapshot initial = fromLegacy != null ? fromLegacy : seed;
        writeBudget(file, initial);
        return initial;
    }

    static Snapshot readBudget(Path file) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JsonProcessingException ex) {
            throw new IOException("wannian.json 损坏，拒绝覆盖: " + file, ex);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("wannian.json 根节点不是对象，拒绝覆盖: " + file);
        }
        JsonNode budget = root.get(BUDGET_KEY);
        if (budget == null || !budget.isObject()) {
            return null;
        }
        JsonNode max = budget.get("maxModelDecisions");
        JsonNode soft = budget.get("softDeadlineSeconds");
        JsonNode hard = budget.get("hardDeadlineSeconds");
        if (max == null || soft == null || hard == null || !max.isNumber() || !soft.isNumber() || !hard.isNumber()) {
            return null;
        }
        try {
            return Snapshot.validate(max.asInt(), soft.asInt(), hard.asInt());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static void writeBudget(Path file, Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        ObjectNode root = objectRoot(file);
        ObjectNode budget = root.putObject(BUDGET_KEY);
        budget.put("maxModelDecisions", snapshot.maxModelDecisions());
        budget.put("softDeadlineSeconds", snapshot.softDeadlineSeconds());
        budget.put("hardDeadlineSeconds", snapshot.hardDeadlineSeconds());
        Files.writeString(
                file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator(),
                StandardCharsets.UTF_8);
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
            // 损坏文件不得用空对象覆盖，以免丢掉同文件其它键。
            throw new IOException("wannian.json 损坏，拒绝覆盖写入: " + file, ex);
        }
    }

    private static Snapshot readLegacyProperties(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            map.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        if (!map.containsKey("maxModelDecisions")
                || !map.containsKey("softDeadlineSeconds")
                || !map.containsKey("hardDeadlineSeconds")) {
            return null;
        }
        try {
            return Snapshot.validate(
                    Integer.parseInt(map.get("maxModelDecisions")),
                    Integer.parseInt(map.get("softDeadlineSeconds")),
                    Integer.parseInt(map.get("hardDeadlineSeconds")));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void apply(Snapshot snapshot) {
        this.current = snapshot;
    }

    /**
     * @param maxModelDecisions 允许的 decide 次数上界；须为正
     * @param softDeadlineSeconds 软截止秒数；须为正
     * @param hardDeadlineSeconds 硬截止秒数；须 ≥ 软截止
     */
    public record Snapshot(int maxModelDecisions, int softDeadlineSeconds, int hardDeadlineSeconds) {

        static Snapshot validate(int maxModelDecisions, int softDeadlineSeconds, int hardDeadlineSeconds) {
            if (maxModelDecisions <= 0) {
                throw new IllegalArgumentException("maxModelDecisions 须为正");
            }
            if (softDeadlineSeconds <= 0) {
                throw new IllegalArgumentException("softDeadlineSeconds 须为正");
            }
            if (hardDeadlineSeconds < softDeadlineSeconds) {
                throw new IllegalArgumentException("hardDeadlineSeconds 不得小于 softDeadlineSeconds");
            }
            return new Snapshot(maxModelDecisions, softDeadlineSeconds, hardDeadlineSeconds);
        }
    }
}
