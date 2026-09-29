package com.wannian.server.app.archive;

import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.app.model.EnabledModelPortResolver.ResolveResult;
import com.wannian.server.app.model.TimeoutModelPort;
import com.wannian.server.kernel.conversation.ArchiveCandidate;
import com.wannian.server.kernel.conversation.ArchiveDecision;
import com.wannian.server.kernel.conversation.ConversationArchiveEvaluator;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

/**
 * 大模型归档评估（2.4.7 默认实现）。
 *
 * <p>与 {@link com.wannian.server.kernel.conversation.HeuristicConversationArchiveEvaluator}
 * 并列；由 {@link ConversationArchiveConfig} 按配置装配。失败一律 KEEP。
 *
 * <p>本类不是 Spring {@code @Component}，避免与启发式条件装配冲突。
 */
public class LlmConversationArchiveEvaluator implements ConversationArchiveEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(LlmConversationArchiveEvaluator.class);
    private static final Duration HARD_LIMIT = Duration.ofSeconds(25);
    private static final int SNIPPET_MAX = 400;

    private final EnabledModelPortResolver modelPorts;
    private final String systemPrompt;

    public LlmConversationArchiveEvaluator(EnabledModelPortResolver modelPorts) {
        this.modelPorts = Objects.requireNonNull(modelPorts, "modelPorts");
        this.systemPrompt = loadSystemPrompt();
    }

    @Override
    public ArchiveDecision evaluate(ArchiveCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        try {
            ResolveResult resolved = modelPorts.resolve();
            if (!(resolved instanceof ResolveResult.Resolved ok)) {
                return ArchiveDecision.keep("no model available");
            }
            ModelPort port = new TimeoutModelPort(ok.port(), HARD_LIMIT);
            Instant deadline = Instant.now().plus(HARD_LIMIT);
            ModelRequest request =
                    new ModelRequest(
                            List.of(
                                    new ModelMessage("system", systemPrompt),
                                    new ModelMessage("user", buildUserPrompt(candidate))));
            ModelCallContext ctx =
                    new ModelCallContext(
                            "archive:" + candidate.conversationId().asString(),
                            1,
                            deadline,
                            false,
                            "auto-archive-" + candidate.conversationId().asString());
            ModelOutcome outcome = port.decide(request, ctx);
            return parse(outcome);
        } catch (RuntimeException ex) {
            LOG.info(
                    "归档评估异常 conversation={}: {}",
                    candidate.conversationId().asString(),
                    ex.toString());
            return ArchiveDecision.keep("evaluator error");
        }
    }

    static ArchiveDecision parse(ModelOutcome outcome) {
        if (!(outcome instanceof ModelOutcome.FinalAnswer answered)) {
            return ArchiveDecision.keep("non-final model outcome");
        }
        String raw = answered.text() == null ? "" : answered.text().strip();
        if (raw.isEmpty()) {
            return ArchiveDecision.keep("empty model output");
        }
        String first =
                raw.lines()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .findFirst()
                        .orElse("")
                        .toUpperCase(Locale.ROOT);
        // 去掉行尾标点，只认整词 KEEP / ARCHIVE
        String token = first.replaceAll("[\\p{Punct}\\s]+$", "");
        int space = token.indexOf(' ');
        if (space > 0) {
            token = token.substring(0, space);
        }
        String reason = extractReason(raw);
        if ("ARCHIVE".equals(token)) {
            return ArchiveDecision.archive(reason.isEmpty() ? "model archive" : reason);
        }
        if ("KEEP".equals(token)) {
            return ArchiveDecision.keep(reason.isEmpty() ? "model keep" : reason);
        }
        return ArchiveDecision.keep("unparsed model output");
    }

    private static String extractReason(String raw) {
        for (String line : raw.lines().toList()) {
            String t = line.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("reason:")) {
                return t.substring("reason:".length()).strip();
            }
        }
        List<String> lines = raw.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (lines.size() >= 2) {
            return lines.get(1);
        }
        return "";
    }

    private static String buildUserPrompt(ArchiveCandidate c) {
        StringBuilder sb = new StringBuilder();
        sb.append("会话标题：").append(c.title()).append('\n');
        sb.append("标题来源：").append(c.titleSource()).append('\n');
        sb.append("闲置天数：").append(c.idleDays()).append('\n');
        sb.append("最后活动：").append(c.lastActivityAt()).append('\n');
        sb.append("最近对话（最多10轮摘要）：\n");
        if (c.recentTurns().isEmpty()) {
            sb.append("（无）\n");
        } else {
            int i = 1;
            for (ArchiveCandidate.TurnSnippet turn : c.recentTurns()) {
                String text = turn.text();
                if (text.length() > SNIPPET_MAX) {
                    text = text.substring(0, SNIPPET_MAX);
                }
                sb.append(i++)
                        .append(". [")
                        .append(turn.role())
                        .append("] ")
                        .append(text)
                        .append('\n');
            }
        }
        sb.append("\n请只按系统要求输出 KEEP 或 ARCHIVE。");
        return sb.toString();
    }

    private static String loadSystemPrompt() {
        ClassPathResource resource = new ClassPathResource("prompt-seeds/ARCHIVE.md");
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            return """
                    你是会话归档助手。根据标题、闲置天数与最近对话，判断是否应归档。
                    仅当会话明显过时、主题已结束、或长期闲置且无继续价值时输出 ARCHIVE。
                    不确定、仍可能继续聊、或重要未完主题时输出 KEEP。
                    第一行只能是 KEEP 或 ARCHIVE；第二行可选 reason: 简短中文说明。
                    """;
        }
    }
}
