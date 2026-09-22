# 04 · 0.2.1 审计复核（对照 03 关闭）

审查日期：2026-09-22。对象：相对 [03-audit-0.2.1](./03-audit-0.2.1.md) 登记缺陷后的**修复工作树**。  
性质：**另建版本报告**；**不改写** 03 / 01 / 02 旧稿正文。  
对照：[施工单收口段](../plans/k01-agent-loop.md)、[插话 followup](../research/in-flight-user-message.md)。

Git HEAD（已提交基线仍同 03）：`e98252d603fd8754c93145aa6a75dc09095c9a5f`。修复仍主要在**未提交工作树**。

关键源码 SHA-256（本轮复核时）：

```text
DefaultAgentLoop.java
35B64BF01FFDA601E5AB7E83FD994FE9B1C8E5F695154CCD178BE1546868FBE3
TurnEngine.java
58D03DD044D4FF34E3B8C84A82288F011CAED615AE6EC83A53C0606BCCEF709C
TurnController.java
4A1B99325A9667FAEDDB884C577743C4DE28B980DD00E28E23F1B194CE8B198B
TimeoutModelPort.java
74E1E73FDB95F8A68C8969CC327C7C552B9CB498CDD2488D7465C11AC257606F
```

（与 03 快照相比：Loop / TurnEngine / Timeout 已变；TurnController 哈希未变。）

---

## 1. 裁决

| 项 | 结论 |
|----|------|
| **相对 03 的 P1（F-01…F-05）** | **关闭**（单 JVM 主路径 + 审阅 nits 已补） |
| **相对 03 的 P2（F-06…F-11）** | **关闭**（工作树实现 + 窄测） |
| **相对 03 的 P3（F-12…F-13）** | **关闭（适度）**；Trace 未达 guide §15 全字段，属有意降级 |
| **可否依赖为 0.2.1 缺陷收口基线** | **是**（未 commit；跨进程 / 队列 UI / 工具 continue / 产品 Stop 仍属后续批次） |

结构审阅（P1）：Pass-with-nits → nits 已在同日后续提交式修复中收口（COMMITTING 入锁、落库失败日志、锁 map 摘除、Failure→DB 贯通测）。

---

## 2. 逐条关闭表（对照 03 Findings）

| ID | 03 严重度 | 本轮状态 | 证据（已验证事实 / 静态推断） |
|----|-----------|----------|------------------------------|
| **F-01** | P1 | **关闭** | `TurnEngine.runConversationSerialized`：同会话 `ReentrantLock(true)` 串行 **RECEIVED + COMMITTING 恢复**；方案 A 写入 k01；编排测 `concurrentDifferentKeysSerializeLoopPerConversation`（`maxInLoop==1`）。**残留（合同边界，非回潮）：** 跨进程无锁；receive 序 worker / `TURN_QUEUED` 未做（已定不做）。 |
| **F-02** | P1 | **关闭** | `AgentBudgetGate`：软截止后禁止*后续* decide；首次仍可开；trace 可标「软截止已过」。`AgentBudgetGateTest`。 |
| **F-03** | P1 | **关闭（闸门可测）** | 闸门单测覆盖 `maxModelDecisions`；Loop **仍不**对 ToolCalls `continue`（与 03/施工单一致）。「永远 ToolCalls → 第 N+1 次耗尽」留给 **0.2.2**。 |
| **F-04** | P1 | **关闭** | `Failure(CANCELLED)`→`AgentOutcome.Cancelled`→`cancelAttempt`；编排测 `modelFailureCancelledThroughDefaultLoopMarksDbCancelled`。**残留：** 产品 Stop HTTP 未接（03 允许后置）。 |
| **F-05** | P1 | **关闭** | freeze `RevisionConflict`→`failAttempt`；测断言 `FAILED`。落库失败打 `System.Logger`（尽力而为）。 |
| **F-06** | P2 | **关闭** | `TimeoutModelPort` 共享池；超时 cancel Future；适配器 HTTP timeout 尊重 `context.deadline()`；`TimeoutModelPortTest`。 |
| **F-07** | P2 | **关闭** | Loop 用户文案经 `ErrorLogFields.redact`；空白回退稳定短句。 |
| **F-08** | P2 | **关闭（边界接线）** | `SafeErrorLog` 已在 `TimeoutModelPort` / `OpenAiCompatibleModelAdapter` 调用。**残留：** 非全仓每个 `log.error` 已清扫；主边界路径已接。 |
| **F-09** | P2 | **关闭** | `DecisionPort` + `NoopDecisionPort`；未挂 `DefaultAgentLoop` 构造器；`NoopDecisionPortTest`。 |
| **F-10** | P2 | **关闭** | 损坏 `wannian.json` 拒绝覆盖写入；`AgentBudgetSettingsTest`。 |
| **F-11** | P2 | **关闭** | 适配器 Failure code 改 `ErrorCodes.*`；主路径无 `"CANCELLED"` / `"MODEL_RATE_LIMITED"` 字面量。 |
| **F-12** | P3 | **关闭（适度）** | 单步 trace 含 step / decision / duration / usage 或 error code 等；**未**凑齐 guide §15 全部字段（无工具则无 tool/operationId）。可随 0.2.2 再加厚。 |
| **F-13** | P3 | **关闭** | 空 `ToolCalls`→`INVALID_MODEL_OUTPUT`；非空仍 `TOOLS_NOT_ENABLED`。 |

---

## 3. 03 §4 清单过宽勾选（复核意见）

| 03 备注项 | 复核 |
|-----------|------|
| 15s 软 / 30s 硬可配置 | 软截止**已有行为**；次数闸门可单测。清单若仍写「可配置」而不写「软截止生效」，建议另授权改清单措辞。 |
| 同会话执行顺序（旁注同键幂等） | **异键串行（方案 A）已成立**；旁注仍易误导，清单改写另授权。 |
| 全进程日志约定 | 类型 + **边界调用点**已有；非「每一处日志」统一。 |
| cancel → Cancelled | 映射与 DB 路径已通；**无产品 Stop API** 仍成立。 |

施工单已诚实保留的 blank / 决策打满 live `[ ]`：**保持待补**，与 F-03「不盲目 continue」一致。

---

## 4. 窄测证据（本轮可用）

本会话曾跑（退出码 0），命令在 `wn-server`：

```text
mvn -pl kernel,app -am test
  -Dtest=AgentBudgetGateTest,DefaultAgentLoopBudgetTest,TurnEngineOrchestrationTest,
         DefaultAgentLoopLiveTest,AgentBudgetSettingsTest,ErrorCodesTest,ErrorLogFieldsTest,
         TimeoutModelPortTest,NoopDecisionPortTest
  -Dsurefire.failIfNoSpecifiedTests=false
```

结果摘要：kernel 16 + app 19，**BUILD SUCCESS**。live 缺密钥允许 skip，未用 fake 冒充 FinalResponse。

**不**把本文件当作外网 live 全量通过证据；C/D live 夹具仍按施工单命令另跑。

---

## 5. 仍属后续批次（03 §7 对齐，非本轮回潮）

| 项 | 归属 |
|----|------|
| ToolRuntime / ToolCalls continue / 次数打满 live | 0.2.2 |
| Memory / 关系注入 | 0.2.3 |
| SSE、队列 UI、receive 序 worker、产品 Stop | 0.2.4 |
| 进程强杀、跨进程单 flight、整库堆峰值 | 0.2.7 |

---

## 6. 一句话

**03 所登记的 P1～P3 在当前工作树已关闭或有意适度降级；旧稿 03 保留作缺陷快照。本文件为关闭复核。** 实施清单 / 路线图 / 产品概览措辞已于同日收紧；代码与文档一并提交后即可开工 **0.2.2**（须保留会话锁与预算闸门，ToolCalls continue 后回归次数打满）。
