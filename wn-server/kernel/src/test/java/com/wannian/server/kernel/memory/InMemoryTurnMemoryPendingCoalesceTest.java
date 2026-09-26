package com.wannian.server.kernel.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryTurnMemoryPendingCoalesceTest {

    @Test
    void sameSubjectKeyKeepsLastWriteOnly() {
        InMemoryTurnMemoryPending pending = new InMemoryTurnMemoryPending();
        pending.addMemory(fact("pref.tea", "喜欢红茶"));
        pending.addMemory(fact("pref.coffee", "偶尔喝咖啡"));
        pending.addMemory(fact("pref.tea", "改喝绿茶"));

        List<ApprovedMemoryChange> snapshot = pending.snapshotMemories();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).subjectKey()).isEqualTo("pref.tea");
        assertThat(snapshot.get(0).claim()).isEqualTo("改喝绿茶");
        assertThat(snapshot.get(1).subjectKey()).isEqualTo("pref.coffee");
    }

    private static ApprovedMemoryChange fact(String subjectKey, String claim) {
        return new ApprovedMemoryChange(
                CompanionIdentity.YANHUO,
                subjectKey,
                claim,
                ContentKind.USER_PREFERENCE,
                SourceKind.EXPLICIT,
                MemoryScope.COMPANION,
                0.8,
                null,
                ApprovedMemoryChange.PROPOSE_TOOL_REMEMBER,
                0L);
    }
}
