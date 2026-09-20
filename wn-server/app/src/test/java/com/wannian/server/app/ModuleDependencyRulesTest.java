package com.wannian.server.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.dependencyfixture.ApiAppViolation;
import com.wannian.server.api.dependencyfixture.ApiJdbcViolation;
import com.wannian.server.api.dependencyfixture.ApiKernelViolation;
import com.wannian.server.kernel.conversation.SequentialConversationTitlePolicy;
import com.wannian.server.kernel.dependencyfixture.KernelAppViolation;
import com.wannian.server.kernel.dependencyfixture.KernelJdbcViolation;
import com.wannian.server.kernel.dependencyfixture.KernelSdkViolation;
import com.wannian.server.kernel.dependencyfixture.KernelSpringViolation;
import com.wannian.server.kernel.turn.Turn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * K01 模块依赖门禁。
 *
 * <p>两层防护：
 * <ol>
 *   <li><b>ArchUnit</b>：已编译类的包依赖不得反向（api↛kernel/app，kernel↛app/Spring Web）</li>
 *   <li><b>POM 扫描</b>：api/kernel 的 {@code <dependency>} 块不得出现 Spring/SQLite/厂商 SDK 片段，Web 资源模块不得反向依赖 app</li>
 *   <li><b>资源树扫描</b>：共享视觉模块只暴露 {@code /ui/} 视觉资源，app 不拥有前端源文件</li>
 * </ol>
 *
 * <p>故意在 api/kernel 的 pom 中加入 spring-boot 依赖时，本测试应失败。
 * 仅扫描 dependency 块，避免 description 文案中的 “sqlite” 等词误报。
 */
@AnalyzeClasses(
        packages = "com.wannian.server",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleDependencyRulesTest {

    /** api 模块 dependency 中禁止出现的 artifact 片段（小写匹配）。 */
    private static final List<String> FORBIDDEN_IN_API = List.of(
            "spring-boot",
            "spring-web",
            "spring-context",
            "sqlite",
            "flyway",
            "openai",
            "anthropic");

    /** kernel 模块 dependency 中禁止出现的 artifact 片段。 */
    private static final List<String> FORBIDDEN_IN_KERNEL = List.of(
            "spring-boot",
            "spring-web",
            "spring-webmvc",
            "spring-context",
            "sqlite",
            "flyway");
    /**
     * api 不得依赖 Spring、kernel、app、JDBC 或厂商 SDK。
     */
    @ArchTest
    static final ArchRule apiMustNotDependOnSpringOrApp =
            noClasses()
                    .that()
                    .resideInAPackage("com.wannian.server.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "com.wannian.server.kernel..",
                            "com.wannian.server.app..",
                            "java.sql..",
                            "javax.sql..",
                            "org.sqlite..",
                            "org.flywaydb..",
                            "com.openai..",
                            "com.anthropic..");

    /**
     * kernel 不得依赖 app、全部 Spring、JDBC、SQLite、Flyway 或厂商 SDK。
     */
    @ArchTest
    static final ArchRule kernelMustNotDependOnAppOrSpringWeb =
            noClasses()
                    .that()
                    .resideInAPackage("com.wannian.server.kernel..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.wannian.server.app..",
                            "org.springframework..",
                            "java.sql..",
                            "javax.sql..",
                            "org.sqlite..",
                            "org.flywaydb..",
                            "com.openai..",
                            "com.anthropic..");
    /** 读取 api/pom.xml，断言无禁止依赖片段。 */
    @Test
    void apiPomMustNotDeclareForbiddenDependencies() throws Exception {
        String pom = Files.readString(modulePom("api"));
        assertNoForbiddenArtifacts(pom, FORBIDDEN_IN_API);
    }

    /**
     * 读取 kernel/pom.xml：无禁止依赖，且必须声明对 {@code wn-server-api} 的依赖。
     */
    @Test
    void kernelPomMustNotDeclareForbiddenDependencies() throws Exception {
        String pom = Files.readString(modulePom("kernel"));
        assertNoForbiddenArtifacts(pom, FORBIDDEN_IN_KERNEL);
        assertThat(pom).contains("wn-server-api");
        assertAllowedKernelDependencies(pom);
    }

    @Test
    void appOwnsPagesAndDependsOnIndependentStylePackage() throws Exception {
        String appPom = Files.readString(modulePom("app"));
        String uiPom = Files.readString(modulePom("wannian-ui"));

        assertThat(dependencyArtifacts(uiPom))
                .as("wannian-ui must be a leaf shared-visual module")
                .doesNotContain("wn-server-app", "wn-server-api", "wn-server-kernel");
        assertThat(dependencyArtifacts(appPom))
                .as("wn-server-app consumes the independent style package directly")
                .contains("wannian-ui")
                .doesNotContain("wn-web");

        Path projectRoot = projectRoot();
        Path sharedResources = projectRoot.resolve("wannian-ui/src/main/resources/META-INF/resources");
        Path pageResources = projectRoot.resolve("wn-server/app/src/main/resources/META-INF/resources");
        assertThat(Files.isDirectory(sharedResources.resolve("ui"))).isTrue();
        assertThat(Files.isDirectory(pageResources.resolve("manage"))).isTrue();
        assertThat(Files.isDirectory(pageResources.resolve("chat"))).isTrue();
        assertThat(Files.isDirectory(pageResources.resolve("shell"))).isTrue();
        assertSharedVisualResourceTree(sharedResources);
        assertThat(projectRoot.resolve("wn-web"))
                .as("pages live inside wn-server, not a sibling web module")
                .doesNotExist();
        assertThat(frontendFiles(pageResources))
                .as("wn-server-app owns the embedded page source")
                .isNotEmpty();
    }

    @Test
    void reactorKeepsStylePackageOutsideTheServer() throws Exception {
        String serverPom = Files.readString(projectRoot().resolve("wn-server/pom.xml"));
        String uiPom = Files.readString(modulePom("wannian-ui"));
        String appPom = Files.readString(modulePom("app"));

        assertThat(serverPom)
                .as("wn-server reactor")
                .contains("<module>../wannian-ui</module>")
                .doesNotContain("<module>../wn-web</module>");
        String dependencyManagement = tag(serverPom, "dependencyManagement");
        assertThat(dependencyArtifacts(dependencyManagement))
                .as("wn-server dependencyManagement")
                .contains("wannian-ui")
                .doesNotContain("wn-web");

        assertThat(dependencyArtifacts(uiPom)).doesNotContain("wn-server-app");
        assertThat(dependencyArtifacts(appPom)).contains("wannian-ui").doesNotContain("wn-web");
    }

    @Test
    void isolatedViolationsFailAndLegalTypesPass() {
        assertThatThrownBy(() -> kernelMustNotDependOnAppOrSpringWeb.check(classesOf(KernelJdbcViolation.class)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> kernelMustNotDependOnAppOrSpringWeb.check(classesOf(KernelSpringViolation.class)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> kernelMustNotDependOnAppOrSpringWeb.check(classesOf(KernelSdkViolation.class)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> apiMustNotDependOnSpringOrApp.check(classesOf(ApiKernelViolation.class)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> apiMustNotDependOnSpringOrApp.check(classesOf(ApiJdbcViolation.class)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> apiMustNotDependOnSpringOrApp.check(classesOf(ApiAppViolation.class)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> kernelMustNotDependOnAppOrSpringWeb.check(classesOf(KernelAppViolation.class)))
                .isInstanceOf(AssertionError.class);

        kernelMustNotDependOnAppOrSpringWeb.check(
                classesOf(Turn.class, SequentialConversationTitlePolicy.class));
        apiMustNotDependOnSpringOrApp.check(classesOf(TurnId.class));
    }

    private static JavaClasses classesOf(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    /**
     * 按常见工作目录解析模块 pom：在 module 根、父工程、或仓根执行测试时都能找到。
     */
    private static Path modulePom(String module) {
        Path cwd = Path.of("").toAbsolutePath();
        Path direct = cwd.resolve(module).resolve("pom.xml");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        for (Path cursor = cwd; cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(module).resolve("pom.xml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Path fromRepo = cwd.resolve("products/wannian-agent/wn-server")
                .resolve(module)
                .resolve("pom.xml");
        if (Files.isRegularFile(fromRepo)) {
            return fromRepo;
        }
        throw new IllegalStateException("Cannot locate " + module + "/pom.xml from " + cwd);
    }

    private static Path projectRoot() {
        return modulePom("wannian-ui").getParent().getParent();
    }

    /** 匹配 pom 中每一个 {@code <dependency>...</dependency>} 块（忽略大小写与换行）。 */
    private static final Pattern DEPENDENCY_BLOCK =
            Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * 只检查 dependency 正文，不检查 {@code <description>}，避免文档用词误伤。
     */
    private static void assertNoForbiddenArtifacts(String pom, List<String> forbidden) {
        for (String block : dependencyBlocks(pom)) {
            for (String token : forbidden) {
                assertThat(block)
                        .as("dependency block must not contain forbidden fragment '%s'", token)
                        .doesNotContain(token);
            }
        }
    }

    private static List<String> dependencyBlocks(String pom) {
        List<String> dependencyBlocks = new ArrayList<>();
        Matcher matcher = DEPENDENCY_BLOCK.matcher(pom);
        while (matcher.find()) {
            dependencyBlocks.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return dependencyBlocks;
    }

    private static List<String> dependencyArtifacts(String pom) {
        return dependencyBlocks(pom).stream()
                .map(block -> tag(block, "artifactId"))
                .filter(artifact -> !artifact.isBlank())
                .toList();
    }

    private static void assertSharedVisualResourceTree(Path resources) throws Exception {
        List<String> topLevel;
        try (var entries = Files.list(resources)) {
            topLevel = entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertThat(topLevel)
                .as("wannian-ui resource root")
                .containsExactly("ui");

        assertThat(sharedVisualViolations(resources))
                .as("wannian-ui may expose only visual assets below /ui")
                .isEmpty();
    }

    private static List<Path> frontendFiles(Path resources) throws Exception {
        try (var paths = Files.walk(resources)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return lower.endsWith(".html")
                                || lower.endsWith(".htm")
                                || lower.endsWith(".js")
                                || lower.endsWith(".mjs")
                                || lower.endsWith(".cjs")
                                || lower.endsWith(".css");
                    })
                    .toList();
        }
    }

    private static List<Path> sharedVisualViolations(Path resources) throws Exception {
        try (var paths = Files.walk(resources)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String relative = resources.relativize(path).toString().replace('\\', '/');
                        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return !relative.startsWith("ui/")
                                || frontendFilesByName(path)
                                || fileName.contains("api");
                    })
                    .toList();
        }
    }

    private static boolean frontendFilesByName(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".html")
                || lower.endsWith(".htm")
                || lower.endsWith(".js")
                || lower.endsWith(".mjs")
                || lower.endsWith(".cjs");
    }

    private static void assertAllowedKernelDependencies(String pom) {
        Matcher matcher = DEPENDENCY_BLOCK.matcher(pom);
        Set<String> allowedTest = Set.of("junit-jupiter", "assertj-core");
        while (matcher.find()) {
            String block = matcher.group(1);
            String artifact = tag(block, "artifactId");
            String scope = tag(block, "scope");
            if ("test".equals(scope)) {
                assertThat(allowedTest).contains(artifact);
            } else {
                assertThat(artifact).isEqualTo("wn-server-api");
            }
        }
    }

    private static String tag(String block, String name) {
        Matcher matcher = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                .matcher(block);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
