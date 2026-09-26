package com.wannian.server.app.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.conversation.ArchiveDecision;
import com.wannian.server.kernel.model.ModelOutcome;
import org.junit.jupiter.api.Test;

class LlmConversationArchiveEvaluatorTest {

    @Test
    void parseArchiveFirstLine() {
        ArchiveDecision d =
                LlmConversationArchiveEvaluator.parse(
                        new ModelOutcome.FinalAnswer("ARCHIVE\nreason: 主题已结束", null));
        assertThat(d.shouldArchive()).isTrue();
        assertThat(d.reason()).contains("主题已结束");
    }

    @Test
    void parseKeepAndUnparsed() {
        assertThat(
                        LlmConversationArchiveEvaluator.parse(
                                        new ModelOutcome.FinalAnswer("KEEP\nreason: 还要聊", null))
                                .shouldArchive())
                .isFalse();
        assertThat(
                        LlmConversationArchiveEvaluator.parse(
                                        new ModelOutcome.FinalAnswer("也许可以归档吧", null))
                                .shouldArchive())
                .isFalse();
        assertThat(
                        LlmConversationArchiveEvaluator.parse(
                                        new ModelOutcome.FinalAnswer("ARCHIVE_MAYBE", null))
                                .shouldArchive())
                .isFalse();
        assertThat(
                        LlmConversationArchiveEvaluator.parse(
                                        new ModelOutcome.FinalAnswer("ARCHIVE.", null))
                                .shouldArchive())
                .isTrue();
    }

    @Test
    void parseNonFinalKeeps() {
        assertThat(
                        LlmConversationArchiveEvaluator.parse(
                                        new ModelOutcome.ToolCalls(java.util.List.of(), null))
                                .shouldArchive())
                .isFalse();
    }
}
