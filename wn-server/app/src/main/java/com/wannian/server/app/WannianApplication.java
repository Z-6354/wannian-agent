package com.wannian.server.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * wn-server 进程入口（Spring Boot 宿主）。
 *
 * @see com.wannian.server.app.platform.LiveController 探活接口
 */
@SpringBootApplication
public class WannianApplication {

    /**
     * 标准 Java 入口；由 {@code spring-boot-maven-plugin} 打包为可执行 jar 后调用。
     *
     * @param args 命令行参数（如 {@code --server.port=8080}），转交给 Spring Environment
     */
    public static void main(String[] args) {
        SpringApplication.run(WannianApplication.class, args);
    }
}
