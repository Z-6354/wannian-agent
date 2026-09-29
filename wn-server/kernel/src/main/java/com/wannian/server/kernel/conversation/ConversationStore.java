package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.ConversationId;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储口：创建、只读近讯、列表/生命周期/搜索（2.4.3）。
 *
 * <p>实现放在 app（SQLite）；kernel 不依赖 JDBC。
 * 写入消息仍走 {@code TurnCommitter}；本接口不提供改写正文的方法。
 *
 * <p>B 段新方法提供默认 {@code UnsupportedOperationException}，便于测试夹具只实现近讯。
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

    default ConversationListResult list(ConversationListQuery query) {
        throw new UnsupportedOperationException("list");
    }

    default Optional<ConversationSummary> findRecentActive() {
        throw new UnsupportedOperationException("findRecentActive");
    }

    default Optional<ConversationSummary> find(ConversationId conversationId) {
        throw new UnsupportedOperationException("find");
    }

    default ConversationHistoryResult listMessages(ConversationHistoryQuery query) {
        throw new UnsupportedOperationException("listMessages");
    }

    default ConversationMutationResult rename(RenameConversationCommand command) {
        throw new UnsupportedOperationException("rename");
    }

    /**
     * 自动标题 CAS：仅当 {@code title_source=AUTO} 且 revision 匹配时写入；
     * MANUAL / 回收站 / revision 冲突均拒绝。成功时应写 Outbox {@code TitleChanged}。
     */
    default ConversationMutationResult applyAutoTitle(
            ConversationId id, long expectedRevision, String title, String sourceTurnId) {
        throw new UnsupportedOperationException("applyAutoTitle");
    }

    default ConversationMutationResult archive(ConversationId id, long expectedRevision) {
        throw new UnsupportedOperationException("archive");
    }

    default ConversationMutationResult unarchive(ConversationId id, long expectedRevision) {
        throw new UnsupportedOperationException("unarchive");
    }

    default ConversationMutationResult trash(ConversationId id, long expectedRevision) {
        throw new UnsupportedOperationException("trash");
    }

    default ConversationMutationResult restore(ConversationId id, long expectedRevision) {
        throw new UnsupportedOperationException("restore");
    }

    default ConversationMutationResult pin(ConversationId id, long expectedRevision) {
        throw new UnsupportedOperationException("pin");
    }

    default ConversationMutationResult unpin(ConversationId id, long expectedRevision) {
        throw new UnsupportedOperationException("unpin");
    }

    default ConversationSearchResult search(ConversationSearchQuery query) {
        throw new UnsupportedOperationException("search");
    }

    default EmptyTrashResult emptyTrash(EmptyTrashCommand command) {
        throw new UnsupportedOperationException("emptyTrash");
    }

    /**
     * 硬删 ARCHIVED 会话（消息 / turn / 搜索索引一并清掉）。忙碌会话跳过。
     */
    default EmptyTrashResult emptyArchive(EmptyArchiveCommand command) {
        throw new UnsupportedOperationException("emptyArchive");
    }

    /**
     * 硬删 ACTIVE 且无 message、无活动 Turn 的空会话。返回删除条数。
     *
     * <p>不碰 memory_record。有界 {@code limit}。
     */
    default int purgeEmptyActiveConversations(int limit) {
        throw new UnsupportedOperationException("purgeEmptyActiveConversations");
    }
}
