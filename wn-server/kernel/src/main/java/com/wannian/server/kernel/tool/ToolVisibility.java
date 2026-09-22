package com.wannian.server.kernel.tool;

import java.util.List;
import java.util.Objects;

/** 生成可见集的结果：画像名 + 描述列表。 */
public record ToolVisibility(
        String profileId, RoleId roleId, FacetId facetId, List<ToolDescriptor> descriptors) {
    public ToolVisibility {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(facetId, "facetId");
        Objects.requireNonNull(descriptors, "descriptors");
        descriptors = List.copyOf(descriptors);
    }
}
