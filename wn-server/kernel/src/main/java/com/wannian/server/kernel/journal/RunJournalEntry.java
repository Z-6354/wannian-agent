package com.wannian.server.kernel.journal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条行为账本条目（0.2.3-L）。
 *
 * <p>{@code requestJson}/{@code resultJson} 须为脱敏后的 JSON 文本；不得含密钥或堆栈。
 * 进程级事件 {@code turnId} 可为 null（仅 JSONL / process_event，不进 turn_step）。
 */
public record RunJournalEntry(
        String id,
        String turnId,
        String conversationId,
        int stepNo,
        JournalActor actor,
        JournalKind kind,
        String requestJson,
        String resultJson,
        String status,
        String errorCode,
        Instant startedAt,
        Instant finishedAt) {

    public RunJournalEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        id = id.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (turnId != null) {
            turnId = turnId.trim();
            if (turnId.isEmpty()) {
                turnId = null;
            }
        }
        if (conversationId != null) {
            conversationId = conversationId.trim();
            if (conversationId.isEmpty()) {
                conversationId = null;
            }
        }
        if (stepNo < 0) {
            throw new IllegalArgumentException("stepNo 不得为负");
        }
        status = status.trim();
        if (status.isEmpty()) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (errorCode != null) {
            errorCode = errorCode.trim();
            if (errorCode.isEmpty()) {
                errorCode = null;
            }
        }
    }

    /** 生成新 id 的条目。 */
    public static RunJournalEntry of(
            String turnId,
            String conversationId,
            int stepNo,
            JournalActor actor,
            JournalKind kind,
            String requestJson,
            String resultJson,
            String status,
            String errorCode,
            Instant startedAt,
            Instant finishedAt) {
        return new RunJournalEntry(
                UUID.randomUUID().toString(),
                turnId,
                conversationId,
                stepNo,
                actor,
                kind,
                requestJson,
                resultJson,
                status,
                errorCode,
                startedAt,
                finishedAt);
    }
}
