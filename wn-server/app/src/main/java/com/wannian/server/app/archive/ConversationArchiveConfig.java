package com.wannian.server.app.archive;

import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.kernel.conversation.ConversationArchiveEvaluator;
import com.wannian.server.kernel.conversation.HeuristicConversationArchiveEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评估器装配：按 {@link ConversationHygieneSettings#evaluatorKind()} 二选一，大小写无关。
 *
 * <p>禁止在调度器内写死 LLM/启发式分支。
 */
@Configuration
public class ConversationArchiveConfig {

    @Bean
    ConversationArchiveEvaluator conversationArchiveEvaluator(
            ConversationHygieneSettings settings, EnabledModelPortResolver modelPorts) {
        if (settings.evaluatorKind() == ConversationHygieneSettings.EvaluatorKind.HEURISTIC) {
            return new HeuristicConversationArchiveEvaluator();
        }
        return new LlmConversationArchiveEvaluator(modelPorts);
    }
}
