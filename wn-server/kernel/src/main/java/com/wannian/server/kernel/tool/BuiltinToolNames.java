package com.wannian.server.kernel.tool;

/** 本批工具对外稳定 id（模型侧 snake_case）。 */
public final class BuiltinToolNames {

    public static final String CURRENT_TIME = "current_time";
    public static final String CALCULATE = "calculate";
    public static final String HTTP_READ = "http_read";

    /** 列出本回合可见工具（名称 / 说明 / 参数 schema）。 */
    public static final String LIST_TOOLS = "list_tools";

    /** PowerShell 5.x 族解析工具（须配 shell.ps.family5）。 */
    public static final String POWERSHELL_RESOLVE_5 = "powershell_resolve_5";

    /** PowerShell 7.x 族解析工具（须配 shell.ps.family7）。 */
    public static final String POWERSHELL_RESOLVE_7 = "powershell_resolve_7";

    /** 旧单一 id；配置迁移用，不在池内登记。 */
    public static final String POWERSHELL_RESOLVE_LEGACY = "powershell_resolve";

    private BuiltinToolNames() {}
}
