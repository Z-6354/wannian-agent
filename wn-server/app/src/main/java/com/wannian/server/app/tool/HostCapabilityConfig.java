package com.wannian.server.app.tool;

import com.wannian.server.kernel.tool.HostCapabilitySet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 启动冻结本机能力 Bean，供 ToolSettings / ContextAssembler / 管理 API 共用。 */
@Configuration
public class HostCapabilityConfig {

    @Bean
    HostCapabilitySet hostCapabilitySet() {
        return LocalHostCapabilityDetector.detect();
    }
}
