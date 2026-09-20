package com.wannian.server.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.app.manage.EnvAccess;
import com.wannian.server.app.manage.ManageReason;
import com.wannian.server.app.manage.ModelVendorStore;
import com.wannian.server.app.manage.ModelVendorStore.EnabledBinding;
import com.wannian.server.app.manage.StubModelCatalog;
import com.wannian.server.app.manage.VendorRecord;
import com.wannian.server.kernel.model.ModelPort;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按当前启用选择装配 {@link ModelPort}。默认 live 走 OpenAI 兼容适配器；fake 不访问网络。
 */
@Component
public class EnabledModelPortResolver {

    private final ModelVendorStore store;
    private final ObjectMapper objectMapper;
    private final EnvAccess envAccess;
    private final String mode;
    private final HttpClient httpClient;

    public EnabledModelPortResolver(
            ModelVendorStore store,
            ObjectMapper objectMapper,
            EnvAccess envAccess,
            @Value("${wannian.model.mode:live}") String mode) {
        this.store = Objects.requireNonNull(store, "store");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.envAccess = Objects.requireNonNull(envAccess, "envAccess");
        this.mode = mode == null ? "live" : mode.trim().toLowerCase(Locale.ROOT);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public ResolveResult resolve() {
        EnabledBinding binding = store.findEnabledBinding();
        if (binding == null) {
            return new ResolveResult.Rejected(ManageReason.MODEL_NOT_ENABLED, "尚未启用模型");
        }
        if (!StubModelCatalog.PROTOCOL.equals(binding.vendor().protocol())) {
            return new ResolveResult.Rejected(ManageReason.PROTOCOL_UNSUPPORTED, "当前只支持 openai-compatible");
        }
        ModelPort port;
        if ("live".equals(mode)) {
            port =
                    new TimeoutModelPort(
                            new OpenAiCompatibleModelAdapter(
                                    binding.vendor(),
                                    binding.modelId(),
                                    httpClient,
                                    objectMapper,
                                    envAccess),
                            Duration.ofSeconds(30));
        } else {
            port = new FakeModelAdapter(binding.modelId());
        }
        return new ResolveResult.Resolved(port, binding.vendor().id(), binding.modelId());
    }

    public sealed interface ResolveResult {
        record Resolved(ModelPort port, String vendorId, String modelId) implements ResolveResult {}

        record Rejected(String code, String detail) implements ResolveResult {}
    }
}
