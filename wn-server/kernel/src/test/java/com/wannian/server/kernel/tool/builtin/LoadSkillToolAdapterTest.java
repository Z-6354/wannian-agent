package com.wannian.server.kernel.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.skill.EmptySkillCatalog;
import com.wannian.server.kernel.skill.SkillCatalog;
import com.wannian.server.kernel.skill.SkillSummary;
import com.wannian.server.kernel.tool.BuiltinToolNames;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 0.2.4-P：load_skill 未知 id / 未接线失败语义。 */
class LoadSkillToolAdapterTest {

    @Test
    void unavailableWhenCatalogNull() {
        LoadSkillToolAdapter adapter = LoadSkillToolAdapter.unavailable();
        ToolAdapterResult result = adapter.execute(request("{\"skill_id\":\"x\"}"));
        assertThat(result)
                .isInstanceOfSatisfying(
                        ToolAdapterResult.Failed.class,
                        failed -> assertThat(failed.code()).isEqualTo(ErrorCodes.TOOL_UNAVAILABLE));
    }

    @Test
    void unknownIdFailsWithoutFakeBody() {
        LoadSkillToolAdapter adapter = new LoadSkillToolAdapter(EmptySkillCatalog.INSTANCE);
        ToolAdapterResult result = adapter.execute(request("{\"skill_id\":\"no-such\"}"));
        assertThat(result)
                .isInstanceOfSatisfying(
                        ToolAdapterResult.Failed.class,
                        failed -> {
                            assertThat(failed.code()).isEqualTo(ErrorCodes.TOOL_INVALID_ARGUMENTS);
                            assertThat(failed.message()).contains("no-such");
                        });
    }

    @Test
    void loadsBodyForKnownId() {
        SkillCatalog catalog =
                new SkillCatalog() {
                    @Override
                    public List<SkillSummary> listSummaries() {
                        return List.of(new SkillSummary("demo", "demo", "desc", "1"));
                    }

                    @Override
                    public Optional<String> loadBody(String skillId) {
                        return "demo".equals(skillId) ? Optional.of("完整正文") : Optional.empty();
                    }
                };
        LoadSkillToolAdapter adapter = new LoadSkillToolAdapter(catalog);
        ToolAdapterResult result = adapter.execute(request("{\"skill_id\":\"demo\"}"));
        assertThat(result)
                .isInstanceOfSatisfying(
                        ToolAdapterResult.Succeeded.class,
                        ok ->
                                assertThat(ok.observationJson())
                                        .contains("完整正文")
                                        .contains("demo"));
    }

    private static ToolAdapterRequest request(String argsJson) {
        return new ToolAdapterRequest(
                "op-1", BuiltinToolNames.LOAD_SKILL, argsJson, List.of(), null);
    }
}
