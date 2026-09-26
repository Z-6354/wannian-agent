package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.conversation.ConversationStatus;
import com.wannian.server.api.conversation.TitleSource;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储口：创建、只读近讯、列表/生命周期/搜索（0.2.4-B）。
 *
 * <p>实现放在 app（SQLite）；kernel 不依赖 JDBC。
 * 写入消息仍走 {@code TurnCommitter}；本接口不提供改写正文的方法。
 */
public interface ConversationStore {

    CreateConversationResult create(CreateConversationCommand command);

    /**
     * 按会话时间线升序返回最近 {@code limit} 条已提交消息。
     *
     * <p>实现应先按 {@code sequence_no} 取最新若干条，再升序交还给调用方。
     * 会话不存在或尚无消息时返回空列表，不得返回 null。
     */
    List<ConversationMessage> listRecentMessages(ConversationId conversationId, int limit);

    /** 分页列表；默认语义由调用方传 status（通常 ACTIVE）。 */
    ConversationListResult list(ConversationListQuery query);

    /** 最近一条可恢复 ACTIVE；无则 empty。只读。 */
    Optional<ConversationSummary> findRecentActive();

    /** 详情；不存在 empty。TRASHED 仍可读。 */
    Optional<ConversationSummary> find(ConversationId conversationId);

    /** 消息历史（升序）；会话不存在 → Rejected。 */
    ConversationHistoryResult listMessages(ConversationHistoryQuery query);

    ConversationMutationResult rename(RenameConversationCommand command);

    ConversationMutationResult archive(ConversationId id, long expectedRevision);

    ConversationMutationResult unarchive(ConversationId id, long expectedRevision);

    ConversationMutationResult trash(ConversationId id, long expectedRevision);

    ConversationMutationResult restore(ConversationId id, long expectedRevision);

    ConversationSearchResult search(ConversationSearchQuery query);

    EmptyTrashResult emptyTrash(EmptyTrashCommand command);
}
