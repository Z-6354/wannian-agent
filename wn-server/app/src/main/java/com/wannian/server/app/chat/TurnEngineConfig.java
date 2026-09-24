package com.wannian.server.app.chat;

import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.app.model.ResolvingModelPort;
import com.wannian.server.kernel.agent.AgentLoop;
import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.agent.DefaultAgentLoop;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.journal.JournalSettings;
import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.memory.DefaultMemoryRecall;
import com.wannian.server.kernel.memory.MemoryRecall;
import com.wannian.server.kernel.memory.MemoryRecallLimits;
import com.wannian.server.kernel.memory.MemoryRecallTouch;
import com.wannian.server.kernel.memory.MemorySearchLimits;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.relationship.RelationshipStore;
import com.wannian.server.kernel.tool.HostCapabilitySet;
import com.wannian.server.kernel.tool.RoleId;
import com.wannian.server.kernel.tool.ToolRuntime;
import com.wannian.server.kernel.tool.ToolVisibilityResolver;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnEngine;
import com.wannian.server.kernel.turn.TurnRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 TurnEngine → AgentLoop。
 *
 * <p><b>0.2.3-D 接线</b>
 *
 * <ul>
 *   <li>{@link MemoryRecallLimits}：从 {@code wannian.memory.recall.*} 注入（yml / 环境变量）。
 *   <li>{@link MemoryRecall}：{@link DefaultMemoryRecall} + {@link MemoryStore}（Store 为
 *       {@code @Component}）。
 *   <li>{@link ContextAssembler}：注入 recall / {@link RelationshipStore} / {@link
 *       MemoryRecallTouch} / limits；Rel 与 Touch 亦为 persistence {@code @Component}，
 *       <strong>本类不手写第二份构造</strong>，避免双 bean。
 *   <li>AgentLoop / TurnEngine 装配与 D 前相同。
 * </ul>
 */
@Configuration
public class TurnEngineConfig {

    @Bean
    MemoryRecallLimits memoryRecallLimits(
            @Value("${wannian.memory.recall.top-n:12}") int topN,
            @Value("${wannian.memory.recall.char-budget:2000}") int charBudget) {
        return new MemoryRecallLimits(topN, charBudget);
    }

    @Bean
    MemorySearchLimits memorySearchLimits(
            @Value("${wannian.memory.search.default-limit:5}") int defaultLimit,
            @Value("${wannian.memory.search.max-limit:20}") int maxLimit) {
        return new MemorySearchLimits(defaultLimit, maxLimit);
    }

    @Bean
    MemoryRecall memoryRecall(MemoryStore memoryStore) {
        return new DefaultMemoryRecall(memoryStore);
    }

    @Bean
    ContextAssembler contextAssembler(
            ConversationStore conversations,
            ToolVisibilityResolver toolVisibilityResolver,
            HostCapabilitySet hostCapabilitySet,
            MemoryRecall memoryRecall,
            RelationshipStore relationshipStore,
            MemoryRecallTouch memoryRecallTouch,
            MemoryRecallLimits memoryRecallLimits) {
        return new ContextAssembler(
                conversations,
                toolVisibilityResolver,
                hostCapabilitySet,
                RoleId.YANHUO,
                Clock.system(ContextAssembler.OBSERVATION_ZONE),
                memoryRecall,
                relationshipStore,
                memoryRecallTouch,
                memoryRecallLimits);
    }

    @Bean
    AgentLoop agentLoop(
            EnabledModelPortResolver modelPorts,
            ToolRuntime toolRuntime,
            RunJournal runJournal,
            JournalSettings journalSettings) {
        return new DefaultAgentLoop(
                new ResolvingModelPort(modelPorts),
                toolRuntime,
                runJournal,
                journalSettings);
    }

    @Bean
    TurnEngine turnEngine(
            TurnRepository turns,
            TurnCommitter turnCommitter,
            ContextAssembler assembler,
            AgentLoop agentLoop,
            MemoryStore memoryStore,
            RunJournal runJournal,
            JournalSettings journalSettings) {
        return new TurnEngine(
                turns, turnCommitter, assembler, agentLoop, memoryStore, runJournal, journalSettings);
    }
}
