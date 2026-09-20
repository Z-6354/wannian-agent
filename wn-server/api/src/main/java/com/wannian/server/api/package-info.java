/**
 * 稳定契约层：跨 Client / Kernel / 未来 OpenAPI 共用的值类型与协议形状。
 *
 * <h2>允许放入本包（及子包）</h2>
 * <ul>
 *   <li>不可变值类型（如 {@code record ConversationId}）</li>
 *   <li>命令 / 回执 DTO、封闭结果类型的接口形状</li>
 *   <li>无 IO 的校验友好异常与错误码常量</li>
 * </ul>
 *
 * <h2>禁止</h2>
 * <ul>
 *   <li>Spring / Spring MVC / Boot 注解与类型</li>
 *   <li>JDBC、SQLite、Flyway 等持久化实现</li>
 *   <li>任一厂商模型 SDK、HTTP Client 实现</li>
 *   <li>{@code @RestController} 或其它 Web 层代码</li>
 * </ul>
 *
 * <p>依赖方向：本模块 <b>不依赖</b> {@code kernel} 与 {@code app}。
 * 门禁见 app 模块的 {@code ModuleDependencyRulesTest}。
 */
package com.wannian.server.api;
