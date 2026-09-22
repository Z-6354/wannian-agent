package com.wannian.server.kernel.tool;

/** 主机能力标签（登记 / 阶段 2 求交用）。 */
public final class HostCapabilities {

    public static final String OS_WINDOWS = "os.windows";
    public static final String OS_LINUX = "os.linux";
    public static final String NET_HTTP = "net.http";

    /** PowerShell 5.x 族（含 5.1、5.2；识别不出时默认点亮此标签）。 */
    public static final String SHELL_PS_FAMILY5 = "shell.ps.family5";

    /** PowerShell 7.x 族（含 7.1、7.2）。 */
    public static final String SHELL_PS_FAMILY7 = "shell.ps.family7";

    private HostCapabilities() {}
}
