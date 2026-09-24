package com.wannian.server.kernel.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.conversation.ConversationMessage;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.memory.InMemoryTurnMemoryPending;
import com.wannian.server.kernel.tool.BuiltinToolNames;
import com.wannian.server.kernel.tool.BuiltinToolRegistrar;
import com.wannian.server.kernel.tool.FacetId;
import com.wannian.server.kernel.tool.HostCapabilities;
import com.wannian.server.kernel.tool.HostCapabilitySet;
import com.wannian.server.kernel.tool.ToolCatalog;
import com.wannian.server.kernel.tool.ToolDescriptor;
import com.wannian.server.kernel.tool.ToolVisibilityResolver;
import com.wannian.server.kernel.tool.YanhuoToolBindings;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 0.2.2 阶段 4：默认聊天模式注入可见工具；可切换工作模式。 */
class ContextAssemblerToolVisibilityTest {

    @Test
    void defaultChatInjectsTimeAndCalculateOnly() {
        ContextAssembler assembler = assembler(HostCapabilitySet.of(
                HostCapabilities.OS_WINDOWS, HostCapabilities.NET_HTTP));
        AgentInput input =
                assembler.assemble(
                        ContextAssembler.AssemblyRequest.of(
                                ConversationId.generate(),
                                TurnId.generate(),
                                MessageId.generate(),
                                "现在几点",
                                "你是烟火",
                                new InMemoryTurnMemoryPending()));
        assertThat(input.toolProfileId()).isEqualTo("yanhuo.chat.default");
        assertThat(names(input))
                .containsExactly(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.CURRENT_TIME,
                        BuiltinToolNames.REMEMBER_FACT,
                        BuiltinToolNames.UPDATE_RELATIONSHIP,
                        BuiltinToolNames.SEARCH_MEMORY,
                        BuiltinToolNames.CALCULATE);
    }

    @Test
    void workFacetOnWindowsIncludesPowershell() {
        ContextAssembler assembler = assembler(HostCapabilitySet.of(
                HostCapabilities.OS_WINDOWS,
                HostCapabilities.NET_HTTP,
                HostCapabilities.SHELL_PS_FAMILY7));
        AgentInput input =
                assembler.assemble(
                        ContextAssembler.AssemblyRequest.of(
                                ConversationId.generate(),
                                TurnId.generate(),
                                MessageId.generate(),
                                "解析 powershell",
                                "你是烟火",
                                FacetId.WORK,
                                new InMemoryTurnMemoryPending()));
        assertThat(input.toolProfileId()).isEqualTo("yanhuo.work.default");
        assertThat(names(input)).contains(BuiltinToolNames.POWERSHELL_RESOLVE_7, BuiltinToolNames.HTTP_READ);
        assertThat(names(input)).doesNotContain(BuiltinToolNames.POWERSHELL_RESOLVE_5);
    }

    private static ContextAssembler assembler(HostCapabilitySet host) {
        ToolCatalog catalog = new ToolCatalog();
        BuiltinToolRegistrar.registerAll(catalog);
        ToolVisibilityResolver resolver =
                new ToolVisibilityResolver(catalog, YanhuoToolBindings.create());
        return new ContextAssembler(new EmptyStore(), resolver, host);
    }

    private static List<String> names(AgentInput input) {
        return input.toolDescriptors().stream().map(ToolDescriptor::name).collect(Collectors.toList());
    }

    private static final class EmptyStore implements ConversationStore {
        @Override
        public List<ConversationMessage> listRecentMessages(ConversationId conversationId, int limit) {
            return List.of();
        }

        @Override
        public com.wannian.server.kernel.conversation.CreateConversationResult create(
                com.wannian.server.kernel.conversation.CreateConversationCommand command) {
            throw new UnsupportedOperationException();
        }
    }
}
