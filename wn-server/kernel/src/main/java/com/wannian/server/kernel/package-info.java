/**
 * 领域内核：Turn、Agent Loop、Memory / Relationship 策略、工具策略等业务含义。
 *
 * <h2>依赖</h2>
 * 仅允许依赖 {@code com.wannian.server.api}（以及 JDK）。
 * 不得依赖 {@code com.wannian.server.app}、Spring Web/Boot、SQLite 驱动或厂商模型客户端。
 *
 * <h2>放置原则</h2>
 * <ul>
 *   <li>业务决策与状态机 → 本模块</li>
 *   <li>HTTP、SSE、JDBC、SDK → {@code app} 的 Adapter</li>
 *   <li>稳定对外形状 → {@code api}</li>
 * </ul>
 *
 * <p>标有 {@code OWNER: USER} 的核心实现（如 DefaultAgentLoop）由维护者按工作簿填写；
 * 框架可提供类型、预算器、Fake 与详细 TODO 注释，但不擅自补全策略代码。
 */
package com.wannian.server.kernel;
