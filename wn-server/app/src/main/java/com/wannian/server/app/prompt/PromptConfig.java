package com.wannian.server.app.prompt;

import com.wannian.server.app.persistence.SqliteConfig;
import com.wannian.server.app.skill.FileSkillCatalog;
import com.wannian.server.kernel.skill.SkillCatalog;
import com.wannian.server.kernel.tool.builtin.LoadSkillToolAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 0.2.4-P：提示词分层与 Skill 目录装配。 */
@Configuration
public class PromptConfig {

    @Bean
    FilePromptLayerStore filePromptLayerStore(
            @Value("${wannian.data-dir:data}") String dataDir,
            @Value("${wannian.prompt.layer-char-budget:4000}") int layerCharBudget)
            throws IOException {
        Path root = SqliteConfig.resolveDataDir(dataDir);
        Path prompts = root.resolve("prompts");
        PromptSeedInstaller.installPrompts(prompts);
        return new FilePromptLayerStore(prompts, layerCharBudget);
    }

    @Bean
    SkillCatalog skillCatalog(
            @Value("${wannian.data-dir:data}") String dataDir,
            @Value("${wannian.skill.index-max-entries:16}") int maxEntries,
            @Value("${wannian.skill.index-desc-chars:160}") int descChars,
            @Value("${wannian.skill.body-char-budget:8000}") int bodyBudget,
            @Value("${wannian.skill.index-char-budget:3000}") int indexCharBudget,
            @Value("${wannian.skill.allowed-ids:}") String allowedIdsCsv)
            throws IOException {
        Path root = SqliteConfig.resolveDataDir(dataDir);
        Path skills = root.resolve("skills");
        PromptSeedInstaller.installSkillSeeds(skills);
        Set<String> allowed = FileSkillCatalog.parseAllowedIds(allowedIdsCsv);
        return new FileSkillCatalog(
                skills, maxEntries, descChars, bodyBudget, indexCharBudget, allowed);
    }

    @Bean
    CompanionPromptService companionPromptService(
            FilePromptLayerStore filePromptLayerStore, SkillCatalog skillCatalog) {
        return new CompanionPromptService(filePromptLayerStore, skillCatalog);
    }

    @Bean
    LoadSkillToolAdapter loadSkillToolAdapter(SkillCatalog skillCatalog) {
        return new LoadSkillToolAdapter(skillCatalog);
    }
}
