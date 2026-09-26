package com.wannian.server.kernel.skill;

import java.util.Objects;

/**
 * Skill 索引条目（进 system 的摘要；不含正文）。
 *
 * @param id 目录名（稳定 id，供 load_skill）
 * @param name frontmatter name；缺省同 id
 * @param description 短说明（已截断）
 * @param version frontmatter version；可空
 */
public record SkillSummary(String id, String name, String description, String version) {

    public SkillSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        id = id.trim();
        name = name.trim();
        description = description.trim();
        version = version == null ? "" : version.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("skill id 不能为空");
        }
        if (name.isEmpty()) {
            name = id;
        }
    }
}
