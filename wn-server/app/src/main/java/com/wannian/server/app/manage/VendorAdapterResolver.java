package com.wannian.server.app.manage;

import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按 {@code wannian.model.mode} 选择真实 OpenAI 兼容适配器或 Fake。
 * 默认 {@code live}。测试要离线时显式设 {@code fake}。
 */
@Component
public class VendorAdapterResolver {

    private final FakeVendorAdapter fake;
    private final OpenAiCompatibleVendorAdapter live;
    private final String mode;

    public VendorAdapterResolver(
            FakeVendorAdapter fake,
            OpenAiCompatibleVendorAdapter live,
            @Value("${wannian.model.mode:live}") String mode) {
        this.fake = Objects.requireNonNull(fake, "fake");
        this.live = Objects.requireNonNull(live, "live");
        this.mode = mode == null ? "live" : mode.trim().toLowerCase(Locale.ROOT);
    }

    public VendorAdapter resolve(String protocol) {
        if (!StubModelCatalog.PROTOCOL.equals(protocol)) {
            return null;
        }
        return "live".equals(mode) ? live : fake;
    }

    public String mode() {
        return mode;
    }
}
