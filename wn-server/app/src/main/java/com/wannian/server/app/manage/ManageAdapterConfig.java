package com.wannian.server.app.manage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ManageAdapterConfig {

    @Bean
    EnvAccess envAccess() {
        return EnvAccess.system();
    }
}
