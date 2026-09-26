package com.wannian.server.app.persona.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 确定性全书分章、命中计数与预算内取样。输入按 Unicode 码点偏移计量。 */
final class PersonaTextScanner {
    static final String VERSION = "chapter-stratified-v2";
    private final int windowWidth;
    private static final int MAX_WINDOWS = 96;
    private static final int SECOND_WINDOW_MIN_CHAPTER = 4500;
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s*(?:第[〇零一二三四五六七八九十百千万0-9]+章[^\\n]*|Chapter\\s+\\d+[^\\n]*|\\d{1,4}[、.．][^\\n]+)\\s*$", Pattern.CASE_INSENSITIVE);
    record Chapter(int number, int start, int end, int score) {}
    record Window(int chapter, int start, int end) {}
    record Scan(List<Chapter> chapters, List<Window> windows, int matched, int inputChars) {}

    PersonaTextScanner() {
        this(com.wannian.server.app.persona.PersonaOverlayLimits.DEFAULT.importWindowChars());
    }

    PersonaTextScanner(int windowWidth) {
        if (windowWidth < 256) {
            throw new IllegalArgumentException("windowWidth 须 ≥ 256");
        }
        this.windowWidth = windowWidth;
    }

    Scan scan(String text, String hint) {
        int[] cps = text.codePoints().toArray();
        String normalized = new String(cps, 0, cps.length);
        var matcher = HEADING.matcher(normalized);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        boolean firstHeading=true;
        while (matcher.find()) {
            if(firstHeading) firstHeading=false;
            else starts.add(normalized.codePointCount(0, matcher.start()));
        }
        starts.add(cps.length);
        String target = hint == null ? "" : hint.strip();
        String alias = target.codePointCount(0,target.length()) >= 3 ? target.substring(target.offsetByCodePoints(0,target.codePointCount(0,target.length())-2)) : "";
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < starts.size() - 1; i++) {
            int a = starts.get(i), b = starts.get(i + 1);
            if (b <= a) continue;
            String section = new String(cps, a, b - a);
            int hits = target.isEmpty() ? 0 : count(section, target);
            if (!alias.isEmpty()) hits += count(section,alias);
            // 目标命中是硬门槛；对白密度只在候选章节内用于排序。
            int score = hits == 0 ? 0 : hits * 4 + Math.min(20, count(section, "\"") + count(section, "“"));
            chapters.add(new Chapter(chapters.size() + 1, a, b, score));
        }
        List<Chapter> matched = chapters.stream().filter(c -> c.score() > 0).toList();
        List<Chapter> selected = select(matched);
        List<Window> windows = new ArrayList<>();
        for (Chapter c : selected) {
            windows.add(primaryWindow(cps, c, target, alias));
        }
        // Long chapters may carry a second window inside the shared 96-window budget.
        for (Chapter c : selected) {
            if (windows.size() >= MAX_WINDOWS) break;
            if (c.end() - c.start() < SECOND_WINDOW_MIN_CHAPTER) continue;
            Window first = windows.stream().filter(w -> w.chapter() == c.number()).findFirst().orElse(null);
            if (first == null) continue;
            Window second = secondaryWindow(cps, c, target, alias, first);
            if (second != null) windows.add(second);
        }
        windows.sort(java.util.Comparator.comparingInt(Window::start));
        return new Scan(List.copyOf(chapters), List.copyOf(windows), matched.size(),
                windows.stream().mapToInt(w -> w.end() - w.start()).sum());
    }

    private Window primaryWindow(int[] cps, Chapter c, String target, String alias) {
        int width = Math.min(windowWidth, c.end() - c.start());
        String body=new String(cps,c.start(),c.end()-c.start());
        int local=indexOf(body,target);
        if(local<0&&!alias.isEmpty()) local=indexOf(body,alias);
        int anchor=c.start()+(local<0? (c.end()-c.start())/2 : body.codePointCount(0,local));
        int start = Math.max(c.start(), Math.min(c.end() - width, anchor - width / 2));
        return new Window(c.number(), start, start + width);
    }

    /** Prefer a later name hit outside the first window; otherwise the chapter tail. */
    private Window secondaryWindow(int[] cps, Chapter c, String target, String alias, Window first) {
        int width = Math.min(windowWidth, c.end() - c.start());
        String body=new String(cps,c.start(),c.end()-c.start());
        int utf16From = body.offsetByCodePoints(0, Math.min(body.codePointCount(0, body.length()), Math.max(0, first.end() - c.start())));
        int local = indexOfFrom(body, target, utf16From);
        if (local < 0 && !alias.isEmpty()) local = indexOfFrom(body, alias, utf16From);
        int anchor;
        if (local >= 0) {
            anchor = c.start() + body.codePointCount(0, local);
        } else {
            // No later hit: take a second window near chapter end if it barely overlaps the first.
            anchor = c.end() - width / 2;
        }
        int start = Math.max(c.start(), Math.min(c.end() - width, anchor - width / 2));
        Window candidate = new Window(c.number(), start, start + width);
        if (overlapRatio(first, candidate) > 0.35) return null;
        return candidate;
    }

    private static double overlapRatio(Window a, Window b) {
        int lo = Math.max(a.start(), b.start());
        int hi = Math.min(a.end(), b.end());
        if (hi <= lo) return 0;
        int overlap = hi - lo;
        int denom = Math.min(a.end() - a.start(), b.end() - b.start());
        return denom <= 0 ? 1 : (double) overlap / denom;
    }

    private static int indexOf(String body, String needle) {
        if (needle == null || needle.isEmpty()) return -1;
        return body.indexOf(needle);
    }

    private static int indexOfFrom(String body, String needle, int fromIndex) {
        if (needle == null || needle.isEmpty()) return -1;
        return body.indexOf(needle, Math.max(0, fromIndex));
    }

    private static List<Chapter> select(List<Chapter> matched) {
        if (matched.size() <= MAX_WINDOWS) return matched;
        List<Chapter> chosen = new ArrayList<>();
        int buckets = Math.min(32, matched.size());
        for (int i = 0; i < buckets; i++) {
            int lo = i * matched.size() / buckets, hi = (i + 1) * matched.size() / buckets;
            List<Chapter> slice = matched.subList(lo, hi);
            Chapter best = slice.stream().max(java.util.Comparator.comparingInt(Chapter::score)).orElseThrow();
            chosen.add(best);
            chosen.add(slice.get(slice.size() / 2));
        }
        for (Chapter c : matched.stream().sorted(java.util.Comparator.comparingInt(Chapter::score).reversed()).toList()) {
            if (chosen.size() >= MAX_WINDOWS) break;
            if (!chosen.contains(c)) chosen.add(c);
        }
        return chosen.stream().distinct().sorted(java.util.Comparator.comparingInt(Chapter::number)).limit(MAX_WINDOWS).toList();
    }
    private static int count(String text, String needle) {
        int n = 0, at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) { n++; at += needle.length(); }
        return n;
    }
}
