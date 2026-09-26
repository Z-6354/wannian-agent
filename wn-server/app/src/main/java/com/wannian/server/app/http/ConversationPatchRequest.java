package com.wannian.server.app.http;

public record ConversationPatchRequest(String op, Long expectedRevision, String title) {}
