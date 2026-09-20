package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 管理页骨架可被取回，且不依赖厂商网络。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagePageTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void manageRootReturnsShell() {
        ResponseEntity<String> response = restTemplate.getForEntity("/manage/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("id=\"manage-nav\"");
        assertThat(response.getBody()).contains("/ui/tokens.css");
        assertThat(response.getBody()).contains("/ui/themes/paper.css");
        assertThat(response.getBody()).contains("/ui/components.css");
        assertThat(response.getBody()).contains("/ui/layouts/app-shell.css");
        assertThat(response.getBody()).contains("/ui/layouts/console.css");
        assertThat(response.getBody()).doesNotContain("/manage/manage.css");
    }

    @Test
    void sharedUiModuleServesThemeComponentsAndSeparateLayouts() {
        ResponseEntity<String> tokens = restTemplate.getForEntity("/ui/tokens.css", String.class);
        ResponseEntity<String> theme = restTemplate.getForEntity("/ui/themes/paper.css", String.class);
        ResponseEntity<String> hermes = restTemplate.getForEntity("/ui/themes/hermes.css", String.class);
        ResponseEntity<String> components = restTemplate.getForEntity("/ui/components.css", String.class);
        ResponseEntity<String> appShell = restTemplate.getForEntity("/ui/layouts/app-shell.css", String.class);
        ResponseEntity<String> console = restTemplate.getForEntity("/ui/layouts/console.css", String.class);

        assertThat(tokens.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokens.getBody()).contains("--radius:", "--space-4:");
        assertThat(tokens.getBody()).doesNotContain("--accent:", "--sidebar-width");
        assertThat(theme.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(theme.getBody()).contains("--bg: #ebe3d6", "--accent: #342d28", "--ink: #342d28");
        assertThat(hermes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hermes.getBody()).contains("--bg: #041c1c", "--accent: #ffe6cb");
        assertThat(tokens.getBody()).doesNotContain("--sidebar-width");
        assertThat(components.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(components.getBody()).contains(".field", ".banner", "button");
        assertThat(components.getBody()).doesNotContain(".sidebar", ".auth-bar", ".card-grid");
        assertThat(appShell.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(appShell.getBody()).contains(".shell", ".sidebar", ".workspace", ".page-head");
        assertThat(appShell.getBody()).doesNotContain(".auth-bar", ".card-grid");
        assertThat(console.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(console.getBody()).contains(".auth-bar", ".card-grid");
        assertThat(console.getBody()).doesNotContain(".sidebar");
    }

    @Test
    void navScriptIsServed() {
        ResponseEntity<String> compatibility = restTemplate.getForEntity("/manage/nav.js", String.class);
        ResponseEntity<String> response = restTemplate.getForEntity("/shell/navigation.js", String.class);

        assertThat(compatibility.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(compatibility.getBody()).contains("MANAGE_NAV", "APP_NAV", "/shell/navigation.js?v=20260920p");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("APP_NAV");
        assertThat(response.getBody()).contains("系统");
        assertThat(response.getBody()).contains("label: \"供应商\"");
        assertThat(response.getBody()).contains("label: \"模型\"");
        assertThat(response.getBody()).contains("href: \"/chat/\"");
    }

    @Test
    void sharedStylesStayThemeDrivenAndBusinessAgnostic() {
        ResponseEntity<String> components = restTemplate.getForEntity("/ui/components.css", String.class);
        ResponseEntity<String> appShell = restTemplate.getForEntity("/ui/layouts/app-shell.css", String.class);
        ResponseEntity<String> chat = restTemplate.getForEntity("/ui/layouts/chat.css", String.class);
        ResponseEntity<String> console = restTemplate.getForEntity("/ui/layouts/console.css", String.class);

        assertThat(components.getBody())
                .contains("var(--surface-input)", "var(--surface-banner)", "var(--ring)")
                .doesNotContain("#041c1c", "#062424", "rgb(6 36 36");
        assertThat(chat.getBody())
                .contains("var(--surface-card)", "[data-role=\"user\"]", "flex: 0 0 auto")
                .doesNotContain("#041c1c", "#062424", "linear-gradient");
        assertThat(console.getBody()).contains("var(--surface-card)").doesNotContain("#041c1c", "#062424");
        assertThat(appShell.getBody())
                .contains(".nav-icon", ".mobile-head", ".shell.nav-open .sidebar", "[data-tone=\"warn\"]")
                .doesNotContain("data-nav=", "vendors", "models", "system");
        assertThat(restTemplate.getForEntity("/manage/page-feedback.js", String.class).getBody())
                .contains("export function clearStatus", "dataset.tone = \"warn\"");
    }

    @Test
    void interactionModulesKeepAccessibilitySemantics() {
        ResponseEntity<String> vendors = restTemplate.getForEntity("/manage/vendors-page.js", String.class);
        ResponseEntity<String> models = restTemplate.getForEntity("/manage/models-page.js", String.class);

        assertThat(vendors.getBody())
                .contains("aria-describedby", "dialog-close", "event.key === \"Tab\"")
                .contains("role\", \"alert", "aria-live\", \"assertive");
        assertThat(models.getBody())
                .contains("createElement(\"thead\")", "createElement(\"tbody\")")
                .contains("[\"选择\", \"供应商\"", "\"当前使用\", \"操作\"");
    }

    @Test
    void shellAndModelPagesUseNarrowModuleInterfaces() {
        ResponseEntity<String> shell = restTemplate.getForEntity("/manage/app.js", String.class);
        ResponseEntity<String> vendors = restTemplate.getForEntity("/manage/vendors-page.js", String.class);
        ResponseEntity<String> models = restTemplate.getForEntity("/manage/models-page.js", String.class);

        assertThat(shell.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shell.getBody())
                .contains("mountVendorsPage", "mountModelsPage", "const pageView")
                .doesNotContain("models-panel.js", "/api/manage/model/");

        assertThat(vendors.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(vendors.getBody())
                .contains("export async function mountVendorsPage(view)", "saveVendor", "deleteVendor")
                .doesNotContain("enableModel", "listModels");

        assertThat(models.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(models.getBody())
                .contains("export async function mountModelsPage(view)", "enableModel", "listModels")
                .doesNotContain("saveVendor", "deleteVendor");
    }
}
