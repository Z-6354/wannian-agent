package com.wannian.server.kernel.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 0.2.1-A：稳定 code 只登记一处；历史 reasonCode 已纳入。 */
class ErrorCodesTest {

    @Test
    void registersHistoricalReasonCodesOnce() {
        assertThat(ErrorCodes.allCodes())
                .contains(
                        "ILLEGAL_ARGUMENT",
                        "CONVERSATION_NOT_FOUND",
                        "REVISION_CONFLICT",
                        "PERSISTENCE_FAILED",
                        "RETRYABLE_BUSY",
                        "OWNER_MISMATCH",
                        "CLAIM_EXPIRED",
                        "MANAGE_UNCONFIGURED",
                        "MODEL_NOT_ENABLED",
                        "DEPENDENCY_UNAVAILABLE",
                        "TOOLS_NOT_ENABLED",
                        "INVALID_MODEL_OUTPUT",
                        "BUDGET_EXHAUSTED",
                        "BUDGET_DECISIONS_EXHAUSTED",
                        "BUDGET_SOFT_DEADLINE",
                        "BUDGET_HARD_DEADLINE",
                        "MODEL_TIMEOUT",
                        "CANCELLED");
        assertThat(ErrorCodes.allCodes().stream().distinct().count())
                .isEqualTo(ErrorCodes.allCodes().size());
    }

    @Test
    void categoriesCoverKernelReferenceBuckets() {
        assertThat(ErrorCodes.categoryOf(ErrorCodes.ILLEGAL_ARGUMENT))
                .contains(ErrorCategory.VALIDATION);
        assertThat(ErrorCodes.categoryOf(ErrorCodes.REVISION_CONFLICT))
                .contains(ErrorCategory.CONFLICT);
        assertThat(ErrorCodes.categoryOf(ErrorCodes.MODEL_NOT_ENABLED))
                .contains(ErrorCategory.POLICY_DENIED);
        assertThat(ErrorCodes.categoryOf(ErrorCodes.DEPENDENCY_UNAVAILABLE))
                .contains(ErrorCategory.DEPENDENCY_UNAVAILABLE);
        assertThat(ErrorCodes.categoryOf(ErrorCodes.COMMIT_FAILED))
                .contains(ErrorCategory.EXECUTION_FAILED);
        assertThat(ErrorCodes.categoryOf(ErrorCodes.INTERNAL_DEFECT))
                .contains(ErrorCategory.INTERNAL_DEFECT);
    }

    @Test
    void requireRegisteredRejectsUnknown() {
        assertThat(ErrorCodes.requireRegistered(ErrorCodes.CLAIM_FAILED))
                .isEqualTo(ErrorCodes.CLAIM_FAILED);
        assertThatThrownBy(() -> ErrorCodes.requireRegistered("NOT_A_REAL_CODE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
