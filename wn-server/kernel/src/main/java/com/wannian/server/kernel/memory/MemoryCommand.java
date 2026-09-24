package com.wannian.server.kernel.memory;

/**
 * 记忆短事务写缝（冷路径 / S11 / 弱 B）。
 *
 * <p>禁止业务绕过本口直写 JDBC。revision CAS；失败返回稳定错误码结果。
 */
public interface MemoryCommand {

    /** 纠正：旧行 SUPERSEDE + 新 ACTIVE（须已 Shape+Policy）。 */
    CommandResult correct(String oldMemoryId, long expectedRevision, ApprovedMemoryChange replacement);

    /** 人主动遗忘。 */
    CommandResult forget(String memoryId, long expectedRevision);

    /** Review APPLY：写入已批准变更（propose_id=llm_review）。 */
    CommandResult applyReview(ApprovedMemoryChange change);

    /** 弱 B 内部墓碑（propose_id=tombstone_scan）。 */
    CommandResult tombstone(String memoryId, long expectedRevision);

    sealed interface CommandResult {
        record Applied(String memoryId, long newRevision) implements CommandResult {}

        record Rejected(String code, String message) implements CommandResult {}
    }
}
