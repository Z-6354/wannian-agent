package com.wannian.server.kernel.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.DefaultMemoryShape;
import com.wannian.server.kernel.memory.InMemoryTurnMemoryPending;
import com.wannian.server.kernel.memory.SecretOnlyMemoryPolicy;
import com.wannian.server.kernel.tool.builtin.RememberFactToolAdapter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RememberFactToolAdapterTest {

    @Test
    void invalidContentKindListsAllowedValues() {
        InMemoryTurnMemoryPending pending = new InMemoryTurnMemoryPending();
        RememberFactToolAdapter adapter =
                new RememberFactToolAdapter(
                        new DefaultMemoryShape(),
                        new SecretOnlyMemoryPolicy(),
                        com.wannian.server.kernel.memory.CompanionIdentity.YANHUO);
        ToolAdapterRequest request =
                new ToolAdapterRequest(
                        "operation-bad-kind",
                        BuiltinToolNames.REMEMBER_FACT,
                        "{\"claim\":\"我叫小明\",\"subjectKey\":\"identity.name\","
                                + "\"contentKind\":\"identity\",\"importance\":\"0.9\"}",
                        List.of(),
                        pending);

        ToolAdapterResult result = adapter.execute(request);

        assertThat(result)
                .isInstanceOfSatisfying(
                        ToolAdapterResult.Failed.class,
                        failed -> {
                            assertThat(failed.code()).isEqualTo(ErrorCodes.TOOL_INVALID_ARGUMENTS);
                            assertThat(failed.message())
                                    .contains("contentKind 无效")
                                    .contains("USER_FACT")
                                    .contains("USER_PREFERENCE");
                        });
        assertThat(pending.snapshotMemories()).isEmpty();
    }

    @Test
    void missingImportanceIsRejectedAtInputBoundaryBeforeDraftCreation() {
        InMemoryTurnMemoryPending pending = new InMemoryTurnMemoryPending();
        RememberFactToolAdapter adapter =
                new RememberFactToolAdapter(
                        new DefaultMemoryShape(),
                        new SecretOnlyMemoryPolicy(),
                        com.wannian.server.kernel.memory.CompanionIdentity.YANHUO);
        ToolAdapterRequest request =
                new ToolAdapterRequest(
                        "operation-1",
                        BuiltinToolNames.REMEMBER_FACT,
                        "{\"claim\":\"我喜欢喝龙井\",\"subjectKey\":\"pref.tea\",\"contentKind\":\"USER_PREFERENCE\"}",
                        List.of(),
                        pending);

        ToolAdapterResult result = adapter.execute(request);

        assertThat(result)
                .isInstanceOfSatisfying(
                        ToolAdapterResult.Failed.class,
                        failed -> assertThat(failed.code()).isEqualTo(ErrorCodes.TOOL_INVALID_ARGUMENTS));
        assertThat(pending.snapshotMemories()).isEmpty();
    }

    @Test
    void acceptedDraftCarriesGenerationCapturedBeforeTheTurn() {
        InMemoryTurnMemoryPending pending = new InMemoryTurnMemoryPending(Map.of("pref.tea", 7L));
        RememberFactToolAdapter adapter =
                new RememberFactToolAdapter(
                        new DefaultMemoryShape(),
                        new SecretOnlyMemoryPolicy(),
                        com.wannian.server.kernel.memory.CompanionIdentity.YANHUO);
        ToolAdapterRequest request =
                new ToolAdapterRequest(
                        "operation-2",
                        BuiltinToolNames.REMEMBER_FACT,
                        "{\"claim\":\"我喜欢喝龙井\",\"subjectKey\":\"pref.tea\","
                                + "\"contentKind\":\"USER_PREFERENCE\",\"importance\":0.8}",
                        List.of(),
                        pending);

        ToolAdapterResult result = adapter.execute(request);

        assertThat(result).isInstanceOf(ToolAdapterResult.Succeeded.class);
        assertThat(pending.snapshotMemories())
                .singleElement()
                .extracting(ApprovedMemoryChange::expectedGeneration)
                .isEqualTo(7L);
    }
}
