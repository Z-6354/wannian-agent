package com.wannian.server.kernel.skill;

import java.util.List;
import java.util.Optional;

/** 空目录（测试 / 关闭 Skill 时）。 */
public final class EmptySkillCatalog implements SkillCatalog {

    public static final EmptySkillCatalog INSTANCE = new EmptySkillCatalog();

    private EmptySkillCatalog() {}

    @Override
    public List<SkillSummary> listSummaries() {
        return List.of();
    }

    @Override
    public Optional<String> loadBody(String skillId) {
        return Optional.empty();
    }
}
