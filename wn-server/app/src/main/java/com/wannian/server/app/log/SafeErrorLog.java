package com.wannian.server.app.log;

import com.wannian.server.kernel.error.ErrorLogFields;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * app 边界错误日志出口（0.2.1-A）。
 *
 * <p>只写 {@link ErrorLogFields#toLogMessage()}；禁止 {@code log.error("…", exception)} 把堆栈 /
 * SQL / 密钥打进常规业务日志。编程缺陷若需诊断，另开受控通道，不经本方法默认路径。
 */
public final class SafeErrorLog {

    private SafeErrorLog() {}

    public static void warn(Logger log, ErrorLogFields fields) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(fields, "fields");
        log.warn("{}", fields.toLogMessage());
    }

    public static void info(Logger log, ErrorLogFields fields) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(fields, "fields");
        log.info("{}", fields.toLogMessage());
    }
}
