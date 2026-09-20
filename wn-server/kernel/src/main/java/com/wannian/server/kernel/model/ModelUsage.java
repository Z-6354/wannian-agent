package com.wannian.server.kernel.model;

/** Token 用量。未知时填 0。 */
public record ModelUsage(int promptTokens, int completionTokens) {}
