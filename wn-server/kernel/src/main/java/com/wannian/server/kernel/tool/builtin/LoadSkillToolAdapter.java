package com.wannian.server.kernel.tool.builtin;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.skill.SkillCatalog;
import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code load_skill}：按 id 读取已授权 Skill 正文（只读）。
 *
 * <p>未接线 Catalog → {@code TOOL_UNAVAILABLE}。未知/未授权 id → {@code TOOL_INVALID_ARGUMENTS}。
 */
public final class LoadSkillToolAdapter implements ToolAdapter {

    private final SkillCatalog catalog;

    public LoadSkillToolAdapter(SkillCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    private LoadSkillToolAdapter() {
        this.catalog = null;
    }

    /** 未提供 Skill 端口时的占位；执行时明确不可用。 */
    public static LoadSkillToolAdapter unavailable() {
        return new LoadSkillToolAdapter();
    }

    @Override
    public ToolAdapterResult execute(ToolAdapterRequest request) {
        Objects.requireNonNull(request, "request");
        if (catalog == null) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_UNAVAILABLE, "本回合未接线 SkillCatalog", false);
        }
        try {
            Map<String, String> fields = ToolJson.parseFlatObject(request.argumentsJson());
            String skillId = ToolJson.requireString(fields, "skill_id").trim();
            if (skillId.isEmpty()) {
                return new ToolAdapterResult.Failed(
                        ErrorCodes.TOOL_INVALID_ARGUMENTS, "skill_id 不得空白", false);
            }
            Optional<String> body = catalog.loadBody(skillId);
            if (body.isEmpty()) {
                return new ToolAdapterResult.Failed(
                        ErrorCodes.TOOL_INVALID_ARGUMENTS,
                        "未知或未授权的 skill_id: " + skillId,
                        false);
            }
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            out.put("skill_id", skillId);
            out.put("body", body.get());
            return new ToolAdapterResult.Succeeded(ToolJson.object(out));
        } catch (ToolJson.ToolJsonException ex) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_INVALID_ARGUMENTS, ex.getMessage(), false);
        }
    }
}
