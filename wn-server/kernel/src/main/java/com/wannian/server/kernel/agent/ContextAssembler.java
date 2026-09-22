package com.wannian.server.kernel.agent;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.kernel.conversation.ConversationMessage;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.tool.FacetId;
import com.wannian.server.kernel.tool.HostCapabilitySet;
import com.wannian.server.kernel.tool.RoleId;
import com.wannian.server.kernel.tool.ToolDescriptor;
import com.wannian.server.kernel.tool.ToolVisibility;
import com.wannian.server.kernel.tool.ToolVisibilityResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把已提交近讯装成不可变 {@link AgentInput}。
 *
 * <p>只读 {@link ConversationStore}，不改原文。当前用户句单独放入 {@code userMessage}，
 * 不重复进 {@code conversationExcerpt}。记忆、关系、世界线本批不填。
 *
 * <p>0.2.2：可选注入 {@link ToolVisibilityResolver}，默认烟火 + 聊天面相可见工具。
 */
public final class ContextAssembler {

    /** 近讯条数上界（不含当前用户句）。约 10 轮。压缩前的固定裁剪。 */
    public static final int DEFAULT_RECENT_MESSAGES = 20;

    private final ConversationStore conversations;
    private final ToolVisibilityResolver visibilityResolver;
    private final HostCapabilitySet hostCapabilities;
    private final RoleId defaultRoleId;

    public ContextAssembler(ConversationStore conversations) {
        this(conversations, null, HostCapabilitySet.empty(), RoleId.YANHUO);
    }

    public ContextAssembler(
            ConversationStore conversations,
            ToolVisibilityResolver visibilityResolver,
            HostCapabilitySet hostCapabilities) {
        this(conversations, visibilityResolver, hostCapabilities, RoleId.YANHUO);
    }

    public ContextAssembler(
            ConversationStore conversations,
            ToolVisibilityResolver visibilityResolver,
            HostCapabilitySet hostCapabilities,
            RoleId defaultRoleId) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.visibilityResolver = visibilityResolver;
        this.hostCapabilities =
                hostCapabilities == null ? HostCapabilitySet.empty() : hostCapabilities;
        this.defaultRoleId = defaultRoleId == null ? RoleId.YANHUO : defaultRoleId;
    }

    /**
     * @param request 本轮身份与当前用户句；不得为 null
     * @return 供 {@link AgentLoop#run} 使用的快照；不得为 null
     */
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

        return new AgentInput(
                request.turnId(),
                request.turnSource(),
                excerpt,
                null,
                null,
                request.userMessage(),
                tools,
                profileId,
                request.systemInstructions(),
                null);
    }

    /**
     * 从已升序的近讯中去掉当前用户句，只保留最新 {@code limit} 条，拼成摘录。
     */
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

    /**
     * @param facetId 面相；null ≡ {@link FacetId#CHAT}
     */
    public record AssemblyRequest(
            ConversationId conversationId,
            TurnId turnId,
            TurnSource turnSource,
            MessageId currentUserMessageId,
            String userMessage,
            String systemInstructions,
            int recentMessageLimit,
            FacetId facetId) {

        public AssemblyRequest {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(turnSource, "turnSource");
            Objects.requireNonNull(currentUserMessageId, "currentUserMessageId");
            Objects.requireNonNull(userMessage, "userMessage");
            Objects.requireNonNull(systemInstructions, "systemInstructions");
            if (recentMessageLimit <= 0) {
                throw new IllegalArgumentException("recentMessageLimit 须为正");
            }
        }

        /** 近讯条数使用 {@link ContextAssembler#DEFAULT_RECENT_MESSAGES}；面相默认聊天。 */
        public static AssemblyRequest of(
                ConversationId conversationId,
                TurnId turnId,
                MessageId currentUserMessageId,
                String userMessage,
                String systemInstructions) {
            return new AssemblyRequest(
                    conversationId,
                    turnId,
                    TurnSource.USER,
                    currentUserMessageId,
                    userMessage,
                    systemInstructions,
                    DEFAULT_RECENT_MESSAGES,
                    FacetId.CHAT);
        }

        /** 指定面相。 */
        public static AssemblyRequest of(
                ConversationId conversationId,
                TurnId turnId,
                MessageId currentUserMessageId,
                String userMessage,
                String systemInstructions,
                FacetId facetId) {
            return new AssemblyRequest(
                    conversationId,
                    turnId,
                    TurnSource.USER,
                    currentUserMessageId,
                    userMessage,
                    systemInstructions,
                    DEFAULT_RECENT_MESSAGES,
                    facetId);
        }
    }
}
