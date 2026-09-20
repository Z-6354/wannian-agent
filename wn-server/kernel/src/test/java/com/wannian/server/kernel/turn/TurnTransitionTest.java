package com.wannian.server.kernel.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.turn.TurnStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Turn 状态机：合法路径与禁止跳转。 */
class TurnTransitionTest {

    private final Instant t0 = Instant.parse("2026-09-18T10:00:00Z");
    private final Instant t1 = Instant.parse("2026-09-18T10:00:30Z");
    private final Instant tExpired = Instant.parse("2026-09-18T10:02:00Z");

    @Test
    void happyPathReceivedToCommitting() {
        Turn turn = newReceived();
        assertThat(turn.status()).isEqualTo(TurnStatus.RECEIVED);
        assertThat(turn.revision()).isEqualTo(1L);

        turn.claim(1L, new ExecutionClaim("local-primary", t1), t0);
        assertThat(turn.status()).isEqualTo(TurnStatus.CLAIMED);
        assertThat(turn.revision()).isEqualTo(2L);

        turn.start(t0);
        assertThat(turn.status()).isEqualTo(TurnStatus.RUNNING);
        assertThat(turn.revision()).isEqualTo(3L);

        turn.beginCommit(t0);
        assertThat(turn.status()).isEqualTo(TurnStatus.COMMITTING);
        assertThat(turn.revision()).isEqualTo(4L);
    }

    @Test
    void cannotStartFromReceivedWithoutClaim() {
        Turn turn = newReceived();
        assertThatThrownBy(() -> turn.start(t0))
                .isInstanceOf(TurnTransitionException.class)
                .hasMessageContaining("RECEIVED");
    }

    @Test
    void cannotGoToRunningFromCompleted() {
        Turn turn =
                Turn.reconstitute(
                        TurnId.generate(),
                        ConversationId.generate(),
                        "req",
                        MessageId.generate(),
                        TurnStatus.COMPLETED,
                        5L,
                        "local-primary",
                        t1,
                        null,
                        t0);
        assertThatThrownBy(() -> turn.start(t0))
                .isInstanceOf(TurnTransitionException.class);
    }

    @Test
    void claimRevisionMismatchIsRejected() {
        Turn turn = newReceived();
        assertThatThrownBy(
                        () -> turn.claim(99L, new ExecutionClaim("local-primary", t1), t0))
                .isInstanceOf(TurnTransitionException.class)
                .extracting(ex -> ((TurnTransitionException) ex).reasonCode())
                .isEqualTo("REVISION_CONFLICT");
    }

    @Test
    void expiredClaimCannotStart() {
        Turn turn = newReceived();
        turn.claim(1L, new ExecutionClaim("local-primary", t1), t0);
        assertThatThrownBy(() -> turn.start(tExpired))
                .isInstanceOf(TurnTransitionException.class)
                .extracting(ex -> ((TurnTransitionException) ex).reasonCode())
                .isEqualTo("CLAIM_EXPIRED");
    }

    @Test
    void cancelFromRunningSucceeds() {
        Turn turn = newReceived();
        turn.claim(1L, new ExecutionClaim("local-primary", t1), t0);
        turn.start(t0);
        turn.cancel(t0);
        assertThat(turn.status()).isEqualTo(TurnStatus.CANCELLED);
    }

    @Test
    void cannotCancelFromCommitting() {
        Turn turn = newReceived();
        turn.claim(1L, new ExecutionClaim("local-primary", t1), t0);
        turn.start(t0);
        turn.beginCommit(t0);
        assertThatThrownBy(() -> turn.cancel(t0))
                .isInstanceOf(TurnTransitionException.class);
    }

    @Test
    void failFromRunningRecordsErrorCode() {
        Turn turn = newReceived();
        turn.claim(1L, new ExecutionClaim("local-primary", t1), t0);
        turn.start(t0);
        turn.fail("MODEL_TIMEOUT", t0);
        assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(turn.errorCode()).isEqualTo("MODEL_TIMEOUT");
    }

    @Test
    void cannotFailFromCompleted() {
        Turn turn =
                Turn.reconstitute(
                        TurnId.generate(),
                        ConversationId.generate(),
                        "req",
                        MessageId.generate(),
                        TurnStatus.FAILED,
                        3L,
                        null,
                        null,
                        "X",
                        t0);
        assertThatThrownBy(() -> turn.beginCommit(t0))
                .isInstanceOf(TurnTransitionException.class);
    }

    @Test
    void expiredClaimIsRejectedWithoutStateChange() {
        Turn turn = newReceived();
        assertThatThrownBy(() -> turn.claim(1L, new ExecutionClaim("attempt-1", t0), t0))
                .isInstanceOf(TurnTransitionException.class)
                .extracting(ex -> ((TurnTransitionException) ex).reasonCode())
                .isEqualTo("CLAIM_EXPIRED");
        assertThat(turn.status()).isEqualTo(TurnStatus.RECEIVED);
        assertThat(turn.revision()).isEqualTo(1L);
        assertThat(turn.executionId()).isNull();
    }

    @Test
    void expiredOwnerCannotBeginCommit() {
        Turn turn = newReceived();
        turn.claim(1L, new ExecutionClaim("attempt-1", t1), t0);
        turn.start(t0);
        assertThatThrownBy(() -> turn.beginCommit(tExpired))
                .isInstanceOf(TurnTransitionException.class)
                .extracting(ex -> ((TurnTransitionException) ex).reasonCode())
                .isEqualTo("CLAIM_EXPIRED");
        assertThat(turn.status()).isEqualTo(TurnStatus.RUNNING);
        assertThat(turn.revision()).isEqualTo(3L);
    }

    @Test
    void reconstituteRejectsContradictorySnapshot() {
        assertThatThrownBy(
                        () ->
                                Turn.reconstitute(
                                        TurnId.generate(),
                                        ConversationId.generate(),
                                        "req",
                                        MessageId.generate(),
                                        TurnStatus.RUNNING,
                                        2L,
                                        null,
                                        null,
                                        null,
                                        t0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Turn newReceived() {
        return Turn.received(
                TurnId.generate(),
                ConversationId.generate(),
                "client-req-1",
                MessageId.generate(),
                t0);
    }
}
