package com.wannian.server.kernel.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolUsePolicyTest {

    private static final HostCapabilitySet BOTH_PS =
            HostCapabilitySet.of(
                    HostCapabilities.OS_WINDOWS,
                    HostCapabilities.NET_HTTP,
                    HostCapabilities.SHELL_PS_FAMILY5,
                    HostCapabilities.SHELL_PS_FAMILY7);

    private static final HostCapabilitySet ONLY_5 =
            HostCapabilitySet.of(
                    HostCapabilities.OS_WINDOWS,
                    HostCapabilities.NET_HTTP,
                    HostCapabilities.SHELL_PS_FAMILY5);

    private static final HostCapabilitySet LINUX =
            HostCapabilitySet.of(HostCapabilities.OS_LINUX, HostCapabilities.NET_HTTP);

    @Test
    void status_unavailableWhenHostLacksCapability() {
        assertThat(
                        ToolUsePolicy.status(
                                BuiltinToolNames.POWERSHELL_RESOLVE_7,
                                ONLY_5,
                                Set.of(BuiltinToolNames.POWERSHELL_RESOLVE_7)))
                .isEqualTo(ToolUsePolicy.STATUS_UNAVAILABLE);
    }

    @Test
    void status_inUseAndNotUsing() {
        assertThat(
                        ToolUsePolicy.status(
                                BuiltinToolNames.CURRENT_TIME,
                                LINUX,
                                Set.of(BuiltinToolNames.CURRENT_TIME)))
                .isEqualTo(ToolUsePolicy.STATUS_IN_USE);
        assertThat(
                        ToolUsePolicy.status(
                                BuiltinToolNames.CALCULATE, LINUX, Set.of(BuiltinToolNames.CURRENT_TIME)))
                .isEqualTo(ToolUsePolicy.STATUS_NOT_USING);
    }

    @Test
    void clamp_dropsUnavailableAndMutexPrefersSeven() {
        List<String> clamped =
                ToolUsePolicy.clampEnabled(
                        List.of(
                                BuiltinToolNames.CURRENT_TIME,
                                BuiltinToolNames.POWERSHELL_RESOLVE_5,
                                BuiltinToolNames.POWERSHELL_RESOLVE_7),
                        BOTH_PS);
        assertThat(clamped)
                .contains(BuiltinToolNames.CURRENT_TIME, BuiltinToolNames.POWERSHELL_RESOLVE_7)
                .doesNotContain(BuiltinToolNames.POWERSHELL_RESOLVE_5);
    }

    @Test
    void clamp_keepsFiveWhenUserOnlySelectsFive() {
        List<String> clamped =
                ToolUsePolicy.clampEnabled(
                        List.of(BuiltinToolNames.POWERSHELL_RESOLVE_5), BOTH_PS);
        assertThat(clamped).containsExactly(
                BuiltinToolNames.POWERSHELL_RESOLVE_5,
                BuiltinToolNames.LIST_TOOLS,
                BuiltinToolNames.CURRENT_TIME,
                BuiltinToolNames.REMEMBER_FACT,
                BuiltinToolNames.UPDATE_RELATIONSHIP,
                BuiltinToolNames.SEARCH_MEMORY);
    }

    @Test
    void clamp_dropsSevenWhenOnlyFiveAvailable() {
        List<String> clamped =
                ToolUsePolicy.clampEnabled(
                        List.of(
                                BuiltinToolNames.POWERSHELL_RESOLVE_5,
                                BuiltinToolNames.POWERSHELL_RESOLVE_7),
                        ONLY_5);
        assertThat(clamped).containsExactly(
                BuiltinToolNames.POWERSHELL_RESOLVE_5,
                BuiltinToolNames.LIST_TOOLS,
                BuiltinToolNames.CURRENT_TIME,
                BuiltinToolNames.REMEMBER_FACT,
                BuiltinToolNames.UPDATE_RELATIONSHIP,
                BuiltinToolNames.SEARCH_MEMORY);
    }

    @Test
    void defaultEnabled_onLinuxHasNoPowershell() {
        List<String> enabled = ToolUsePolicy.defaultEnabled(LINUX);
        assertThat(enabled)
                .contains(BuiltinToolNames.CURRENT_TIME, BuiltinToolNames.HTTP_READ)
                .doesNotContain(
                        BuiltinToolNames.POWERSHELL_RESOLVE_5, BuiltinToolNames.POWERSHELL_RESOLVE_7);
    }

    @Test
    void defaultEnabled_bothPsPrefersSeven() {
        List<String> enabled = ToolUsePolicy.defaultEnabled(BOTH_PS);
        assertThat(enabled)
                .contains(BuiltinToolNames.POWERSHELL_RESOLVE_7)
                .doesNotContain(BuiltinToolNames.POWERSHELL_RESOLVE_5);
    }
}
