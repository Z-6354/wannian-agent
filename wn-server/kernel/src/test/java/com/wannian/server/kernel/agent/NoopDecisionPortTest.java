package com.wannian.server.kernel.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-09：DecisionPort Noop 默认不分享。 */
class NoopDecisionPortTest {

    @Test
    void noopNeverShares() {
        DecisionPort port = new NoopDecisionPort();
        assertThat(port.shouldShare(null)).isFalse();
        assertThat(port.shouldShare("")).isFalse();
        assertThat(port.shouldShare("世界事件：该起床了")).isFalse();
    }
}
