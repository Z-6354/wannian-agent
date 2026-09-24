package com.wannian.server.kernel.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 全进程唯一的稳定错误 code 登记处（0.2.1-A）。
 *
 * <p>历史 HTTP / 持久化 {@code reasonCode}、管理面 {@code ManageReason}、Agent
 * {@code ControlledFailure#errorCode} 一律引用此处常量，禁止再各写一套字符串表。
 *
 * <p>业务接口仍用具名封闭结果（Accepted / Rejected / Held…）；本类不合并成通用 {@code Result}。
 * 编程缺陷用 {@link InternalDefectException}，不把一切失败都变成受控 code。
 */
public final class ErrorCodes {

    // —— 校验：请求形状或引用目标不合法 ——

    /** 参数不合法（空体、非法 UUID、角色不符等）。 */
    public static final String ILLEGAL_ARGUMENT = "ILLEGAL_ARGUMENT";

    /** 会话不存在。 */
    public static final String CONVERSATION_NOT_FOUND = "CONVERSATION_NOT_FOUND";

    /** 回合不存在。 */
    public static final String TURN_NOT_FOUND = "TURN_NOT_FOUND";

    /** 记忆行不存在或非可操作状态（非 ACTIVE）。 */
    public static final String MEMORY_NOT_FOUND = "MEMORY_NOT_FOUND";

    /** 同一伴身与 subjectKey 已有 ACTIVE 记忆，不能创建第二条。 */
    public static final String MEMORY_SUBJECT_CONFLICT = "MEMORY_SUBJECT_CONFLICT";

    /** 模型不在供应商本次目录中。 */
    public static final String MODEL_NOT_IN_CATALOG = "MODEL_NOT_IN_CATALOG";

    /** 模型未加入本机已选列表。 */
    public static final String MODEL_NOT_LISTED = "MODEL_NOT_LISTED";

    /** 供应商不存在。 */
    public static final String VENDOR_NOT_FOUND = "VENDOR_NOT_FOUND";

    /** 协议不受支持（例如非 openai-compatible）。 */
    public static final String PROTOCOL_UNSUPPORTED = "PROTOCOL_UNSUPPORTED";

    /** 本批尚未支持的扩展能力（如 Memory / Task 变更）。 */
    public static final String UNSUPPORTED_EXTENSION = "UNSUPPORTED_EXTENSION";

    /** 已完成回合缺少可展示的助手正文。 */
    public static final String REPLY_MISSING = "REPLY_MISSING";

    // —— 冲突：revision、幂等键、owner 或状态争用 ——

    /** 乐观锁 revision 与库中不一致。 */
    public static final String REVISION_CONFLICT = "REVISION_CONFLICT";

    /** 同一 clientRequestId 正文冲突（幂等键争用）。 */
    public static final String CLIENT_REQUEST_CONFLICT = "CLIENT_REQUEST_CONFLICT";

    /** 执行身份 executionId 与库中冻结身份不一致。 */
    public static final String OWNER_MISMATCH = "OWNER_MISMATCH";

    /** 回合仍有未过期的执行 owner，不能重复认领。 */
    public static final String OWNER_ACTIVE = "OWNER_ACTIVE";

    /** 提交计划与冻结计划不一致。 */
    public static final String PLAN_MISMATCH = "PLAN_MISMATCH";

    /** 本轮执行权已失效（迟到提交 / 取消）。 */
    public static final String STALE_ATTEMPT = "STALE_ATTEMPT";

    /** 状态机不允许该迁移。 */
    public static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";

    /** 当前回合状态不允许开始这一次执行。 */
    public static final String ILLEGAL_STATUS = "ILLEGAL_STATUS";

    /** 工具目录中同名工具已注册，拒绝覆盖。 */
    public static final String TOOL_ALREADY_REGISTERED = "TOOL_ALREADY_REGISTERED";

    /** 工具名未在封闭目录中登记。 */
    public static final String TOOL_NOT_FOUND = "TOOL_NOT_FOUND";

    /** 工具参数不合法或违反工具专用约束。 */
    public static final String TOOL_INVALID_ARGUMENTS = "TOOL_INVALID_ARGUMENTS";

    /** 同一 operationId 绑定与已存记录冲突。 */
    public static final String TOOL_OPERATION_CONFLICT = "TOOL_OPERATION_CONFLICT";

    /** 工具所需本机能力不可用（如无 PowerShell）。 */
    public static final String TOOL_UNAVAILABLE = "TOOL_UNAVAILABLE";

    // —— 策略：权限、预算或产品能力未开放 ——

    /** 管理口令未通过。 */
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    /** 管理口令未配置。 */
    public static final String MANAGE_UNCONFIGURED = "MANAGE_UNCONFIGURED";

    /** 尚未启用模型。 */
    public static final String MODEL_NOT_ENABLED = "MODEL_NOT_ENABLED";

    /** 模型决策次数已用尽。 */
    public static final String BUDGET_DECISIONS_EXHAUSTED = "BUDGET_DECISIONS_EXHAUSTED";

    /** 已到软截止，不再发起新的模型决策。 */
    public static final String BUDGET_SOFT_DEADLINE = "BUDGET_SOFT_DEADLINE";

    /** 已到硬截止，无法继续调用模型。 */
    public static final String BUDGET_HARD_DEADLINE = "BUDGET_HARD_DEADLINE";

    /** 单个系统工具本轮调用次数已达上限。 */
    public static final String BUDGET_SYSTEM_TOOL_EXHAUSTED = "BUDGET_SYSTEM_TOOL_EXHAUSTED";

    /**
     * 历史统一预算耗尽码（0.2.1）。新路径请用 {@link #BUDGET_DECISIONS_EXHAUSTED} /
     * {@link #BUDGET_SOFT_DEADLINE} / {@link #BUDGET_HARD_DEADLINE}；本常量仍登记以兼容旧日志与断言。
     */
    public static final String BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";

    /** 本轮尚未启用工具。 */
    public static final String TOOLS_NOT_ENABLED = "TOOLS_NOT_ENABLED";

    /** 本轮尚未启用后台任务。 */
    public static final String BACKGROUND_NOT_ENABLED = "BACKGROUND_NOT_ENABLED";

    /** 模型拒绝回答。 */
    public static final String MODEL_REFUSAL = "MODEL_REFUSAL";

    /** 调用已取消或中断。 */
    public static final String CANCELLED = "CANCELLED";

    // —— 依赖：模型、数据库或外部服务暂不可用 ——

    /** 依赖不可用（密钥缺失、供应商拒连、解析失败等）。 */
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";

    /** 持久化写入失败（非唯一业务冲突）。 */
    public static final String PERSISTENCE_FAILED = "PERSISTENCE_FAILED";

    /** 数据库忙 / 锁竞争，可有界重试。 */
    public static final String RETRYABLE_BUSY = "RETRYABLE_BUSY";

    /** 模型调用超时或已过截止时间。 */
    public static final String MODEL_TIMEOUT = "MODEL_TIMEOUT";

    /** 模型供应商限流。 */
    public static final String MODEL_RATE_LIMITED = "MODEL_RATE_LIMITED";

    // —— 执行：认领、提交、取消等受控执行失败 ——

    /** 认领租约已过期。 */
    public static final String CLAIM_EXPIRED = "CLAIM_EXPIRED";

    /** 认领未能完成（未分类的认领失败兜底）。 */
    public static final String CLAIM_FAILED = "CLAIM_FAILED";

    /** 开始执行（CLAIMED → RUNNING）失败。 */
    public static final String START_FAILED = "START_FAILED";

    /** 完成提交失败。 */
    public static final String COMMIT_FAILED = "COMMIT_FAILED";

    /** 取消落库失败。 */
    public static final String CANCEL_FAILED = "CANCEL_FAILED";

    /** COMMITTING 缺少可恢复完成计划。 */
    public static final String MISSING_COMMIT_PLAN = "MISSING_COMMIT_PLAN";

    /** 模型返回了空的最终回答。 */
    public static final String EMPTY_FINAL_ANSWER = "EMPTY_FINAL_ANSWER";

    /** 模型输出形状非法（如 ToolCalls 无 calls）。 */
    public static final String INVALID_MODEL_OUTPUT = "INVALID_MODEL_OUTPUT";

    // —— 内部缺陷：不应发生的程序错误 ——

    /** 编程缺陷；走异常通道，不伪装成可重试业务失败。 */
    public static final String INTERNAL_DEFECT = "INTERNAL_DEFECT";

    private static final Map<String, Meta> REGISTRY = buildRegistry();

    private ErrorCodes() {}

    /** 是否已在本登记处注册。 */
    public static boolean isRegistered(String code) {
        return code != null && REGISTRY.containsKey(code);
    }

    /** 已登记 code 的不可变集合（插入序）。 */
    public static Set<String> allCodes() {
        return REGISTRY.keySet();
    }

    /** 查分类；未登记则 empty。 */
    public static Optional<ErrorCategory> categoryOf(String code) {
        Meta meta = REGISTRY.get(code);
        return meta == null ? Optional.empty() : Optional.of(meta.category());
    }

    /** 默认是否可重试；未登记则 empty。 */
    public static Optional<Boolean> defaultRetryable(String code) {
        Meta meta = REGISTRY.get(code);
        return meta == null ? Optional.empty() : Optional.of(meta.defaultRetryable());
    }

    /**
     * 要求 code 已登记；供测试与严格边界使用。
     *
     * @throws IllegalArgumentException 未登记或空白
     */
    public static String requireRegistered(String code) {
        Objects.requireNonNull(code, "code");
        String trimmed = code.trim();
        if (trimmed.isEmpty() || !REGISTRY.containsKey(trimmed)) {
            throw new IllegalArgumentException("未登记的错误 code: " + code);
        }
        return trimmed;
    }

    private static Map<String, Meta> buildRegistry() {
        Map<String, Meta> map = new LinkedHashMap<>();
        put(map, ILLEGAL_ARGUMENT, ErrorCategory.VALIDATION, false);
        put(map, CONVERSATION_NOT_FOUND, ErrorCategory.VALIDATION, false);
        put(map, TURN_NOT_FOUND, ErrorCategory.VALIDATION, false);
        put(map, MEMORY_NOT_FOUND, ErrorCategory.VALIDATION, false);
        put(map, MEMORY_SUBJECT_CONFLICT, ErrorCategory.CONFLICT, false);
        put(map, MODEL_NOT_IN_CATALOG, ErrorCategory.VALIDATION, false);
        put(map, MODEL_NOT_LISTED, ErrorCategory.VALIDATION, false);
        put(map, VENDOR_NOT_FOUND, ErrorCategory.VALIDATION, false);
        put(map, PROTOCOL_UNSUPPORTED, ErrorCategory.VALIDATION, false);
        put(map, UNSUPPORTED_EXTENSION, ErrorCategory.VALIDATION, false);
        put(map, REPLY_MISSING, ErrorCategory.VALIDATION, false);

        put(map, REVISION_CONFLICT, ErrorCategory.CONFLICT, true);
        put(map, CLIENT_REQUEST_CONFLICT, ErrorCategory.CONFLICT, false);
        put(map, OWNER_MISMATCH, ErrorCategory.CONFLICT, false);
        put(map, OWNER_ACTIVE, ErrorCategory.CONFLICT, true);
        put(map, PLAN_MISMATCH, ErrorCategory.CONFLICT, false);
        put(map, STALE_ATTEMPT, ErrorCategory.CONFLICT, false);
        put(map, ILLEGAL_TRANSITION, ErrorCategory.CONFLICT, false);
        put(map, ILLEGAL_STATUS, ErrorCategory.CONFLICT, false);
        put(map, TOOL_ALREADY_REGISTERED, ErrorCategory.CONFLICT, false);
        put(map, TOOL_NOT_FOUND, ErrorCategory.VALIDATION, false);
        put(map, TOOL_INVALID_ARGUMENTS, ErrorCategory.VALIDATION, false);
        put(map, TOOL_OPERATION_CONFLICT, ErrorCategory.CONFLICT, false);

        put(map, UNAUTHENTICATED, ErrorCategory.POLICY_DENIED, false);
        put(map, MANAGE_UNCONFIGURED, ErrorCategory.POLICY_DENIED, false);
        put(map, MODEL_NOT_ENABLED, ErrorCategory.POLICY_DENIED, false);
        put(map, BUDGET_DECISIONS_EXHAUSTED, ErrorCategory.POLICY_DENIED, false);
        put(map, BUDGET_SOFT_DEADLINE, ErrorCategory.POLICY_DENIED, false);
        put(map, BUDGET_HARD_DEADLINE, ErrorCategory.POLICY_DENIED, false);
        put(map, BUDGET_SYSTEM_TOOL_EXHAUSTED, ErrorCategory.POLICY_DENIED, false);
        put(map, BUDGET_EXHAUSTED, ErrorCategory.POLICY_DENIED, false);
        put(map, TOOLS_NOT_ENABLED, ErrorCategory.POLICY_DENIED, false);
        put(map, BACKGROUND_NOT_ENABLED, ErrorCategory.POLICY_DENIED, false);
        put(map, MODEL_REFUSAL, ErrorCategory.POLICY_DENIED, false);
        put(map, CANCELLED, ErrorCategory.POLICY_DENIED, false);

        put(map, DEPENDENCY_UNAVAILABLE, ErrorCategory.DEPENDENCY_UNAVAILABLE, true);
        put(map, PERSISTENCE_FAILED, ErrorCategory.DEPENDENCY_UNAVAILABLE, true);
        put(map, RETRYABLE_BUSY, ErrorCategory.DEPENDENCY_UNAVAILABLE, true);
        put(map, MODEL_TIMEOUT, ErrorCategory.DEPENDENCY_UNAVAILABLE, true);
        put(map, MODEL_RATE_LIMITED, ErrorCategory.DEPENDENCY_UNAVAILABLE, true);
        put(map, TOOL_UNAVAILABLE, ErrorCategory.DEPENDENCY_UNAVAILABLE, false);

        put(map, CLAIM_EXPIRED, ErrorCategory.EXECUTION_FAILED, true);
        put(map, CLAIM_FAILED, ErrorCategory.EXECUTION_FAILED, true);
        put(map, START_FAILED, ErrorCategory.EXECUTION_FAILED, true);
        put(map, COMMIT_FAILED, ErrorCategory.EXECUTION_FAILED, true);
        put(map, CANCEL_FAILED, ErrorCategory.EXECUTION_FAILED, true);
        put(map, MISSING_COMMIT_PLAN, ErrorCategory.EXECUTION_FAILED, false);
        put(map, EMPTY_FINAL_ANSWER, ErrorCategory.EXECUTION_FAILED, false);
        put(map, INVALID_MODEL_OUTPUT, ErrorCategory.EXECUTION_FAILED, false);

        put(map, INTERNAL_DEFECT, ErrorCategory.INTERNAL_DEFECT, false);
        return Collections.unmodifiableMap(map);
    }

    private static void put(
            Map<String, Meta> map, String code, ErrorCategory category, boolean defaultRetryable) {
        if (map.put(code, new Meta(category, defaultRetryable)) != null) {
            throw new ExceptionInInitializerError("重复登记错误 code: " + code);
        }
    }

    /** 登记元数据：分类与默认是否可重试。 */
    private record Meta(ErrorCategory category, boolean defaultRetryable) {}
}
