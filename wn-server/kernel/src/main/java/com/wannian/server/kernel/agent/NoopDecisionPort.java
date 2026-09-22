package com.wannian.server.kernel.agent;

/**
 * {@link DecisionPort} 默认实现：永远不分享（无外网、无 Key、无 Jev）。
 */
public final class NoopDecisionPort implements DecisionPort {

    @Override
    public boolean shouldShare(String situationSummary) {
        return false;
    }
}
