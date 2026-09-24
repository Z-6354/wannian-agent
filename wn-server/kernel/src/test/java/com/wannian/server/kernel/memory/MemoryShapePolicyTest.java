package com.wannian.server.kernel.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemoryShapePolicyTest {

    private final DefaultMemoryShape shape = new DefaultMemoryShape();
    private final SecretOnlyMemoryPolicy policy = new SecretOnlyMemoryPolicy();

    @Test
    void rejectsSecretButAcceptsOrdinaryLowImportancePreference() {
        MemoryToolDraft secret = draft("账号密码是 hunter2", 0.8);
        MemoryToolDraft ordinaryLowImportance = draft("我喜欢喝龙井", 0.05);

        assertThat(policy.evaluate(accepted(secret)))
                .isInstanceOf(MemoryPolicy.PolicyResult.Rejected.class)
                .extracting(result -> ((MemoryPolicy.PolicyResult.Rejected) result).code())
                .isEqualTo(SecretOnlyMemoryPolicy.REJECT_CODE);
        assertThat(policy.evaluate(accepted(ordinaryLowImportance)))
                .isInstanceOf(MemoryPolicy.PolicyResult.Accepted.class);
    }

    @Test
    void clampsImportanceAndLeavesRelativeOrDeicticClaimUntouched() {
        String unnormalizedClaim = "我今天在这里喜欢喝龙井";
        MemoryToolDraft belowRange = draft(unnormalizedClaim, -0.25);
        MemoryToolDraft aboveRange = draft("我喜欢喝咖啡", 1.25);

        MemoryToolDraft low = accepted(belowRange);
        MemoryToolDraft high = accepted(aboveRange);

        assertThat(low.importance()).isZero();
        assertThat(low.claim()).isEqualTo(unnormalizedClaim);
        assertThat(high.importance()).isEqualTo(1.0);
        assertThat(policy.evaluate(low)).isInstanceOf(MemoryPolicy.PolicyResult.Accepted.class);
    }

    private MemoryToolDraft accepted(MemoryToolDraft draft) {
        return ((MemoryShape.ShapeResult.Accepted) shape.shape(draft)).shaped();
    }

    private static MemoryToolDraft draft(String claim, double importance) {
        return new MemoryToolDraft(
                CompanionIdentity.YANHUO,
                "pref.tea",
                claim,
                ContentKind.USER_PREFERENCE,
                SourceKind.EXPLICIT,
                MemoryScope.COMPANION,
                importance,
                null);
    }
}
