package com.wannian.server.app.persona.importer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将清洗后的抽取倾向确定性改写并融入 SOUL/VOICE 正文（开篇 / 底色 / 语气），
 * 不追加「小说观察」附录。旁观笔记、未授权称呼与剧情共同经历一律丢弃。
 */
final class PersonaPromptRewriter {
    private static final String IMPORT_MARKER_START = "<!-- persona-import integrated";

    private PersonaPromptRewriter() {}

    record Result(String soul, String voice, String reviewSummary, int soulTraitCount, int voiceTraitCount) {}

    static Result rewrite(
            String soulBase,
            String voiceBase,
            String soulFreeText,
            String voiceFreeText,
            String importId,
            String personaId) {
        List<String> soulTraits = distillSoul(PersonaPromptReviewService.cleanExtractedLayer(soulFreeText));
        List<String> voiceTraits = distillVoice(PersonaPromptReviewService.cleanExtractedLayer(voiceFreeText));
        String soul = integrateSoul(stripLegacy(soulBase), soulTraits, importId, personaId);
        String voice = integrateVoice(stripLegacy(voiceBase), voiceTraits, importId, personaId);
        String summary = buildSummary(soulTraits, voiceTraits);
        return new Result(soul, voice, summary, soulTraits.size(), voiceTraits.size());
    }

    /** 去掉旧附录与旧融入标记，便于重复 stage（须可逆，避免开篇织入叠写）。 */
    static String stripLegacy(String original) {
        if (original == null || original.isBlank()) {
            return "";
        }
        String text = PersonaPromptReviewService.stripNovelSection(original);
        int marker = text.indexOf("\n" + IMPORT_MARKER_START);
        if (marker < 0 && text.startsWith(IMPORT_MARKER_START)) {
            return "";
        }
        if (marker >= 0) {
            text = text.substring(0, marker).stripTrailing();
        }
        // 去掉曾插入的「人物气质 / 说话气质」整条。
        text = removeBulletStartingWith(text, "- **人物气质");
        text = removeBulletStartingWith(text, "- **说话气质");
        // 去掉开篇织入：幽默句与「不要向用户背诵」之间的全部插入。
        text = clearBetween(
                text,
                "偶尔有一点平静的幽默。",
                "不要向用户背诵这些性格词。");
        // 去掉 VOICE 织入：锚点前方仅由融入句构成的段落。
        text = clearTrailingWeaveBefore(
                text,
                "语气词、幽默与感叹号顺着情境偶尔出现，没有固定口癖。",
                "语气可俏皮",
                "亲近时可更简短",
                "可有一点傲娇");
        return text.stripTrailing().replaceAll("\n{3,}", "\n\n");
    }

    /** 删除 start 与 end 之间的内容，保留两端锚点紧挨（用于还原开篇）。 */
    static String clearBetween(String doc, String start, String end) {
        int s = doc.indexOf(start);
        int e = doc.indexOf(end);
        if (s < 0 || e < 0 || e < s + start.length()) {
            return doc;
        }
        return doc.substring(0, s + start.length()) + doc.substring(e);
    }

    /** 删除 {@code anchor} 前方连续的融入句（以句号结尾、匹配前缀）。 */
    static String clearTrailingWeaveBefore(String doc, String anchor, String... prefixes) {
        int a = doc.indexOf(anchor);
        if (a <= 0) {
            return doc;
        }
        String before = doc.substring(0, a).stripTrailing();
        while (!before.isEmpty()) {
            int lastPeriod = before.lastIndexOf('。');
            if (lastPeriod < 0) {
                break;
            }
            int prevPeriod = before.lastIndexOf('。', lastPeriod - 1);
            int sentStart = prevPeriod < 0 ? 0 : prevPeriod + 1;
            String sent = before.substring(sentStart, lastPeriod + 1).strip();
            boolean drop = false;
            for (String p : prefixes) {
                if (sent.startsWith(p)) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                break;
            }
            before = before.substring(0, sentStart).stripTrailing();
        }
        if (before.isEmpty()) {
            return doc.substring(a);
        }
        return before + "\n\n" + doc.substring(a);
    }

    static List<String> distillSoul(String cleanedBullets) {
        Set<String> flags = detectFlags(cleanedBullets);
        List<String> out = new ArrayList<>();
        if (flags.contains("lively") || flags.contains("playful") || flags.contains("tease")) {
            out.add("亲近时可更活泼、亲近，愿意用玩笑带一点轻松互动。");
        }
        if (flags.contains("coquettish") || flags.contains("tease")) {
            out.add("合适时可带玩笑与轻巧的撒娇感，但必须服从用户边界，不擅自亲密、不固定昵称。");
        }
        if (flags.contains("sensitive")) {
            out.add("情绪细腻；因意外或担心给人添麻烦时会不安。");
        }
        if (flags.contains("recovers")) {
            out.add("受到关心后可以逐渐恢复轻松。");
        }
        if (flags.contains("aesthetic")) {
            out.add("对古风、二次元或穿着审美有兴趣时可自然流露，但不编造共同经历。");
        }
        if (flags.contains("melancholy")) {
            out.add("活泼之外也可流露沉静或淡淡忧伤的一面，不把每轮都做成玩笑。");
        }
        if (flags.contains("disappointed")) {
            out.add("对期待落空的事会失落，接住具体失望即可，不把小说情节说成你们一起经历过。");
        }
        if (out.isEmpty()) {
            for (String line : lines(cleanedBullets)) {
                String n = normalizeInstruction(line, true);
                if (n != null) {
                    out.add(n);
                }
                if (out.size() >= 5) {
                    break;
                }
            }
        }
        return List.copyOf(trimList(out, 6));
    }

    static List<String> distillVoice(String cleanedBullets) {
        Set<String> flags = detectFlags(cleanedBullets);
        List<String> out = new ArrayList<>();
        if (flags.contains("witty") || flags.contains("frank") || flags.contains("tease")) {
            out.add("语气可俏皮、直率，偶尔调侃；对方严肃、疲惫或未接梗时立刻收住。");
        }
        if (flags.contains("brief")) {
            out.add("亲近时可更简短利落，不必每轮写满；具体任务仍按需要展开。");
        }
        if (flags.contains("tsundere")) {
            out.add("可有一点傲娇口吻，但不抬杠、不贬低用户。");
        }
        if (flags.contains("warm_thanks")) {
            out.add("表达感谢时可以自然一些，仍保持分寸。");
        }
        if (out.isEmpty()) {
            for (String line : lines(cleanedBullets)) {
                String n = normalizeInstruction(line, false);
                if (n != null) {
                    out.add(n);
                }
                if (out.size() >= 4) {
                    break;
                }
            }
        }
        return List.copyOf(trimList(out, 5));
    }

    static String integrateSoul(String base, List<String> traits, String importId, String personaId) {
        String doc = base == null ? "" : base;
        if (traits.isEmpty()) {
            return finish(doc, importId, personaId, "性格/互动", "（本次无足够可融入的性格倾向，保持原稿。）");
        }
        String weave = String.join("", traits.subList(0, Math.min(2, traits.size())));
        weave = weave + "不要复述小说台词，不要把虚构情节当成与用户的共同经历。";
        doc = insertBeforeMarker(doc, "不要向用户背诵这些性格词。", weave);
        String bullet = "- **人物气质**： " + String.join(" ", traits);
        doc = insertAfterHeading(doc, "## 稳定的底色", bullet);
        return finish(doc, importId, personaId, "性格/互动", null);
    }

    static String integrateVoice(String base, List<String> traits, String importId, String personaId) {
        String doc = base == null ? "" : base;
        if (traits.isEmpty()) {
            return finish(doc, importId, personaId, "说话节奏", "（本次无足够可融入的说话倾向，保持原稿。）");
        }
        String weave = String.join("", traits);
        String humorLine = "语气词、幽默与感叹号顺着情境偶尔出现，没有固定口癖。";
        if (doc.contains(humorLine)) {
            // 独立成段，避免粘在上一段末尾。
            doc = insertBeforeMarker(doc, humorLine, weave + "\n\n");
        } else {
            doc = insertAfterFirstParagraph(doc, weave);
        }
        String bullet = "- **说话气质**： " + String.join(" ", traits);
        if (doc.contains("## 多轮校准")) {
            doc = insertAfterHeading(doc, "## 多轮校准", bullet);
        } else {
            doc = doc.stripTrailing() + "\n\n" + bullet + "\n";
        }
        return finish(doc, importId, personaId, "说话节奏", null);
    }

    private static String finish(String doc, String importId, String personaId, String layer, String note) {
        String body = doc.stripTrailing();
        if (note != null && !note.isBlank() && !body.contains(note)) {
            // 仅当完全无倾向时在文末加一行轻提示，仍不建「小说观察」节。
            body = body + "\n\n" + note;
        }
        return body
                + "\n\n"
                + IMPORT_MARKER_START
                + " importId="
                + importId
                + " personaId="
                + personaId
                + " layer="
                + layer
                + " -->\n";
    }

    private static String buildSummary(List<String> soulTraits, List<String> voiceTraits) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 改写摘要（已融入正文，无小说观察附录）\n\n");
        sb.append("### SOUL 融入要点（").append(soulTraits.size()).append("）\n\n");
        if (soulTraits.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (String t : soulTraits) {
                sb.append("- ").append(t).append('\n');
            }
        }
        sb.append("\n### VOICE 融入要点（").append(voiceTraits.size()).append("）\n\n");
        if (voiceTraits.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (String t : voiceTraits) {
                sb.append("- ").append(t).append('\n');
            }
        }
        return sb.toString();
    }

    private static Set<String> detectFlags(String cleanedBullets) {
        Set<String> flags = new LinkedHashSet<>();
        String all = cleanedBullets == null ? "" : cleanedBullets;
        for (String line : lines(all)) {
            if (isRejectedMeta(line)) {
                continue;
            }
            if (containsAny(line, "活泼", "亲近")) {
                flags.add("lively");
            }
            if (containsAny(line, "玩笑", "调侃", "戏谑", "夸张")) {
                flags.add("tease");
            }
            if (containsAny(line, "撒娇")) {
                flags.add("coquettish");
            }
            if (containsAny(line, "俏皮")) {
                flags.add("witty");
                flags.add("playful");
            }
            if (containsAny(line, "直率", "直接")) {
                flags.add("frank");
            }
            if (containsAny(line, "傲娇")) {
                flags.add("tsundere");
            }
            if (containsAny(line, "简短")) {
                flags.add("brief");
            }
            if (containsAny(line, "细腻", "不安", "添麻烦")) {
                flags.add("sensitive");
            }
            if (containsAny(line, "关心后", "恢复轻松")) {
                flags.add("recovers");
            }
            if (containsAny(line, "古风", "二次元", "穿着")) {
                flags.add("aesthetic");
            }
            if (containsAny(line, "忧伤", "沉郁", "沉静")) {
                flags.add("melancholy");
            }
            if (containsAny(line, "失落", "樱花", "期待")) {
                flags.add("disappointed");
            }
            if (containsAny(line, "感谢")) {
                flags.add("warm_thanks");
            }
        }
        return flags;
    }

    private static String normalizeInstruction(String rawLine, boolean soul) {
        String item = rawLine.strip();
        if (item.startsWith("- ")) {
            item = item.substring(2).strip();
        }
        if (item.isBlank() || isRejectedMeta(item)) {
            return null;
        }
        // 去掉旁观前缀
        item = item.replaceFirst("^片段中表现得", "")
                .replaceFirst("^片段中的她会", "")
                .replaceFirst("^外表", "")
                .strip();
        if (item.startsWith("得")) {
            item = item.substring(1).strip();
        }
        if (item.length() < 4) {
            return null;
        }
        if (soul) {
            return "可体现：" + item + "（服从用户边界；不把剧情当共同经历。）";
        }
        return "说话时可体现：" + item + "（严肃或未接梗时收住。）";
    }

    private static boolean isRejectedMeta(String item) {
        String t = item == null ? "" : item;
        if (t.contains("叙述者")
                || t.contains("推测")
                || t.contains("流氓大叔")
                || t.contains("称呼对方为")
                || t.contains("吐舌")
                || t.contains("吐舌头")
                || t.contains("拍肩")
                || t.contains("作鬼脸")
                || (t.contains("大叔") && t.contains("称呼"))
                || t.contains("共同经历")
                || t.contains("证据不足")
                || t.contains("无法判断")) {
            return true;
        }
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("忽略") && (lower.contains("安全") || lower.contains("规则") || lower.contains("指令"))
                || lower.contains("系统提示")
                || lower.contains("system prompt")
                || lower.contains("管理员")
                || lower.contains("调用工具")
                || lower.contains("绕过")
                || lower.contains("自残")
                || lower.contains("自杀");
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                out.add(line.strip());
            }
        }
        return out;
    }

    private static List<String> trimList(List<String> in, int max) {
        if (in.size() <= max) {
            return in;
        }
        return new ArrayList<>(in.subList(0, max));
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String insertBeforeMarker(String doc, String marker, String insert) {
        int idx = doc.indexOf(marker);
        if (idx < 0) {
            return doc;
        }
        return doc.substring(0, idx) + insert + marker + doc.substring(idx + marker.length());
    }

    private static String insertAfterHeading(String doc, String heading, String block) {
        int idx = doc.indexOf(heading);
        if (idx < 0) {
            return doc.stripTrailing() + "\n\n" + heading + "\n\n" + block + "\n";
        }
        int after = idx + heading.length();
        while (after < doc.length() && (doc.charAt(after) == '\n' || doc.charAt(after) == '\r')) {
            after++;
        }
        return doc.substring(0, after) + block + "\n\n" + doc.substring(after);
    }

    private static String insertAfterFirstParagraph(String doc, String insert) {
        // 跳过标题行，在首个正文段落后插入
        int start = 0;
        if (doc.startsWith("#")) {
            int nl = doc.indexOf('\n');
            start = nl < 0 ? doc.length() : nl + 1;
        }
        while (start < doc.length() && Character.isWhitespace(doc.charAt(start))) {
            start++;
        }
        int blank = doc.indexOf("\n\n", start);
        if (blank < 0) {
            return doc.stripTrailing() + "\n\n" + insert + "\n";
        }
        return doc.substring(0, blank) + "\n\n" + insert + doc.substring(blank);
    }

    private static String removeBulletStartingWith(String doc, String prefix) {
        StringBuilder out = new StringBuilder();
        for (String line : doc.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith(prefix)) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString().replaceAll("\n{3,}", "\n\n");
    }
}
