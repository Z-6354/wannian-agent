package com.wannian.server.app.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.kernel.task.BackgroundTaskRepository;
import com.wannian.server.kernel.task.SubAgentRunRepository;
import org.junit.jupiter.api.Test;

class BackgroundTaskQueryServiceTest {

    private final BackgroundTaskQueryService query = new BackgroundTaskQueryService(
            mock(BackgroundTaskRepository.class),
            mock(SubAgentRunRepository.class),
            new ObjectMapper(),
            160);

    @Test
    void reminderPreviewUsesBodyWhenModelGivesGenericTitle() {
        String input = "{\"message\":\"看微信\",\"title\":\"提醒\"}";

        assertEquals("看微信", query.reminderText(input));
        assertEquals("看微信", query.humanInputPreview(input, 160));
    }

    @Test
    void reminderPreviewFallsBackToTitleWhenBodyIsMissing() {
        assertEquals("喝水提醒", query.reminderText("{\"title\":\"喝水提醒\"}"));
    }

}
