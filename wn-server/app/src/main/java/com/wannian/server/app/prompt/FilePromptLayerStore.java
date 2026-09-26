package com.wannian.server.app.prompt;

import com.wannian.server.kernel.prompt.PromptLayerTexts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 从 data-dir/prompts 读常驻 Markdown 分层；按 mtime 缓存；超长截断。
 *
 * <p>含 VOICE.md（声线常驻）。Skill 正文不在此读。
 */
public final class FilePromptLayerStore {

    private static final System.Logger LOG = System.getLogger(FilePromptLayerStore.class.getName());

    private final Path promptsDir;
    private final int layerCharBudget;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Cache cache = Cache.empty();

    public FilePromptLayerStore(Path promptsDir, int layerCharBudget) {
        this.promptsDir = Objects.requireNonNull(promptsDir, "promptsDir");
        if (layerCharBudget < 64) {
            throw new IllegalArgumentException("layerCharBudget 须 ≥ 64");
        }
        this.layerCharBudget = layerCharBudget;
    }

    public PromptLayerTexts load() {
        lock.lock();
        try {
            long stamp = stamp();
            Cache current = cache;
            if (current.stamp == stamp) {
                return current.layers;
            }
            PromptLayerTexts layers =
                    new PromptLayerTexts(
                            readClipped("SOUL.md"),
                            readClipped("VOICE.md"),
                            readClipped("IDENTITY.md"),
                            readClipped("USER.md"),
                            readClipped("SAFETY.md"));
            cache = new Cache(stamp, layers);
            return layers;
        } finally {
            lock.unlock();
        }
    }

    private long stamp() {
        long s = 0;
        for (String name : PromptSeedInstaller.PROMPT_FILES) {
            Path file = promptsDir.resolve(name);
            try {
                if (Files.isRegularFile(file)) {
                    s = 31 * s + Files.getLastModifiedTime(file).toMillis();
                }
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.WARNING, () -> "读 mtime 失败: " + file + " " + ex.getMessage());
            }
        }
        return s;
    }

    private String readClipped(String name) {
        Path file = promptsDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            return clip(stripCommentOnlySeed(raw), layerCharBudget);
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, () -> "读提示词失败: " + file + " " + ex.getMessage());
            return "";
        }
    }

    /**
     * 仅含 {@code #} 注释行（无其它正文）的种子视为空，避免占 prompt。
     *
     * <p>有任意非注释正文时原样保留（含 Markdown {@code #}/{@code ##} 标题），不得剥标题。
     */
    static String stripCommentOnlySeed(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        boolean hasBody = false;
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            hasBody = true;
            break;
        }
        if (!hasBody) {
            return "";
        }
        return raw.strip();
    }

    public static String clip(String text, int budget) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= budget) {
            return text;
        }
        return text.substring(0, budget);
    }

    private record Cache(long stamp, PromptLayerTexts layers) {
        static Cache empty() {
            return new Cache(Long.MIN_VALUE, PromptLayerTexts.empty());
        }
    }
}
