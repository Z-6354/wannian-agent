package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wannian.server.kernel.tool.BuiltinToolNames;
import com.wannian.server.kernel.tool.BuiltinToolRegistrar;
import com.wannian.server.kernel.tool.FacetId;
import com.wannian.server.kernel.tool.HostCapabilities;
import com.wannian.server.kernel.tool.HostCapabilitySet;
import com.wannian.server.kernel.tool.RoleId;
import com.wannian.server.kernel.tool.ToolBindingTable;
import com.wannian.server.kernel.tool.ToolCatalog;
import com.wannian.server.kernel.tool.ToolUsePolicy;
import com.wannian.server.kernel.tool.ToolVisibilityResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ToolSettings byName 三态与热重建。 */
class ToolSettingsTest {

    private static final HostCapabilitySet RICH_HOST =
            HostCapabilitySet.of(
                    HostCapabilities.OS_WINDOWS,
                    HostCapabilities.NET_HTTP,
                    HostCapabilities.SHELL_PS_FAMILY5,
                    HostCapabilities.SHELL_PS_FAMILY7);

    @TempDir
    Path tempDir;

    @Test
    void defaultsSeedAndApplyCatalog() throws Exception {
        ToolCatalog catalog = new ToolCatalog();
        ToolBindingTable table = new ToolBindingTable();
        ToolSettings settings = new ToolSettings(tempDir.toString(), catalog, table, RICH_HOST);

        assertThat(settings.snapshot().enabled()).contains(BuiltinToolNames.CALCULATE);
        assertThat(settings.snapshot().enabled())
                .contains(BuiltinToolNames.POWERSHELL_RESOLVE_7)
                .doesNotContain(BuiltinToolNames.POWERSHELL_RESOLVE_5);
        assertThat(settings.snapshot().byName().get(BuiltinToolNames.LIST_TOOLS)).isEqualTo("locked");
        assertThat(settings.snapshot().byName().get(BuiltinToolNames.REMEMBER_FACT)).isEqualTo("locked");
        assertThat(catalog.findByName(BuiltinToolNames.CURRENT_TIME)).isPresent();
        assertThat(Files.readString(tempDir.resolve("wannian.json")))
                .contains("\"byName\"")
                .doesNotContain("\"enabled\"");
    }

    @Test
    void facetCannotReferenceDisabledTool() {
        Map<String, String> byName = new LinkedHashMap<>(ToolUsePolicy.defaultByName());
        byName.put(BuiltinToolNames.CALCULATE, "off");
        assertThatThrownBy(
                        () ->
                                ToolSettings.Snapshot.validate(
                                        byName,
                                        new ToolSettings.FacetLists(
                                                List.of(BuiltinToolNames.CALCULATE),
                                                List.of(),
                                                List.of()),
                                        RICH_HOST,
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    void unknownPoolNameRejected() {
        assertThatThrownBy(
                        () ->
                                ToolSettings.Snapshot.validate(
                                        Map.of("no_such_tool", "on"),
                                        new ToolSettings.FacetLists(List.of(), List.of(), List.of()),
                                        RICH_HOST,
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不在内置池");
    }

    @Test
    void disableCalculateRemovesFromCatalogAndVisibility() throws Exception {
        ToolCatalog catalog = new ToolCatalog();
        ToolBindingTable table = new ToolBindingTable();
        ToolSettings settings = new ToolSettings(tempDir.toString(), catalog, table, RICH_HOST);

        Map<String, String> byName = new LinkedHashMap<>(settings.snapshot().byName());
        byName.put(BuiltinToolNames.CALCULATE, "off");
        byName.put(BuiltinToolNames.HTTP_READ, "off");
        byName.put(BuiltinToolNames.POWERSHELL_RESOLVE_5, "off");
        byName.put(BuiltinToolNames.POWERSHELL_RESOLVE_7, "off");
        List<String> lockedFacet =
                List.of(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.CURRENT_TIME,
                        BuiltinToolNames.REMEMBER_FACT,
                        BuiltinToolNames.UPDATE_RELATIONSHIP);
        settings.update(byName, new ToolSettings.FacetLists(lockedFacet, lockedFacet, lockedFacet));

        assertThat(catalog.findByName(BuiltinToolNames.CALCULATE)).isEmpty();
        ToolVisibilityResolver resolver = new ToolVisibilityResolver(catalog, table);
        assertThat(
                        resolver
                                .resolve(RoleId.YANHUO, FacetId.CHAT, RICH_HOST)
                                .descriptors())
                .extracting(d -> d.name())
                .contains(
                        BuiltinToolNames.LIST_TOOLS,
                        BuiltinToolNames.CURRENT_TIME,
                        BuiltinToolNames.REMEMBER_FACT,
                        BuiltinToolNames.UPDATE_RELATIONSHIP)
                .doesNotContain(BuiltinToolNames.CALCULATE);
    }

    @Test
    void lockedNamesCannotBeTurnedOff() throws Exception {
        ToolCatalog catalog = new ToolCatalog();
        ToolBindingTable table = new ToolBindingTable();
        ToolSettings settings = new ToolSettings(tempDir.toString(), catalog, table, RICH_HOST);

        Map<String, String> byName = new LinkedHashMap<>(settings.snapshot().byName());
        byName.put(BuiltinToolNames.LIST_TOOLS, "off");
        byName.put(BuiltinToolNames.REMEMBER_FACT, "off");
        List<String> facets = settings.snapshot().yanhuo().chat();
        settings.update(
                byName,
                new ToolSettings.FacetLists(facets, facets, facets));

        assertThat(settings.snapshot().byName().get(BuiltinToolNames.LIST_TOOLS)).isEqualTo("locked");
        assertThat(settings.snapshot().enabled()).contains(BuiltinToolNames.REMEMBER_FACT);
    }

    @Test
    void registerEnabledRejectsUnknown() {
        ToolCatalog catalog = new ToolCatalog();
        assertThatThrownBy(() -> BuiltinToolRegistrar.registerEnabled(catalog, java.util.Set.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
