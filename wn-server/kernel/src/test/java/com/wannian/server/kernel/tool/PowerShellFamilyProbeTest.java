package com.wannian.server.kernel.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** K02-R：主版本归桶。 */
class PowerShellFamilyProbeTest {

    @Test
    void bucket_major7IsFamily7() {
        assertThat(PowerShellFamilyProbe.bucketCapability(7))
                .isEqualTo(HostCapabilities.SHELL_PS_FAMILY7);
    }

    @Test
    void bucket_major5AndOthersDefaultFamily5() {
        assertThat(PowerShellFamilyProbe.bucketCapability(5))
                .isEqualTo(HostCapabilities.SHELL_PS_FAMILY5);
        assertThat(PowerShellFamilyProbe.bucketCapability(6))
                .isEqualTo(HostCapabilities.SHELL_PS_FAMILY5);
        assertThat(PowerShellFamilyProbe.bucketCapability(0))
                .isEqualTo(HostCapabilities.SHELL_PS_FAMILY5);
        assertThat(PowerShellFamilyProbe.bucketCapability(8))
                .isEqualTo(HostCapabilities.SHELL_PS_FAMILY5);
    }

    @Test
    void parseMajor_readsLeadingNumber() {
        assertThat(PowerShellFamilyProbe.parseMajor("7.4.1")).isEqualTo(7);
        assertThat(PowerShellFamilyProbe.parseMajor("5.1.22621.2506")).isEqualTo(5);
        assertThat(PowerShellFamilyProbe.parseMajor("")).isEqualTo(0);
        assertThat(PowerShellFamilyProbe.parseMajor("x")).isEqualTo(0);
    }
}
