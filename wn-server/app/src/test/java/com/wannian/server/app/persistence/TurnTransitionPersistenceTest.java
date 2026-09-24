package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.ExecutionClaim;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import com.wannian.server.kernel.turn.FreezeCommitResult;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnRepository;
import com.wannian.server.kernel.turn.TurnCommitter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 领域迁移经 {@link TurnRepository#save} 落库：每步 revision +1，冲突不覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TurnTransitionPersistenceTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private TurnCommitter turnCommitter;

    @Autowired
    private TurnRepository turnRepository;

    @BeforeEach
    void clearTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn_commit_plan");
            connection.createStatement().executeUpdate("DELETE FROM turn_step");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
        }
    }

    @Test
    void claimStartBeginCommitEachPersistOneStep() throws Exception {
        TurnId turnId = receiveTurn("persist-req-1");
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        ExecutionClaim claim = new ExecutionClaim("local-primary", now.plusSeconds(60));

        Turn claimed = turnRepository.find(turnId).orElseThrow();
        long revision = claimed.revision();
        claimed.claim(revision, claim, now);
        assertThat(turnRepository.save(claimed, revision, now))
                .isInstanceOf(SaveTurnResult.Saved.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.CLAIMED.name());
        assertThat(revisionOf(turnId)).isEqualTo(2L);
        assertThat(scalar(turnId, "execution_id")).isEqualTo("local-primary");

        Turn running = turnRepository.find(turnId).orElseThrow();
        revision = running.revision();
        running.start(now);
        assertThat(turnRepository.save(running, revision, now)).isInstanceOf(SaveTurnResult.Saved.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.RUNNING.name());
        assertThat(revisionOf(turnId)).isEqualTo(3L);

        Turn committing = turnRepository.find(turnId).orElseThrow();
        revision = committing.revision();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"冻结\"}", 0);
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(turnId, revision, "local-primary", now, assistant, List.of(), List.of(), null));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.COMMITTING.name());
        assertThat(revisionOf(turnId)).isEqualTo(4L);
        assertThat(turnCommitter.frozenCommitPlan(turnId)).isPresent();
    }

    @Test
    void staleRevisionDoesNotOverwrite() throws Exception {
        TurnId turnId = receiveTurn("persist-req-2");
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        ExecutionClaim claim = new ExecutionClaim("local-primary", now.plusSeconds(60));

        Turn winner = turnRepository.find(turnId).orElseThrow();
        Turn loser = turnRepository.find(turnId).orElseThrow();
        winner.claim(1L, claim, now);
        assertThat(turnRepository.save(winner, 1L, now)).isInstanceOf(SaveTurnResult.Saved.class);

        loser.claim(1L, new ExecutionClaim("other-owner", now.plusSeconds(60)), now);
        SaveTurnResult conflict = turnRepository.save(loser, 1L, now);
        assertThat(conflict).isInstanceOf(SaveTurnResult.RevisionConflict.class);
        assertThat(((SaveTurnResult.RevisionConflict) conflict).actualRevision()).isEqualTo(2L);
        assertThat(scalar(turnId, "execution_id")).isEqualTo("local-primary");
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.CLAIMED.name());
    }

    @Test
    void saveRejectsSkippingMultipleTransitions() throws Exception {
        TurnId turnId = receiveTurn("persist-req-3");
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        Turn turn = turnRepository.find(turnId).orElseThrow();
        turn.claim(1L, new ExecutionClaim("local-primary", now.plusSeconds(60)), now);
        turn.start(now);

        assertThatThrownBy(() -> turnRepository.save(turn, 1L, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一次领域迁移");
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.RECEIVED.name());
        assertThat(revisionOf(turnId)).isEqualTo(1L);
    }

    @Test
    void saveMissingTurnIsNotFound() {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        Turn ghost =
                Turn.reconstitute(
                        TurnId.generate(),
                        ConversationId.generate(),
                        "missing",
                        MessageId.generate(),
                        TurnStatus.CLAIMED,
                        2L,
                        "local-primary",
                        now.plusSeconds(60),
                        null,
                        now);
        assertThat(turnRepository.save(ghost, 1L, now)).isInstanceOf(SaveTurnResult.NotFound.class);
    }

    @Test
    void saveCannotForgeCompletionOrReviveTerminalTurns() throws Exception {
        TurnId turnId = receiveTurn("persist-terminal");
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        Turn received = turnRepository.find(turnId).orElseThrow();
        Turn forgedComplete =
                Turn.reconstitute(
                        received.id(),
                        received.conversationId(),
                        received.clientRequestId(),
                        received.inputMessageId(),
                        TurnStatus.COMPLETED,
                        2L,
                        null,
                        null,
                        null,
                        now);
        SaveTurnResult forged = turnRepository.save(forgedComplete, 1L, now);
        assertThat(forged).isInstanceOf(SaveTurnResult.Rejected.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.RECEIVED.name());
        assertThat(revisionOf(turnId)).isEqualTo(1L);

        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "UPDATE turn SET status = 'FAILED', error_code = 'X', revision = 3 WHERE id = ?")) {
            ps.setString(1, turnId.asString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        Turn revived =
                Turn.reconstitute(
                        received.id(),
                        received.conversationId(),
                        received.clientRequestId(),
                        received.inputMessageId(),
                        TurnStatus.RUNNING,
                        4L,
                        "other",
                        now.plusSeconds(30),
                        null,
                        now);
        SaveTurnResult rejected = turnRepository.save(revived, 3L, now);
        assertThat(rejected).isInstanceOf(SaveTurnResult.Rejected.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.FAILED.name());
        assertThat(scalar(turnId, "error_code")).isEqualTo("X");
    }

    @Test
    void expiredClaimCannotBePersistedAsCommitting() throws Exception {
        TurnId turnId = receiveTurn("persist-expired");
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        ExecutionClaim claim = new ExecutionClaim("owner-a", now.plusSeconds(30));
        Turn turn = turnRepository.find(turnId).orElseThrow();
        turn.claim(1L, claim, now);
        assertThat(turnRepository.save(turn, 1L, now)).isInstanceOf(SaveTurnResult.Saved.class);
        turn = turnRepository.find(turnId).orElseThrow();
        turn.start(now);
        assertThat(turnRepository.save(turn, 2L, now)).isInstanceOf(SaveTurnResult.Saved.class);

        Turn expired =
                Turn.reconstitute(
                        turn.id(),
                        turn.conversationId(),
                        turn.clientRequestId(),
                        turn.inputMessageId(),
                        TurnStatus.COMMITTING,
                        4L,
                        "owner-a",
                        now.plusSeconds(30),
                        null,
                        now.plusSeconds(31));
        SaveTurnResult rejected = turnRepository.save(expired, 3L, now.plusSeconds(31));
        assertThat(rejected).isInstanceOf(SaveTurnResult.Rejected.class);
        assertThat(((SaveTurnResult.Rejected) rejected).reasonCode()).isEqualTo("ILLEGAL_TRANSITION");
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.RUNNING.name());
        assertThat(revisionOf(turnId)).isEqualTo(3L);

        Turn forgedClock =
                Turn.reconstitute(
                        turn.id(),
                        turn.conversationId(),
                        turn.clientRequestId(),
                        turn.inputMessageId(),
                        TurnStatus.COMMITTING,
                        4L,
                        "owner-a",
                        now.plusSeconds(30),
                        null,
                        now);
        SaveTurnResult forged = turnRepository.save(forgedClock, 3L, now.plusSeconds(31));
        assertThat(forged).isInstanceOf(SaveTurnResult.Rejected.class);
        assertThat(((SaveTurnResult.Rejected) forged).reasonCode()).isEqualTo("ILLEGAL_TRANSITION");
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.RUNNING.name());
        assertThat(revisionOf(turnId)).isEqualTo(3L);

        FreezeCommitResult expiredFreeze =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(
                                turnId,
                                3L,
                                "owner-a",
                                now.plusSeconds(31),
                                new CommitTurnPlan.AssistantMessageDraft(
                                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1}", 0), List.of(), List.of(), null));
        assertThat(expiredFreeze).isInstanceOf(FreezeCommitResult.Rejected.class);
        assertThat(((FreezeCommitResult.Rejected) expiredFreeze).reasonCode()).isEqualTo("CLAIM_EXPIRED");
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.RUNNING.name());
    }

    private TurnId receiveTurn(String clientRequestId) {
        ConversationId conversationId = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(conversationId)))
                .isInstanceOf(CreateConversationResult.Created.class);
        TurnId turnId = TurnId.generate();
        ReceiveTurnResult received =
                turnCommitter.receive(
                        new ReceiveTurnPlan(
                                conversationId,
                                clientRequestId,
                                turnId,
                                new ReceiveTurnPlan.UserMessageDraft(
                                        MessageId.generate(),
                                        MessageRole.USER,
                                        "{\"v\":1,\"text\":\"迁移\"}",
                                        1)));
        assertThat(received).isInstanceOf(ReceiveTurnResult.Accepted.class);
        return turnId;
    }

    private String statusOf(TurnId turnId) throws Exception {
        return scalar(turnId, "status");
    }

    private long revisionOf(TurnId turnId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT revision FROM turn WHERE id = ?")) {
            ps.setString(1, turnId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String scalar(TurnId turnId, String column) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT " + column + " FROM turn WHERE id = ?")) {
            ps.setString(1, turnId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
