package com.wannian.server.kernel.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 0.2.1-A：日志样例不含 key / SQL / 堆栈。 */
class ErrorLogFieldsTest {

    @Test
    void formatsStableFieldsWithoutStack() {
        String line =
                new ErrorLogFields(
                                ErrorCodes.DEPENDENCY_UNAVAILABLE,
                                "model.decide",
                                "turn-1",
                                42L,
                                "供应商暂不可用")
                        .toLogMessage();
        assertThat(line)
                .isEqualTo(
                        "code=DEPENDENCY_UNAVAILABLE op=model.decide id=turn-1 ms=42 reason=供应商暂不可用");
        assertThat(line).doesNotContain("\n").doesNotContain("at ").doesNotContain("Exception");
    }

    @Test
    void redactsSecretsAndSqlShapes() {
        assertThat(
                        new ErrorLogFields(
                                        ErrorCodes.DEPENDENCY_UNAVAILABLE,
                                        "vendor.list",
                                        null,
                                        null,
                                        "Authorization: Bearer sk-secret-value")
                                .toLogMessage())
                .contains("reason=[redacted]")
                .doesNotContain("sk-secret");

        assertThat(
                        new ErrorLogFields(
                                        ErrorCodes.PERSISTENCE_FAILED,
                                        "turn.save",
                                        "t1",
                                        null,
                                        "INSERT INTO turn VALUES (...)")
                                .toLogMessage())
                .contains("reason=[sql-redacted]")
                .doesNotContain("INSERT INTO");
    }

    @Test
    void redactIsReusableForUserChannels() {
        assertThat(ErrorLogFields.redact("Bearer sk-abc")).isEqualTo("[redacted]");
        assertThat(ErrorLogFields.redact("SELECT * FROM t")).isEqualTo("[sql-redacted]");
        assertThat(ErrorLogFields.redact("a".repeat(300))).endsWith("…").hasSize(241);
    }
}
