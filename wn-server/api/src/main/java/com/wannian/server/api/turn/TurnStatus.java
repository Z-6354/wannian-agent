package com.wannian.server.api.turn;

/**
 * 回合状态，对应表 {@code turn.status}（库中存枚举名字符串）。
 *
 * <p>合法迁移见 docs/guide/29；本枚举只定义取值集合，不实现转换方法
 * （转换由后续领域对象、{@code OWNER: USER} 负责）。
 *
 * <p>禁止依赖本枚举做「改库字符串跳状态」；持久化层应写入 {@link #name()}。
 */
public enum TurnStatus {

    /** 已接收，尚未被执行方 claim。 */
    RECEIVED,

    /** 已认领，准备进入运行。 */
    CLAIMED,

    /** 执行中（模型 / 工具等）。 */
    RUNNING,

    /** 已有最终 Outcome，正在与 Message / Outbox 同事务提交。 */
    COMMITTING,

    /** 成功终态。 */
    COMPLETED,

    /** 取消终态。 */
    CANCELLED,

    /** 失败终态。 */
    FAILED
}
