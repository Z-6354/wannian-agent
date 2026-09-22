package com.wannian.server.kernel.agent;

/**
 * Turn 触发来源。
 *
 * <p>0.2.1-C 仅使用 {@link #USER}；其余值为契约预留，不得半套实现世界线 ingress。
 */
public enum TurnSource {
    /** 用户主动发话。 */
    USER,
    /** 世界事件投递给烟火（预留）。 */
    WORLD,
    /** 日程/定时触发（预留）。 */
    SCHEDULE,
    /** 游戏等外部通道（预留）。 */
    GAME,
    /** 系统内部触发（预留）。 */
    SYSTEM
}
