package com.wannian.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * K01 探活验收：真实启动 Boot 容器后请求 {@code /internal/live}。
 *
 * <p>类名使用 {@code *Test} 而非 {@code *IT}，以便绑定在 Surefire 的 {@code package}
 * 阶段执行（{@code *IT} 默认归 Failsafe，需 {@code verify} 才会跑）。
 *
 * <p>使用随机端口，避免本机 8080 占用导致失败。
 *
 * <p>K02 起需要 SQLite：测试写入临时 {@code wannian.data-dir}，避免污染真实数据目录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveEndpointTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    /** Spring Boot Test 注入的 HTTP 客户端，基址已指向随机端口。 */
    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 断言：HTTP 200，且响应体含固定字段 {@code status=live}、{@code service=wn-server}。
     *
     * <p>字段变更属于对外协议变更，须同步改 {@link com.wannian.server.app.platform.LiveController}
     * 与本测试。
     */
    @Test
    void liveReturnsFixedStructure() {
        ResponseEntity<String> response = restTemplate.getForEntity("/internal/live", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"live\"");
        assertThat(response.getBody()).contains("\"service\":\"wn-server\"");
    }
}
