package com.wannian.server.app.manage;

/** 读取环境变量。测试可注入固定表，生产用 {@link System#getenv(String)}。 */
@FunctionalInterface
public interface EnvAccess {

    String get(String name);

    static EnvAccess system() {
        return System::getenv;
    }
}
