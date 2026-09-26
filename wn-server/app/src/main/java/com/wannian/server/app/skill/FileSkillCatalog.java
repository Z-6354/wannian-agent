package com.wannian.server.app.skill;

import com.wannian.server.app.prompt.FilePromptLayerStore;
import com.wannian.server.kernel.skill.SkillCatalog;
import com.wannian.server.kernel.skill.SkillSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 扫 skills 目录下各子目录的 SKILL.md，解析简易 frontmatter；按 mtime 缓存。
 *
 * <p>索引条数/描述/整段字符有界；{@code byId} 保留全部已授权包，超索引上限仍可 {@code load_skill}。
 */
public final class FileSkillCatalog implements SkillCatalog {

    private static final System.Logger LOG = System.getLogger(FileSkillCatalog.class.getName());

    private static final String INDEX_HEADER =
            "可用 Skill（按需调用 load_skill 读取正文；勿臆造未列出的 id）：";

    private final Path skillsDir;
    private final int maxEntries;
    private final int descCharBudget;
    private final int bodyCharBudget;
    private final int indexCharBudget;
    /** 空集 = 盘上安全 id 全部授权；非空 = 仅白名单。 */
    private final Set<String> allowedIds;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Cache cache = Cache.empty();

    public FileSkillCatalog(
            Path skillsDir, int maxEntries, int descCharBudget, int bodyCharBudget) {
        this(skillsDir, maxEntries, descCharBudget, bodyCharBudget, 3000, Set.of());
    }

    public FileSkillCatalog(
            Path skillsDir,
            int maxEntries,
            int descCharBudget,
            int bodyCharBudget,
            int indexCharBudget,
            Set<String> allowedIds) {
        this.skillsDir = Objects.requireNonNull(skillsDir, "skillsDir");
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries 须 ≥ 1");
        }
        if (descCharBudget < 16 || bodyCharBudget < 64 || indexCharBudget < 64) {
            throw new IllegalArgumentException("Skill 截断预算过小");
        }
        this.maxEntries = maxEntries;
        this.descCharBudget = descCharBudget;
        this.bodyCharBudget = bodyCharBudget;
        this.indexCharBudget = indexCharBudget;
        this.allowedIds = normalizeAllowed(allowedIds);
    }

    private static Set<String> normalizeAllowed(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : raw) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = id.trim();
            if (isSafeSkillId(trimmed)) {
                out.add(trimmed);
            }
        }
        return Set.copyOf(out);
    }

    /** 解析 {@code a,b,c} 或空白（空白 = 不限制）。 */
    public static Set<String> parseAllowedIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String id = part.trim();
            if (isSafeSkillId(id)) {
                out.add(id);
            }
        }
        return Set.copyOf(out);
    }

    private boolean isAuthorized(String id) {
        return allowedIds.isEmpty() || allowedIds.contains(id);
    }

    @Override
    public List<SkillSummary> listSummaries() {
        return refresh().summaries;
    }

    @Override
    public Optional<String> loadBody(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return Optional.empty();
        }
        String id = skillId.trim();
        if (!isAuthorized(id)) {
            return Optional.empty();
        }
        Cache snap = refresh();
        ParsedSkill row = snap.byId.get(id);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(row.body());
    }

    @Override
    public String indexPromptText() {
        List<SkillSummary> rows = listSummaries();
        if (rows.isEmpty()) {
            return "";
        }
        if (INDEX_HEADER.length() > indexCharBudget) {
            return "";
        }
        StringBuilder out = new StringBuilder(INDEX_HEADER);
        for (SkillSummary row : rows) {
            String line = "\n- " + row.id() + ": " + row.description();
            if (out.length() + line.length() > indexCharBudget) {
                break;
            }
            out.append(line);
        }
        return out.toString();
    }

    private Cache refresh() {
        lock.lock();
        try {
            long stamp = stamp();
            Cache current = cache;
            if (current.stamp == stamp) {
                return current;
            }
            Cache next = scan();
            cache = next;
            return next;
        } finally {
            lock.unlock();
        }
    }

    private long stamp() {
        if (!Files.isDirectory(skillsDir)) {
            return 0L;
        }
        long s = 0;
        int count = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(skillsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Path skill = dir.resolve("SKILL.md");
                if (Files.isRegularFile(skill)) {
                    count++;
                    s = 31 * s + Files.getLastModifiedTime(skill).toMillis();
                    s = 31 * s + Files.size(skill);
                    s = 31 * s + dir.getFileName().toString().hashCode();
                }
            }
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, () -> "扫 skills mtime 失败: " + ex.getMessage());
        }
        s = 31 * s + count;
        s = 31 * s + allowedIds.hashCode();
        return s;
    }

    private Cache scan() {
        LinkedHashMap<String, ParsedSkill> byId = new LinkedHashMap<>();
        if (!Files.isDirectory(skillsDir)) {
            return new Cache(0L, List.of(), Map.of());
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
            for (Path dir : stream) {
                if (Files.isDirectory(dir)) {
                    dirs.add(dir);
                }
            }
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, () -> "列 skills 失败: " + ex.getMessage());
            return new Cache(stamp(), List.of(), Map.of());
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        List<SkillSummary> summaries = new ArrayList<>();
        for (Path dir : dirs) {
            String id = dir.getFileName().toString();
            if (!isSafeSkillId(id) || !isAuthorized(id)) {
                continue;
            }
            Path file = dir.resolve("SKILL.md");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                ParsedSkill parsed = parse(id, raw);
                byId.put(id, parsed);
                if (summaries.size() < maxEntries) {
                    summaries.add(
                            new SkillSummary(
                                    id,
                                    parsed.name(),
                                    FilePromptLayerStore.clip(parsed.description(), descCharBudget),
                                    parsed.version()));
                }
            } catch (IOException | RuntimeException ex) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () -> "解析 Skill 失败: " + file + " " + ex.getMessage());
            }
        }
        return new Cache(stamp(), List.copyOf(summaries), Map.copyOf(byId));
    }

    static boolean isSafeSkillId(String id) {
        if (id == null || id.isBlank() || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || c == '-'
                    || c == '_')) {
                return false;
            }
        }
        return true;
    }

    ParsedSkill parse(String id, String raw) {
        FrontMatter fm = FrontMatter.parse(raw);
        String name = fm.fields.getOrDefault("name", id);
        String description = fm.fields.getOrDefault("description", name);
        String version = fm.fields.getOrDefault("version", "");
        String body = FilePromptLayerStore.clip(fm.body.strip(), bodyCharBudget);
        if (body.isEmpty()) {
            body = "(空 Skill 正文)";
        }
        return new ParsedSkill(name, description, version, body);
    }

    private record ParsedSkill(String name, String description, String version, String body) {}

    private record Cache(long stamp, List<SkillSummary> summaries, Map<String, ParsedSkill> byId) {
        static Cache empty() {
            return new Cache(Long.MIN_VALUE, List.of(), Map.of());
        }
    }

    /** 极简 YAML-like frontmatter：首段 {@code ---} … {@code ---}。 */
    static final class FrontMatter {
        final Map<String, String> fields;
        final String body;

        FrontMatter(Map<String, String> fields, String body) {
            this.fields = fields;
            this.body = body;
        }

        static FrontMatter parse(String raw) {
            if (raw == null) {
                return new FrontMatter(Map.of(), "");
            }
            String text = raw.strip();
            if (!text.startsWith("---")) {
                return new FrontMatter(Map.of(), text);
            }
            int firstNl = text.indexOf('\n');
            if (firstNl < 0) {
                return new FrontMatter(Map.of(), text);
            }
            int end = text.indexOf("\n---", firstNl);
            if (end < 0) {
                return new FrontMatter(Map.of(), text);
            }
            String header = text.substring(firstNl + 1, end);
            String rest = text.substring(end + 4).strip();
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            for (String line : header.split("\\R")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int colon = t.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = t.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = t.substring(colon + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                fields.put(key, value);
            }
            return new FrontMatter(Map.copyOf(fields), rest);
        }
    }
}
