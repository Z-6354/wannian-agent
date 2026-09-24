package com.wannian.server.kernel.memory;

import java.util.List;
import java.util.Objects;

/** Applies a complete Review result batch under its durable job lease. */
public interface MemoryReviewBatchApplier {

    /**
     * Atomically applies the approved changes and completes the leased Review job.
     *
     * <p>Implementations must fence the lease in the same transaction as durable writes and make
     * replays for the same job/draft idempotent. A completed job replay is a successful no-op.
     */
    ApplyResult applyAndComplete(MemoryReviewLease lease, List<ApprovedMemoryChange> changes);

    sealed interface ApplyResult {
        record Completed(boolean replayed) implements ApplyResult {}

        record LeaseLost() implements ApplyResult {}
    }
}
