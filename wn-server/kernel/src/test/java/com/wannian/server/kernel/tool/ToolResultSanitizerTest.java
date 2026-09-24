package com.wannian.server.kernel.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolResultSanitizerTest {

    @Test
    void redactsSecretFieldsWithoutChangingJsonStructure() {
        String sanitized =
                ToolResultSanitizer.sanitize(
                        "{\"authorization\":\"Bearer private-token\","
                                + "\"nested\":[{\"api_key\":\"secret\"}],"
                                + "\"text\":\"authorization: Bearer hidden\"}");

        assertThat(sanitized)
                .isEqualTo(
                        "{\"authorization\":\"***\",\"nested\":[{\"api_key\":\"***\"}],"
                                + "\"text\":\"authorization=***\"}");
    }

    @Test
    void oversizedNestedObservationIsValidBoundedJsonAndMarkedTruncated() {
        String payload = "x\\\"汉字🙂".repeat(10_000);
        String source = "{\"matches\":[{\"claim\":\"" + payload + "\"}]}";

        String sanitized = ToolResultSanitizer.sanitize(source);

        assertThat(sanitized.length()).isLessThanOrEqualTo(ToolResultSanitizer.MAX_OBSERVATION_CHARS);
        assertThat(sanitized).contains("\"truncated\":true").startsWith("{").endsWith("}");
        assertThat(ToolResultSanitizer.sanitize(sanitized)).isEqualTo(sanitized);
    }
}
