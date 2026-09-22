package com.wannian.server.app.manage;

import com.wannian.server.kernel.error.ErrorCodes;

/**
 * 管理接口对 {@link ErrorCodes} 的兼容别名。
 *
 * <p>0.2.1-A 起稳定 code 只在 {@link ErrorCodes} 登记；本类不再新增字符串，仅作既有调用点过渡。
 * 新代码请直接引用 {@link ErrorCodes}。
 */
public final class ManageReason {

    public static final String MANAGE_UNCONFIGURED = ErrorCodes.MANAGE_UNCONFIGURED;
    public static final String UNAUTHENTICATED = ErrorCodes.UNAUTHENTICATED;
    public static final String ILLEGAL_ARGUMENT = ErrorCodes.ILLEGAL_ARGUMENT;
    public static final String REVISION_CONFLICT = ErrorCodes.REVISION_CONFLICT;
    public static final String PROTOCOL_UNSUPPORTED = ErrorCodes.PROTOCOL_UNSUPPORTED;
    public static final String VENDOR_NOT_FOUND = ErrorCodes.VENDOR_NOT_FOUND;
    public static final String MODEL_NOT_IN_CATALOG = ErrorCodes.MODEL_NOT_IN_CATALOG;
    public static final String MODEL_NOT_LISTED = ErrorCodes.MODEL_NOT_LISTED;
    public static final String DEPENDENCY_UNAVAILABLE = ErrorCodes.DEPENDENCY_UNAVAILABLE;
    public static final String MODEL_NOT_ENABLED = ErrorCodes.MODEL_NOT_ENABLED;

    private ManageReason() {}
}
