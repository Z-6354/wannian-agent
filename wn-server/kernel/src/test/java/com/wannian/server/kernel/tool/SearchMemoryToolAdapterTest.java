package com.wannian.server.kernel.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryLifecycle;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.MemorySearchLimits;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.SourceKind;
import com.wannian.server.kernel.memory.StoredMemoryRecord;
import com.wannian.server.kernel.tool.builtin.SearchMemoryToolAdapter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SearchMemoryToolAdapterTest {

    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    @Test
    void validatesQueryAndLimitAndReportsMissingWiring() {
        assertFailure(
                SearchMemoryToolAdapter.unavailable().execute(request("{\"query\":\"tea\"}")),
                ErrorCodes.TOOL_UNAVAILABLE);
        assertFailure(
                adapter(new Store(List.of()), null).execute(request("{\"query\":\" \"}")),
                ErrorCodes.TOOL_INVALID_ARGUMENTS);
        assertFailure(
                adapter(new Store(List.of()), null)
                        .execute(request("{\"query\":\"tea\",\"limit\":\"0\"}")),
                ErrorCodes.TOOL_INVALID_ARGUMENTS);
    }

    @Test
    void searchesActiveClaimAndSubjectWithLimitAndTouchesReturnedIdsOnly() {
        Store store = new Store(List.of(
                row("claim", "tea.preference", "I prefer tea", 0.7, NOW.minusSeconds(86400)),
                row("subject", "tea.preference", "No match in claim", 0.4, NOW.minusSeconds(3L * 86400)),
                row("inactive", "tea.old", "Tea", 1.0, NOW, MemoryLifecycle.FORGOTTEN)));
        List<String> touched = new ArrayList<>();
        ToolAdapterResult result =
                adapter(store, (ids, now) -> touched.addAll(ids))
                        .execute(request("{\"query\":\"TEA\",\"limit\":\"1\"}"));

        assertThat(result).isInstanceOf(ToolAdapterResult.Succeeded.class);
        String json = ((ToolAdapterResult.Succeeded) result).observationJson();
        assertThat(json).contains("\"count\":1").contains("\"id\":\"claim\"");
        assertThat(json).doesNotContain("\"id\":\"inactive\"")
                .doesNotContain("\"id\":\"subject\"");
        assertThat(touched).containsExactly("claim");
    }

    @Test
    void touchFailureIsBestEffortAndSearchStillSucceeds() {
        ToolAdapterResult result =
                adapter(
                                new Store(List.of(row("one", "tea", "tea", 0.5, NOW))),
                                (ids, now) -> {
                                    throw new IllegalStateException("busy");
                                })
                        .execute(request("{\"query\":\"tea\"}"));
        assertThat(result).isInstanceOf(ToolAdapterResult.Succeeded.class);
    }

    @Test
    void searchMemoryIsLockedAndRegisteredInBuiltinPool() {
        assertThat(ToolUsePolicy.isLocked(BuiltinToolNames.SEARCH_MEMORY)).isTrue();
        assertThat(ToolUsePolicy.productDefault(BuiltinToolNames.SEARCH_MEMORY))
                .isEqualTo(ToolUsePolicy.ConfigState.LOCKED);
        assertThat(BuiltinToolPool.find(BuiltinToolNames.SEARCH_MEMORY)).isPresent();
        assertThat(BuiltinToolPool.registrationOf(BuiltinToolNames.SEARCH_MEMORY).toolName())
                .isEqualTo(BuiltinToolNames.SEARCH_MEMORY);
    }

    @Test
    void runtimeRejectsUnknownFieldsAndLimitsBeyondConfiguredMaximum() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(
                BuiltinToolPool.registrationOf(
                        BuiltinToolNames.SEARCH_MEMORY, adapter(new Store(List.of()), null)));
        DefaultToolRuntime runtime = new DefaultToolRuntime(catalog);
        ToolExecutionContext context = new ToolExecutionContext("op", "turn", "attempt", List.of(), null);

        ToolExecutionOutcome unknownField = runtime.execute(new ToolInvocation(
                "call1", BuiltinToolNames.SEARCH_MEMORY,
                "{\"query\":\"tea\",\"extra\":\"no\"}"), context);
        ToolExecutionOutcome overLimit = runtime.execute(new ToolInvocation(
                "call2", BuiltinToolNames.SEARCH_MEMORY,
                "{\"query\":\"tea\",\"limit\":\"4\"}"), context);

        assertThat(unknownField).isInstanceOf(ToolExecutionOutcome.Rejected.class);
        assertThat(((ToolExecutionOutcome.Rejected) unknownField).code())
                .isEqualTo(ErrorCodes.TOOL_INVALID_ARGUMENTS);
        assertThat(overLimit).isInstanceOf(ToolExecutionOutcome.Failed.class);
        assertThat(((ToolExecutionOutcome.Failed) overLimit).code())
                .isEqualTo(ErrorCodes.TOOL_INVALID_ARGUMENTS);
    }

    private static ToolAdapterRequest request(String args) {
        return new ToolAdapterRequest("op", BuiltinToolNames.SEARCH_MEMORY, args, List.of(), null);
    }

    private static SearchMemoryToolAdapter adapter(
            MemoryStore store, com.wannian.server.kernel.memory.MemoryRecallTouch touch) {
        return new SearchMemoryToolAdapter(
                CompanionIdentity.YANHUO,
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                touch,
                limits());
    }

    private static MemorySearchLimits limits() { return new MemorySearchLimits(2, 3); }

    private static void assertFailure(ToolAdapterResult result, String code) {
        assertThat(result).isInstanceOf(ToolAdapterResult.Failed.class);
        assertThat(((ToolAdapterResult.Failed) result).code()).isEqualTo(code);
    }

    private static StoredMemoryRecord row(String id, String subject, String claim, double importance, Instant createdAt) {
        return row(id, subject, claim, importance, createdAt, MemoryLifecycle.ACTIVE);
    }

    private static StoredMemoryRecord row(String id, String subject, String claim, double importance,
            Instant createdAt, MemoryLifecycle lifecycle) {
        return new StoredMemoryRecord(id, CompanionIdentity.YANHUO, subject, claim, ContentKind.USER_FACT,
                SourceKind.EXPLICIT, MemoryScope.COMPANION, importance, null, lifecycle, "test", 1,
                createdAt, null, null);
    }

    private static final class Store implements MemoryStore {
        private final List<StoredMemoryRecord> rows;
        private Store(List<StoredMemoryRecord> rows) { this.rows = rows; }
        @Override public Optional<StoredMemoryRecord> findById(String id) { return Optional.empty(); }
        @Override public List<StoredMemoryRecord> listByCompanion(CompanionIdentity identity, MemoryLifecycle lifecycle) {
            return rows.stream().filter(row -> row.lifecycle() == lifecycle).toList();
        }
        @Override public List<StoredMemoryRecord> listActive(CompanionIdentity identity) {
            return listByCompanion(identity, MemoryLifecycle.ACTIVE);
        }
    }
}
