package com.wannian.server.kernel.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** MemoryDecay 固定半衰期与弱 B（A+B）钉表。 */
class MemoryDecayTest {

    @Test
    void highImportanceAfterOneYearStillAboveHalfAndNotTombstoned() {
        Duration age = Duration.ofDays(365);
        double score = MemoryDecay.score(0.95, age);

        assertThat(score).isGreaterThan(0.5);
        assertThat(MemoryDecay.shouldTombstone(0.95, age)).isFalse();
    }

    @Test
    void lowImportancePastEphemeralWindowIsTombstoned() {
        assertThat(MemoryDecay.shouldTombstone(0.2, Duration.ofDays(14).plusSeconds(1)))
                .isTrue();
        assertThat(MemoryDecay.shouldTombstone(0.2, Duration.ofDays(15))).isTrue();
    }

    @Test
    void lowImportanceAtEightDaysNotTombstoned() {
        Duration age = Duration.ofDays(8);

        assertThat(MemoryDecay.shouldTombstone(0.2, age)).isFalse();
        assertThat(MemoryDecay.score(0.2, age)).isGreaterThan(MemoryDecay.TOMBSTONE_EPSILON);
    }

    @Test
    void highImportanceAfterTenYearsNotTombstoned() {
        Duration age = Duration.ofDays(3650);

        assertThat(MemoryDecay.shouldTombstone(0.95, age)).isFalse();
        assertThat(MemoryDecay.score(0.95, age)).isGreaterThan(0.5);
    }

    @Test
    void zeroAgeDecayIsOneAndInvalidImportanceClampsToZero() {
        assertThat(MemoryDecay.decay(Duration.ZERO)).isEqualTo(1.0);

        double nanScore = MemoryDecay.score(Double.NaN, Duration.ZERO);
        double infScore = MemoryDecay.score(Double.POSITIVE_INFINITY, Duration.ZERO);
        double expectedFloor = MemoryDecay.WEIGHT_RECENCY * 1.0;

        assertThat(nanScore).isEqualTo(expectedFloor);
        assertThat(infScore).isEqualTo(expectedFloor);
    }

    @Test
    void negativeAgeUsesZeroForScoreAndNeverTombstones() {
        Duration negative = Duration.ofDays(-3);

        assertThat(MemoryDecay.decay(negative)).isEqualTo(1.0);
        assertThat(MemoryDecay.score(0.2, negative)).isEqualTo(MemoryDecay.score(0.2, Duration.ZERO));
        assertThat(MemoryDecay.shouldTombstone(0.2, negative)).isFalse();
    }
}
