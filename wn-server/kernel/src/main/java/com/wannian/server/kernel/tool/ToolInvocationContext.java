package com.wannian.server.kernel.tool;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;

/** Trusted, frozen source context for the tool call; never populated from model arguments. */
public record ToolInvocationContext(String userMessage, ConversationId conversationId, TurnId turnId) {}
