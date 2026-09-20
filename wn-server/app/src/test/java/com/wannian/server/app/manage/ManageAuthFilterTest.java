package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.app.manage.ManageAuthFilter.Decision;
import org.junit.jupiter.api.Test;

class ManageAuthFilterTest {

    @Test
    void loopbackSkipsToken() {
        assertThat(ManageAuthFilter.decide("127.0.0.1", null, "")).isEqualTo(Decision.ALLOW);
        assertThat(ManageAuthFilter.decide("::1", "Bearer wrong", "")).isEqualTo(Decision.ALLOW);
    }

    @Test
    void remoteRequiresConfiguredToken() {
        assertThat(ManageAuthFilter.decide("203.0.113.10", null, "")).isEqualTo(Decision.UNCONFIGURED);
        assertThat(ManageAuthFilter.decide("203.0.113.10", "Bearer wrong", "dev-manage"))
                .isEqualTo(Decision.UNAUTHENTICATED);
        assertThat(ManageAuthFilter.decide("192.168.1.8", null, "dev-manage")).isEqualTo(Decision.UNAUTHENTICATED);
        assertThat(ManageAuthFilter.decide("203.0.113.10", "Bearer dev-manage", "dev-manage"))
                .isEqualTo(Decision.ALLOW);
    }
}
