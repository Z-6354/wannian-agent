package com.wannian.server.app.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.prompt.PromptLayerTexts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 2.4.2：分层读盘与注释种子语义。 */
class FilePromptLayerStoreTest {

    @TempDir Path tempDir;

    @Test
    void stripCommentOnlySeed_keepsMarkdownHeadings() {
        String raw =
                """
                # 烟火的性格

                你是烟火。

                ## 不能改变的底色

                - 先听清目标。
                """;
        String out = FilePromptLayerStore.stripCommentOnlySeed(raw);
        assertThat(out).contains("# 烟火的性格");
        assertThat(out).contains("## 不能改变的底色");
        assertThat(out).contains("你是烟火。");
    }

    @Test
    void stripCommentOnlySeed_commentOnlyBecomesEmpty() {
        String raw =
                """
                # 可选：填写对用户的稳定说明
                # 本文件默认可为空
                """;
        assertThat(FilePromptLayerStore.stripCommentOnlySeed(raw)).isEmpty();
    }

    @Test
    void stripCommentOnlySeed_blankIsEmpty() {
        assertThat(FilePromptLayerStore.stripCommentOnlySeed(null)).isEmpty();
        assertThat(FilePromptLayerStore.stripCommentOnlySeed("   \n  ")).isEmpty();
    }

    @Test
    void load_readsLayersAndClips() throws Exception {
        Path prompts = tempDir.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(
                prompts.resolve("SOUL.md"),
                "# 标题\n\n灵魂正文\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                prompts.resolve("VOICE.md"),
                "# 声音\n\n声线正文\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                prompts.resolve("IDENTITY.md"),
                "# 身份\n\n身份正文\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                prompts.resolve("USER.md"),
                "# 仅注释\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                prompts.resolve("SAFETY.md"),
                "# 补充\n\n运营补充一句。\n",
                StandardCharsets.UTF_8);

        FilePromptLayerStore store = new FilePromptLayerStore(prompts, 4000);
        PromptLayerTexts layers = store.load();
        assertThat(layers.soul()).contains("# 标题").contains("灵魂正文");
        assertThat(layers.voice()).contains("声线正文");
        assertThat(layers.identity()).contains("身份正文");
        assertThat(layers.userNote()).isEmpty();
        assertThat(layers.safetySupplement()).contains("运营补充一句");
    }

    @Test
    void load_reloadsAfterMtimeChange() throws Exception {
        Path prompts = tempDir.resolve("prompts");
        Files.createDirectories(prompts);
        Path soul = prompts.resolve("SOUL.md");
        Files.writeString(soul, "第一版\n", StandardCharsets.UTF_8);

        FilePromptLayerStore store = new FilePromptLayerStore(prompts, 4000);
        assertThat(store.load().soul()).isEqualTo("第一版");

        Thread.sleep(20);
        Files.writeString(soul, "第二版\n", StandardCharsets.UTF_8);
        assertThat(store.load().soul()).isEqualTo("第二版");
    }

    @Test
    void clip_truncatesToBudget() {
        assertThat(FilePromptLayerStore.clip("abcdef", 3)).isEqualTo("abc");
        assertThat(FilePromptLayerStore.clip("ab", 3)).isEqualTo("ab");
    }
}
