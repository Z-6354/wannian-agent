package com.wannian.server.kernel.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StoredMemoryRecordTest {

    @Test
    void forgottenRecordMayHaveClearedClaimButActiveRecordMayNot() {
        StoredMemoryRecord forgotten = record(MemoryLifecycle.FORGOTTEN, "");

        assertThat(forgotten.claim()).isEmpty();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> record(MemoryLifecycle.ACTIVE, ""))
                .withMessage("ACTIVE 记忆 claim 不得空白");
    }

    private static StoredMemoryRecord record(MemoryLifecycle lifecycle, String claim) {
        return new StoredMemoryRecord(
                "memory-id",
                CompanionIdentity.YANHUO,
                "subject.key",
                claim,
                ContentKind.USER_FACT,
                SourceKind.EXPLICIT,
                MemoryScope.COMPANION,
                0.5,
                null,
                lifecycle,
                "test",
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null);
    }
}
