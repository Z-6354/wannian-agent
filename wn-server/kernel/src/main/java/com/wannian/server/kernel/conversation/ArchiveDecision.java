package com.wannian.server.kernel.conversation;

import java.util.Objects;

/**
 * 自动归档评估结果。
 *
 * <p>{@link #KEEP}：不改状态。{@link #ARCHIVE}：调度器执行 CAS archive。
 */
public record ArchiveDecision(Verdict verdict, String reason) {

    public enum Verdict {
        KEEP,
        ARCHIVE
    }

    public ArchiveDecision {
        Objects.requireNonNull(verdict, "verdict");
        reason = reason == null ? "" : reason.strip();
    }

    public static ArchiveDecision keep(String reason) {
        return new ArchiveDecision(Verdict.KEEP, reason);
    }

    public static ArchiveDecision archive(String reason) {
        return new ArchiveDecision(Verdict.ARCHIVE, reason);
    }

    public boolean shouldArchive() {
        return verdict == Verdict.ARCHIVE;
    }
}
