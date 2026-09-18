# 20 · 三核自动接管与修复讨论稿

`date`: 2026-09-17  
`status`: **brainstorm-draft** — 定义服务核故障后的自动接管和修复路径；不表示方案已确认，不授权实施。

## 1. 结论

可以实现：A 故障后由 B 自动接管生产，再由 C 修复或重建 A。但安全成立的前提不是简单检测“进程没了”后修改路由，而是：

```text
故障确认
→ 撤销 A 的生产租约
→ fencing epoch 前移
→ B 从已认证稳定代启动并接管
→ A 进入 QUARANTINED
→ C 在隔离环境诊断并重建 A
→ B + GraduationHarness 审核修复结果
→ A 恢复为可用目标/待命槽
```

旧 A 即使稍后自行恢复，也不能再使用过期 token 写入生产状态。

## 2. 两套正交角色

三核需要把高可用角色与自主迭代角色分开：

```text
AvailabilityRole
  SERVING | STANDBY | QUARANTINED

EvolutionRole
  AUDITOR | EVOLVER | TARGET | IDLE
```

正常情况下可以是：

| 核心 | AvailabilityRole | EvolutionRole |
|---|---|---|
| A | SERVING | AUDITOR |
| B | STANDBY | EVOLVER |
| C | STANDBY | TARGET |

B 的 `EVOLVER` 工作发生在独立 workspace；B 自己用于故障接管的 certified runtime 不得被修改。因此 B 可以一边生成候选，一边保留启动最后稳定代的能力。

## 3. A 故障后的角色变化

```text
故障前：
A = SERVING + AUDITOR
B = STANDBY + EVOLVER
C = STANDBY + TARGET

故障后：
A = QUARANTINED
B = SERVING + AUDITOR
C = STANDBY + EVOLVER/REPAIRER
```

正在进行的普通演化任务默认暂停。C 丢弃或冻结未毕业候选，切换到已认证 runtime，在新的修复 workspace 中分析 A 的故障证据。可用性恢复优先于继续功能演化。

## 4. 自动接管时序

```text
1. HealthMonitor 连续探测 A 失败或 A 的 serving lease 超时
2. GenerationManager 使用事务/CAS 确认当前 epoch 和租约仍属于 A
3. 将 A 标记为 SUSPECT；停止向 A 分配新 Turn
4. 达到故障阈值后原子增加 fencing epoch，撤销 A 的 lease
5. B 使用 lastKnownGoodGeneration 启动或提升为 SERVING
6. B 获取新 epoch，恢复入口和新 Turn 接收
7. 清点 A 的 in-flight Turn 与未决工具操作
8. A 被隔离，恢复后也只能进入诊断模式
9. C 根据故障类型修复 A 或生成替代 artifact
10. B 与 GraduationHarness 审核修复结果
11. A 通过后恢复为 STANDBY/TARGET；不必立即抢回 SERVING
```

为防止短暂卡顿触发错误切换，需要 `SUSPECT → FAILED` 两阶段，并结合租约超时、进程状态、readiness、事件循环停滞和依赖健康，而不是一次 ping 失败就接管。

## 5. 为什么必须 fencing

典型危险场景是 A 并未死亡，只是暂停或与状态库暂时断连：

```text
A 暂停
→ 系统提升 B
→ A 恢复
→ A/B 都以为自己是服务核
```

每次生产写入必须校验当前 `servingEpoch`：

```text
Turn commit
Message append
Memory mutation
Relationship mutation
Tool effect record
SSE terminal event
```

A 的旧 epoch 失效后，其任何迟到提交都被存储层拒绝。仅靠反向代理不再把请求发给 A，无法阻止 A 提交已经在执行的旧任务。

## 6. 流式输出与在途 Turn

A 故障时不能假设 B 可以从最后一个 token 无缝续写。首版采用明确恢复语义：

```text
未开始 Turn：重新分配给 B

已开始但无外部副作用：
  标记 INTERRUPTED
  B 使用原始输入创建带 parentTurnId 的重试 Turn

已经部分 SSE 输出：
  向客户端发送或补记明确的 interrupted 终止状态
  不拼接 A/B 两次独立生成的 token

存在未决工具副作用：
  使用 operationId 查询执行账本
  已成功则读取结果
  明确未执行才允许重试
  状态未知的不可逆操作不得自动重复
```

未来若增加模型状态 checkpoint，可以优化续跑，但不应成为 MVP 接管正确性的前提。

## 7. C 如何修复 A

C 读取：

- A 最后的 heartbeat、线程转储和退出码；
- 崩溃前后的 Turn、工具调用和状态提交记录；
- A 当前 generation、配置和依赖版本；
- B 接管后是否复现相同故障；
- 历史相似事故与上一稳定 artifact。

C 根据故障分类选择动作：

| 故障类别 | 修复方式 |
|---|---|
| A 单实例进程损坏 | 清理运行态，以同一稳定 artifact 重建 A |
| A 槽文件损坏 | 从不可变 GenerationStore 校验并重新安装 |
| 当前 generation 代码缺陷 | 基于上一稳定代或修复候选生成新 artifact |
| 配置/依赖漂移 | 恢复版本化 RuntimeBindings 并重新验证 |
| 状态数据问题 | 不自动覆盖；进入数据恢复流程 |
| 机器/磁盘故障 | 在其他故障域重建槽；同机 C 无法修复物理主机 |

C 不能直接修改损坏 A 的原目录后宣布修复。它应生成新的、可校验的 artifact 或重建清单，再由 B 和 GraduationHarness 验证。

## 8. 同版本相关故障

如果 B 使用与 A 相同的 Generation，A 因代码缺陷崩溃时，B 可能立即复现。因此接管策略需要：

```text
第一次实例故障：B 尝试 lastKnownGoodGeneration
同一故障指纹再次出现：熔断该 generation
自动回到 previousKnownGoodGeneration 或 SafeMode
C 针对故障输入生成修复代
```

`SafeMode` 只提供最小对话、状态导出、诊断和修复能力，禁用导致崩溃的工具或可选 Module。它不是第四个人格，而是一份经过预先验证的受限运行策略。

## 9. 故障域限制

三核的稳定性取决于它们是否共享故障域：

| 部署方式 | 能抵抗 |
|---|---|
| 同进程内三个逻辑 Agent | 主要是逻辑错误，不能抵抗进程崩溃 |
| 同机三个独立进程 | 单进程崩溃、单实例卡死 |
| 同机独立目录/容器 | 额外抵抗依赖和文件污染 |
| 不同机器/节点 | 可抵抗单机、磁盘和部分网络故障 |

如果 A/B/C 与 GenerationManager、数据库都在同一台机器，断电和系统盘损坏仍会同时失效。MVP 可以先承诺进程级自动恢复，不能宣传为完整物理容灾。

## 10. Manager 故障

A/B/C 都不能自行宣布成为服务核，否则 Manager 故障时容易 split-brain。MVP 由 OS supervisor 重启单个 GenerationManager；其状态通过原子文件或事务数据库持久化：

```text
currentServingSlot
servingEpoch
lastKnownGoodGeneration
previousKnownGoodGeneration
roleLeases
inFlightEvolutionRun
```

未来跨机器部署时，再把这部分迁移到具备 lease/CAS 能力的协调存储；不需要在单机 MVP 内自行实现共识算法。

## 11. 恢复完成条件

A 只有满足以下条件才能退出 `QUARANTINED`：

1. 根因或可操作故障分类已经记录；
2. artifact 与配置 hash 校验通过；
3. 能独立启动、readiness、处理测试 Turn 和正常结束 SSE；
4. 原故障输入不再导致相同失败；
5. 无生产写权限时完成影子回放；
6. B 与 GraduationHarness 均通过审核；
7. 证明停止、重启和再次重建可用。

恢复后的 A 默认成为 `STANDBY/TARGET`，不自动抢回生产角色。让当前稳定的 B 继续服务，可以避免刚修复完成就发生第二次不必要切换。

## 12. 推荐规则

> A 故障时，B 可以自动接管，但 B 必须使用独立保存的已认证稳定代；GenerationManager 必须先撤销 A 的租约并前移 fencing epoch。C 随后暂停普通演化任务，在隔离环境修复或重建 A，由 B 和 GraduationHarness 审核。A 恢复后先作为待命槽，不立即切回。若不足两个可启动的稳定槽，系统进入 SafeMode；若三核位于同一主机，只承诺进程级而非主机级容灾。
