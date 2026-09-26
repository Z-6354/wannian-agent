package com.wannian.server.app.model;



import com.fasterxml.jackson.databind.ObjectMapper;

import com.wannian.server.app.manage.ManageReason;

import com.wannian.server.app.manage.ModelVendorStore;

import com.wannian.server.app.manage.ModelVendorStore.EnabledBinding;

import com.wannian.server.app.manage.StubModelCatalog;

import com.wannian.server.app.manage.VendorCredentialAccess;

import com.wannian.server.kernel.model.ModelPort;

import java.net.http.HttpClient;

import java.time.Duration;

import java.util.Locale;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Component;



/**

 * 按当前启用选择装配 {@link ModelPort}。默认 live 走 {@link ModelPortFactory}；fake 不访问网络。

 */

@Component

public class EnabledModelPortResolver {



    private final ModelVendorStore store;

    private final ModelPortFactory portFactory;

    private final String mode;



    public EnabledModelPortResolver(

            ModelVendorStore store,

            ObjectMapper objectMapper,

            VendorCredentialAccess credentials,

            @Value("${wannian.model.mode:live}") String mode,

            @Value("${wannian.model.sampling.temperature:0.85}") Double temperature,

            @Value("${wannian.model.sampling.top-p:0.95}") Double topP,

            @Value("${wannian.model.sampling.presence-penalty:0.35}") Double presencePenalty) {

        this.store = Objects.requireNonNull(store, "store");

        Objects.requireNonNull(objectMapper, "objectMapper");

        Objects.requireNonNull(credentials, "credentials");

        this.mode = mode == null ? "live" : mode.trim().toLowerCase(Locale.ROOT);

        ModelSamplingSettings sampling =

                new ModelSamplingSettings(temperature, topP, presencePenalty);

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        this.portFactory = new ModelPortFactory(httpClient, objectMapper, credentials, sampling);

    }



    public ResolveResult resolve() {

        EnabledBinding binding = store.findEnabledBinding();

        if (binding == null) {

            return new ResolveResult.Rejected(ManageReason.MODEL_NOT_ENABLED, "尚未启用模型");

        }

        if (!StubModelCatalog.isSupportedProtocol(binding.vendor().protocol())) {

            return new ResolveResult.Rejected(

                    ManageReason.PROTOCOL_UNSUPPORTED, "当前只支持 openai-compatible 或 openai-responses");

        }

        ModelPort port;

        if ("live".equals(mode)) {

            port =

                    new TimeoutModelPort(

                            portFactory.createLive(binding.vendor(), binding.modelId()),

                            // 覆盖 agent 硬截止（约 100s）与人物导入（120s）；短 deadline 仍由 context 收紧。
                            Duration.ofSeconds(120));

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


