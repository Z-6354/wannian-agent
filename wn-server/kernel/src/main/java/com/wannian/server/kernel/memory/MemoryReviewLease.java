package com.wannian.server.kernel.memory;

import java.util.Objects;

/** Immutable fencing credential issued by the Review job claimant. */
public record MemoryReviewLease(String jobId, String ownerToken) {

    public MemoryReviewLease {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(ownerToken, "ownerToken");
        if (jobId.isBlank() || ownerToken.isBlank()) {
            throw new IllegalArgumentException("jobId/ownerToken must not be blank");
        }
    }
}
