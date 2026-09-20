package com.wannian.server.app.manage;

/**
 * K03-W 管理接口的稳定 code。本批只允许在这里登记新字符串，Controller 不得再手写。
 * K03-A 再与回合接口已有的 code 收成一处。
 */
public final class ManageReason {

    public static final String MANAGE_UNCONFIGURED = "MANAGE_UNCONFIGURED";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String ILLEGAL_ARGUMENT = "ILLEGAL_ARGUMENT";
    public static final String REVISION_CONFLICT = "REVISION_CONFLICT";
    public static final String PROTOCOL_UNSUPPORTED = "PROTOCOL_UNSUPPORTED";
    public static final String VENDOR_NOT_FOUND = "VENDOR_NOT_FOUND";
    public static final String MODEL_NOT_IN_CATALOG = "MODEL_NOT_IN_CATALOG";
    public static final String MODEL_NOT_LISTED = "MODEL_NOT_LISTED";
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";
    public static final String MODEL_NOT_ENABLED = "MODEL_NOT_ENABLED";

    private ManageReason() {}
}
