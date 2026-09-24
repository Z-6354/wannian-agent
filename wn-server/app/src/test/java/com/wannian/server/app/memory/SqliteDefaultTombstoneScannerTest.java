package com.wannian.server.app.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryCommand;
import com.wannian.server.kernel.memory.MemoryLifecycle;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.SourceKind;
import com.wannian.server.kernel.memory.StoredMemoryRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqliteDefaultTombstoneScannerTest {

    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    @Test
    void enforcesBatchLimitAndContinuesAfterRejectedCandidateWithoutCallingLlm() {
        // importance 0.1 and age 20d satisfy weak-B clause 2; order is by oldest first.
        Store store = new Store(List.of(row("later", NOW.minusSeconds(30L * 86400)),
                row("reject", NOW.minusSeconds(40L * 86400)),
                row("apply", NOW.minusSeconds(50L * 86400))));
        List<String> attempted = new ArrayList<>();
        MemoryCommand command = new MemoryCommand() {
            @Override public CommandResult correct(String id, long revision, ApprovedMemoryChange replacement) {
                throw new UnsupportedOperationException();
            }
            @Override public CommandResult forget(String id, long revision) {
                throw new UnsupportedOperationException();
            }
            @Override public CommandResult applyReview(ApprovedMemoryChange change) {
                throw new AssertionError("弱 B 不得调用 Review / LLM 写入口");
            }
            @Override public CommandResult tombstone(String id, long revision) {
                attempted.add(id);
                return id.equals("reject")
                        ? new CommandResult.Rejected(ErrorCodes.REVISION_CONFLICT, "stale")
                        : new CommandResult.Applied(id, revision + 1);
            }
        };
        SqliteDefaultTombstoneScanner scanner = new SqliteDefaultTombstoneScanner(
                store, command, Clock.fixed(NOW, ZoneOffset.UTC), 2);

        int changed = scanner.scanOnce();

        assertThat(changed).isEqualTo(1);
        assertThat(attempted).containsExactly("apply", "reject");
    }

    @Test
    void doesNotAttemptNonCandidates() {
        Store store = new Store(List.of(row("new", NOW.minusSeconds(3L * 86400)),
                row("important", NOW.minusSeconds(90L * 86400), 0.9)));
        List<String> attempted = new ArrayList<>();
        MemoryCommand command = new MemoryCommand() {
            @Override public CommandResult correct(String id, long revision, ApprovedMemoryChange replacement) { throw new UnsupportedOperationException(); }
            @Override public CommandResult forget(String id, long revision) { throw new UnsupportedOperationException(); }
            @Override public CommandResult applyReview(ApprovedMemoryChange change) { throw new AssertionError(); }
            @Override public CommandResult tombstone(String id, long revision) {
                attempted.add(id);
                return new CommandResult.Applied(id, revision + 1);
            }
        };

        assertThat(new SqliteDefaultTombstoneScanner(
                store, command, Clock.fixed(NOW, ZoneOffset.UTC), 5).scanOnce()).isZero();
        assertThat(attempted).isEmpty();
    }

    private static StoredMemoryRecord row(String id, Instant createdAt) {
        return row(id, createdAt, 0.1);
    }

    private static StoredMemoryRecord row(String id, Instant createdAt, double importance) {
        return new StoredMemoryRecord(id, CompanionIdentity.YANHUO, "subject." + id, "claim " + id,
                ContentKind.USER_FACT, SourceKind.EXPLICIT, MemoryScope.COMPANION, importance, null,
                MemoryLifecycle.ACTIVE, "test", 1, createdAt, null, null);
    }

    private static final class Store implements MemoryStore {
        private final List<StoredMemoryRecord> rows;
        private Store(List<StoredMemoryRecord> rows) { this.rows = rows; }
        @Override public Optional<StoredMemoryRecord> findById(String id) { return Optional.empty(); }
        @Override public List<StoredMemoryRecord> listByCompanion(CompanionIdentity companion, MemoryLifecycle lifecycle) {
            return rows.stream().filter(row -> row.lifecycle() == lifecycle).toList();
        }
        @Override public List<StoredMemoryRecord> listActive(CompanionIdentity companion) {
            return listByCompanion(companion, MemoryLifecycle.ACTIVE);
        }
    }
}
