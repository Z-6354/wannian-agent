package com.wannian.server.app.archive;

import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.kernel.conversation.ArchiveCandidate;
import com.wannian.server.kernel.conversation.ArchiveDecision;
import com.wannian.server.kernel.conversation.ConversationArchiveEvaluator;
import com.wannian.server.kernel.conversation.ConversationMessage;
import com.wannian.server.kernel.conversation.ConversationMutationResult;
import com.wannian.server.kernel.conversation.ConversationSummary;
import com.wannian.server.app.persistence.SqliteConfig;
import com.wannian.server.app.persistence.SqliteConversationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 空会话清理 + 闲置候选 + 调用 {@link ConversationArchiveEvaluator} 自动归档。
 *
 * <p>不内嵌启发式/LLM 分支；只调注入的评估器。启动补跑 + 周期扫。
 *
 * <p><b>单机假设</b>：stamp 文件与内存锁仅防本进程重入；多实例部署需另加租约，否则可能重复评估
 *（archive CAS 仍保护状态，但通知可能重复）。
 */
@Component
@Order(50)
public class ConversationArchiveService implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationArchiveService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int RECENT_TURNS = 10;
    private static final int BODY_CLIP = 400;

    private final SqliteConversationStore conversations;
    private final ConversationArchiveEvaluator evaluator;
    private final ConversationHygieneSettings settings;
    private final ArchiveNoticeStore notices;
    private final Path stampFile;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ZoneId zone = ZoneId.systemDefault();

    public ConversationArchiveService(
            SqliteConversationStore conversations,
            ConversationArchiveEvaluator evaluator,
            ConversationHygieneSettings settings,
            ArchiveNoticeStore notices,
            @org.springframework.beans.factory.annotation.Value("${wannian.data-dir:data}")
                    String dataDir) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.notices = Objects.requireNonNull(notices, "notices");
        Path dir = SqliteConfig.resolveDataDir(dataDir);
        this.stampFile = dir.resolve("archive-schedule-stamp.json");
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int purged = conversations.purgeEmptyActiveConversations(100);
            if (purged > 0) {
                LOG.info("启动清理空会话 deleted={}", purged);
            }
        } catch (RuntimeException ex) {
            LOG.warn("启动清理空会话失败: {}", ex.toString());
        }
        if (shouldRunCatchUp(Instant.now())) {
            LOG.info("归档任务启动补跑");
            runArchivePass("startup-catchup");
        }
    }

    /** 每分钟检查是否到达配置时刻（到点触发一次，靠 stamp 防重）。 */
    @Scheduled(cron = "15 * * * * *")
    public void tick() {
        Instant now = Instant.now();
        if (!isDueNow(now)) {
            return;
        }
        if (!shouldRunCatchUp(now)) {
            return;
        }
        runArchivePass("schedule");
    }

    public void runArchivePass(String reason) {
        if (!running.compareAndSet(false, true)) {
            LOG.info("归档任务跳过：已在运行 reason={}", reason);
            return;
        }
        try {
            Instant now = Instant.now();
            Instant cutoff = now.minus(Duration.ofDays(settings.idleArchiveDays()));
            List<ConversationSummary> candidates =
                    conversations.listIdleActiveWithMessages(cutoff, settings.archiveBatchLimit());
            List<ArchiveNoticeStore.ArchivedItem> archived = new ArrayList<>();
            for (ConversationSummary summary : candidates) {
                try {
                    ArchiveCandidate candidate = buildCandidate(summary, now);
                    ArchiveDecision decision = evaluator.evaluate(candidate);
                    if (!decision.shouldArchive()) {
                        continue;
                    }
                    ConversationMutationResult result =
                            conversations.archive(summary.id(), summary.revision());
                    if (result instanceof ConversationMutationResult.Ok ok) {
                        long newRev = ok.conversation().revision();
                        archived.add(
                                new ArchiveNoticeStore.ArchivedItem(
                                        summary.id(),
                                        ok.conversation().title(),
                                        newRev,
                                        decision.reason()));
                        LOG.info(
                                "自动归档 conversation={} reason={}",
                                summary.id().asString(),
                                decision.reason());
                    }
                } catch (RuntimeException ex) {
                    LOG.info(
                            "自动归档单条失败 conversation={}: {}",
                            summary.id().asString(),
                            ex.toString());
                }
            }
            if (!archived.isEmpty()) {
                notices.publishBatch(archived, now);
            }
            writeStamp(now);
        } finally {
            running.set(false);
        }
    }

    private ArchiveCandidate buildCandidate(ConversationSummary summary, Instant now) {
        Instant activity =
                parseInstant(summary.lastActivityAt())
                        .or(() -> parseInstant(summary.createdAt()))
                        .orElse(now);
        long idleDays = Math.max(0, Duration.between(activity, now).toDays());
        List<ConversationMessage> recent =
                conversations.listRecentMessages(summary.id(), RECENT_TURNS * 4);
        // 只取 USER/ASSISTANT，最多 RECENT_TURNS 条（近似最近若干轮）
        List<ArchiveCandidate.TurnSnippet> turns = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0 && turns.size() < RECENT_TURNS; i--) {
            ConversationMessage msg = recent.get(i);
            if (msg.role() != MessageRole.USER && msg.role() != MessageRole.ASSISTANT) {
                continue;
            }
            turns.add(
                    0,
                    new ArchiveCandidate.TurnSnippet(
                            msg.role() == MessageRole.USER ? "USER" : "ASSISTANT",
                            extractText(msg.contentJson())));
        }
        return new ArchiveCandidate(
                summary.id(),
                summary.title(),
                summary.titleSource(),
                summary.revision(),
                activity,
                idleDays,
                turns);
    }

    private boolean isDueNow(Instant now) {
        LocalDateTime ldt = LocalDateTime.ofInstant(now, zone);
        if (ldt.getHour() != settings.archiveAtHour()
                || ldt.getMinute() != settings.archiveAtMinute()) {
            return false;
        }
        if (settings.scheduleKind() == ConversationHygieneSettings.ScheduleKind.WEEKLY) {
            return ldt.getDayOfWeek() == settings.archiveWeekday();
        }
        return true;
    }

    /** 自上次 stamp 起是否已错过至少一个计划点。 */
    boolean shouldRunCatchUp(Instant now) {
        Instant last = readStamp().orElse(Instant.EPOCH);
        Instant due = previousDueInstant(now);
        return last.isBefore(due);
    }

    Instant previousDueInstant(Instant now) {
        LocalDateTime ldt = LocalDateTime.ofInstant(now, zone);
        LocalTime at = LocalTime.of(settings.archiveAtHour(), settings.archiveAtMinute());
        if (settings.scheduleKind() == ConversationHygieneSettings.ScheduleKind.WEEKLY) {
            LocalDateTime candidate =
                    LocalDateTime.of(ldt.toLocalDate(), at)
                            .with(TemporalAdjusters.previousOrSame(settings.archiveWeekday()));
            if (candidate.isAfter(ldt)) {
                candidate = candidate.minusWeeks(1);
            }
            return candidate.atZone(zone).toInstant();
        }
        LocalDateTime dueToday = LocalDateTime.of(ldt.toLocalDate(), at);
        if (dueToday.isAfter(ldt)) {
            dueToday = dueToday.minusDays(1);
        }
        return dueToday.atZone(zone).toInstant();
    }

    private void writeStamp(Instant now) {
        try {
            Files.createDirectories(stampFile.getParent());
            String json = "{\"lastRunAt\":\"" + now + "\"}\n";
            Files.writeString(stampFile, json, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            LOG.warn("写入归档 stamp 失败: {}", ex.toString());
        }
    }

    private java.util.Optional<Instant> readStamp() {
        try {
            if (!Files.isRegularFile(stampFile)) {
                return java.util.Optional.empty();
            }
            JsonNode n = JSON.readTree(Files.readString(stampFile, StandardCharsets.UTF_8));
            String raw = n.path("lastRunAt").asText("");
            return parseInstant(raw);
        } catch (Exception ex) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Instant> parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Instant.parse(raw));
        } catch (RuntimeException ex) {
            return java.util.Optional.empty();
        }
    }

    private static String extractText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            JsonNode n = JSON.readTree(contentJson);
            String t = n.path("text").asText("");
            if (t.length() > BODY_CLIP) {
                return t.substring(0, BODY_CLIP);
            }
            return t;
        } catch (Exception ex) {
            return "";
        }
    }
}
