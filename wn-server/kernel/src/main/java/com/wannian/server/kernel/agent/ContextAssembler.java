package com.wannian.server.kernel.agent;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.kernel.conversation.ConversationMessage;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryRecall;
import com.wannian.server.kernel.memory.MemoryRecallLimits;
import com.wannian.server.kernel.memory.MemoryRecallTouch;
import com.wannian.server.kernel.memory.StoredMemoryRecord;
import com.wannian.server.kernel.memory.TurnMemoryPending;
import com.wannian.server.kernel.relationship.RelationshipStore;
import com.wannian.server.kernel.relationship.StoredRelationshipState;
import com.wannian.server.kernel.tool.FacetId;
import com.wannian.server.kernel.tool.HostCapabilitySet;
import com.wannian.server.kernel.tool.RoleId;
import com.wannian.server.kernel.tool.ToolDescriptor;
import com.wannian.server.kernel.tool.ToolVisibility;
import com.wannian.server.kernel.tool.ToolVisibilityResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把已提交近讯装成不可变 {@link AgentInput}。
 *
 * <p>只读 {@link ConversationStore}，不改原文。当前用户句单独放入 {@code userMessage}，
 * 不重复进 {@code conversationExcerpt}。
 *
 * <p>0.2.2：可选注入 {@link ToolVisibilityResolver}，默认烟火 + 聊天面相可见工具。
 *
 * <p>0.2.3-B：注入 Mem0 式 Observation Date（{@link #OBSERVATION_ZONE}）与地点「未说明」锚块。
 *
 * <p>0.2.3-D / D+：
 *
 * <ul>
 *   <li>可选 {@link MemoryRecall} + {@link MemoryRecallLimits}（yml）→ {@code memoryContext}；
 *       Top-N 后按字符预算装填；超预算对本条 {@code continue}（不 break），只 touch 实际拼入的 id。
 *   <li>可选 {@link RelationshipStore} → {@code relationshipSnapshot}（{@code toPromptText}）。
 *   <li>可选 {@link MemoryRecallTouch} best-effort bump；召回/关系失败只打日志，memory/rel 置 null，不挡 Turn。
 *   <li>启用 recall 时必须同时注入 limits；kernel 不读 yml。
 * </ul>
 */
public final class ContextAssembler {

    private static final System.Logger LOG = System.getLogger(ContextAssembler.class.getName());

    public static final int DEFAULT_RECENT_MESSAGES = 20;
    public static final ZoneId OBSERVATION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter OBSERVATION_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ConversationStore conversations;
    private final ToolVisibilityResolver visibilityResolver;
    private final HostCapabilitySet hostCapabilities;
    private final RoleId defaultRoleId;
    private final Clock clock;
    private final MemoryRecall memoryRecall;
    private final RelationshipStore relationshipStore;
    private final MemoryRecallTouch recallTouch;
    /** 召回限额；可为 null（关闭注入时可不配）。非 null 时 topN/charBudget 来自配置。 */
    private final MemoryRecallLimits recallLimits;

    public ContextAssembler(ConversationStore conversations) {
        this(
                conversations,
                null,
                HostCapabilitySet.empty(),
                RoleId.YANHUO,
                Clock.system(OBSERVATION_ZONE),
                null,
                null,
                null,
                null);
    }

    public ContextAssembler(
            ConversationStore conversations,
            ToolVisibilityResolver visibilityResolver,
            HostCapabilitySet hostCapabilities) {
        this(
                conversations,
                visibilityResolver,
                hostCapabilities,
                RoleId.YANHUO,
                Clock.system(OBSERVATION_ZONE),
                null,
                null,
                null,
                null);
    }

    public ContextAssembler(
            ConversationStore conversations,
            ToolVisibilityResolver visibilityResolver,
            HostCapabilitySet hostCapabilities,
            RoleId defaultRoleId) {
        this(
                conversations,
                visibilityResolver,
                hostCapabilities,
                defaultRoleId,
                Clock.system(OBSERVATION_ZONE),
                null,
                null,
                null,
                null);
    }

    public ContextAssembler(
            ConversationStore conversations,
            ToolVisibilityResolver visibilityResolver,
            HostCapabilitySet hostCapabilities,
            RoleId defaultRoleId,
            Clock clock) {
        this(
                conversations,
                visibilityResolver,
                hostCapabilities,
                defaultRoleId,
                clock,
                null,
                null,
                null,
                null);
    }

    public ContextAssembler(
            ConversationStore conversations,
            ToolVisibilityResolver visibilityResolver,
            HostCapabilitySet hostCapabilities,
            RoleId defaultRoleId,
            Clock clock,
            MemoryRecall memoryRecall,
            RelationshipStore relationshipStore,
            MemoryRecallTouch recallTouch,
            MemoryRecallLimits recallLimits) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.visibilityResolver = visibilityResolver;
        this.hostCapabilities =
                hostCapabilities == null ? HostCapabilitySet.empty() : hostCapabilities;
        this.defaultRoleId = defaultRoleId == null ? RoleId.YANHUO : defaultRoleId;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.memoryRecall = memoryRecall;
        this.relationshipStore = relationshipStore;
        this.recallTouch = recallTouch;
        this.recallLimits = recallLimits;
        if (memoryRecall != null && recallLimits == null) {
            throw new IllegalArgumentException("启用 memoryRecall 时须注入 recallLimits（来自配置）");
        }
    }

    public AgentInput assemble(AssemblyRequest request) {
        Objects.requireNonNull(request, "request");
        List<ConversationMessage> recent =
                conversations.listRecentMessages(
                        request.conversationId(), request.recentMessageLimit() + 1);
        String excerpt =
                formatExcerpt(recent, request.currentUserMessageId(), request.recentMessageLimit());

        List<ToolDescriptor> tools = List.of();
        String profileId = null;
        if (visibilityResolver != null) {
            FacetId facet = request.facetId() == null ? FacetId.CHAT : request.facetId();
            ToolVisibility visibility =
                    visibilityResolver.resolve(defaultRoleId, facet, hostCapabilities);
            tools = visibility.descriptors();
            profileId = visibility.profileId();
        }

        String systemInstructions =
                mergeSystemInstructions(request.systemInstructions(), observationAnchorBlock());

        Instant now = Instant.now(clock);
        String memoryContext = buildMemoryContext(now);
        String relationshipSnapshot = buildRelationshipSnapshot();

        return new AgentInput(
                request.turnId(),
                request.conversationId(),
                request.turnSource(),
                excerpt,
                memoryContext,
                relationshipSnapshot,
                request.userMessage(),
                tools,
                profileId,
                systemInstructions,
                null,
                request.pending());
    }

    /**
     * 按配置 Top-N 召回后拼装记忆块；失败或全跳过 → null。
     *
     * <p>D+：单条会超 {@code charBudget} 时跳过该条继续后续，避免一条过长堵死其后短高分条。
     */
    private String buildMemoryContext(Instant now) {
        if (memoryRecall == null) {
            return null;
        }
        try {
            List<StoredMemoryRecord> top =
                    memoryRecall.recallTop(
                            CompanionIdentity.YANHUO, now, recallLimits.topN());
            if (top.isEmpty()) {
                return null;
            }
            StringBuilder out = new StringBuilder("记忆（按相关性）：");
            List<String> touched = new ArrayList<>();
            int charBudget = recallLimits.charBudget();
            for (StoredMemoryRecord row : top) {
                String line =
                        "\n- "
                                + row.claim()
                                + "（importance="
                                + formatImportance(row.importance())
                                + "）";
                if (out.length() + line.length() > charBudget) {
                    // D+：超预算跳过本条继续试后续（OpenClaw 式），避免单条过长堵死其后短高分条
                    continue;
                }
                out.append(line);
                touched.add(row.id());
            }
            if (touched.isEmpty()) {
                return null;
            }
            if (recallTouch != null) {
                try {
                    recallTouch.touchRecalled(touched, now);
                } catch (RuntimeException ex) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            () -> "touchRecalled 失败: " + ex.getMessage());
                }
            }
            return out.toString();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, () -> "记忆召回失败，跳过注入: " + ex.getMessage());
            return null;
        }
    }

    /**
     * 读伴身关系快照；无行、空白 {@code toPromptText}、或读库失败 → null。
     */
    private String buildRelationshipSnapshot() {
        if (relationshipStore == null) {
            return null;
        }
        try {
            return relationshipStore
                    .findByCompanion(CompanionIdentity.YANHUO)
                    .map(StoredRelationshipState::toPromptText)
                    .filter(text -> text != null && !text.isBlank())
                    .orElse(null);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, () -> "关系快照读取失败: " + ex.getMessage());
            return null;
        }
    }

    private static String formatImportance(double importance) {
        if (importance == (long) importance) {
            return Long.toString((long) importance);
        }
        return Double.toString(importance);
    }

    String observationAnchorBlock() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(OBSERVATION_ZONE));
        String date = OBSERVATION_DATE.format(now);
        String instant = now.toOffsetDateTime().toString();
        return "观察日（Observation Date）："
                + date
                + "\n时刻（ISO）："
                + instant
                + "\n地点：未说明。禁止把「这里」写成臆测地名；未知则不记或写明地点未说明。";
    }

    static String mergeSystemInstructions(String existing, String anchors) {
        Objects.requireNonNull(anchors, "anchors");
        if (existing == null || existing.isBlank()) {
            return anchors;
        }
        return existing.stripTrailing() + "\n\n" + anchors;
    }

    static String formatExcerpt(
            List<ConversationMessage> recent, MessageId currentUserMessageId, int limit) {
        Objects.requireNonNull(recent, "recent");
        Objects.requireNonNull(currentUserMessageId, "currentUserMessageId");
        if (limit <= 0) {
            throw new IllegalArgumentException("recentMessageLimit 须为正");
        }
        List<ConversationMessage> history = new ArrayList<>();
        for (ConversationMessage message : recent) {
            if (!message.messageId().equals(currentUserMessageId)) {
                history.add(message);
            }
        }
        int from = Math.max(0, history.size() - limit);
        StringBuilder excerpt = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            if (excerpt.length() > 0) {
                excerpt.append('\n');
            }
            ConversationMessage message = history.get(i);
            excerpt.append(label(message.role())).append(textOf(message.contentJson()));
        }
        return excerpt.toString();
    }

    private static String label(MessageRole role) {
        return switch (role) {
            case USER -> "用户: ";
            case ASSISTANT -> "助手: ";
        };
    }

    public static String textOf(String contentJson) {
        Objects.requireNonNull(contentJson, "contentJson");
        int key = indexOfKey(contentJson, "text");
        if (key < 0) {
            throw new IllegalArgumentException("content_json 缺少 text");
        }
        int i = skipWs(contentJson, key);
        if (i >= contentJson.length() || contentJson.charAt(i) != ':') {
            throw new IllegalArgumentException("content_json 的 text 不是字段");
        }
        i = skipWs(contentJson, i + 1);
        if (i >= contentJson.length() || contentJson.charAt(i) != '"') {
            throw new IllegalArgumentException("content_json 的 text 不是字符串");
        }
        return readJsonString(contentJson, i + 1);
    }

    private static int indexOfKey(String json, String key) {
        String needle = "\"" + key + "\"";
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i <= json.length() - needle.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                if (json.startsWith(needle, i)) {
                    return i + needle.length();
                }
                inString = true;
            }
        }
        return -1;
    }

    private static int skipWs(String json, int i) {
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String readJsonString(String json, int start) {
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                out.append(
                        switch (c) {
                            case '"', '\\', '/' -> c;
                            case 'b' -> '\b';
                            case 'f' -> '\f';
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case 'u' -> {
                                if (i + 4 >= json.length()) {
                                    throw new IllegalArgumentException("非法 \\u");
                                }
                                int code = Integer.parseInt(json.substring(i + 1, i + 5), 16);
                                i += 4;
                                yield (char) code;
                            }
                            default -> throw new IllegalArgumentException("非法转义");
                        });
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return out.toString();
            }
            out.append(c);
        }
        throw new IllegalArgumentException("字符串未闭合");
    }

    public record AssemblyRequest(
            ConversationId conversationId,
            TurnId turnId,
            TurnSource turnSource,
            MessageId currentUserMessageId,
            String userMessage,
            String systemInstructions,
            int recentMessageLimit,
            FacetId facetId,
            TurnMemoryPending pending) {

        public AssemblyRequest {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(turnSource, "turnSource");
            Objects.requireNonNull(currentUserMessageId, "currentUserMessageId");
            Objects.requireNonNull(userMessage, "userMessage");
            Objects.requireNonNull(systemInstructions, "systemInstructions");
            Objects.requireNonNull(pending, "pending");
            if (recentMessageLimit <= 0) {
                throw new IllegalArgumentException("recentMessageLimit 须为正");
            }
        }

        public static AssemblyRequest of(
                ConversationId conversationId,
                TurnId turnId,
                MessageId currentUserMessageId,
                String userMessage,
                String systemInstructions,
                TurnMemoryPending pending) {
            return new AssemblyRequest(
                    conversationId,
                    turnId,
                    TurnSource.USER,
                    currentUserMessageId,
                    userMessage,
                    systemInstructions,
                    DEFAULT_RECENT_MESSAGES,
                    FacetId.CHAT,
                    pending);
        }

        public static AssemblyRequest of(
                ConversationId conversationId,
                TurnId turnId,
                MessageId currentUserMessageId,
                String userMessage,
                String systemInstructions,
                FacetId facetId,
                TurnMemoryPending pending) {
            return new AssemblyRequest(
                    conversationId,
                    turnId,
                    TurnSource.USER,
                    currentUserMessageId,
                    userMessage,
                    systemInstructions,
                    DEFAULT_RECENT_MESSAGES,
                    facetId,
                    pending);
        }
    }
}
