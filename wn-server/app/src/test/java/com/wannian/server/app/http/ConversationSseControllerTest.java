package com.wannian.server.app.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.app.persistence.SqliteOutboxQuery;
import com.wannian.server.app.stream.RunEvent;
import com.wannian.server.app.stream.RunEventBus;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationSseControllerTest {

    @Test
    void recentReplayExcludesTerminalForeignAndMalformedTurns() {
        String conversationId = UUID.randomUUID().toString();
        TurnRepository turns = mock(TurnRepository.class);
        RunEventBus bus = new RunEventBus();
        ConversationSseController controller =
                new ConversationSseController(mock(SqliteOutboxQuery.class), bus, turns);

        Turn active = turn(conversationId, TurnStatus.RUNNING);
        Turn complete = turn(conversationId, TurnStatus.COMPLETED);
        Turn failed = turn(conversationId, TurnStatus.FAILED);
        Turn cancelled = turn(conversationId, TurnStatus.CANCELLED);
        Turn foreign = turn(UUID.randomUUID().toString(), TurnStatus.RUNNING);
        for (Turn turn : List.of(active, complete, failed, cancelled, foreign)) {
            when(turns.find(turn.id())).thenReturn(Optional.of(turn));
        }

        publish(bus, conversationId, active.id().asString());
        publish(bus, conversationId, complete.id().asString());
        publish(bus, conversationId, failed.id().asString());
        publish(bus, conversationId, cancelled.id().asString());
        publish(bus, conversationId, foreign.id().asString());
        publish(bus, conversationId, "not-a-uuid");
        publish(bus, UUID.randomUUID().toString(), active.id().asString());

        assertThat(controller.replayableRecent(conversationId))
                .extracting(RunEvent::turnId)
                .containsExactly(active.id().asString());
    }

    private static Turn turn(String conversationId, TurnStatus status) {
        TurnId id = new TurnId(UUID.randomUUID());
        return mock(Turn.class, invocation -> {
            if (invocation.getMethod().getName().equals("conversationId")) {
                return new ConversationId(UUID.fromString(conversationId));
            }
            if (invocation.getMethod().getName().equals("status")) {
                return status;
            }
            if (invocation.getMethod().getName().equals("id")) {
                return id;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static void publish(RunEventBus bus, String conversationId, String turnId) {
        bus.publish(
                new RunEvent(
                        "reply.delta",
                        conversationId,
                        turnId,
                        "execution",
                        1L,
                        "{}",
                        Instant.now()));
    }
}
