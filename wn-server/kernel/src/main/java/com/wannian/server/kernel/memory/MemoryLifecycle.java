package com.wannian.server.kernel.memory;

/**
 * 记忆生命周期（落库 status）。
 *
 * <p>由 Committer / MemoryCommand / 弱 B 扫墓改写，不是模型分类字段。
 * 本批无 {@code CANDIDATE}：S4-a-min 不做候选提升；过 Policy 即 {@link #ACTIVE} 或拒而不落库。
 *
 * <p>召回（S8-a-min）与 GET ACTIVE 只认 {@link #ACTIVE}；
 * {@link #SUPERSEDED} / {@link #REJECTED} / {@link #FORGOTTEN} 不注入。
 */
public enum MemoryLifecycle {
    /** 可召回 / 可列 ACTIVE；参与 importance 衰减排序。 */
    ACTIVE,
    /** 被纠正替代；保留 supersedes 链，不召回。 */
    SUPERSEDED,
    /** 策略拒绝后若落墓碑用；本批密钥类默认不落库。 */
    REJECTED,
    /** 人主动 forget 或弱 B 低 effective 墓碑；不召回。 */
    FORGOTTEN
}
