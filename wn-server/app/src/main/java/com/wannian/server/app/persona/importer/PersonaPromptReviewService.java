package com.wannian.server.app.persona.importer;

import com.wannian.server.app.persona.PersonaDataPaths;
import com.wannian.server.kernel.persona.DefaultPersonaOverlayPort;
import com.wannian.server.kernel.persona.DefaultPersonaOverlayRevision;
import com.wannian.server.kernel.persona.PersonaDefinition;
import com.wannian.server.kernel.persona.PersonaProfileV1;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 默认杜小洛：小说画像先落临时合成 md 供人工审核；通过后再整文件替换 prompts，并清空 DB overlay。
 * <p>唯一稳定生效路径：approve 后正式 {@code prompts/SOUL.md} + {@code VOICE.md}（正文已融入改写）。
 * 未批准前 staging 仅供审核，API 标记 {@code personalityEffective=false}。
 */
@Service
public class PersonaPromptReviewService {
    static final String NOVEL_SECTION = "## 小说人物补充";
    /** 短有效观察（如「说话俏皮」4 字）须保留；再短视为噪声。 */
    static final int MIN_OBSERVATION_CHARS = 4;
    static final int MAX_OBSERVATION_ITEMS = 8;
    static final int MAX_OBSERVATION_CHARS = 900;
    private static final int SNIPPET_CHARS = 160;

    private final Path dataDir;
    private final DefaultPersonaOverlayPort overlayPort;

    public PersonaPromptReviewService(DefaultPersonaOverlayPort overlayPort,
            @Value("${wannian.data-dir:data}") String dataDir) {
        this.overlayPort = Objects.requireNonNull(overlayPort, "overlayPort");
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        try {
            PersonaDataPaths.migrateLegacyLayout(this.dataDir);
        } catch (IOException ex) {
            throw new IllegalStateException("PERSONA_DATA_LAYOUT_MIGRATE_FAILED", ex);
        }
    }

    public Path reviewDir(String importId) {
        return PersonaDataPaths.reviewStaging(dataDir).resolve(safeId(importId));
    }

    public PersonaImportDtos.PromptReviewStaging stage(String importId, PersonaDefinition draft, String operationId)
            throws IOException {
        if (draft.profile().defaultOverlayTraits() == null) {
            throw new IllegalStateException("DEFAULT_OVERLAY_TRAITS_REQUIRED");
        }
        Path prompts = dataDir.resolve("prompts");
        String soulBase = readPrompt(prompts.resolve("SOUL.md"));
        String voiceBase = readPrompt(prompts.resolve("VOICE.md"));
        PersonaPromptRewriter.Result rewritten = PersonaPromptRewriter.rewrite(
                soulBase,
                voiceBase,
                draft.profile().soul(),
                draft.profile().voice(),
                importId,
                draft.id().asString());
        Path dir = reviewDir(importId);
        Files.createDirectories(dir);
        Path soulFile = dir.resolve("SOUL.md");
        Path voiceFile = dir.resolve("VOICE.md");
        Path metaFile = dir.resolve("REVIEW.md");
        atomicWrite(soulFile, rewritten.soul());
        atomicWrite(voiceFile, rewritten.voice());
        atomicWrite(metaFile, buildMeta(importId, draft, operationId, rewritten));
        Path ops = dir.resolve("operations");
        Files.createDirectories(ops);
        // 新 stage 使旧 approve 标记失效，便于重写后再次合并。
        Files.deleteIfExists(dir.resolve("APPROVED.json"));
        atomicWrite(ops.resolve(safeId(operationId) + ".staged"), Instant.now() + "\n");
        return new PersonaImportDtos.PromptReviewStaging(
                importId,
                dir.toString(),
                soulFile.toString(),
                voiceFile.toString(),
                metaFile.toString(),
                "STAGED",
                rewritten.soulTraitCount(),
                rewritten.voiceTraitCount(),
                false);
    }

    public PersonaImportDtos.PromptReviewStaging status(String importId) {
        Path dir = reviewDir(importId);
        Path soul = dir.resolve("SOUL.md");
        Path voice = dir.resolve("VOICE.md");
        Path meta = dir.resolve("REVIEW.md");
        boolean ready = Files.isRegularFile(soul) && Files.isRegularFile(voice);
        boolean approved = Files.isRegularFile(dir.resolve("APPROVED.json"));
        String status = approved ? "APPROVED" : ready ? "STAGED" : "MISSING";
        int soulObs = 0;
        int voiceObs = 0;
        if (ready) {
            try {
                soulObs = countIntegratedTraits(Files.readString(soul, StandardCharsets.UTF_8), "人物气质");
                voiceObs = countIntegratedTraits(Files.readString(voice, StandardCharsets.UTF_8), "说话气质");
            } catch (IOException ignored) {
                // status 仍返回路径；条数保持 0
            }
        }
        return new PersonaImportDtos.PromptReviewStaging(
                importId,
                dir.toString(),
                soul.toString(),
                voice.toString(),
                meta.toString(),
                status,
                soulObs,
                voiceObs,
                approved);
    }

    public PersonaImportDtos.PromptMergeResult approve(String importId, String operationId) throws IOException {
        if (operationId == null || operationId.isBlank() || operationId.length() > 160) {
            throw new IllegalArgumentException("OPERATION_ID_INVALID");
        }
        Path dir = reviewDir(importId);
        Path pendingSoul = dir.resolve("SOUL.md");
        Path pendingVoice = dir.resolve("VOICE.md");
        if (!Files.isRegularFile(pendingSoul) || !Files.isRegularFile(pendingVoice)) {
            throw new IllegalStateException("PROMPT_REVIEW_NOT_STAGED");
        }
        Path ops = dir.resolve("operations");
        Files.createDirectories(ops);
        Path done = ops.resolve(safeId(operationId) + ".approved");
        if (Files.isRegularFile(done) && Files.isRegularFile(dir.resolve("APPROVED.json"))) {
            return readApproved(importId, dir);
        }
        String soul = Files.readString(pendingSoul, StandardCharsets.UTF_8);
        String voice = Files.readString(pendingVoice, StandardCharsets.UTF_8);
        if (soul.isBlank() || voice.isBlank()) {
            throw new IllegalStateException("PROMPT_REVIEW_EMPTY");
        }
        Path prompts = dataDir.resolve("prompts");
        Path backupRoot = PersonaDataPaths.reviewBackups(dataDir).resolve(Instant.now().toString().replace(':', '-'));
        Files.createDirectories(backupRoot);
        backupFile(prompts.resolve("SOUL.md"), backupRoot.resolve("SOUL.md"));
        backupFile(prompts.resolve("VOICE.md"), backupRoot.resolve("VOICE.md"));
        atomicWrite(prompts.resolve("SOUL.md"), soul);
        atomicWrite(prompts.resolve("VOICE.md"), voice);

        long previous = overlayPort.current().map(DefaultPersonaOverlayRevision::revision).orElse(0L);
        boolean cleared = false;
        if (previous > 0) {
            overlayPort.rollback(0, previous);
            cleared = true;
        }
        String soulSha = sha256Hex(soul);
        String voiceSha = sha256Hex(voice);
        int soulObs = countIntegratedTraits(soul, "人物气质");
        int voiceObs = countIntegratedTraits(voice, "说话气质");
        String approvedJson = """
                {"importId":"%s","operationId":"%s","previousOverlayRevision":%d,"overlayCleared":%s,"backupDir":"%s","soulSha256":"%s","voiceSha256":"%s","soulObservationCount":%d,"voiceObservationCount":%d,"approvedAt":"%s"}
                """
                .formatted(
                        escape(importId),
                        escape(operationId),
                        previous,
                        cleared,
                        escape(backupRoot.toString().replace('\\', '/')),
                        soulSha,
                        voiceSha,
                        soulObs,
                        voiceObs,
                        Instant.now());
        atomicWrite(dir.resolve("APPROVED.json"), approvedJson);
        atomicWrite(done, Instant.now() + "\n");
        return new PersonaImportDtos.PromptMergeResult(
                importId,
                prompts.resolve("SOUL.md").toString(),
                prompts.resolve("VOICE.md").toString(),
                previous,
                cleared,
                backupRoot.toString(),
                soulSha,
                voiceSha,
                snippet(soul),
                snippet(voice),
                soulObs,
                voiceObs);
    }

    /**
     * 去掉「证据不足 / 无法判断」等空样本，保留有内容的观察，写成条目。
     * 阈值：条目 ≥ {@link #MIN_OBSERVATION_CHARS} 码点；最多 {@link #MAX_OBSERVATION_ITEMS} 条、约
     * {@link #MAX_OBSERVATION_CHARS} 字。
     */
    static String cleanExtractedLayer(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.strip();
        java.util.ArrayList<String> keep = new java.util.ArrayList<>();
        for (String part : text.split("；")) {
            String item = part.strip();
            if (item.isBlank()) {
                continue;
            }
            item = item.replaceFirst("^样本\\d+[：:]\\s*", "").strip();
            if (item.isBlank()) {
                continue;
            }
            if (isInsufficientObservation(item)) {
                continue;
            }
            if (item.length() < MIN_OBSERVATION_CHARS) {
                continue;
            }
            keep.add("- " + item);
        }
        if (keep.isEmpty() && !looksLikeInsufficientOnly(text)) {
            // 非整「样本N」结构时整段保留（截断）。
            String flat = text.length() > 800 ? text.substring(0, 800) : text;
            return "- " + flat;
        }
        // 去重保序，最多 8 条，总长约 900。
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>(keep);
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String line : uniq) {
            if (n >= MAX_OBSERVATION_ITEMS || out.length() + line.length() > MAX_OBSERVATION_CHARS) {
                break;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
            n++;
        }
        return out.toString();
    }

    static int countObservationBullets(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int n = 0;
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (t.startsWith("- ") && t.length() > 2) {
                n++;
            }
        }
        return n;
    }

    private static boolean isInsufficientObservation(String item) {
        return item.contains("证据不足")
                || item.contains("无法判断")
                || item.contains("无法可靠")
                || item.contains("没有足够")
                || item.contains("未提供足够")
                || item.contains("没有杜小洛的")
                || item.contains("没有她的直接")
                || item.contains("没有呈现她说话")
                || item.contains("当前片段没有")
                || item.contains("当前片段未")
                || item.contains("仅出现章节标题")
                || item.contains("更多性格信息不足")
                || item.contains("仍需更多材料")
                || item.contains("无法确定")
                || item.contains("未在此处呈现")
                || (item.contains("仅能看出") && item.length() < 40)
                || (item.contains("片段仅显示") && item.contains("无法"))
                || (item.contains("片段显示她正经历") && item.length() < 40);
    }

    private static boolean looksLikeInsufficientOnly(String text) {
        return text.contains("证据不足") || text.contains("无法判断");
    }

    /** 去掉旧「## 小说人物补充」附录，便于重复融入。 */
    static String stripNovelSection(String original) {
        int idx = original.indexOf("\n" + NOVEL_SECTION);
        if (idx < 0 && original.startsWith(NOVEL_SECTION)) {
            return "";
        }
        if (idx >= 0) {
            return original.substring(0, idx).stripTrailing();
        }
        return original.stripTrailing();
    }

    /** 统计已融入的气质要点（按句号拆分气质条目）。 */
    static int countIntegratedTraits(String doc, String label) {
        if (doc == null || doc.isBlank()) {
            return 0;
        }
        String needle = "- **" + label + "**：";
        for (String line : doc.split("\n")) {
            String t = line.strip();
            if (!t.startsWith(needle) && !t.startsWith("- **" + label + "**:")) {
                continue;
            }
            int colon = t.indexOf('：');
            if (colon < 0) {
                colon = t.indexOf(':');
            }
            if (colon < 0 || colon + 1 >= t.length()) {
                return 1;
            }
            String body = t.substring(colon + 1).strip();
            int n = 0;
            for (String part : body.split("。")) {
                if (part.strip().length() >= 4) {
                    n++;
                }
            }
            return Math.max(1, n);
        }
        return 0;
    }

    private String buildMeta(
            String importId, PersonaDefinition draft, String operationId, PersonaPromptRewriter.Result rewritten) {
        PersonaProfileV1 profile = draft.profile();
        String traits = profile.defaultOverlayTraits() == null
                ? ""
                : profile.defaultOverlayTraits().toString();
        return "# 性格审核说明\n\n"
                + "- importId: `"
                + importId
                + "`\n"
                + "- personaId: `"
                + draft.id().asString()
                + "`\n"
                + "- operationId: `"
                + operationId
                + "`\n"
                + "- stagedAt: `"
                + Instant.now()
                + "`\n"
                + "- traits(枚举参考，未写入正文附录): `"
                + traits
                + "`\n"
                + "- soulTraitCount: `"
                + rewritten.soulTraitCount()
                + "`\n"
                + "- voiceTraitCount: `"
                + rewritten.voiceTraitCount()
                + "`\n"
                + "- personalityEffective: `false`（尚未 approve；正式对话仍读旧 prompts）\n"
                + "- mergeMode: `integrated-rewrite`（开篇/底色/语气已改写，无小说观察附录）\n\n"
                + "## 审核步骤\n\n"
                + "1. 打开同目录 `SOUL.md` / `VOICE.md`，确认开篇与底色已体现人物气质（不是文末观察清单）。\n"
                + "2. 按需修改；满意后调用 `POST /api/personas/imports/{importId}/approve-prompt-merge`。\n"
                + "3. 通过后会备份原 prompts、整文件替换 `data/prompts/SOUL.md` 与 `VOICE.md`，并清空 DB overlay。\n"
                + "4. **不改** `IDENTITY.md` / `USER.md` / `SAFETY.md`。\n"
                + "5. 合并后新对话直接读正式 md；旧会话需新开才保证热加载。\n\n"
                + rewritten.reviewSummary();
    }

    private PersonaImportDtos.PromptMergeResult readApproved(String importId, Path dir) throws IOException {
        Path prompts = dataDir.resolve("prompts");
        Path soulPath = prompts.resolve("SOUL.md");
        Path voicePath = prompts.resolve("VOICE.md");
        long previous = 0;
        boolean cleared = false;
        String backup = "";
        String soulSha = "";
        String voiceSha = "";
        int soulObs = 0;
        int voiceObs = 0;
        String raw = Files.readString(dir.resolve("APPROVED.json"), StandardCharsets.UTF_8);
        previous = readJsonLong(raw, "previousOverlayRevision", 0);
        cleared = raw.contains("\"overlayCleared\":true");
        backup = readJsonString(raw, "backupDir").replace('/', java.io.File.separatorChar);
        soulSha = readJsonString(raw, "soulSha256");
        voiceSha = readJsonString(raw, "voiceSha256");
        soulObs = (int) readJsonLong(raw, "soulObservationCount", 0);
        voiceObs = (int) readJsonLong(raw, "voiceObservationCount", 0);
        String soul = Files.isRegularFile(soulPath) ? Files.readString(soulPath, StandardCharsets.UTF_8) : "";
        String voice = Files.isRegularFile(voicePath) ? Files.readString(voicePath, StandardCharsets.UTF_8) : "";
        if (soulSha.isBlank() && !soul.isBlank()) {
            soulSha = sha256Hex(soul);
        }
        if (voiceSha.isBlank() && !voice.isBlank()) {
            voiceSha = sha256Hex(voice);
        }
        if (soulObs == 0 && !soul.isBlank()) {
            soulObs = countIntegratedTraits(soul, "人物气质");
        }
        if (voiceObs == 0 && !voice.isBlank()) {
            voiceObs = countIntegratedTraits(voice, "说话气质");
        }
        return new PersonaImportDtos.PromptMergeResult(
                importId,
                soulPath.toString(),
                voicePath.toString(),
                previous,
                cleared,
                backup,
                soulSha,
                voiceSha,
                snippet(soul),
                snippet(voice),
                soulObs,
                voiceObs);
    }

    private static long readJsonLong(String raw, String key, long fallback) {
        String needle = "\"" + key + "\":";
        int p = raw.indexOf(needle);
        if (p < 0) {
            return fallback;
        }
        int start = p + needle.length();
        while (start < raw.length() && Character.isWhitespace(raw.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
            end++;
        }
        if (end > start) {
            return Long.parseLong(raw.substring(start, end));
        }
        return fallback;
    }

    private static String readJsonString(String raw, String key) {
        String needle = "\"" + key + "\":\"";
        int b = raw.indexOf(needle);
        if (b < 0) {
            return "";
        }
        int s = b + needle.length();
        int e = raw.indexOf('"', s);
        if (e > s) {
            return raw.substring(s, e);
        }
        return "";
    }

    private static String readPrompt(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("PROMPT_FILE_MISSING:" + path.getFileName());
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void backupFile(Path source, Path dest) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".review-", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeId(String id) {
        String s = id == null ? "" : id.strip();
        if (s.isBlank() || s.length() > 160 || !s.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("IMPORT_ID_INVALID");
        }
        return s;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", e);
        }
    }

    private static String snippet(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replace('\r', ' ').replace('\n', ' ').strip();
        int idx = flat.indexOf("人物气质");
        if (idx < 0) {
            idx = flat.indexOf("说话气质");
        }
        if (idx < 0) {
            idx = flat.indexOf("活泼");
        }
        if (idx >= 0) {
            flat = flat.substring(idx);
        }
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "…";
    }
}
