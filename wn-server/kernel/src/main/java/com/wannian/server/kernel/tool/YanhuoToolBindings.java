package com.wannian.server.kernel.tool;

import java.util.List;
import java.util.Objects;

/** 本批烟火三模式绑定（世界树等后挂，只加表项）。默认数据，真源看用户 JSON。 */
public final class YanhuoToolBindings {

    private YanhuoToolBindings() {}

    /**
     * 默认种子：三面共同包含锁死工具 + calculate；工作/科研再加读网与 PowerShell 解析。
     *
     * <p>硬编码出厂，非配置文件。
     */
    public static ToolBindingTable create() {
        List<String> core =
                List.of(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.CURRENT_TIME,
                        BuiltinToolNames.REMEMBER_FACT,
                        BuiltinToolNames.UPDATE_RELATIONSHIP,
                        BuiltinToolNames.SEARCH_MEMORY,
                        BuiltinToolNames.CALCULATE);
        List<String> withHost =
                List.of(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.CURRENT_TIME,
                        BuiltinToolNames.REMEMBER_FACT,
                        BuiltinToolNames.UPDATE_RELATIONSHIP,
                        BuiltinToolNames.SEARCH_MEMORY,
                        BuiltinToolNames.CALCULATE,
                        BuiltinToolNames.HTTP_READ,
                        BuiltinToolNames.POWERSHELL_RESOLVE_5,
                        BuiltinToolNames.POWERSHELL_RESOLVE_7);
        return fromFacetLists(core, withHost, withHost);
    }

    public static ToolBindingTable fromFacetLists(
            List<String> chat, List<String> work, List<String> research) {
        ToolBindingTable table = new ToolBindingTable();
        applyTo(table, chat, work, research);
        return table;
    }

    /** 清空并写入烟火三模式（供热重载）。 */
    public static void applyTo(
            ToolBindingTable table, List<String> chat, List<String> work, List<String> research) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(research, "research");
        table.clear();
        table.put(RoleId.YANHUO, FacetId.CHAT, "yanhuo.chat.default", chat);
        table.put(RoleId.YANHUO, FacetId.WORK, "yanhuo.work.default", work);
        table.put(RoleId.YANHUO, FacetId.RESEARCH, "yanhuo.research.default", research);
    }
}
