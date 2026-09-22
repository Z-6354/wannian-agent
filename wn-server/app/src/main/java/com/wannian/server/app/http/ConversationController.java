package com.wannian.server.app.http;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.error.ErrorCodes;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 建会话。只解析请求并映射结果，不接收消息，不改 Turn。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationStore conversationStore;

    public ConversationController(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
    }

    @PostMapping
    public ResponseEntity<CreateConversationResponse> create(
            @RequestBody(required = false) CreateConversationRequest request) {
        String rawId = request == null ? null : request.conversationId();
        ConversationId id;
        if (rawId == null || rawId.isBlank()) {
            id = ConversationId.generate();
        } else {
            Optional<ConversationId> parsed = HttpMapping.conversationId(rawId);
            if (parsed.isEmpty()) {
                return HttpMapping.rejectedConversation(ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
            }
            id = parsed.get();
        }
        String title = request == null ? null : request.title();
        CreateConversationCommand command =
                new CreateConversationCommand(id, title == null ? Optional.empty() : Optional.of(title));
        return HttpMapping.conversation(conversationStore.create(command));
    }
}
