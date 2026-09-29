package com.wannian.server.app.title;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.model.ModelOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 2.4.6：标题抽取拒垃圾 / 截断；失败路径不写库（由 extract 返回 null 保证）。 */
class ConversationAutoTitleServiceTest {

    @Test
    void extractTitleAcceptsShortChinese() {
        String title =
                ConversationAutoTitleService.extractTitle(
                        new ModelOutcome.FinalAnswer("周末计划", null));
        assertThat(title).isEqualTo("周末计划");
    }

    @Test
    void extractTitleRejectsFakeWrapperAndEmpty() {
        assertThat(
                        ConversationAutoTitleService.extractTitle(
                                new ModelOutcome.FinalAnswer("假模型答案", null)))
                .isNull();
        assertThat(
                        ConversationAutoTitleService.extractTitle(
                                new ModelOutcome.FinalAnswer("   ", null)))
                .isNull();
        assertThat(
                        ConversationAutoTitleService.extractTitle(
                                new ModelOutcome.ToolCalls(List.of(), null)))
                .isNull();
    }

    @Test
    void extractTitleTruncatesOverlong() {
        String longText = "甲".repeat(80);
        String title =
                ConversationAutoTitleService.extractTitle(
                        new ModelOutcome.FinalAnswer(longText, null));
        assertThat(title).isNotNull();
        assertThat(title.length()).isLessThanOrEqualTo(40);
    }
}
