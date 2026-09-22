package com.wannian.server.kernel.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * PowerShell 引擎探测与主版本归桶（启动期 HostCapability；工具 execute 复用）。
 *
 * <p>无全局可变状态；可重复调用。装配层应只在启动调用一次并把结果冻进 {@link HostCapabilitySet}。
 */
public final class PowerShellFamilyProbe {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);

    private PowerShellFamilyProbe() {}

    /**
     * 按主版本归桶。7 → family7；5 或其它/不可解析 → family5（调用方在「完全无引擎」时不应调用）。
     */
    public static String bucketCapability(int major) {
        if (major == 7) {
            return HostCapabilities.SHELL_PS_FAMILY7;
        }
        return HostCapabilities.SHELL_PS_FAMILY5;
    }

    /**
     * 探测本机：优先 family7，否则有任一可用引擎则 family5，皆无则 empty。
     *
     * <p>非 Windows 直接 empty（不抛错）。
     */
    public static Optional<String> detectPreferredFamilyCapability() {
        Set<String> available = detectAvailableFamilyCapabilities();
        if (available.contains(HostCapabilities.SHELL_PS_FAMILY7)) {
            return Optional.of(HostCapabilities.SHELL_PS_FAMILY7);
        }
        if (available.contains(HostCapabilities.SHELL_PS_FAMILY5)) {
            return Optional.of(HostCapabilities.SHELL_PS_FAMILY5);
        }
        return Optional.empty();
    }

    /**
     * 本机实际可提供的 PS family 标签（可同时含 5 与 7）。非 Windows 或无引擎 → 空集。
     */
    public static Set<String> detectAvailableFamilyCapabilities() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return Set.of();
        }
        LinkedHashSet<String> families = new LinkedHashSet<>();
        for (ProbedEngine engine : probePwshCandidates()) {
            if (engine.major() == 7) {
                families.add(HostCapabilities.SHELL_PS_FAMILY7);
            } else {
                // 含 major 5、其它主版本、版本不可解析（major 0）
                families.add(HostCapabilities.SHELL_PS_FAMILY5);
            }
        }
        probeClassicPowerShell().ifPresent(e -> families.add(HostCapabilities.SHELL_PS_FAMILY5));
        return Set.copyOf(families);
    }

    /** 查找指定族的一台引擎（供 Adapter 报告）；无则 empty。 */
    public static Optional<ProbedEngine> findEngineForFamily(int familyMajor) {
        if (familyMajor == 7) {
            return probePwshCandidates().stream().filter(e -> e.major() == 7).findFirst();
        }
        if (familyMajor == 5) {
            Optional<ProbedEngine> classic = probeClassicPowerShell();
            if (classic.isPresent()) {
                return classic;
            }
            return probePwshCandidates().stream()
                    .filter(e -> e.major() != 7)
                    .findFirst();
        }
        return Optional.empty();
    }

    private static List<ProbedEngine> probePwshCandidates() {
        List<ProbedEngine> found = new ArrayList<>();
        List<Path> candidates = new ArrayList<>();
        whichOnPath("pwsh").ifPresent(candidates::add);
        Path programFiles =
                Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"));
        try {
            Path psRoot = programFiles.resolve("PowerShell");
            if (Files.isDirectory(psRoot)) {
                try (var stream = Files.list(psRoot)) {
                    stream.filter(Files::isDirectory)
                            .map(p -> p.resolve("pwsh.exe"))
                            .filter(Files::isRegularFile)
                            .forEach(candidates::add);
                }
            }
        } catch (Exception ignored) {
            // 忽略目录枚举失败
        }
        for (Path path : candidates) {
            runVersion(path).ifPresent(found::add);
        }
        return found;
    }

    private static Optional<ProbedEngine> probeClassicPowerShell() {
        Path systemRoot = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"));
        Path classic =
                systemRoot.resolve("System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        if (!Files.isRegularFile(classic)) {
            return Optional.empty();
        }
        return runVersion(classic);
    }

    private static Optional<Path> whichOnPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return Optional.empty();
        }
        for (String dir : pathEnv.split(";")) {
            if (dir.isBlank()) {
                continue;
            }
            Path exe = Path.of(dir.trim(), command + ".exe");
            if (Files.isRegularFile(exe)) {
                return Optional.of(exe.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private static Optional<ProbedEngine> runVersion(Path executable) {
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(
                            executable.toString(),
                            "-NoProfile",
                            "-NonInteractive",
                            "-Command",
                            "$PSVersionTable.PSVersion.ToString()");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String version;
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                version = reader.readLine();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            if (version == null || version.isBlank()) {
                // 有壳但版本串空 → 视为可归桶为 family5 的引擎
                return Optional.of(
                        new ProbedEngine(
                                executable.toAbsolutePath().normalize().toString(),
                                "",
                                0));
            }
            version = version.trim();
            int major = parseMajor(version);
            return Optional.of(
                    new ProbedEngine(
                            executable.toAbsolutePath().normalize().toString(), version, major));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    static int parseMajor(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        int dot = version.indexOf('.');
        String major = dot < 0 ? version : version.substring(0, dot);
        try {
            String digits = major.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                return 0;
            }
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 探测到的引擎快照。 */
    public record ProbedEngine(String executable, String version, int major) {
        public String engineLabel() {
            if (major == 7) {
                return "pwsh7";
            }
            if (major == 5) {
                return "powershell51";
            }
            return "powershell_other";
        }
    }
}
