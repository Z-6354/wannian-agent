package com.wannian.server.app.stream;

import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.app.model.ResolvingModelPort;
import com.wannian.server.app.stream.DurableTurnScheduler.StreamingActivityListener;
import com.wannian.server.app.stream.DurableTurnScheduler.StreamingTurnRunListener;
import com.wannian.server.kernel.agent.AgentLoop;
import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.agent.DefaultAgentLoop;
import com.wannian.server.kernel.journal.JournalSettings;
import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.prompt.CrisisResourceDirectory;
import com.wannian.server.kernel.tool.ToolRuntime;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnEngine;
import com.wannian.server.kernel.turn.TurnRepository;
import com.wannian.server.kernel.turn.TurnTerminalWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 0.2.4-C：流式交付 — AgentLoop / TurnEngine 装配（含终态 Outbox 与运行投影）。 */
@Configuration
public class StreamDeliveryConfig {

    @Bean
    CrisisResourceDirectory crisisResourceDirectory() {
        // 没有经目标地区责任方核验的资源清单时，明确保持未知地区降级。
        return CrisisResourceDirectory.empty();
    }

    @Bean
    AgentLoop agentLoop(
            EnabledModelPortResolver modelPorts,
            ToolRuntime toolRuntime,
            RunJournal runJournal,
            JournalSettings journalSettings,
            StreamingActivityListener activityListener,
            CrisisResourceDirectory crisisResources) {
        return new DefaultAgentLoop(
                new ResolvingModelPort(modelPorts),
                toolRuntime,
                runJournal,
                journalSettings,
                TurnRunContext::streamObserverOrNoop,
                activityListener,
                crisisResources);
    }

    @Bean
    TurnEngine turnEngine(
            TurnRepository turns,
            TurnCommitter turnCommitter,
            ContextAssembler assembler,
            AgentLoop agentLoop,
            MemoryStore memoryStore,
            RunJournal runJournal,
            JournalSettings journalSettings,
            TurnTerminalWriter terminalWriter,
            StreamingTurnRunListener runListener,
            @Value("${wannian.context.recent-message-limit:20}") int recentMessageLimit) {
        return new TurnEngine(
                turns,
                turnCommitter,
                assembler,
                agentLoop,
                memoryStore,
                runJournal,
                journalSettings,
                terminalWriter,
                runListener,
                recentMessageLimit);
    }
}
