package com.wannian.server.app.chat;

import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.app.model.ResolvingModelPort;
import com.wannian.server.kernel.agent.AgentLoop;
import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.agent.DefaultAgentLoop;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnEngine;
import com.wannian.server.kernel.turn.TurnRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配 TurnEngine → AgentLoop（live/fake 由 EnabledModelPortResolver 决定）。 */
@Configuration
public class TurnEngineConfig {

    @Bean
    ContextAssembler contextAssembler(ConversationStore conversations) {
        return new ContextAssembler(conversations);
    }

    @Bean
    AgentLoop agentLoop(EnabledModelPortResolver modelPorts) {
        return new DefaultAgentLoop(new ResolvingModelPort(modelPorts));
    }

    @Bean
    TurnEngine turnEngine(
            TurnRepository turns,
            TurnCommitter turnCommitter,
            ContextAssembler assembler,
            AgentLoop agentLoop) {
        return new TurnEngine(turns, turnCommitter, assembler, agentLoop);
    }
}
