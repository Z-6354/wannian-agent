package com.wannian.server.kernel.memory;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * S4-a-min：仅密钥类 REJECT；其余一律 ACCEPT（含低 importance）。
 *
 * <p>词表/模式是安全门，不是内容抽记。规则见施工单 §3.3。
 */
public final class SecretOnlyMemoryPolicy implements MemoryPolicy {

    public static final String REJECT_CODE = "MEMORY_SECRET_REJECTED";

    private static final Pattern PASSWORD_CTX =
            Pattern.compile("密码是|口令是|password\\s*=", Pattern.CASE_INSENSITIVE);

    private static final Pattern TOKEN_CTX =
            Pattern.compile(
                    "api[_-]?key|access[_-]?token|refresh[_-]?token|bearer\\s+eyj",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PEM_PRIVATE_KEY =
            Pattern.compile(
                    "BEGIN\\s+(RSA\\s+)?PRIVATE\\s+KEY", Pattern.CASE_INSENSITIVE);

    /** 16+ 连续数字 + 同段出现 CVV/验证码。 */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{16,}");

    private static final Pattern CVV_OR_CODE =
            Pattern.compile("cvv|验证码|cvc|security\\s*code", Pattern.CASE_INSENSITIVE);

    @Override
    public PolicyResult evaluate(MemoryToolDraft shapedDraft) {
        Objects.requireNonNull(shapedDraft, "shapedDraft");
        String haystack = haystack(shapedDraft);
        if (PASSWORD_CTX.matcher(haystack).find()
                || TOKEN_CTX.matcher(haystack).find()
                || PEM_PRIVATE_KEY.matcher(haystack).find()
                || looksLikeCardWithCvv(haystack)) {
            return new PolicyResult.Rejected(REJECT_CODE, "拒绝存储疑似密钥类内容");
        }
        return new PolicyResult.Accepted();
    }

    private static String haystack(MemoryToolDraft draft) {
        StringBuilder sb = new StringBuilder();
        sb.append(draft.claim());
        sb.append('\n').append(draft.subjectKey());
        if (draft.path() != null) {
            sb.append('\n').append(draft.path());
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeCardWithCvv(String haystack) {
        return LONG_DIGIT_RUN.matcher(haystack).find()
                && CVV_OR_CODE.matcher(haystack).find();
    }
}
