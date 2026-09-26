package com.wannian.server.app.persona.importer;

import java.util.concurrent.Executor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.util.unit.DataSize;
import jakarta.servlet.MultipartConfigElement;

@Configuration
class PersonaImportConfiguration {
    @Bean(name = "personaImportExecutor", destroyMethod = "close")
    Executor personaImportExecutor() { return new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"persona-import");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy()); }

    @Bean
    MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(8));
        factory.setMaxRequestSize(DataSize.ofMegabytes(9));
        return factory.createMultipartConfig();
    }
}
