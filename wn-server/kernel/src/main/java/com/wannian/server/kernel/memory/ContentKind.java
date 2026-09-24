package com.wannian.server.kernel.memory;

/**
 * 记忆内容种类（落库轴；非法值拒，不靠正则补 kind）。
 *
 * <p>WebUI {@code type=user|context} 须经映射表落到本枚举，不得把二值当全集。
 */
public enum ContentKind {
    /** 用户偏好（口味、称呼习惯、沟通风格等）。 */
    USER_PREFERENCE,
    /** 用户事实（姓名、城市等）；WebUI type=user 缺省映射至此。 */
    USER_FACT,
    /** 共同历史；WebUI type=context 缺省映射至此。 */
    SHARED_HISTORY,
    /** 任务上下文。 */
    TASK_CONTEXT
}
