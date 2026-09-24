package com.wannian.server.kernel.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 0.2.2 阶段 2 + K02-R：可见集 A/B/C/D + PS family 求交。 */
class ToolVisibilityPhase2Test {

    private ToolVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        ToolCatalog catalog = new ToolCatalog();
        BuiltinToolRegistrar.registerAll(catalog);
        resolver = new ToolVisibilityResolver(catalog, YanhuoToolBindings.create());
    }

    @Test
    void scenarioA_windowsWorkFamily7SeesOnlyResolve7AndHttp() {
        ToolVisibility v =
                resolver.resolve(
                        RoleId.YANHUO,
                        FacetId.WORK,
                        HostCapabilitySet.of(
                                HostCapabilities.OS_WINDOWS,
                                HostCapabilities.NET_HTTP,
                                HostCapabilities.SHELL_PS_FAMILY7));
        assertThat(names(v))
                .containsExactly(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.CURRENT_TIME,
                        BuiltinToolNames.REMEMBER_FACT,
                        BuiltinToolNames.UPDATE_RELATIONSHIP,
                        BuiltinToolNames.SEARCH_MEMORY,
                        BuiltinToolNames.CALCULATE,
                        BuiltinToolNames.HTTP_READ,
                        BuiltinToolNames.POWERSHELL_RESOLVE_7);
        assertThat(v.profileId()).isEqualTo("yanhuo.work.default");
    }

    @Test
    void scenarioA2_windowsWorkFamily5SeesOnlyResolve5() {
        ToolVisibility v =
                resolver.resolve(
                        RoleId.YANHUO,
                        FacetId.WORK,
                        HostCapabilitySet.of(
                                HostCapabilities.OS_WINDOWS,
                                HostCapabilities.NET_HTTP,
                                HostCapabilities.SHELL_PS_FAMILY5));
        assertThat(names(v))
                .contains(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.POWERSHELL_RESOLVE_5,
                        BuiltinToolNames.HTTP_READ)
                .doesNotContain(BuiltinToolNames.POWERSHELL_RESOLVE_7);
    }

    @Test
    void scenarioB_linuxWorkNoHttpHidesHttpAndPowershell() {
        ToolVisibility v =
                resolver.resolve(
                        RoleId.YANHUO, FacetId.WORK, HostCapabilitySet.of(HostCapabilities.OS_LINUX));
        assertThat(names(v))
                .containsExactly(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.CURRENT_TIME,
                        BuiltinToolNames.REMEMBER_FACT,
                        BuiltinToolNames.UPDATE_RELATIONSHIP,
                        BuiltinToolNames.SEARCH_MEMORY,
                        BuiltinToolNames.CALCULATE);
    }

    @Test
    void scenarioC_chatNeverSeesHttpOrPowershell() {
        ToolVisibility v =
                resolver.resolve(
                        RoleId.YANHUO,
                        FacetId.CHAT,
                        HostCapabilitySet.of(
                                HostCapabilities.OS_WINDOWS,
                                HostCapabilities.NET_HTTP,
                                HostCapabilities.SHELL_PS_FAMILY7));
        assertThat(names(v))
                .containsExactly(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.CURRENT_TIME,
                        BuiltinToolNames.REMEMBER_FACT,
                        BuiltinToolNames.UPDATE_RELATIONSHIP,
                        BuiltinToolNames.SEARCH_MEMORY,
                        BuiltinToolNames.CALCULATE);
    }

    @Test
    void scenarioD_windowsWorkNoHttpFamily7SeesPowershellNotHttp() {
        ToolVisibility v =
                resolver.resolve(
                        RoleId.YANHUO,
                        FacetId.WORK,
                        HostCapabilitySet.of(
                                HostCapabilities.OS_WINDOWS, HostCapabilities.SHELL_PS_FAMILY7));
        assertThat(names(v))
                .containsExactly(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.CURRENT_TIME,
                        BuiltinToolNames.REMEMBER_FACT,
                        BuiltinToolNames.UPDATE_RELATIONSHIP,
                        BuiltinToolNames.SEARCH_MEMORY,
                        BuiltinToolNames.CALCULATE,
                        BuiltinToolNames.POWERSHELL_RESOLVE_7);
    }

    @Test
    void windowsWithoutPsFamilySeesNoPowershellTools() {
        ToolVisibility v =
                resolver.resolve(
                        RoleId.YANHUO,
                        FacetId.WORK,
                        HostCapabilitySet.of(HostCapabilities.OS_WINDOWS, HostCapabilities.NET_HTTP));
        assertThat(names(v))
                .containsExactly(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.CURRENT_TIME,
                        BuiltinToolNames.REMEMBER_FACT,
                        BuiltinToolNames.UPDATE_RELATIONSHIP,
                        BuiltinToolNames.SEARCH_MEMORY,
                        BuiltinToolNames.CALCULATE,
                        BuiltinToolNames.HTTP_READ)
                .doesNotContain(
                        BuiltinToolNames.POWERSHELL_RESOLVE_5, BuiltinToolNames.POWERSHELL_RESOLVE_7);
    }

    @Test
    void frameworkSeam_tempRoleOnlyNeedsBindingTable() {
        ToolCatalog catalog = new ToolCatalog();
        BuiltinToolRegistrar.registerAll(catalog);
        ToolBindingTable table = YanhuoToolBindings.create();
        RoleId temp = new RoleId("temp_probe");
        FacetId tick = new FacetId("tick");
        table.put(temp, tick, "temp.tick.default", List.of(BuiltinToolNames.CURRENT_TIME));
        ToolVisibilityResolver local = new ToolVisibilityResolver(catalog, table);
        ToolVisibility v = local.resolve(temp, tick, HostCapabilitySet.empty());
        assertThat(names(v)).containsExactly(BuiltinToolNames.CURRENT_TIME);
        assertThat(v.profileId()).isEqualTo("temp.tick.default");
    }

    private static List<String> names(ToolVisibility v) {
        return v.descriptors().stream().map(ToolDescriptor::name).collect(Collectors.toList());
    }
}
