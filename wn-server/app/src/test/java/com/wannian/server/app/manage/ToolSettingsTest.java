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
import com.wannian.server.kernel.tool.ToolVisibilityResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ToolSettings 校验与热重建。 */
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
        assertThat(catalog.findByName(BuiltinToolNames.CURRENT_TIME)).isPresent();
        assertThat(Files.readString(tempDir.resolve("wannian.json"))).contains("\"tools\"");
    }

    @Test
    void facetCannotReferenceDisabledTool() {
        assertThatThrownBy(
                        () ->
                                ToolSettings.Snapshot.validate(
                                        List.of(BuiltinToolNames.CURRENT_TIME),
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
                                        List.of("no_such_tool"),
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

        settings.update(
                List.of(BuiltinToolNames.CURRENT_TIME),
                new ToolSettings.FacetLists(
                        List.of(BuiltinToolNames.CURRENT_TIME),
                        List.of(BuiltinToolNames.CURRENT_TIME),
                        List.of(BuiltinToolNames.CURRENT_TIME)));

        assertThat(catalog.findByName(BuiltinToolNames.CALCULATE)).isEmpty();
        ToolVisibilityResolver resolver = new ToolVisibilityResolver(catalog, table);
        assertThat(
                        resolver
                                .resolve(RoleId.YANHUO, FacetId.CHAT, HostCapabilitySet.empty())
                                .descriptors())
                .extracting(d -> d.name())
                .containsExactly(BuiltinToolNames.CURRENT_TIME);
    }

    @Test
    void migrateLegacyPowershellResolveExpandsThenMutex() {
        assertThat(
                        ToolSettings.migrateLegacyPowershellNames(
                                List.of("current_time", "powershell_resolve", "http_read")))
                .contains(
                        "current_time",
                        "http_read",
                        BuiltinToolNames.POWERSHELL_RESOLVE_5,
                        BuiltinToolNames.POWERSHELL_RESOLVE_7);
        var snap =
                ToolSettings.Snapshot.validate(
                        ToolSettings.migrateLegacyPowershellNames(
                                List.of("current_time", "powershell_resolve")),
                        new ToolSettings.FacetLists(List.of("current_time"), List.of(), List.of()),
                        RICH_HOST,
                        true);
        assertThat(snap.enabled())
                .contains(BuiltinToolNames.POWERSHELL_RESOLVE_7)
                .doesNotContain(BuiltinToolNames.POWERSHELL_RESOLVE_5);
    }

    @Test
    void registerEnabledRejectsUnknown() {
        ToolCatalog catalog = new ToolCatalog();
        assertThatThrownBy(() -> BuiltinToolRegistrar.registerEnabled(catalog, java.util.Set.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
