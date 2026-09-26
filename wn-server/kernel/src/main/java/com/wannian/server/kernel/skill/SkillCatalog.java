package com.wannian.server.kernel.skill;

import java.util.List;
import java.util.Optional;

/**
 * Skill 发现与正文读取端口（实现在 app；kernel 工具只依赖本口）。
 */
public interface SkillCatalog {

    /** 当前授权可见的摘要列表（已截断、有界）。 */
    List<SkillSummary> listSummaries();

    /** 按 id 读已截断正文；不存在或未授权 → empty。 */
    Optional<String> loadBody(String skillId);

    /** 供 PromptComposer 注入的索引文本；无 Skill 时返回空串。 */
    default String indexPromptText() {
        List<SkillSummary> rows = listSummaries();
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("可用 Skill（按需调用 load_skill 读取正文；勿臆造未列出的 id）：");
        for (SkillSummary row : rows) {
            out.append("\n- ").append(row.id()).append(": ").append(row.description());
        }
        return out.toString();
    }
}
