package com.wannian.server.kernel.error;

/**
 * 错误分类（见 guide/04-kernel-reference §14）。
 *
 * <p>稳定 {@code code} 字符串挂在 {@link ErrorCodes}；本枚举只表达类别，不另开第二套码表。
 */
public enum ErrorCategory {

    /** 用户输入或请求形状不合法。 */
    VALIDATION,

    /** revision、幂等键或状态冲突。 */
    CONFLICT,

    /** 权限、预算或产品策略不允许。 */
    POLICY_DENIED,

    /** 模型、数据库或外部依赖暂不可用。 */
    DEPENDENCY_UNAVAILABLE,

    /** 已受控的执行失败（含认领/提交/模型受控失败）。 */
    EXECUTION_FAILED,

    /** 不应发生的程序缺陷；走异常通道，不伪装成可重试业务失败。 */
    INTERNAL_DEFECT
}
