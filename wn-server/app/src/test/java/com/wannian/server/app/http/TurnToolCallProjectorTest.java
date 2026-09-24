package com.wannian.server.app.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.kernel.journal.JournalActor;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.RunJournalEntry;
import com.wannian.server.kernel.journal.TurnStepStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnToolCallProjectorTest {

    @Test
    void projectsToolCallsInStepOrder() {
        Instant t0 = Instant.parse("2026-09-24T02:00:00Z");
        Instant t1 = Instant.parse("2026-09-24T02:00:01Z");
        TurnStepStore store =
                turnId ->
                        List.of(
                                entry(
                                        turnId,
                                        1,
                                        JournalKind.USER_INPUT,
                                        "{}",
                                        t0,
                                        t0),
                                entry(
                                        turnId,
                                        2,
                                        JournalKind.TOOL_CALL,
                                        "{\"name\":\"remember_fact\",\"argumentsJson\":\"{\\\"claim\\\":\\\"x\\\"}\"}",
                                        t0,
                                        t1),
                                entry(
                                        turnId,
                                        3,
                                        JournalKind.TOOL_CALL,
                                        "{\"name\":\"search_memory\",\"argumentsJson\":\"{\\\"query\\\":\\\"寒若\\\"}\"}",
                                        t1,
                                        t1));
        TurnToolCallProjector projector = new TurnToolCallProjector(store, new ObjectMapper());

        List<ToolCallView> views = projector.listForTurn("turn-1");

        assertThat(views).hasSize(2);
        assertThat(views.get(0).name()).isEqualTo("remember_fact");
        assertThat(views.get(0).argumentsJson()).contains("claim");
        assertThat(views.get(0).startedAt()).isEqualTo(t0.toString());
        assertThat(views.get(0).finishedAt()).isEqualTo(t1.toString());
        assertThat(views.get(1).name()).isEqualTo("search_memory");
    }

    @Test
    void blankTurnIdReturnsEmpty() {
        TurnToolCallProjector projector =
                new TurnToolCallProjector(turnId -> List.of(), new ObjectMapper());
        assertThat(projector.listForTurn("")).isEmpty();
        assertThat(projector.listForTurn(null)).isEmpty();
    }

    private static RunJournalEntry entry(
            String turnId,
            int stepNo,
            JournalKind kind,
            String requestJson,
            Instant started,
            Instant finished) {
        return new RunJournalEntry(
                "id-" + stepNo,
                turnId,
                "conv-1",
                stepNo,
                JournalActor.AGENT,
                kind,
                requestJson,
                "{}",
                "SUCCEEDED",
                null,
                started,
                finished);
    }
}
