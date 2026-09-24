package com.wannian.server.kernel.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.InMemoryTurnMemoryPending;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** 0.2.2 阶段 1 出口：行为 1–6（识别/校验/幂等/执行/收口）。 */
class ToolRuntimePhase1Test {

    private ToolRuntime runtime;

    @BeforeEach
    void setUp() {
        ToolCatalog catalog = new ToolCatalog();
        BuiltinToolRegistrar.registerAll(catalog);
        runtime = new DefaultToolRuntime(catalog);
    }

    @Test
    void currentTimeSucceeds() {
        ToolExecutionOutcome outcome =
                runtime.execute(
                        new ToolInvocation("c1", BuiltinToolNames.CURRENT_TIME, "{}"),
                        ctx("op-time-1"));
        assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Succeeded.class);
        String json = ((ToolExecutionOutcome.Succeeded) outcome).observationJson();
        assertThat(json).contains("iso8601").contains("timezone");
    }

    @Test
    void calculateSucceeds() {
        ToolExecutionOutcome outcome =
                runtime.execute(
                        new ToolInvocation(
                                "c1", BuiltinToolNames.CALCULATE, "{\"expression\":\"(1+2)*3\"}"),
                        ctx("op-calc-1"));
        assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Succeeded.class);
        assertThat(((ToolExecutionOutcome.Succeeded) outcome).observationJson())
                .contains("\"result\":\"9\"");
    }

    @Test
    void httpReadRejectsPrivateHost() {
        ToolExecutionOutcome outcome =
                runtime.execute(
                        new ToolInvocation(
                                "c1",
                                BuiltinToolNames.HTTP_READ,
                                "{\"url\":\"http://127.0.0.1/\"}"),
                        ctx("op-http-private"));
        assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Rejected.class);
        ToolExecutionOutcome.Rejected rejected = (ToolExecutionOutcome.Rejected) outcome;
        assertThat(rejected.code()).isEqualTo(ErrorCodes.TOOL_INVALID_ARGUMENTS);
    }

    @Test
    void httpReadPublicUrlSucceedsWhenNetworkAvailable() {
        ToolExecutionOutcome outcome =
                runtime.execute(
                        new ToolInvocation(
                                "c1",
                                BuiltinToolNames.HTTP_READ,
                                "{\"url\":\"https://example.com\"}"),
                        ctx("op-http-public"));
        if (outcome instanceof ToolExecutionOutcome.Succeeded succeeded) {
            assertThat(succeeded.observationJson()).contains("status").contains("body");
            return;
        }
        assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Failed.class);
        assertThat(((ToolExecutionOutcome.Failed) outcome).code())
                .isEqualTo(ErrorCodes.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void unknownToolRejected() {
        ToolExecutionOutcome outcome =
                runtime.execute(
                        new ToolInvocation("c1", "no_such_tool", "{}"), ctx("op-unknown"));
        assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Rejected.class);
        assertThat(((ToolExecutionOutcome.Rejected) outcome).code())
                .isEqualTo(ErrorCodes.TOOL_NOT_FOUND);
    }

    @Test
    void badCalculateArgsRejected() {
        ToolExecutionOutcome outcome =
                runtime.execute(
                        new ToolInvocation(
                                "c1", BuiltinToolNames.CALCULATE, "{\"expression\":\"os.system(1)\"}"),
                        ctx("op-bad-calc"));
        assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Rejected.class);
        assertThat(((ToolExecutionOutcome.Rejected) outcome).code())
                .isEqualTo(ErrorCodes.TOOL_INVALID_ARGUMENTS);
    }

    @Test
    void powershellScriptArgsRejected() {
        ToolExecutionOutcome outcome =
                runtime.execute(
                        new ToolInvocation(
                                "c1",
                                BuiltinToolNames.POWERSHELL_RESOLVE_5,
                                "{\"script\":\"Get-Process\"}"),
                        ctx("op-ps-script"));
        assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Rejected.class);
        assertThat(((ToolExecutionOutcome.Rejected) outcome).code())
                .isEqualTo(ErrorCodes.TOOL_INVALID_ARGUMENTS);
    }

    @Test
    void sameOperationIdSameBindingReplaysWithoutSecondEffect() {
        ToolInvocation inv =
                new ToolInvocation("c1", BuiltinToolNames.CALCULATE, "{\"expression\":\"2+2\"}");
        ToolExecutionContext context = ctx("op-replay");
        ToolExecutionOutcome first = runtime.execute(inv, context);
        ToolExecutionOutcome second = runtime.execute(inv, context);
        assertThat(first).isInstanceOf(ToolExecutionOutcome.Succeeded.class);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void sameOperationIdDifferentBindingConflicts() {
        ToolExecutionContext context = ctx("op-conflict");
        ToolExecutionOutcome first =
                runtime.execute(
                        new ToolInvocation(
                                "c1", BuiltinToolNames.CALCULATE, "{\"expression\":\"1+1\"}"),
                        context);
        assertThat(first).isInstanceOf(ToolExecutionOutcome.Succeeded.class);
        ToolExecutionOutcome second =
                runtime.execute(
                        new ToolInvocation(
                                "c1", BuiltinToolNames.CALCULATE, "{\"expression\":\"2+2\"}"),
                        context);
        assertThat(second).isInstanceOf(ToolExecutionOutcome.Rejected.class);
        assertThat(((ToolExecutionOutcome.Rejected) second).code())
                .isEqualTo(ErrorCodes.TOOL_OPERATION_CONFLICT);
    }

    @Test
    void runtimeSanitizerKeepsOversizedAdapterObservationValidJson() {
        String payload = ToolJson.escape("引号\"和\nemoji🙂").repeat(10_000);
        String observation =
                "{\"authorization\":\"Bearer private-token\",\"matches\":[{\"claim\":\""
                        + payload
                        + "\"}]}";
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(
                new ToolRegistration(
                        "sanitizer_test",
                        "test observation sanitizing",
                        new ToolParameterSchema("{\"type\":\"object\",\"properties\":{}}"),
                        java.util.Set.of(),
                        request -> new ToolAdapterResult.Succeeded(observation)));
        ToolRuntime sanitizerRuntime = new DefaultToolRuntime(catalog);

        ToolExecutionOutcome outcome =
                sanitizerRuntime.execute(
                        new ToolInvocation("c1", "sanitizer_test", "{}"), ctx("op-sanitize"));

        assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Succeeded.class);
        String cleaned = ((ToolExecutionOutcome.Succeeded) outcome).observationJson();
        assertThat(cleaned)
                .contains("\"authorization\":\"***\"")
                .contains("\"truncated\":true")
                .doesNotContain("private-token");
        assertThat(cleaned.length()).isLessThanOrEqualTo(ToolResultSanitizer.MAX_OBSERVATION_CHARS);
        assertThat(ToolResultSanitizer.sanitize(cleaned)).isEqualTo(cleaned);
    }

    @Test
    void duplicateRegisterRejected() {
        ToolCatalog catalog = new ToolCatalog();
        BuiltinToolRegistrar.registerAll(catalog);
        RegisterToolResult again =
                catalog.register(
                        new ToolRegistration(
                                BuiltinToolNames.CURRENT_TIME,
                                "dup",
                                new ToolParameterSchema("{\"type\":\"object\"}"),
                                java.util.Set.of(),
                                new com.wannian.server.kernel.tool.builtin.CurrentTimeToolAdapter()));
        assertThat(again).isInstanceOf(RegisterToolResult.Rejected.class);
        assertThat(((RegisterToolResult.Rejected) again).code())
                .isEqualTo(ErrorCodes.TOOL_ALREADY_REGISTERED);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powershellResolveOnWindowsPreferPwsh7OrFallback() {
        var family = PowerShellFamilyProbe.detectPreferredFamilyCapability();
        if (family.isEmpty()) {
            // 本机无引擎：工具应不可用
            ToolExecutionOutcome outcome =
                    runtime.execute(
                            new ToolInvocation("c1", BuiltinToolNames.POWERSHELL_RESOLVE_5, "{}"),
                            ctx("op-ps-none"));
            assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Failed.class);
            return;
        }
        String toolName =
                HostCapabilities.SHELL_PS_FAMILY7.equals(family.get())
                        ? BuiltinToolNames.POWERSHELL_RESOLVE_7
                        : BuiltinToolNames.POWERSHELL_RESOLVE_5;
        ToolExecutionOutcome outcome =
                runtime.execute(new ToolInvocation("c1", toolName, "{}"), ctx("op-ps-win"));
        if (outcome instanceof ToolExecutionOutcome.Succeeded succeeded) {
            String json = succeeded.observationJson();
            assertThat(json).contains("engine").contains("executable").contains("family");
        } else {
            assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Failed.class);
            assertThat(((ToolExecutionOutcome.Failed) outcome).code())
                    .isEqualTo(ErrorCodes.TOOL_UNAVAILABLE);
        }
    }

    @Test
    void listToolsReturnsVisibleDescriptors() {
        List<ToolDescriptor> visible =
                List.of(
                        new ToolDescriptor(
                                BuiltinToolNames.CURRENT_TIME,
                                "time",
                                "{\"type\":\"object\",\"properties\":{}}"),
                        new ToolDescriptor(
                                BuiltinToolNames.LIST_TOOLS,
                                "list",
                                "{\"type\":\"object\",\"properties\":{}}"));
        ToolExecutionOutcome outcome =
                runtime.execute(
                        new ToolInvocation("c1", BuiltinToolNames.LIST_TOOLS, "{}"),
                        ToolExecutionContext.basic(
                                "op-list-1", "turn-1", "attempt-1", visible, null));
        assertThat(outcome).isInstanceOf(ToolExecutionOutcome.Succeeded.class);
        String json = ((ToolExecutionOutcome.Succeeded) outcome).observationJson();
        assertThat(json)
                .contains("\"count\":\"2\"")
                .contains(BuiltinToolNames.CURRENT_TIME)
                .contains(BuiltinToolNames.LIST_TOOLS)
                .contains("parameters");
    }

    private static ToolExecutionContext ctx(String operationId) {
        return ToolExecutionContext.basic(operationId, "turn-1", "attempt-1", List.of(), null);
    }
}
