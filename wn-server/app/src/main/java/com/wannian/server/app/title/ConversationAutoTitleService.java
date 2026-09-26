package com.wannian.server.app.title;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.ConversationStatus;
import com.wannian.server.api.conversation.TitleSource;
import com.wannian.server.app.chat.CompletedTurnReplyLoader;
import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.app.model.EnabledModelPortResolver.ResolveResult;
import com.wannian.server.app.model.TimeoutModelPort;
import com.wannian.server.app.persistence.SqliteConversationStore;
import com.wannian.server.app.persistence.SqliteTurnQueue;
import com.wannian.server.kernel.conversation.ConversationMutationResult;
import com.wannian.server.kernel.conversation.ConversationSummary;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 首轮成功完成 Turn 后的异步标题任务（0.2.4-E）。
 *
 * <p>独立于主回复与 SSE 终态；CAS 仅当 {@code title_source=AUTO}；失败保留临时标题。
 * 幂等键：conversationId + turnId + revision。
 */
@Component
public class ConversationAutoTitleService {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationAutoTitleService.class);
    private static final int USER_SNIPPET_MAX = 500;
    private static final int ASSISTANT_SNIPPET_MAX = 500;
    private static final int TITLE_SOFT_MAX = 40;
    private static final Duration TITLE_HARD_LIMIT = Duration.ofSeconds(20);

    private final DataSource dataSource;
    private final SqliteConversationStore conversations;
    private final SqliteTurnQueue turnQueue;
    private final CompletedTurnReplyLoader replies;
    private final EnabledModelPortResolver modelPorts;
    private final String systemPrompt;
    private final ConcurrentHashMap<String, Boolean> scheduled = new ConcurrentHashMap<>();
    private final ExecutorService workers;

    public ConversationAutoTitleService(
            DataSource dataSource,
            SqliteConversationStore conversations,
            SqliteTurnQueue turnQueue,
            CompletedTurnReplyLoader replies,
            EnabledModelPortResolver modelPorts) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.turnQueue = Objects.requireNonNull(turnQueue, "turnQueue");
        this.replies = Objects.requireNonNull(replies, "replies");
        this.modelPorts = Objects.requireNonNull(modelPorts, "modelPorts");
        this.systemPrompt = loadSystemPrompt();
        AtomicInteger seq = new AtomicInteger();
        this.workers =
                new ThreadPoolExecutor(
                        1,
                        2,
                        60L,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(32),
                        (ThreadFactory)
                                r -> {
                                    Thread t =
                                            new Thread(
                                                    r, "auto-title-" + seq.incrementAndGet());
                                    t.setDaemon(true);
                                    return t;
                                },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 首个成功完成的用户 Turn 后调度一次；非首轮 / MANUAL / 回收站 / 重复键均静默跳过。
     * 不阻塞调用方。
     */
    public void scheduleAfterCompleted(ConversationId conversationId, TurnId turnId) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(turnId, "turnId");
        Optional<ConversationSummary> found = conversations.find(conversationId);
        if (found.isEmpty()) {
            return;
        }
        ConversationSummary summary = found.get();
        if (summary.titleSource() != TitleSource.AUTO) {
            return;
        }
        if (summary.status() == ConversationStatus.TRASHED) {
            return;
        }
        if (!isFirstCompletedTurn(conversationId, turnId)) {
            return;
        }
        String key =
                conversationId.asString()
                        + "|"
                        + turnId.asString()
                        + "|"
                        + summary.revision();
        if (scheduled.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        long expectedRevision = summary.revision();
        try {
            workers.execute(
                    () ->
                            runTitleJob(
                                    conversationId, turnId, expectedRevision, key));
        } catch (RuntimeException ex) {
            scheduled.remove(key);
            LOG.info(
                    "标题任务未能入队 conversation={} turn={}: {}",
                    conversationId.asString(),
                    turnId.asString(),
                    ex.toString());
        }
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }

    private void runTitleJob(
            ConversationId conversationId, TurnId turnId, long expectedRevision, String key) {
        try {
            Optional<ConversationSummary> again = conversations.find(conversationId);
            if (again.isEmpty()
                    || again.get().titleSource() != TitleSource.AUTO
                    || again.get().revision() != expectedRevision
                    || again.get().status() == ConversationStatus.TRASHED) {
                return;
            }
            Optional<String> userText = turnQueue.loadUserText(turnId);
            if (userText.isEmpty() || userText.get().isBlank()) {
                LOG.info(
                        "标题任务跳过：无用户正文 conversation={} turn={}",
                        conversationId.asString(),
                        turnId.asString());
                return;
            }
            String userSnippet = clip(userText.get(), USER_SNIPPET_MAX);
            String assistantSnippet =
                    clip(replies.loadText(turnId).orElse(""), ASSISTANT_SNIPPET_MAX);

            ResolveResult resolved = modelPorts.resolve();
            if (!(resolved instanceof ResolveResult.Resolved ok)) {
                LOG.info(
                        "标题任务跳过：无可用模型 conversation={} turn={}",
                        conversationId.asString(),
                        turnId.asString());
                return;
            }
            ModelPort port = new TimeoutModelPort(ok.port(), TITLE_HARD_LIMIT);
            Instant deadline = Instant.now().plus(TITLE_HARD_LIMIT);
            ModelRequest request =
                    new ModelRequest(
                            List.of(
                                    new ModelMessage("system", systemPrompt),
                                    new ModelMessage(
                                            "user",
                                            "用户消息片段：\n"
                                                    + userSnippet
                                                    + "\n\n助手回复片段：\n"
                                                    + (assistantSnippet.isEmpty()
                                                            ? "（无）"
                                                            : assistantSnippet))));
            ModelCallContext ctx =
                    new ModelCallContext(
                            "title:" + turnId.asString(),
                            1,
                            deadline,
                            false,
                            "auto-title-" + conversationId.asString());
            ModelOutcome outcome = port.decide(request, ctx);
            String title = extractTitle(outcome);
            if (title == null || title.isBlank()) {
                LOG.info(
                        "标题任务失败：模型输出不可用，保留临时标题 conversation={} turn={}",
                        conversationId.asString(),
                        turnId.asString());
                return;
            }
            ConversationMutationResult result =
                    conversations.applyAutoTitle(
                            conversationId, expectedRevision, title, turnId.asString());
            if (result instanceof ConversationMutationResult.Ok) {
                LOG.info(
                        "自动标题已写入 conversation={} turn={} titleLen={}",
                        conversationId.asString(),
                        turnId.asString(),
                        title.length());
            } else if (result instanceof ConversationMutationResult.Rejected rejected) {
                LOG.info(
                        "自动标题 CAS 未写入 conversation={} turn={} code={} detail={}",
                        conversationId.asString(),
                        turnId.asString(),
                        rejected.reasonCode(),
                        rejected.detail());
            }
        } catch (RuntimeException ex) {
            LOG.info(
                    "标题任务异常 conversation={} turn={}: {}",
                    conversationId.asString(),
                    turnId.asString(),
                    ex.toString());
        } finally {
            // 保留 key，避免同 revision 重复刷模型；revision 变化后新 key 可再调度
            if (scheduled.size() > 2000) {
                int toRemove = scheduled.size() - 1800;
                for (String k : scheduled.keySet()) {
                    if (toRemove <= 0) {
                        break;
                    }
                    if (!k.equals(key) && scheduled.remove(k, Boolean.TRUE)) {
                        toRemove--;
                    }
                }
            }
        }
    }

    private boolean isFirstCompletedTurn(ConversationId conversationId, TurnId turnId) {
        String sql =
                """
                SELECT COUNT(*) FROM turn
                WHERE conversation_id = ? AND status = 'COMPLETED'
                """;
        try (var connection = dataSource.getConnection();
                var ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId.asString());
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                long count = rs.getLong(1);
                if (count != 1) {
                    return false;
                }
            }
        } catch (Exception ex) {
            LOG.info(
                    "标题任务首轮探测失败 conversation={}: {}",
                    conversationId.asString(),
                    ex.toString());
            return false;
        }
        // 确认这唯一的 COMPLETED 就是本 turn
        String check =
                """
                SELECT 1 FROM turn
                WHERE id = ? AND conversation_id = ? AND status = 'COMPLETED'
                """;
        try (var connection = dataSource.getConnection();
                var ps = connection.prepareStatement(check)) {
            ps.setString(1, turnId.asString());
            ps.setString(2, conversationId.asString());
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception ex) {
            return false;
        }
    }

    static String extractTitle(ModelOutcome outcome) {
        if (!(outcome instanceof ModelOutcome.FinalAnswer answered)) {
            return null;
        }
        String cleaned = SqliteConversationStore.cleanTitle(answered.text());
        if (cleaned.isEmpty()) {
            return null;
        }
        // 拒绝明显解释性垃圾 / fake 包装
        if (cleaned.length() > TITLE_SOFT_MAX + 20) {
            cleaned = cleaned.substring(0, TITLE_SOFT_MAX).strip();
        }
        if (cleaned.startsWith("假模型")
                || cleaned.contains("标题助手")
                || cleaned.toLowerCase().startsWith("title:")) {
            return null;
        }
        // 若模型仍夹带说明，取第一行已由 cleanTitle 压成单行；过长再截
        if (cleaned.length() > TITLE_SOFT_MAX) {
            cleaned = cleaned.substring(0, TITLE_SOFT_MAX).strip();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String clip(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String t = raw.strip();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max);
    }

    private static String loadSystemPrompt() {
        ClassPathResource resource = new ClassPathResource("prompt-seeds/TITLE.md");
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            return "生成一个简短中文会话标题。只输出标题，单行，不超过40字。";
        }
    }
}
