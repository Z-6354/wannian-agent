package com.wannian.server.app.chat;

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

/** 初步对话页可被取回，且页面脚本不直接写 HTTP 路径。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatPageTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void chatRootReturnsShell() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("id=\"composer\"");
        assertThat(response.getBody()).contains("id=\"transcript\"");
        assertThat(response.getBody()).contains("id=\"view-chat\"");
        assertThat(response.getBody()).contains("id=\"view-console\"");
        assertThat(response.getBody()).contains("class=\"workspace-stack\"");
        assertThat(response.getBody()).contains("/shell/app.js");
        assertThat(response.getBody()).contains("/ui/tokens.css");
        assertThat(response.getBody()).contains("/ui/themes/paper.css");
        assertThat(response.getBody()).contains("/ui/components.css");
        assertThat(response.getBody()).contains("/ui/layouts/app-shell.css");
        assertThat(response.getBody()).contains("/ui/layouts/chat.css");
        assertThat(response.getBody()).contains("/ui/layouts/console.css");
        assertThat(response.getBody()).contains("class=\"shell chat-shell\"");
        assertThat(response.getBody()).contains("class=\"sidebar chat-sidebar\"");
        assertThat(response.getBody()).contains("id=\"chat-conversation-sidebar\"");
        assertThat(response.getBody()).contains("data-mobile-nav-close");
        assertThat(response.getBody()).contains("id=\"app-nav\"");
        assertThat(response.getBody()).contains("id=\"mobile-nav-toggle\"");
        assertThat(response.getBody()).contains("id=\"mobile-nav-backdrop\"");
        assertThat(response.getBody()).contains("id=\"panel\"");
        assertThat(response.getBody()).doesNotContain("/manage/manage.css");
        assertThat(response.getBody()).doesNotContain("/chat/chat.css");
        assertThat(response.getBody()).doesNotContain("/api/conversations");
        assertThat(response.getBody()).doesNotContain("id=\"chat-conv-toggle\"");
    }

    @Test
    void legacyChatPathStillServesShell() {
        ResponseEntity<String> response = restTemplate.getForEntity("/chat/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("id=\"composer\"");
        assertThat(response.getBody()).contains("location.replace");
    }

    @Test
    void chatUsesSharedBusinessNavigationModule() {
        ResponseEntity<String> navigation = restTemplate.getForEntity("/shell/navigation.js", String.class);

        assertThat(navigation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(navigation.getBody())
                .contains("export const APP_NAV", "renderAppNavigation", "installMobileNavigation")
                .contains(
                        "href: \"/#chat\"",
                        "href: \"/#vendors\"",
                        "href: \"/#models\"",
                        "href: \"/#tools\"")
                .contains("label: \"对话\"", "label: \"供应商\"", "label: \"模型\"");
    }

    @Test
    void apiModuleOwnsConversationPaths() {
        ResponseEntity<String> response = restTemplate.getForEntity("/chat/api.js", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("/api/conversations");
        assertThat(body).contains("export function createConversation");
        assertThat(body).contains("export function sendTurn");
        assertThat(body).doesNotContain("document.");
        assertThat(body).doesNotContain("textContent");
        assertThat(body).doesNotContain("innerHTML");
    }

    @Test
    void pageModuleDoesNotFetch() {
        ResponseEntity<String> response = restTemplate.getForEntity("/chat/app.js", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("from \"/chat/api.js");
        assertThat(body).contains("export function startChatApp");
        assertThat(body).contains("createConversation");
        assertThat(body).contains("sendTurn");
        assertThat(body).contains("draft.disabled");
        assertThat(body).doesNotContain("window.fetch(", "globalThis.fetch(");
        assertThat(body).doesNotContain("/api/conversations");
        assertThat(body).doesNotContain("innerHTML");

        ResponseEntity<String> shell = restTemplate.getForEntity("/shell/app.js", String.class);
        assertThat(shell.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shell.getBody()).contains("startChatApp", "createConsoleController", "hashchange");

        ResponseEntity<String> renderer = restTemplate.getForEntity("/chat/render.js", String.class);
        assertThat(renderer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renderer.getBody()).contains("dataset.role = \"user\"");
        assertThat(renderer.getBody()).contains("dataset.role = \"assistant\"");
        assertThat(renderer.getBody()).contains("transcriptEl.scrollTop");
    }
}
