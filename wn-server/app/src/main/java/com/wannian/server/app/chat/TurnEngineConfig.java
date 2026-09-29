package com.wannian.server.app.chat;

import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.memory.DefaultMemoryRecall;
import com.wannian.server.kernel.memory.MemoryRecall;
import com.wannian.server.kernel.memory.MemoryRecallLimits;
import com.wannian.server.kernel.memory.MemoryRecallTouch;
import com.wannian.server.kernel.memory.MemorySearchLimits;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.relationship.RelationshipStore;
import com.wannian.server.kernel.tool.HostCapabilitySet;
import com.wannian.server.kernel.tool.RoleId;
import com.wannian.server.kernel.tool.ToolVisibilityResolver;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 ContextAssembler / Memory 召回。
 *
 * <p>AgentLoop / TurnEngine 由 {@code StreamDeliveryConfig}（2.4.4）装配。
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
}
