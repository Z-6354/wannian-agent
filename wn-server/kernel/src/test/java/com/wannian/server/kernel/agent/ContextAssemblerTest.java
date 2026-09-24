package com.wannian.server.kernel.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.kernel.conversation.ConversationMessage;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryLifecycle;
import com.wannian.server.kernel.memory.MemoryRecallLimits;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.SourceKind;
import com.wannian.server.kernel.memory.StoredMemoryRecord;
import java.util.List;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ContextAssemblerTest {

    @Test
    void excerptDropsCurrentUserMessageAndKeepsText() {
        MessageId older = MessageId.generate();
        MessageId reply = MessageId.generate();
        MessageId current = MessageId.generate();
        List<ConversationMessage> recent =
                List.of(
                        message(older, MessageRole.USER, "  上一句\n", 1),
                        message(reply, MessageRole.ASSISTANT, "好", 2),
                        message(current, MessageRole.USER, "现在", 3));

        String excerpt = ContextAssembler.formatExcerpt(recent, current, 20);

        assertThat(excerpt).isEqualTo("用户:   上一句\n\n助手: 好");
    }

    @Test
    void keepsNewestWhenOverLimit() {
        MessageId a = MessageId.generate();
        MessageId b = MessageId.generate();
        MessageId current = MessageId.generate();
        List<ConversationMessage> recent =
                List.of(
                        message(a, MessageRole.USER, "旧", 1),
                        message(b, MessageRole.ASSISTANT, "新", 2),
                        message(current, MessageRole.USER, "当前", 3));

        assertThat(ContextAssembler.formatExcerpt(recent, current, 1)).isEqualTo("助手: 新");
    }

    @Test
    void assembleReadsStore() {
        MessageId past = MessageId.generate();
        MessageId current = MessageId.generate();
        ConversationId conversationId = ConversationId.generate();
        RecordingStore store =
                new RecordingStore(
                        List.of(
                                message(past, MessageRole.ASSISTANT, "早", 1),
                                message(current, MessageRole.USER, "问", 2)));
        ContextAssembler assembler = new ContextAssembler(store);

        AgentInput input =
                assembler.assemble(
                        ContextAssembler.AssemblyRequest.of(
                                conversationId,
                                com.wannian.server.api.common.TurnId.generate(),
                                current,
                                "问",
                                "你是烟火",
                                new com.wannian.server.kernel.memory.InMemoryTurnMemoryPending()));

        assertThat(store.requestedLimit).isEqualTo(ContextAssembler.DEFAULT_RECENT_MESSAGES + 1);
        assertThat(input.conversationExcerpt()).isEqualTo("助手: 早");
        assertThat(input.userMessage()).isEqualTo("问");
        assertThat(input.systemInstructions())
                .contains("你是烟火", "观察日（Observation Date）", "地点：未说明");
        assertThat(input.memoryContext()).isNull();
        assertThat(input.relationshipSnapshot()).isNull();
        assertThat(input.worldContext()).isNull();
        assertThat(input.turnSource()).isEqualTo(TurnSource.USER);
    }

    @Test
    void unescapesText() {
        assertThat(ContextAssembler.textOf("{\"v\":1,\"text\":\"a\\\"b\\n\"}")).isEqualTo("a\"b\n");
    }

    @Test
    void skipsOverBudgetMemoryAndContinuesToLaterShortMemory() {
        List<String> touched = new ArrayList<>();
        ContextAssembler assembler = assembler(
                List.of(memory("long", "这是一个长度远远超过预算的高分记忆"), memory("short", "短记忆")),
                (ids, now) -> touched.addAll(ids),
                31);

        AgentInput input = assembler.assemble(request());

        assertThat(input.memoryContext()).contains("短记忆").doesNotContain("长度远远超过");
        assertThat(touched).containsExactly("short");
    }

    @Test
    void recallTouchFailureDoesNotPreventMemoryInjection() {
        ContextAssembler assembler = assembler(
                List.of(memory("remembered", "用户喜欢龙井")),
                (ids, now) -> { throw new IllegalStateException("database busy"); },
                100);

        AgentInput input = assembler.assemble(request());

        assertThat(input.memoryContext()).contains("用户喜欢龙井");
    }

    @Test
    void injectsRecalledMemoriesInProvidedScoreOrderAndTouchesInjectedIds() {
        List<String> touched = new ArrayList<>();
        // recallTop 已按 score 排序；Assembler 保持顺序拼入
        ContextAssembler assembler = assembler(
                List.of(
                        memory("high", "我叫小明", 0.95),
                        memory("low", "今晚想吃炒蛋", 0.2)),
                (ids, now) -> touched.addAll(ids),
                200);

        AgentInput input = assembler.assemble(request());

        assertThat(input.memoryContext()).contains("我叫小明", "今晚想吃炒蛋");
        int highAt = input.memoryContext().indexOf("我叫小明");
        int lowAt = input.memoryContext().indexOf("今晚想吃炒蛋");
        assertThat(highAt).isGreaterThanOrEqualTo(0).isLessThan(lowAt);
        assertThat(touched).containsExactly("high", "low");
    }

    private static ContextAssembler assembler(
            List<StoredMemoryRecord> memories,
            com.wannian.server.kernel.memory.MemoryRecallTouch touch,
            int budget) {
        return new ContextAssembler(
                new RecordingStore(List.of()),
                null,
                com.wannian.server.kernel.tool.HostCapabilitySet.empty(),
                com.wannian.server.kernel.tool.RoleId.YANHUO,
                java.time.Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), java.time.ZoneOffset.UTC),
                (identity, now, limit) -> memories,
                null,
                touch,
                new MemoryRecallLimits(5, budget));
    }

    private static ContextAssembler.AssemblyRequest request() {
        return ContextAssembler.AssemblyRequest.of(
                ConversationId.generate(),
                com.wannian.server.api.common.TurnId.generate(),
                MessageId.generate(),
                "你好",
                "系统指令",
                new com.wannian.server.kernel.memory.InMemoryTurnMemoryPending());
    }

    private static StoredMemoryRecord memory(String id, String claim) {
        return memory(id, claim, 0.8);
    }

    private static StoredMemoryRecord memory(String id, String claim, double importance) {
        return new StoredMemoryRecord(
                id,
                CompanionIdentity.YANHUO,
                "subject." + id,
                claim,
                ContentKind.USER_FACT,
                SourceKind.EXPLICIT,
                MemoryScope.COMPANION,
                importance,
                null,
                MemoryLifecycle.ACTIVE,
                "test",
                1,
                Instant.parse("2026-09-20T00:00:00Z"),
                null,
                null);
    }

    private static ConversationMessage message(MessageId id, MessageRole role, String text, int sequenceNo) {
        String escaped = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
        return new ConversationMessage(id, role, "{\"v\":1,\"text\":\"" + escaped + "\"}", sequenceNo);
    }

    private static final class RecordingStore implements ConversationStore {
        private final List<ConversationMessage> messages;
        private int requestedLimit;

        private RecordingStore(List<ConversationMessage> messages) {
            this.messages = messages;
        }

        @Override
        public CreateConversationResult create(CreateConversationCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ConversationMessage> listRecentMessages(ConversationId conversationId, int limit) {
            requestedLimit = limit;
            return messages;
        }
    }
}
