package com.wannian.server.kernel.error;

import java.util.Objects;

/**
 * 错误日志字段约定（0.2.1-A）。
 *
 * <p>只记稳定 code、操作类别、关联 ID、耗时与脱敏原因。不含密钥、SQL、堆栈或原始敏感正文。
 * kernel 不依赖具体日志实现；app 把本记录格式化后写入 Logger。
 */
public record ErrorLogFields(
        String code, String operation, String correlationId, Long durationMillis, String safeReason) {

    public ErrorLogFields {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(operation, "operation");
        code = code.trim();
        operation = operation.trim();
        if (code.isEmpty() || operation.isEmpty()) {
            throw new IllegalArgumentException("code / operation 不能为空");
        }
        if (correlationId != null) {
            correlationId = correlationId.trim();
            if (correlationId.isEmpty()) {
                correlationId = null;
            }
        }
        if (safeReason != null) {
            safeReason = redact(safeReason.trim());
            if (safeReason.isEmpty()) {
                safeReason = null;
            }
        }
    }

    /** 单行日志正文；不含换行与堆栈。 */
    public String toLogMessage() {
        StringBuilder out = new StringBuilder(96);
        out.append("code=").append(code);
        out.append(" op=").append(operation);
        if (correlationId != null) {
            out.append(" id=").append(correlationId);
        }
        if (durationMillis != null) {
            out.append(" ms=").append(durationMillis);
        }
        if (safeReason != null) {
            out.append(" reason=").append(safeReason);
        }
        return out.toString();
    }

    /**
     * 去掉明显密钥形态与过长正文，避免误把敏感串写进日志或用户可见通道。
     *
     * <p>不是密码学擦除；边界适配器仍须先译成安全文案再传入。
     */
    public static String redact(String raw) {
        String lowered = raw.toLowerCase();
        if (lowered.contains("authorization")
                || lowered.contains("api-key")
                || lowered.contains("api_key")
                || lowered.contains("bearer ")
                || lowered.contains("sk-")) {
            return "[redacted]";
        }
        if (lowered.contains("insert into")
                || lowered.contains("update ")
                || lowered.contains("select ")
                || lowered.contains("jdbc:")) {
            return "[sql-redacted]";
        }
        if (raw.length() > 240) {
            return raw.substring(0, 240) + "…";
        }
        return raw;
    }
}
