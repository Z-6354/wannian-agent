package com.wannian.server.kernel.memory;

import java.time.Duration;

/**
 * 阶段 C Review 写死常数（间隔 / 空闲 / 条数上限 / 重试）。
 *
 * <p>改常数须同步改本类与相关单测。
 */
public final class MemoryReviewConstants {

    /** 每 N 次已完成 USER 回合 enqueue INTERVAL。 */
    public static final int INTERVAL_USER_TURNS = 10;

    /** 空闲多久 enqueue IDLE。 */
    public static final Duration IDLE_THRESHOLD = Duration.ofMinutes(30);

    /** Worker 读近讯条数上限（对齐 WebUI ~16）。 */
    public static final int RECENT_MESSAGE_LIMIT = 16;

    /** Worker ACTIVE 摘要行数上限（对齐 WebUI ~80）。 */
    public static final int ACTIVE_MEMORY_SUMMARY_LIMIT = 80;

    /** 失败后最多再试次数（含首次）；超出 → DEAD。 */
    public static final int MAX_ATTEMPTS = 3;

    /** Job 租约时长（单 JVM 消费者；为副节点预留）。 */
    public static final Duration LEASE_DURATION = Duration.ofMinutes(5);

    /** 地点锚本批固定文案。 */
    public static final String PLACE_ANCHOR_UNSPECIFIED = "未说明";

    private MemoryReviewConstants() {}
}
