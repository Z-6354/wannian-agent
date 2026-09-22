package com.wannian.server.kernel.agent;

/**
 * 烟火侧决策缝（K01 B0 预留）：是否把世界经历分享给用户等。
 *
 * <p>不生成用户可见正文；不替代 {@link com.wannian.server.kernel.model.ModelPort}；无 Jev 编译依赖。
 * {@link DefaultAgentLoop} 本批不依赖本接口。
 */
public interface DecisionPort {

    /**
     * 是否允许把当前情境分享给用户。
     *
     * @param situationSummary 脱敏情境摘要；可为 null / blank
     * @return true 表示可分享；默认实现应保守返回 false
     */
    boolean shouldShare(String situationSummary);
}
