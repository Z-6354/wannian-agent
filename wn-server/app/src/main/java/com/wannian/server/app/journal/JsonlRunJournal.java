package com.wannian.server.app.journal;

import com.wannian.server.kernel.journal.JournalJson;
import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.journal.RunJournalEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only JSONL 运行日志（{@code data/logs/run-YYYY-MM-DD.jsonl}）。
 *
 * <p>失败只打 WARNING，不抛回调用方。字符串转义复用 {@link JournalJson#escape}。
 */
public final class JsonlRunJournal implements RunJournal {

    private static final System.Logger LOG = System.getLogger(JsonlRunJournal.class.getName());
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final Path logsDir;
    private final ReentrantLock lock = new ReentrantLock();

    public JsonlRunJournal(Path dataDir) throws IOException {
        Objects.requireNonNull(dataDir, "dataDir");
        this.logsDir = dataDir.resolve("logs");
        Files.createDirectories(logsDir);
    }

    @Override
    public void append(RunJournalEntry entry) {
        Objects.requireNonNull(entry, "entry");
        String line = toJsonLine(entry);
        Path file = logsDir.resolve("run-" + LocalDate.now(ZONE) + ".jsonl");
        lock.lock();
        try {
            Files.writeString(
                    file,
                    line + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ex) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () -> "JSONL 运行日志写入失败: " + ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    static String toJsonLine(RunJournalEntry e) {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        field(out, "id", e.id(), true);
        field(out, "turnId", e.turnId(), false);
        field(out, "conversationId", e.conversationId(), false);
        raw(out, "stepNo", String.valueOf(e.stepNo()));
        field(out, "actor", e.actor().name(), false);
        field(out, "kind", e.kind().name(), false);
        rawJson(out, "request", e.requestJson());
        rawJson(out, "result", e.resultJson());
        field(out, "status", e.status(), false);
        field(out, "errorCode", e.errorCode(), false);
        field(out, "startedAt", e.startedAt().toString(), false);
        field(out, "finishedAt", e.finishedAt() == null ? null : e.finishedAt().toString(), false);
        out.append('}');
        return out.toString();
    }

    private static void field(StringBuilder out, String key, String value, boolean first) {
        if (!first) {
            out.append(',');
        }
        out.append('"').append(JournalJson.escape(key)).append('"').append(':');
        if (value == null) {
            out.append("null");
        } else {
            out.append('"').append(JournalJson.escape(value)).append('"');
        }
    }

    private static void raw(StringBuilder out, String key, String rawValue) {
        out.append(',').append('"').append(JournalJson.escape(key)).append('"').append(':').append(rawValue);
    }

    private static void rawJson(StringBuilder out, String key, String jsonOrNull) {
        out.append(',').append('"').append(JournalJson.escape(key)).append('"').append(':');
        if (jsonOrNull == null || jsonOrNull.isBlank()) {
            out.append("null");
        } else {
            out.append(jsonOrNull);
        }
    }
}
