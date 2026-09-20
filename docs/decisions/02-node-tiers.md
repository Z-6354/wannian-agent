# 02 · 节点能力分层

`date`: 2026-09-17  
`status`: **historical-ladder** — 2026-09-17。这张 L1–L4 是「节点变多才允许自更新」的旧分层，**不是**现行版本。现行是：v0.1 当前进度；v0.2 单核 harness + 单核节点；v0.3 世界树 + 多核节点（设备/节点/角色，见 [多核心](../research/multi-node-companion-world.md)）。L2 高可用与 L3 自更新属于更后，不要按本文把它们当成 v0.2 或 v0.3。

## 1. 分层结论

万年根据健康、可信且兼容的节点数量逐级获得能力：

| 等级 | 最低节点 | 核心能力 | 明确不具备 |
|---|---:|---|---|
| L1 · Agent | 1 | 普通 Agent、对话、记忆、工具、流式输出 | 自主更新、高可用 |
| L2 · Resilient | 2 | L1 + 故障接管、自动重启/重建、稳定版本恢复 | 自主生成并晋升新代码 |
| L3 · Evolving | 3 | L2 + 候选生成、独立审核、全 Kernel 自主更新 | 多主写入、无审查晋升 |
| L4 · Fleet | 4+ | L3 + 多候选、异构审核、跨设备冗余和滚动演化 | 默认仍不开放同 Turn 多写 |

关键原则：双节点只修复已知稳定状态，三节点才创造和晋升未知的新状态。

## 2. L1：单节点普通 Agent

```text
Node A
└─ Agent Runtime
   ├─ Conversation / Turn / SSE
   ├─ Memory / Relationship
   ├─ Tools
   └─ Local persistence
```

单节点提供完整用户功能，但不执行自我源码修改和候选晋升：

- 可以由 OS supervisor 重启同一进程；
- 可以读取诊断信息并向用户报告；
- 可以安装由外部可信发布渠道提供的常规版本；
- 不能自己生成新 Kernel 后宣布升级成功；
- 不能声称单节点故障时仍保持连续可用。

“不具备自主更新”是产品模式限制，不代表代码结构另写一套。它仍使用 `WannianNode` 和统一 Kernel Interface，加入更多节点后无需迁移业务数据或更换人格。

## 3. L2：双节点高可用与自动修复

```text
Node A：SERVING
Node B：STANDBY / REPAIRER
```

能力包括：

- A 进程失效后 B 经 lease/fencing 接管；
- B 使用 `lastKnownGoodGeneration`，不使用实验代码；
- B 接管后重启、重新安装或重建 A；
- A 恢复后先追赶状态，再成为 STANDBY；
- 在途 Turn 按中断和幂等规则恢复；
- 相同版本反复崩溃时回到 `previousKnownGoodGeneration` 或 SafeMode。

双节点“自动修复”的允许范围：

```text
允许：
  重启进程
  重建运行目录
  从 hash 校验通过的 artifact 重装
  恢复版本化配置
  切换 last/previous known-good generation
  清理可安全重建的缓存和临时状态

不允许：
  自主改写源码
  生成未知补丁并直接上线
  修改毕业规则后宣布修复
  两个节点同时写生产状态
```

原因是两节点无法同时保持“稳定服务者、候选生成者、独立审核者”三方隔离。让 B 一边改代码一边自行审核，会退回生成者自证。

## 4. L3：三节点自主更新

```text
Node A：SERVING_AUDITOR
Node B：EVOLVER
Node C：TARGET
```

三节点首次满足自主更新的最小职责隔离：

1. A 继续为用户服务并冻结 UpdateIntent/GraduationPlan；
2. B 在隔离 workspace 生成完整候选代；
3. C 承载候选并以影子状态运行；
4. GraduationHarness 执行确定性硬门槛；
5. A 执行独立语义审核；
6. C 通过后取得新的 serving epoch；
7. A 保留为回退代，B/C/A 按环形规则轮换。

三节点允许修改全部 Kernel，包括 Agent Loop、Memory、工具调度和后续演化实现；同一轮仍禁止候选改写其当前审核者和 sealed cases。

## 5. L4：四节点及以上

额外节点不改变基本架构，而是增加并行性和独立性：

```text
1 SERVING
1+ EVOLVER          // 不同方法或模型生成多个候选
1+ TARGET           // 隔离启动候选
1+ AUDITOR/WITNESS  // 异构环境和独立评测
```

可能获得：

- 多候选竞争而不是单路径修改；
- 不同模型、操作系统或硬件上的异构审核；
- 保留独立回退节点的同时继续下一轮演化；
- 跨设备和跨故障域滚动更新；
- 某节点故障时仍维持 L3 能力。

节点增加不意味着生产写入者增加。默认始终保持每个 Turn 一个所有者、每个 CompanionIdentity 一条权威提交链。

## 6. 模式由有效节点决定，不只数进程

不能只判断 `nodeCount >= 3`。参与某一能力等级的节点必须全部满足：

```text
HEALTHY
AUTHENTICATED
COMPATIBLE_PROTOCOL
STATE_CAUGHT_UP
CERTIFIED_RUNTIME_AVAILABLE
ROLE_CAPABILITY_MATCHED
```

自主更新还要求：

- 至少一个节点具备 `EVOLUTION_BUILD`；
- 至少一个不同节点具备 `AUDIT_EXECUTION`；
- 至少一个非生成节点可以承载候选；
- 当前服务和上一稳定代具有可恢复副本；
- 生成者、审核者和目标角色没有落到同一故障实例。

三个逻辑 Agent 如果都在一个进程中，不等价于三个可用节点；三个进程如果共享同一系统盘，也只能提供有限的物理容灾。

## 7. 自动升降级

```text
1 → 2：
  新节点认证、同步并校验稳定代
  开启 STANDBY 和 failover

2 → 3：
  第三节点完成认证、追赶和能力验证
  开启 EVOLVER/TARGET/AUDITOR 状态机

3 → 2：
  立即停止新 EvolutionRun
  冻结或隔离未晋升候选
  保留 SERVING + STANDBY，退回 L2

2 → 1：
  撤销失效节点租约
  单节点继续普通 Agent，退回 L1
```

降级不能中断当前用户数据，也不能因为失去第三节点就把未经审核的候选上线。节点恢复后必须 `REJOINING → ONLINE_REPLICA`，完成状态追赶才可重新提升等级。

## 8. 演化过程中的掉线规则

| 时点 | 节点丢失 | 处理 |
|---|---|---|
| BUILD 前 | 任一必要节点 | 不启动本轮 |
| BUILD 中 | EVOLVER | 本轮失败，保留服务 |
| VERIFY 中 | AUDITOR/TARGET | 暂停或取消候选，禁止晋升 |
| PROMOTE 前 | 任一节点导致有效数降至 2 | 取消 promote，退回 L2 |
| PROBATION 中 | 回退节点丢失 | 冻结后续演化，视风险回退或维持当前代 |
| COMMIT 后 | 第三节点丢失 | 当前代继续服务，能力退回 L2 |

自主更新资格在 `PROMOTE` 时必须重新检查，不能因为构建开始时有三个节点就一直沿用旧判断。

## 9. 多设备含义

节点分层与部署位置无关：

```text
一台电脑一个节点              → L1
一台电脑两个隔离进程/目录      → L2（进程级恢复）
电脑 + 云端两个节点            → L2（跨设备恢复）
电脑 + 云端 + 另一设备         → L3
四个以上跨设备/环境节点        → L4
```

手机等资源有限节点只有在满足角色能力时才计入相应等级。例如只能发送通知的手机可以加入集群，但不能仅凭“在线”就成为 EVOLVER 或 AUDITOR。

## 10. 推荐产品表达

```text
一个节点：万年可以生活
两个节点：万年可以在一个身体故障后继续生活并修复它
三个节点：万年可以在继续生活的同时创造、检验并继承新的自己
更多节点：万年可以比较多个后代，并跨设备分担能力和风险
```

工程上对应：

```text
L1 Agent
L2 Resilient Agent
L3 Self-Evolving Agent
L4 Evolution Fleet
```

该分层保持同一套 Node、Kernel、Identity、lease 和 artifact 模型；增加节点只开启更高能力状态机，不切换到另一套系统。
