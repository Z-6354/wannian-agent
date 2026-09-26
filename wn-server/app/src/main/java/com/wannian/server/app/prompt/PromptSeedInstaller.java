package com.wannian.server.app.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 首次启动把 classpath 种子拷到 data-dir；已存在的用户文件不覆盖。
 */
public final class PromptSeedInstaller {

    private static final System.Logger LOG = System.getLogger(PromptSeedInstaller.class.getName());

    /** 常驻分层：VOICE 为声线／示例（Hermes 式常驻，非 Skill）。 */
    static final List<String> PROMPT_FILES =
            List.of("SOUL.md", "VOICE.md", "IDENTITY.md", "USER.md", "SAFETY.md");
    /** 仅按需 Skill；陪伴声线不在此列（已迁 VOICE.md）。 */
    static final List<String> SKILL_SEEDS = List.of("how-to-remember", "persona-extraction");

    private PromptSeedInstaller() {}

    public static void installPrompts(Path promptsDir) throws IOException {
        Objects.requireNonNull(promptsDir, "promptsDir");
        Files.createDirectories(promptsDir);
        for (String name : PROMPT_FILES) {
            Path target = promptsDir.resolve(name);
            if (Files.isRegularFile(target)) {
                continue;
            }
            String resource = "/prompt-seeds/" + name;
            try (InputStream in = PromptSeedInstaller.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("缺少 classpath 种子: " + resource);
                }
                Files.writeString(target, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                LOG.log(System.Logger.Level.INFO, () -> "已安装提示词种子: " + target);
            }
        }
    }

    public static void installSkillSeeds(Path skillsDir) throws IOException {
        Objects.requireNonNull(skillsDir, "skillsDir");
        for (String id : SKILL_SEEDS) {
            Path skillDir = skillsDir.resolve(id);
            Path target = skillDir.resolve("SKILL.md");
            if (Files.isRegularFile(target)) {
                continue;
            }
            Files.createDirectories(skillDir);
            String resource = "/skill-seeds/" + id + "/SKILL.md";
            try (InputStream in = PromptSeedInstaller.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("缺少 classpath 种子: " + resource);
                }
                Files.writeString(target, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                LOG.log(System.Logger.Level.INFO, () -> "已安装 Skill 种子: " + target);
            }
        }
    }
}
