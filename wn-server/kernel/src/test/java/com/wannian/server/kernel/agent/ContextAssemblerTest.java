package com.wannian.server.kernel.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.kernel.conversation.ConversationMessage;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import java.util.List;
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
                                "你是烟火"));

        assertThat(store.requestedLimit).isEqualTo(ContextAssembler.DEFAULT_RECENT_MESSAGES + 1);
        assertThat(input.conversationExcerpt()).isEqualTo("助手: 早");
        assertThat(input.userMessage()).isEqualTo("问");
        assertThat(input.systemInstructions()).isEqualTo("你是烟火");
        assertThat(input.memoryContext()).isNull();
        assertThat(input.relationshipSnapshot()).isNull();
        assertThat(input.worldContext()).isNull();
        assertThat(input.turnSource()).isEqualTo(TurnSource.USER);
    }

    @Test
    void unescapesText() {
        assertThat(ContextAssembler.textOf("{\"v\":1,\"text\":\"a\\\"b\\n\"}")).isEqualTo("a\"b\n");
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
