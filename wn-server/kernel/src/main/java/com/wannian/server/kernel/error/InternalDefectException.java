package com.wannian.server.kernel.error;

import java.util.Objects;

/**
 * 编程缺陷 / 进程级不应发生的故障。
 *
 * <p>走异常通道，不伪装成可重试业务 {@code Rejected}。对外映射时使用
 * {@link ErrorCodes#INTERNAL_DEFECT}；日志只记 {@link ErrorLogFields}，不直接打印本异常堆栈给用户。
 */
public final class InternalDefectException extends RuntimeException {

    public InternalDefectException(String detail) {
        super(Objects.requireNonNull(detail, "detail"));
    }

    public InternalDefectException(String detail, Throwable cause) {
        super(Objects.requireNonNull(detail, "detail"), cause);
    }

    public String errorCode() {
        return ErrorCodes.INTERNAL_DEFECT;
    }
}
