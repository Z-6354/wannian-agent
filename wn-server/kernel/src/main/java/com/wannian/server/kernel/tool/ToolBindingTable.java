package com.wannian.server.kernel.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 角色 × 面相 → 工具画像成员表（多对多配置；加角色只加表项）。
 *
 * <p>热重载时对 {@link #clear}/{@link #put} 同步，避免半成品可见集。
 */
public final class ToolBindingTable {

    private final Map<String, ProfileBinding> byKey = new LinkedHashMap<>();

    public synchronized void put(RoleId roleId, FacetId facetId, String profileId, List<String> toolNames) {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(facetId, "facetId");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(toolNames, "toolNames");
        byKey.put(key(roleId, facetId), new ProfileBinding(profileId, List.copyOf(toolNames)));
    }

    public synchronized Optional<ProfileBinding> find(RoleId roleId, FacetId facetId) {
        return Optional.ofNullable(byKey.get(key(roleId, facetId)));
    }

    public synchronized List<FacetId> facetsOf(RoleId roleId) {
        List<FacetId> facets = new ArrayList<>();
        String prefix = roleId.value() + "/";
        for (String k : byKey.keySet()) {
            if (k.startsWith(prefix)) {
                facets.add(new FacetId(k.substring(prefix.length())));
            }
        }
        return List.copyOf(facets);
    }

    /** 清空全部绑定（管理页热重建前调用）。 */
    public synchronized void clear() {
        byKey.clear();
    }

    private static String key(RoleId roleId, FacetId facetId) {
        return roleId.value() + "/" + facetId.value();
    }

    public record ProfileBinding(String profileId, List<String> toolNames) {}
}
