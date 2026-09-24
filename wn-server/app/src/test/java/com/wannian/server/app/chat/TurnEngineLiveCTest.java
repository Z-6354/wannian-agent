package com.wannian.server.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.app.manage.VendorRecord;
import com.wannian.server.app.model.OpenAiCompatibleModelAdapter;
import com.wannian.server.kernel.agent.AgentBudget;
import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.agent.DefaultAgentLoop;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.turn.ExecuteTurn;
import com.wannian.server.kernel.turn.ExecuteTurnResult;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnEngine;
import com.wannian.server.kernel.turn.TurnRepository;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 0.2.1-C live：同回合二次 execute 不增加模型 decide（AlreadyCompleted 收口）。
 *
 * <p>需 {@code DEEPSEEK_API_KEY}；无密钥时跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class TurnEngineLiveCTest {

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
    void liveExecuteThenAlreadyCompletedDoesNotDecideAgain() {
        CountingPort model = new CountingPort(liveDeepseek());
        TurnEngine engine =
                new TurnEngine(
                        turnRepository,
                        turnCommitter,
                        new ContextAssembler(conversationStore),
                        new DefaultAgentLoop(model));

        ConversationId conversationId = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(conversationId)))
                .isInstanceOf(CreateConversationResult.Created.class);
        TurnId turnId = TurnId.generate();
        String text = "只回复一个字：好";
        assertThat(
                        turnCommitter.receive(
                                new ReceiveTurnPlan(
                                        conversationId,
                                        "live-c-1",
                                        turnId,
                                        new ReceiveTurnPlan.UserMessageDraft(
                                                MessageId.generate(),
                                                MessageRole.USER,
                                                "{\"v\":1,\"text\":\"" + text + "\"}",
                                                0))))
                .isInstanceOf(ReceiveTurnResult.Accepted.class);

        ExecuteTurn command =
                new ExecuteTurn(
                        turnId,
                        text,
                        "你是万年，一个有帮助的助手。",
                        AgentBudget.of(3, 15, 30, Instant.now()),
                        Duration.ofSeconds(60));

        ExecuteTurnResult first = engine.execute(command);
        assertThat(first).isInstanceOf(ExecuteTurnResult.Replied.class);
        assertThat(((ExecuteTurnResult.Replied) first).text()).isNotBlank();
        assertThat(model.decideCount()).isEqualTo(1);

        ExecuteTurnResult second = engine.execute(command);
        assertThat(second).isInstanceOf(ExecuteTurnResult.AlreadyCompleted.class);
        assertThat(model.decideCount()).isEqualTo(1);
    }

    private static ModelPort liveDeepseek() {
        VendorRecord vendor =
                new VendorRecord(
                        "deepseek",
                        "openai-compatible",
                        "https://api.deepseek.com/v1",
                        "DEEPSEEK_API_KEY");
        return new OpenAiCompatibleModelAdapter(
                vendor, "deepseek-flash", HttpClient.newHttpClient(), new ObjectMapper(), System::getenv);
    }

    private static final class CountingPort implements ModelPort {
        private final ModelPort inner;
        private final AtomicInteger decides = new AtomicInteger();

        private CountingPort(ModelPort inner) {
            this.inner = inner;
        }

        @Override
        public ModelOutcome decide(ModelRequest request, ModelCallContext context) {
            decides.incrementAndGet();
            return inner.decide(request, context);
        }

        int decideCount() {
            return decides.get();
        }
    }
}
