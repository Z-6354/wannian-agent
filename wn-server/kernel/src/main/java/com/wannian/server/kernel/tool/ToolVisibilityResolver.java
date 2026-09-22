package com.wannian.server.kernel.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 生成可见工具集：Catalog ∩ 画像成员 ∩ HostCapability（行为：阶段 2）。
 *
 * <p>不感知具体角色名特例；只查绑定表。
 */
public final class ToolVisibilityResolver {

    private final ToolCatalog catalog;
    private final ToolBindingTable bindings;

    public ToolVisibilityResolver(ToolCatalog catalog, ToolBindingTable bindings) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    public ToolVisibility resolve(RoleId roleId, FacetId facetId, HostCapabilitySet host) {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(facetId, "facetId");
        Objects.requireNonNull(host, "host");
        var binding =
                bindings
                        .find(roleId, facetId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "未绑定的角色/模式: "
                                                        + roleId.value()
                                                        + "/"
                                                        + facetId.value()));
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (String toolName : binding.toolNames()) {
            var entry = catalog.findByName(toolName);
            if (entry.isEmpty()) {
                continue;
            }
            ToolCatalog.CatalogEntry catalogEntry = entry.get();
            if (!host.containsAll(catalogEntry.requiredCapabilities())) {
                continue;
            }
            descriptors.add(
                    new ToolDescriptor(
                            catalogEntry.toolName(),
                            catalogEntry.description(),
                            catalogEntry.parameterSchemaJson()));
        }
        return new ToolVisibility(binding.profileId(), roleId, facetId, descriptors);
    }
}
