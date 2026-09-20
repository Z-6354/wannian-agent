package com.wannian.server.app.platform;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进程存活（liveness）探针，供未来 Guardian / 编排侧探活。
 *
 * <p><b>语义（见 updates/25）</b>：
 * <ul>
 *   <li>{@code live}：事件循环仍能响应 HTTP —— 本接口只证明这一点</li>
 *   <li>{@code ready}：库可用、migration 完成 —— <b>K08</b>，本类不做</li>
 *   <li>{@code version}/{@code drain}：同样留到 K08</li>
 * </ul>
 *
 * <p><b>K01 约束</b>：不访问数据库、不检查模型密钥、不触发业务初始化。
 * 返回体保持固定字段，便于脚本与门禁测试断言。
 */
@RestController
public class LiveController {

    /**
     * {@code GET /internal/live}
     *
     * @return 固定 JSON：{@code status=live}，{@code service=wn-server}
     */
    @GetMapping(path = "/internal/live", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> live() {
        // 使用不可变 Map，避免后续误改共享状态；字段名勿随意变更。
        return Map.of(
                "status", "live",
                "service", "wn-server");
    }
}
