package com.wannian.server.kernel.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultMemoryRecallTest {

    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    @Test
    void ranksByDecayScoreThenNewestAndAppliesTopN() {
        StoredMemoryRecord oldImportant = row("important", 1.0, NOW.minusSeconds(40L * 86400));
        double tiedScore = MemoryDecay.score(0.5, java.time.Duration.ofDays(2));
        double newImportance =
                (tiedScore - MemoryDecay.WEIGHT_RECENCY * MemoryDecay.decay(java.time.Duration.ofDays(1)))
                        / MemoryDecay.WEIGHT_IMPORTANCE;
        StoredMemoryRecord sameScoreOld =
                row("tie-old", 0.5, NOW.minusSeconds(2L * 86400));
        StoredMemoryRecord sameScoreNew =
                row("tie-new", newImportance, NOW.minusSeconds(86400));
        Store store = new Store(List.of(oldImportant, sameScoreOld, sameScoreNew));

        List<StoredMemoryRecord> result = new DefaultMemoryRecall(store)
                .recallTop(CompanionIdentity.YANHUO, NOW, 2);

        assertThat(result).extracting(StoredMemoryRecord::id)
                .containsExactly("important", "tie-new");
        assertThatThrownBy(() -> result.add(oldImportant)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nonPositiveLimitReturnsEmptyWithoutReadingStore() {
        Store store = new Store(List.of());

        assertThat(new DefaultMemoryRecall(store).recallTop(CompanionIdentity.YANHUO, NOW, 0))
                .isEmpty();
        assertThat(store.listCalls).isZero();
    }

    private static StoredMemoryRecord row(String id, double importance, Instant createdAt) {
        return new StoredMemoryRecord(
                id,
                CompanionIdentity.YANHUO,
                "subject." + id,
                id,
                ContentKind.USER_FACT,
                SourceKind.EXPLICIT,
                MemoryScope.COMPANION,
                importance,
                null,
                MemoryLifecycle.ACTIVE,
                "test",
                1,
                createdAt,
                null,
                null);
    }

    private static final class Store implements MemoryStore {
        private final List<StoredMemoryRecord> rows;
        private int listCalls;

        private Store(List<StoredMemoryRecord> rows) {
            this.rows = rows;
        }

        @Override
        public Optional<StoredMemoryRecord> findById(String id) {
            return Optional.empty();
        }

        @Override
        public List<StoredMemoryRecord> listByCompanion(
                CompanionIdentity companionIdentity, MemoryLifecycle lifecycle) {
            listCalls++;
            return rows.stream().filter(row -> row.lifecycle() == lifecycle).toList();
        }

        @Override
        public List<StoredMemoryRecord> listActive(CompanionIdentity companionIdentity) {
            return listByCompanion(companionIdentity, MemoryLifecycle.ACTIVE);
        }
    }
}
