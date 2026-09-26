package com.wannian.server.app.manage;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 供应商密钥解析：优先 {@link VendorSecretStore} 文件（按 Preset {@code secretId}），其次环境变量。
 */
@Component
public final class VendorCredentialAccess {

    private final VendorSecretStore secrets;
    private final EnvAccess env;
    private final VendorPresetSource presets;

    @Autowired
    public VendorCredentialAccess(
            VendorSecretStore secrets, EnvAccess env, VendorPresetSource presets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.env = Objects.requireNonNull(env, "env");
        this.presets = Objects.requireNonNull(presets, "presets");
    }

    /** 测试 / 仅环境变量：不读密钥文件。 */
    public static VendorCredentialAccess envOnly(EnvAccess env) {
        return new VendorCredentialAccess(env);
    }

    private VendorCredentialAccess(EnvAccess env) {
        this.secrets = null;
        this.env = Objects.requireNonNull(env, "env");
        this.presets = null;
    }

    public String getApiKey(String vendorId, String apiKeyEnv) {
        if (secrets != null) {
            String fromFile = secrets.read(secretIdFor(vendorId));
            if (fromFile != null && !fromFile.isBlank()) {
                return fromFile;
            }
        }
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
            return null;
        }
        return env.get(apiKeyEnv);
    }

    /** 文件或环境变量任一可用即为已配置（与运行时取钥一致）。 */
    public boolean hasCredential(String vendorId, String apiKeyEnv) {
        String key = getApiKey(vendorId, apiKeyEnv);
        return key != null && !key.isBlank();
    }

    String secretIdFor(String vendorId) {
        if (presets == null || vendorId == null) {
            return vendorId;
        }
        return presets.find(vendorId).map(VendorPreset::secretId).orElse(vendorId);
    }
}
