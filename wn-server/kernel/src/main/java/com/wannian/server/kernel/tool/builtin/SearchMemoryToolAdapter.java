package com.wannian.server.kernel.tool.builtin;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryDecay;
import com.wannian.server.kernel.memory.MemoryRecallTouch;
import com.wannian.server.kernel.memory.MemorySearchLimits;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.StoredMemoryRecord;
import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolJson;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code search_memory}：按关键词在 ACTIVE 记忆上子串搜索（无向量）。
 *
 * <p><b>行为（D+ 写死）</b>
 *
 * <ul>
 *   <li>范围：构造时绑定的伴身（烟火为 {@link CompanionIdentity#YANHUO}）+ {@link
 *       MemoryStore#listActive}。
 *   <li>匹配：{@code claim} 或 {@code subjectKey} 含 query（大小写不敏感）；空白 query →
 *       {@code TOOL_INVALID_ARGUMENTS}。
 *   <li>排序：匹配集上 {@link MemoryDecay#score} desc，同分 {@code createdAt} desc。
 *   <li>截断：默认/上限来自装配时注入的 {@link MemorySearchLimits}（yml）；显式 limit 钳制在 1..max。
 *   <li>只读：不写 claim/status；可选 best-effort {@link MemoryRecallTouch}（失败吞掉）。
 *   <li>未接线 Store / Limits → {@code TOOL_UNAVAILABLE}（禁止假成功）。
 * </ul>
 *
 * <p><b>依赖</b>：通过构造器注入 MemoryStore、Clock、MemorySearchLimits 和可选 MemoryRecallTouch；
 * 这些领域端口不进入通用 ToolAdapterRequest。
 */
public final class SearchMemoryToolAdapter implements ToolAdapter {

    private static final System.Logger LOG =
            System.getLogger(SearchMemoryToolAdapter.class.getName());

    private final CompanionIdentity companion;
    private final MemoryStore store;
    private final Clock clock;
    private final MemoryRecallTouch recallTouch;
    private final MemorySearchLimits limits;

    public SearchMemoryToolAdapter(
            CompanionIdentity companion,
            MemoryStore store,
            Clock clock,
            MemoryRecallTouch recallTouch,
            MemorySearchLimits limits) {
        this.companion = Objects.requireNonNull(companion, "companion");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recallTouch = recallTouch;
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    private SearchMemoryToolAdapter(CompanionIdentity companion) {
        this.companion = Objects.requireNonNull(companion, "companion");
        this.store = null;
        this.clock = null;
        this.recallTouch = null;
        this.limits = null;
    }

    /** 用于未提供 memory ports 的普通内核/管理配置场景，执行时明确返回不可用。 */
    public static SearchMemoryToolAdapter unavailable() {
        return new SearchMemoryToolAdapter(CompanionIdentity.YANHUO);
    }

    @Override
    public ToolAdapterResult execute(ToolAdapterRequest request) {
        Objects.requireNonNull(request, "request");
        if (store == null) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_UNAVAILABLE, "本回合未接线 MemoryStore", false);
        }
        try {
            Map<String, String> fields = ToolJson.parseFlatObject(request.argumentsJson());
            String query = ToolJson.requireString(fields, "query").trim();
            if (query.isEmpty()) {
                return new ToolAdapterResult.Failed(
                        ErrorCodes.TOOL_INVALID_ARGUMENTS, "query 不得空白", false);
            }
            int limit = limits.defaultLimit();
            var limitOpt = ToolJson.optionalString(fields, "limit");
            if (limitOpt.isPresent()) {
                limit = Integer.parseInt(limitOpt.get().trim());
            }
            int maxLimit = limits.maxLimit();
            if (limit < 1 || limit > maxLimit) {
                return new ToolAdapterResult.Failed(
                        ErrorCodes.TOOL_INVALID_ARGUMENTS,
                        "limit 须在 1.." + maxLimit,
                        false);
            }

            Instant now = clock.instant();
            String needle = query.toLowerCase(Locale.ROOT);
            List<Scored> matched = new ArrayList<>();
            for (StoredMemoryRecord row : store.listActive(companion)) {
                String claim = row.claim() == null ? "" : row.claim();
                String subject = row.subjectKey() == null ? "" : row.subjectKey();
                if (!claim.toLowerCase(Locale.ROOT).contains(needle)
                        && !subject.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                Duration age = Duration.between(row.createdAt(), now);
                if (age.isNegative()) {
                    age = Duration.ZERO;
                }
                matched.add(new Scored(row, MemoryDecay.score(row.importance(), age)));
            }
            matched.sort(
                    Comparator.comparingDouble(Scored::score)
                            .reversed()
                            .thenComparing(s -> s.row().createdAt(), Comparator.reverseOrder()));

            int n = Math.min(limit, matched.size());
            List<String> touched = new ArrayList<>(n);
            StringBuilder matchesJson = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                Scored s = matched.get(i);
                if (i > 0) {
                    matchesJson.append(',');
                }
                matchesJson.append(formatMatch(s));
                touched.add(s.row().id());
            }
            matchesJson.append(']');

            MemoryRecallTouch touch = recallTouch;
            if (touch != null && !touched.isEmpty()) {
                try {
                    touch.touchRecalled(touched, now);
                } catch (RuntimeException ex) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            () -> "search_memory touchRecalled 失败: " + ex.getMessage());
                }
            }

            return new ToolAdapterResult.Succeeded(
                    ToolJson.objectWithRaw(
                            Map.of(),
                            Map.of("count", Integer.toString(n), "matches", matchesJson.toString())));
        } catch (ToolJson.ToolJsonException | NumberFormatException ex) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_INVALID_ARGUMENTS, ex.getMessage(), false);
        }
    }

    private static String formatMatch(Scored s) {
        LinkedHashMap<String, String> strings = new LinkedHashMap<>();
        strings.put("id", s.row().id());
        strings.put("claim", s.row().claim());
        strings.put("subjectKey", s.row().subjectKey());
        LinkedHashMap<String, String> numbers = new LinkedHashMap<>();
        numbers.put("importance", String.format(Locale.ROOT, "%.3f", s.row().importance()));
        numbers.put("score", String.format(Locale.ROOT, "%.4f", s.score()));
        return ToolJson.objectWithRaw(strings, numbers);
    }

    private record Scored(StoredMemoryRecord row, double score) {}
}
