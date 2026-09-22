package com.wannian.server.app.tool;

import com.wannian.server.kernel.tool.HostCapabilities;
import com.wannian.server.kernel.tool.HostCapabilitySet;
import com.wannian.server.kernel.tool.PowerShellFamilyProbe;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 探测本机主机能力标签（启动一次；可同时点亮多个 PS family）。 */
public final class LocalHostCapabilityDetector {

    private LocalHostCapabilityDetector() {}

    public static HostCapabilitySet detect() {
        LinkedHashSet<String> caps = new LinkedHashSet<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            caps.add(HostCapabilities.OS_WINDOWS);
            caps.addAll(PowerShellFamilyProbe.detectAvailableFamilyCapabilities());
        } else if (os.contains("linux") || os.contains("nux")) {
            caps.add(HostCapabilities.OS_LINUX);
        }
        caps.add(HostCapabilities.NET_HTTP);
        return new HostCapabilitySet(Set.copyOf(caps));
    }
}
