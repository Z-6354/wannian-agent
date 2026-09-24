package com.wannian.server.app.tool;

import com.wannian.server.app.manage.ToolSettings;
import com.wannian.server.kernel.tool.DefaultToolRuntime;
import com.wannian.server.kernel.tool.ToolBindingTable;
import com.wannian.server.kernel.tool.ToolCatalog;
import com.wannian.server.kernel.tool.ToolRuntime;
import com.wannian.server.kernel.tool.ToolVisibilityResolver;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryRecallTouch;
import com.wannian.server.kernel.memory.MemorySearchLimits;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.tool.builtin.SearchMemoryToolAdapter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配封闭工具目录与 ToolRuntime；启用名单与面相绑定由 {@link ToolSettings}（wannian.json）驱动。
 */
@Configuration
public class ToolRuntimeConfig {

    @Bean
    SearchMemoryToolAdapter searchMemoryToolAdapter(
            MemoryStore memoryStore,
            MemoryRecallTouch recallTouch,
            MemorySearchLimits memorySearchLimits) {
        return new SearchMemoryToolAdapter(
                CompanionIdentity.YANHUO,
                memoryStore,
                Clock.systemUTC(),
                recallTouch,
                memorySearchLimits);
    }

    @Bean
    ToolCatalog toolCatalog() {
        return new ToolCatalog();
    }

    @Bean
    ToolBindingTable toolBindingTable() {
        return new ToolBindingTable();
    }

    @Bean
    ToolVisibilityResolver toolVisibilityResolver(
            ToolCatalog toolCatalog, ToolBindingTable toolBindingTable, ToolSettings toolSettings) {
        // 依赖 toolSettings：构造时已从 wannian.json 填充 catalog/bindings
        toolSettings.snapshot();
        return new ToolVisibilityResolver(toolCatalog, toolBindingTable);
    }

    @Bean
    ToolRuntime toolRuntime(ToolCatalog toolCatalog, ToolSettings toolSettings) {
        toolSettings.snapshot();
        return new DefaultToolRuntime(toolCatalog);
    }
}
