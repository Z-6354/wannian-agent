package com.wannian.server.kernel.memory;

/**
 * 记忆 Policy（S4-a-min：仅密钥类 REJECT；其余一律可存）。
 *
 * <p>不写库；低 importance 不得据此拒绝。默认实现见 {@code SecretOnlyMemoryPolicy}。
 */
public interface MemoryPolicy {

    PolicyResult evaluate(MemoryToolDraft shapedDraft);

    /** Policy 封闭结果。 */
    sealed interface PolicyResult {

        /** 放行；由调用方构造 {@link ApprovedMemoryChange}。 */
        record Accepted() implements PolicyResult {}

        /** 拒绝落库（本批密钥类）。 */
        record Rejected(String code, String message) implements PolicyResult {}
    }
}
