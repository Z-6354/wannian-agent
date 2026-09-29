package com.wannian.server.app;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * SpringBootTest 公共动态属性：隔离 data-dir，并降低后台调度与清库的 Windows SQLite 锁冲突。
 */
public final class WannianTestProps {

    private WannianTestProps() {}

    public static void registerIsolatedDataDir(DynamicPropertyRegistry registry, Path tempDataDir) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        // 拉长拍间隔，减少与 @BeforeEach DELETE / Flyway / 关库 的 SQLITE_BUSY 风暴
        registry.add("wannian.task.dispatch.tick-ms", () -> "60000");
        registry.add("wannian.task.schedule.promote-tick-ms", () -> "60000");
        registry.add("wannian.task.idle-delivery.tick-ms", () -> "60000");
    }
}
