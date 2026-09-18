# 16 · 全内核自托管架构审阅

`date`: 2026-09-17  
`status`: **architecture-review** — 审阅理论可行性、最小实现和可替换性；不表示方案已确认，不授权实施。

## 1. 审阅结论

该系统在工程理论上可实现，但只能在明确故障模型下成立：保证候选 Kernel 的代码、构建、启动和运行错误不会破坏当前稳定代；不能保证磁盘、操作系统、代际管理器本身和所有备份同时损坏时仍自动恢复。

“父代生成并教育子代，子代毕业后继承”是有效产品叙事；工程实现应压缩为三个深 Module，而不是创建多个互相聊天的 Agent：

```text
GenerationManager
CandidateFactory
GraduationHarness
```

Kernel 本身作为可替换 artifact 运行。最小版本不需要 Guardian A/B，也不需要复杂多 Agent 协商。

## 2. 最小可行结构

```text
OS service manager
└─ GenerationManager（小、稳定）
   ├─ stable slot    → Kernel N
   ├─ candidate slot → Kernel N+1
   ├─ previous slot  → Kernel N-1
   ├─ CandidateFactory
   └─ GraduationHarness
```

### GenerationManager

负责版本目录、进程启动、影子端口、状态机、入口切换、观察窗口和回退。它是一个深 Module：外部只看到 `evolve`、`status`、`rollback` 三个操作，内部隐藏 slot、进程、lease、原子指针和 crash-loop 处理。

### CandidateFactory

输入父代源码快照、用户要求和失败证据，输出不可变 CandidateArtifact。实现可以是烟火自己、Codex、Claude Code、其他模型或未来专用模型；调用者不依赖具体编码 Agent。

### GraduationHarness

输入 CandidateArtifact 和 GraduationPlan，输出不可变 EvaluationReport。内部运行构建、回归、未知任务、故障注入、状态兼容和自托管测试。它不参与生成候选，避免生成者自评。

## 3. 最小 Interface

建议只暴露以下概念 Interface：

```text
SelfEvolution
  evolve(UpdateIntent) -> EvolutionRunId
  status(EvolutionRunId) -> EvolutionStatus
  rollback(targetGenerationId?) -> RollbackResult

CandidateFactory
  build(ParentSnapshot, UpdateIntent) -> CandidateArtifact

GraduationHarness
  evaluate(CandidateArtifact, GraduationPlan) -> EvaluationReport

KernelLifecycle
  start(GenerationManifest, RuntimeBindings)
  probe(ProbeRequest) -> ProbeResult
  stop(StopReason)
```

实际语言接口可以不同，但不应把 git、端口、进程、测试框架或模型参数泄漏给上层。否则模块只是浅层转发，未来难以替换。

## 4. 可替换点

真实需要的 seam：

| Seam | Adapter 示例 | 为什么需要 |
|---|---|---|
| CandidateFactory | 烟火自编码、Codex、Claude Code、DGM-style evolver | 候选生成方法会变化 |
| GraduationHarness | 本地测试、容器测试、远程评测 | 测试环境和成本会变化 |
| KernelLauncher | 本地 Java 进程、Docker、远程节点 | 部署形态会变化 |
| TrafficSwitcher | 本地反向代理、端口指针、服务发现 | 接管方式会变化 |
| GenerationStore | 文件目录、对象存储 | artifact 保存方式可能变化 |

暂时只有一个实现的内部细节不要过早做成公共 seam。例如 MVP 只有 SQLite 时，不必为 selector 状态库设计多数据库插件体系。

## 5. CandidateArtifact 契约

候选不是工作目录，而是不可变、可验证的产物：

```text
generationId
parentGenerationId
sourceRevision
artifactHash
entrypoint
kernelProtocolVersion
readableStateSchemaRange
writableStateSchemaVersion
requiredRuntime
buildEvidence
```

GenerationManager 只认识 manifest 和 KernelLifecycle，不理解候选内部模块。因此 Agent Loop、插件加载器、Memory、Turn、工具和自更新逻辑都可以整体替换。

## 6. 理论可行性的关键条件

### 6.1 代码与身份状态分离

烟火身份、Owner、Conversation、Memory 和代际谱系不能内嵌在某一代 Kernel 私有目录中。Kernel 是实现，持久状态是跨代数据。否则替换 Kernel 等于更换角色或丢失人生经历。

### 6.2 当前代不被原地覆盖

候选写入全新目录；stable/previous 指针只在验证完成后原子切换。候选编译失败或进程崩溃不会改变 stable。

### 6.3 评测标准不由候选同时改写

候选可以提出新的 GraduationPlan，但当前轮必须由父代固定的产品不变量和 sealed cases 判定。否则候选可以删除失败测试后宣布毕业。

### 6.4 数据变更可回退

候选使用影子库验证。生产接管窗口只允许兼容性扩展；破坏性 schema 收缩延后到父代回退窗口结束之后。只保存旧 jar 而不保存旧数据语义不是真回退。

### 6.5 稳定的代际协议

GenerationManager 与 Kernel 之间只保留小型版本化协议：启动参数、readiness、canary、commit token、drain 和 stop。业务接口可以演化，但生命周期协议必须至少跨相邻代兼容。

## 7. 对“教育”概念的修正

教育不应实现成长时间很长的第二个在线 Agent。它实际上是生成候选所需的结构化输入：

```text
InheritanceBundle
  identityContract
  architectureMap
  parentSource
  userChangeRequest
  failureCorpus
  compatibilityContract
  graduationPlan
```

子代的“学习”表现为修改、构建、测试和修正候选代码。这样流程可重复、可缓存、可中断恢复，也能替换 CandidateFactory。

## 8. 对 A/B Guardian 的审阅

A/B Guardian 理论可行，但不是第一阶段必需品。过早加入会造成：

- 两套监督状态同步；
- 谁监督切换者的递归问题；
- 双方共享错误补丁导致相关失效；
- 故障排查路径成倍增加。

最小方案先让 GenerationManager 由 OS service manager 拉起，它只更新 Kernel。等 Kernel 代际闭环稳定后，再让 GenerationManager 自身使用 A/B slot 更新：当前 Manager 写入备用 Manager，新 Manager 未提交健康 token 则 OS launcher 回到旧 Manager。

因此阶段顺序应是：

```text
阶段 1：Kernel N/N+1/previous + 单 GenerationManager
阶段 2：GenerationManager A/B + 极小 launcher
阶段 3：跨设备/远程恢复副本（若确有需要）
```

## 9. 应删减的复杂设计

为保持简单，首版删除或延期：

- 多个智能监控 Agent；
- 多候选种群和开放式 DGM archive；
- 在线 shadow 全流量复制；
- 自动破坏性数据库迁移；
- 多节点共识；
- Manager 自更新；
- 每次毕业都必须生成完整孙代。

自托管能力首版可用较小测试替代：候选读取自己的一个测试 Module，生成受控修改，重新构建并通过指定测试。无需每轮真的启动完整孙代。

## 10. 最小端到端流程

```text
1. 用户提出代码迭代要求
2. GenerationManager 固化 ParentSnapshot 和 UpdateIntent
3. CandidateFactory 在独立工作区生成候选
4. 构建为不可变 CandidateArtifact
5. GraduationHarness 在隔离环境评测
6. GenerationManager 在备用端口冷启动候选
7. 执行 KernelLifecycle probe 和最小真实 Turn
8. 原子切换入口，父代进入 drain/standby
9. 观察窗口通过后 commit
10. 异常则切回 previous
```

这十步已经覆盖自我迭代的必要闭环，不需要在首版增加更多角色。

## 11. 主要剩余风险

- GraduationHarness 覆盖不足，错误版本通过；
- 新旧 Kernel 对状态的解释不同但 schema 仍表面兼容；
- 用户请求本身改变了关键产品语义，旧测试不再适用；
- 切换前外部工具产生不可逆副作用；
- GenerationManager 缺陷导致错误切换；
- 存储损坏同时破坏 stable、previous 和状态数据。

这些风险不能靠更多 Agent 互相评论解决，需要故障注入、离线备份、稳定协议和可恢复数据设计。

## 12. 最终判断

| 问题 | 判断 |
|---|---|
| 完整 Kernel 能否由自身下一代替换 | 可以 |
| 是否必须另一个人格 Agent | 不需要 |
| 单进程能否可靠原地覆盖全部自身代码 | 不可作为可靠方案 |
| 是否需要永远不变的大内核 | 不需要 |
| 是否需要极小稳定恢复根 | 需要 |
| 首版是否需要 A/B Guardian | 不需要 |
| 是否能保持模块可替换 | 可以，前提是守住少量 Interface |
| 是否能保证绝对不崩 | 不能；只能在声明的故障模型内自动恢复 |

推荐以“单 GenerationManager＋不可变 Kernel 代际＋可替换 CandidateFactory/GraduationHarness”为最小架构。它足够简单，也允许未来替换生成器、评测器、启动方式和整个 Kernel，而不重写调用方。
