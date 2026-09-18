# 18 · 自主迭代闭环讨论稿

`date`: 2026-09-17  
`status`: **brainstorm-draft** — 将自主迭代定义为可执行状态机；不表示方案已确认，不授权实施。

## 1. 自主迭代的定义

自主迭代不是让当前生产 Kernel 直接编辑自己的运行目录并重启，而是：

> 当前稳定代根据用户需求、运行证据或能力评测形成可验证的 `UpdateIntent`，在非活动槽生成一个完整候选代；候选经过独立毕业与观察后接管同一个烟火身份，失败则不影响稳定代。

系统可以自主决定如何调查、修改、测试和修正代码，但“是否变好”不能只由修改者自己口头判断。

## 2. 三个自主层级

| 层级 | 触发来源 | 例子 | 建议 |
|---|---|---|---|
| L1 需求驱动 | 用户明确提出目标 | “增加图片理解”“改善记忆冲突” | 首先实现 |
| L2 证据驱动 | 故障、回归、性能或评测证据 | SSE 经常中断、某类任务持续失败 | 闭环稳定后开启 |
| L3 开放探索 | Kernel 自己提出新能力或重构 | 主动更换 Agent Loop | 只允许提出假设和候选，不应无界自动发布 |

L1/L2 有外部目标和可复现证据，容易判断是否真正改善。L3 没有明确目标函数时容易出现无意义重写、奖励投机和复杂度膨胀。

## 3. 完整状态机

```text
OBSERVE
  ↓ 形成证据
PROPOSE
  ↓ 固化 UpdateIntent 与验收
SNAPSHOT
  ↓ 冻结父代、测试和继承包
BUILD
  ↓ 在非活动 Kernel 槽生成候选
VERIFY
  ↓ 构建/回归/能力/故障/自托管验证
SHADOW
  ↓ 影子状态与真实请求回放
PROMOTE
  ↓ drain + fencing epoch + 原子接管
PROBATION
  ├─ 通过 → COMMIT → LEARN → OBSERVE
  └─ 失败 → ROLLBACK → LEARN → PROPOSE 或 STOP
```

每个状态和转换都持久化。进程中断后从最后一个已提交状态恢复，不能依赖某次长 Prompt 一直活着。

## 4. 多 Kernel 如何协作

双核首版：

```text
Kernel A / SERVING
  处理用户请求、收集运行证据、形成 UpdateIntent

Kernel B / CANDIDATE
  在隔离源码和状态中调查、修改、构建、测试和修正

GraduationHarness
  使用父代冻结的不变量和独立样本进行验收

GenerationManager
  只负责状态机、角色分配、接管和回退
```

毕业后 B 成为 `SERVING`，A 成为 `STANDBY/ROLLBACK`。下一轮反向执行。增加第三、第四核后，可以同时产生多个候选或加入异构见证，但不改变状态机。

## 5. UpdateIntent 必须是可验证对象

自主迭代的第一步不是写代码，而是生成不可变意图：

```text
UpdateIntent
  intentId
  trigger                 // USER_REQUEST | INCIDENT | EVALUATION | EXPLORATION
  problemEvidence[]       // 日志、失败 Turn、指标、复现输入
  expectedBehavior[]      // 可观察的预期变化
  invariants[]            // 不允许回归的能力
  allowedScope[]
  forbiddenChanges[]
  resourceBudget
  stopConditions[]
  graduationPlanRef
```

如果候选在实施中发现目标或验收本身错误，应产生新的 intent revision；不能悄悄修改当前轮的毕业标准。

## 6. 候选 Kernel 的内部工作循环

候选可以自主运行以下循环：

```text
读取 UpdateIntent、父代源码和架构地图
        ↓
定位相关 Module 与 Interface
        ↓
提出最小变更假设和预测结果
        ↓
建立可复现失败或能力基线
        ↓
修改候选工作区
        ↓
执行窄测、受影响回归和静态检查
        ↓
失败 → 根据证据修正假设并继续
通过 → 生成 CandidateArtifact 与 EvidenceBundle
```

这一循环可以使用烟火自己的编码能力，也可以在 `CandidateFactory` seam 接入 Codex、Claude Code 或其他编码 Agent。对 GenerationManager 而言，它们都是 Adapter。

## 7. 候选交付物

候选不能只声称“测试通过”，必须输出：

```text
CandidateArtifact
  完整不可变 Kernel 产物
  sourceRevision 与 artifactHash
  父代 generationId
  Kernel 生命周期协议版本
  可读/可写状态 schema 范围

EvidenceBundle
  问题复现证据
  实际修改差异
  构建记录
  测试与回放结果
  失败尝试及其原因
  已知风险和未覆盖范围
  回退兼容性声明
```

这样独立毕业者可以复核事实，而不是重新相信候选的上下文。

## 8. 毕业的五道门

1. **可构建**：从干净快照可重复生成 artifact。
2. **不变量**：Turn、Message、SSE、Memory、关系和工具协议不回归。
3. **目标改善**：UpdateIntent 的每条预期行为都有可复现证据。
4. **存活与兼容**：启动、重启、崩溃恢复、状态兼容和回退成功。
5. **繁衍能力**：候选仍能读取自身、形成一次受控变更并构建后继，证明没有破坏自主迭代链。

前三道失败不能进入生产；后两道失败说明它可能能工作，但不能作为可继续自托管的新稳定代。

## 9. 生产接管

```text
1. 候选进入 READY，仍无生产写权限
2. GenerationManager 停止给旧代分配新 Turn
3. 旧代完成或明确中止在途流式 Turn
4. 等待工具副作用记录落库
5. 增加 fencing epoch
6. 将新 Turn 和生产写权限交给候选
7. 旧代进入 STANDBY，过期 token 不能再提交
8. 开始 probation window
```

观察期使用错误率、Turn 完成率、SSE 中断、工具异常、资源使用和语义回放等证据。越界即自动回退；不能询问刚刚接管的候选“你觉得自己是否健康”。

## 10. 代际学习

无论成功或失败，每次迭代都生成 `EvolutionRecord`：

```text
EvolutionRecord
  intentId
  parentGenerationId
  candidateGenerationIds[]
  selectedGenerationId?
  hypothesis
  predictedEffects
  observedEffects
  evaluationReport
  failureClassification?
  rollbackReason?
```

下一代接收的是经过筛选的故障模式、架构决策和评测结果，不是无上限地把全部日志塞进 Prompt。长期记录用于发现重复失败、退化趋势和哪些类型的修改最可靠。

## 11. 防止无限迭代与自我欺骗

每个 EvolutionRun 必须有：

- 最大候选数量；
- 最大修正轮数；
- 时间、token、算力和存储预算；
- 连续相同失败的停止条件；
- 不满足最小改善幅度时保持父代；
- 禁止候选修改本轮 sealed cases、历史证据和 GraduationHarness；
- 复杂度上升但行为无改善时拒绝毕业；
- 失败后进入冷却期，避免 crash-loop 式反复改写。

自主的含义是系统能完成整个闭环，不是无限运行，也不是必须每一轮都产生新版本。“没有足够证据，保留当前稳定代”是合法且重要的结果。

## 12. 哪些部分可以被自我更新

所有智能和产品代码都可以进入候选代，包括：

- Agent Loop 与规划策略；
- Prompt、Skill 和工具调度；
- Conversation、Memory 和关系逻辑；
- CandidateFactory；
- GraduationHarness 的下一版本；
- GenerationManager 的下一版本。

但同一轮候选不能同时修改自己必须通过的当前验收者。更新顺序应为：

```text
先由 GraduationHarness N 验证 Kernel N+1
再由 Manager N 验证 Manager N+1
新 Harness/Manager 只有在旧版本验证并接管后才生效
```

最终仍保留 OS launcher、原子 manifest 读取和失败回退这一极小恢复根。它不包含烟火智能，只保证存在一个可启动的上一稳定代。

## 13. 首版最小闭环

首版不要立即做开放式自我改进，只实现一条纵向链路：

```text
用户提交 UpdateIntent
→ A 固化父代与验收
→ B 自主修改完整 Kernel
→ GraduationHarness 独立验收
→ A/B 原子切换
→ 观察失败自动回退
→ 保存 EvolutionRecord
```

该链路稳定后再依次增加：运行故障自动提案、多个候选竞争、异构见证、低风险自动发布。这样每一步都复用相同 Interface，而不是另外搭建一套“自我意识系统”。

## 14. 当前建议

万年的自主迭代核心可以定义为：

> 由证据触发、以 UpdateIntent 为输入、以不可变候选代为产物、由独立 GraduationHarness 判定、通过 KernelFleet 轮换接管、失败可回退并沉淀 EvolutionRecord 的持续代际循环。

多 Kernel 解决的是“谁在旧核心被修改时继续活着”；UpdateIntent、独立验收、fencing、回退和代际记录共同解决“如何证明新核心值得接班”。两者缺一不可。
