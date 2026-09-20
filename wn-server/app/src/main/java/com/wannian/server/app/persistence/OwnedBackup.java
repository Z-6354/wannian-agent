package com.wannian.server.app.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 自动快照的命名、manifest 和清理资格。
 *
 * <p>只有受控目录名、可解析的自有 manifest、真实数据库文件和 {@code complete} 状态同时满足，
 * 才算可淘汰的备份。手工目录、残缺目录和指向备份根以外的链接一律保留。
 */
final class OwnedBackup {

    static final String DIRECTORY_PREFIX = "wn-snap-";
    static final String DATABASE_FILE = "wannian.db";
    static final String MANIFEST_FILE = "wannian-backup.json";
    static final int FORMAT_VERSION = 1;
    static final String SOURCE = "wannian-auto-snapshot";
    static final String STATUS_COMPLETE = "complete";

    private static final Pattern DIRECTORY_NAME =
            Pattern.compile("wn-snap-\\d{17}(-\\d+)?");
    private static final int DIGEST_BUFFER = 8192;

    private OwnedBackup() {}

    static boolean hasControlledName(Path directory) {
        Path name = directory.getFileName();
        return name != null && DIRECTORY_NAME.matcher(name.toString()).matches();
    }

    static String manifest(
            ObjectMapper objectMapper,
            Instant createdAt,
            String schemaVersion,
            String applicationBuildId,
            String databaseDigest)
            throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("formatVersion", FORMAT_VERSION);
        node.put("source", SOURCE);
        node.put("status", STATUS_COMPLETE);
        node.put("createdAt", createdAt.toString());
        node.put("schemaVersion", schemaVersion);
        node.put("applicationBuildId", applicationBuildId);
        node.put("databaseDigest", databaseDigest);
        return objectMapper.writeValueAsString(node);
    }

    /**
     * 清理候选。解析失败、链接绕出备份根、或 manifest 不完整时返回 false，调用方必须保留该目录。
     */
    static boolean isPrunable(Path backupsRoot, Path directory, ObjectMapper objectMapper) {
        if (!hasControlledName(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (!contained(backupsRoot, directory)) {
                return false;
            }
            Path database = directory.resolve(DATABASE_FILE);
            Path manifest = directory.resolve(MANIFEST_FILE);
            if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (Files.isSymbolicLink(database) || Files.isSymbolicLink(manifest)) {
                return false;
            }
            JsonNode node = objectMapper.readTree(Files.readString(manifest));
            return node.path("formatVersion").asInt() == FORMAT_VERSION
                    && SOURCE.equals(node.path("source").asText())
                    && STATUS_COMPLETE.equals(node.path("status").asText())
                    && !node.path("schemaVersion").asText().isBlank()
                    && !node.path("databaseDigest").asText().isBlank()
                    && !node.path("applicationBuildId").asText().isBlank()
                    && !node.path("createdAt").asText().isBlank();
        } catch (IOException ex) {
            return false;
        }
    }

    static boolean contained(Path backupsRoot, Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            return false;
        }
        Path root = backupsRoot.toRealPath();
        Path real = directory.toRealPath();
        if (!real.startsWith(root) || real.equals(root)) {
            return false;
        }
        Path walk = root;
        for (Path part : root.relativize(real)) {
            walk = walk.resolve(part);
            if (Files.isSymbolicLink(walk)) {
                return false;
            }
        }
        return true;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[DIGEST_BUFFER];
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
