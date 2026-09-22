package com.wannian.server.kernel.tool;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** 本机已点亮的主机能力标签集合。 */
public final class HostCapabilitySet {

    private final Set<String> capabilities;

    public HostCapabilitySet(Set<String> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String c : capabilities) {
            if (c == null || c.isBlank()) {
                throw new IllegalArgumentException("capability 不得空白");
            }
            copy.add(c.trim());
        }
        this.capabilities = Collections.unmodifiableSet(copy);
    }

    public static HostCapabilitySet of(String... caps) {
        return new HostCapabilitySet(Set.of(caps));
    }

    public static HostCapabilitySet empty() {
        return new HostCapabilitySet(Set.of());
    }

    public boolean contains(String capability) {
        return capabilities.contains(capability);
    }

    public boolean containsAll(Set<String> required) {
        return capabilities.containsAll(required);
    }

    public Set<String> asSet() {
        return capabilities;
    }
}
