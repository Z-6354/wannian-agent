package com.wannian.server.app.manage;

import com.wannian.server.app.persistence.SqliteConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本机明文密钥文件：{@code {data-dir}/secrets/{vendorId}.key}。
 *
 * <p>刻意不加密：本机部署、读写优先于安全边界。文件权限依赖 OS 默认。
 */
@Component
public final class VendorSecretStore {

    private final Path secretsDir;

    public VendorSecretStore(@Value("${wannian.data-dir:data}") String dataDir) {
        Path root = SqliteConfig.resolveDataDir(dataDir);
        this.secretsDir = root.resolve("secrets");
    }

    public boolean hasSecret(String vendorId) {
        return Files.isRegularFile(pathFor(vendorId));
    }

    public String read(String vendorId) {
        Path path = pathFor(vendorId);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (IOException ex) {
            throw new IllegalStateException("读取供应商密钥失败", ex);
        }
    }

    public void write(String vendorId, String apiKey) {
        Objects.requireNonNull(vendorId, "vendorId");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey");
        }
        try {
            Files.createDirectories(secretsDir);
            Path path = pathFor(vendorId);
            Files.writeString(
                    path,
                    apiKey.trim(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException ex) {
            throw new IllegalStateException("写入供应商密钥失败", ex);
        }
    }

    public void delete(String vendorId) {
        try {
            Files.deleteIfExists(pathFor(vendorId));
        } catch (IOException ex) {
            throw new IllegalStateException("删除供应商密钥失败", ex);
        }
    }

    private Path pathFor(String vendorId) {
        if (vendorId == null || !VendorRules.validId(vendorId)) {
            throw new IllegalArgumentException("vendorId");
        }
        return secretsDir.resolve(vendorId + ".key");
    }
}
