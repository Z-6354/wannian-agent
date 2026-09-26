package com.wannian.server.app.persona;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code {data-dir}/persona/} 下人物相关目录的唯一路径约定。
 *
 * <pre>
 * persona/
 *   sources/                 上传 TXT
 *   import/checkpoint/       导入续跑
 *   import/debug/            模型原文排障
 *   review/staging/{id}/     审核合成稿
 *   review/backups/          审核合并前 prompts 备份
 *   overlay-backups/         首次 overlay 前原 SOUL/VOICE/IDENTITY
 * </pre>
 */
public final class PersonaDataPaths {
    public static final String ROOT = "persona";

    private PersonaDataPaths() {}

    public static Path root(Path dataDir) {
        return dataDir.resolve(ROOT);
    }

    public static Path sources(Path dataDir) {
        return root(dataDir).resolve("sources");
    }

    public static Path importCheckpoint(Path dataDir) {
        return root(dataDir).resolve("import").resolve("checkpoint");
    }

    public static Path importDebug(Path dataDir) {
        return root(dataDir).resolve("import").resolve("debug");
    }

    public static Path reviewStaging(Path dataDir) {
        return root(dataDir).resolve("review").resolve("staging");
    }

    public static Path reviewBackups(Path dataDir) {
        return root(dataDir).resolve("review").resolve("backups");
    }

    public static Path overlayBackups(Path dataDir) {
        return root(dataDir).resolve("overlay-backups");
    }

    /**
     * 将早期扁平 {@code persona-*} 目录迁入 {@link #ROOT}。幂等：目标已存在则跳过该项。
     */
    public static void migrateLegacyLayout(Path dataDir) throws IOException {
        if (dataDir == null) {
            return;
        }
        Map<String, Path> moves = new LinkedHashMap<>();
        moves.put("persona-sources", sources(dataDir));
        moves.put("persona-import-checkpoint", importCheckpoint(dataDir));
        moves.put("persona-import-debug", importDebug(dataDir));
        moves.put("persona-review", reviewStaging(dataDir));
        moves.put("persona-review-backups", reviewBackups(dataDir));
        moves.put("persona-overlay-backups", overlayBackups(dataDir));
        for (Map.Entry<String, Path> e : moves.entrySet()) {
            Path legacy = dataDir.resolve(e.getKey());
            Path target = e.getValue();
            if (!Files.exists(legacy)) {
                continue;
            }
            if (Files.exists(target)) {
                if (isEmptyDir(legacy)) {
                    Files.deleteIfExists(legacy);
                }
                continue;
            }
            Files.createDirectories(target.getParent());
            try {
                Files.move(legacy, target);
            } catch (IOException moveFailed) {
                copyTree(legacy, target);
                deleteTree(legacy);
            }
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walk(source).forEach(path -> {
            try {
                Path rel = source.relativize(path);
                Path dest = target.resolve(rel.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }
}
