package com.wannian.server.app.journal;

import com.wannian.server.app.manage.AgentBudgetSettings;
import com.wannian.server.app.persistence.SqliteConfig;
import com.wannian.server.kernel.journal.JournalJson;
import com.wannian.server.kernel.journal.JournalSettings;
import com.wannian.server.kernel.journal.RunJournal;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * 行为账本装配与进程启停。
 *
 * <p>2.4.1 开关组合：
 *
 * <pre>
 * journal.enabled | jsonl | sqlite | RunJournal 行为              | SqliteTurnStepJournal Bean
 * ----------------+-------+--------+-------------------------------+---------------------------
 * false           | *     | *      | noop                          | 仍创建（供 Committer 强写）
 * true            | true  | true   | Sequencing(透传)+JSONL+SQLite | 与 sink 共用同一实例
 * true            | false | true   | Sequencing(透传)+SQLite       | 同上
 * true            | true  | false  | Sequencing(编号)+仅 JSONL      | 仍创建（强写不依赖此开关）
 * true            | false | false  | noop（无 sink）               | 仍创建
 * </pre>
 *
 * <p>正式 {@code MEMORY_WRITE} 走 {@link SqliteTurnStepJournal#insertInTransaction}，
 * 不经 {@link RunJournal#append}，故不受 {@code sqlite-enabled} 关闭运行观察扇出的影响。
 */
@Configuration
public class JournalConfig {

    private final Instant startedAt = Instant.now();
    private final boolean enabled;
    private final String buildId;
    private final String dataDir;

    private ProcessEventWriter processEvents;
    private AgentBudgetSettings budgetSettings;

    public JournalConfig(
            @Value("${wannian.journal.enabled:true}") boolean enabled,
            @Value("${wannian.build-id:unknown}") String buildId,
            @Value("${wannian.data-dir:data}") String dataDir) {
        this.enabled = enabled;
        this.buildId = buildId;
        this.dataDir = dataDir;
    }

    @Bean
    JournalSettings journalSettings(
            @Value("${wannian.journal.include-full-messages:true}") boolean includeFullMessages,
            @Value("${wannian.journal.max-payload-chars:65536}") int maxPayloadChars) {
        return new JournalSettings(includeFullMessages, maxPayloadChars);
    }

    /**
     * 唯一的 {@code turn_step} 写入器实例：运行观察 sink 与 Committer 强写共用，
     * 保证编号能力与 INSERT SQL 只有一处。
     */
    @Bean
    SqliteTurnStepJournal sqliteTurnStepJournal(DataSource dataSource) {
        return new SqliteTurnStepJournal(dataSource);
    }

    @Bean
    RunJournal runJournal(
            DataSource dataSource,
            SqliteTurnStepJournal sqliteTurnStepJournal,
            @Value("${wannian.journal.jsonl-enabled:true}") boolean jsonlEnabled,
            @Value("${wannian.journal.sqlite-enabled:true}") boolean sqliteEnabled)
            throws IOException {
        if (!enabled) {
            return RunJournal.noop();
        }
        Path dir = SqliteConfig.resolveDataDir(dataDir);
        List<RunJournal> sinks = new ArrayList<>(2);
        if (jsonlEnabled) {
            sinks.add(new JsonlRunJournal(dir));
        }
        if (sqliteEnabled) {
            sinks.add(sqliteTurnStepJournal);
        }
        if (sinks.isEmpty()) {
            return RunJournal.noop();
        }
        RunJournal composite = new CompositeRunJournal(sinks);
        if (sqliteEnabled) {
            // DB 为 step_no 权威；Sequencing 透传
            return new SequencingRunJournal(composite, dataSource);
        }
        // 仅 JSONL：Sequencing 进程内编号
        return new SequencingRunJournal(composite);
    }

    @Bean
    ProcessEventWriter processEventWriter(
            DataSource dataSource, RunJournal runJournal, AgentBudgetSettings budgetSettings) {
        this.budgetSettings = budgetSettings;
        ProcessEventWriter writer = new ProcessEventWriter(dataSource, runJournal);
        this.processEvents = writer;
        return writer;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!enabled || processEvents == null) {
            return;
        }
        Path resolved = SqliteConfig.resolveDataDir(dataDir);
        String dataDirSummary = resolved.getFileName() != null ? resolved.getFileName().toString() : resolved.toString();
        int max = 10;
        int systemCap = 5;
        int soft = 60;
        int hard = 100;
        if (budgetSettings != null) {
            AgentBudgetSettings.Snapshot snap = budgetSettings.snapshot();
            max = snap.maxModelDecisions();
            systemCap = snap.maxSystemToolInvocationsPerTool();
            soft = snap.softDeadlineSeconds();
            hard = snap.hardDeadlineSeconds();
        }
        String payload =
                JournalJson.object(
                        "buildId",
                        buildId,
                        "dataDirLeaf",
                        dataDirSummary,
                        "maxModelDecisions",
                        String.valueOf(max),
                        "maxSystemToolInvocationsPerTool",
                        String.valueOf(systemCap),
                        "softDeadlineSeconds",
                        String.valueOf(soft),
                        "hardDeadlineSeconds",
                        String.valueOf(hard));
        processEvents.writeStart(payload);
    }

    @PreDestroy
    void onShutdown() {
        if (!enabled || processEvents == null) {
            return;
        }
        long uptimeSec = Duration.between(startedAt, Instant.now()).getSeconds();
        String payload =
                JournalJson.object(
                        "reason", "context-closed", "uptimeSeconds", String.valueOf(uptimeSec));
        processEvents.writeShutdown(payload);
    }
}
