# 17 · 多 Kernel 架构审阅

`date`: 2026-09-17  
`status`: **historical** — 只保留「同一角色制品的宿主主备」。现行多核心拓扑见 [multi-node-companion-world.md](./multi-node-companion-world.md)，排期在 N/W 之后，不要把本文的双 Kernel 主备当成第一部署。  
`status-original`: architecture-review-draft — 对多核方向进行理论审阅并给出推荐收敛方案；不表示已经定案，不授权实施。

## 1. 结论

可以做多核，而且它确实比单 Kernel 原地自更新更稳定。最合适的定义不是“多个烟火”，而是：

> 一个 `CompanionIdentity`，多个可替换的 Kernel 实例；任一时刻只有一个 Kernel 对某个 Turn 拥有最终执行与写入权。

推荐首版采用 **双 Kernel 主备轮换**，而不是直接做多活：

```text
持久身份与状态：烟火（唯一）
          │
GenerationManager（唯一路由与代际裁决）
    ├─ Kernel A：SERVING
    └─ Kernel B：STANDBY / CANDIDATE
```

升级 B 时 A 继续服务；B 毕业后接管，A 进入待命和回退位。下一次升级反向进行。这样两个 Kernel 可以彼此完成生成、验证和接班，但不会同时争夺同一段人生、同一个会话或同一次工具执行。

## 2. “多核”与“多角色”不是一回事

多角色是多个独立主体，各自可以拥有身份、关系、目标和记忆。多 Kernel 是同一主体的多个执行实现，共享同一份身份真源：

```text
companionIdentityId = 烟火，永久稳定
kernelGenerationId  = 实现代际，可替换
kernelInstanceId    = 某次运行实例，可重建
```

因此用户始终只面对烟火，不会因为 A/B 切换突然出现两个关系阶段、两份长期记忆或两个互相矛盾的自我。Kernel 不拥有身份，只在获得租约后代表该身份处理工作。

## 3. 三种可选结构

| 结构 | 工作方式 | 收益 | 代价 | 当前判断 |
|---|---|---|---|---|
| 主备双核 | A 服务，B 待命或升级；验证后交换 | 升级不中断、快速回退、模型简单 | 正常时部分算力闲置 | **推荐首版** |
| 多活双核 | A/B 同时处理不同会话或 Turn | 吞吐量更高、单实例故障影响小 | lease、幂等、版本偏差、双写和 split-brain 明显复杂 | 单用户阶段不采用 |
| 服务核＋候选核＋见证核 | A 服务，B 生成候选，C 独立评测 | 降低生成者自评和共同盲点 | 成本高，见证核本身也可能误判 | 后续可选；先用确定性 GraduationHarness |

多个 Kernel 只有在角色不对称、所有权唯一时提高稳定性。若它们都能自由回复用户、写 Memory、执行工具和决定谁是主核，故障面反而扩大。

## 4. 推荐升级时序

```text
初始：A = SERVING(N)，B = STANDBY

1. A 接收用户的迭代要求并固化 UpdateIntent
2. CandidateFactory 在独立位置构建 Kernel B(N+1)
3. B 使用影子状态启动，执行构建、回归、故障注入和流式 Turn 验证
4. B 通过 GraduationHarness 后进入 READY
5. GenerationManager 停止给 A 分配新 Turn，等待在途 Turn drain
6. 原子切换 serving generation 和 fencing epoch
7. B = SERVING(N+1)，A = STANDBY/ROLLBACK(N)
8. 观察窗口异常：立即禁止 B 新写入并切回 A
9. 观察通过：B commit；A 仍保留一个回退窗口
10. 下一轮由 B 生成 A(N+2)，角色反转
```

这比“父代生成子代后父代退出”更进一步：父代不只归档，而是成为下一轮可复用的安全槽位。生物式“繁衍”与工程式“蓝绿轮换”可以使用同一状态机表达。

## 5. 必须新增的两个不变量

### 5.1 每个 Turn 只有一个所有者

切换不能只改端口。每个 Turn 需要由 `TurnOwnership` 发放带 fencing epoch 的所有权：

```text
claim(conversationId, turnId, generationId) -> FencingToken
complete(FencingToken, result)
abandon(FencingToken, reason)
```

Message 落库、Memory 提升、关系状态变更、工具调用结果和 SSE 完成事件都必须携带或关联该 token。旧 Kernel 即使在网络延迟后恢复，也不能凭过期 token 继续提交。

### 5.2 外部副作用必须幂等或可判定

切换期间最危险的不是重复生成文本，而是重复发消息、下单、删除文件或调用设备。每次工具调用必须有稳定的 `operationId`；接管方先查询执行记录，不得因“未收到结果”直接重做不可逆操作。

流式输出还需遵守：已经向用户发送的 token 不由新 Kernel 从头重放。首版可让在途 Turn 固定由 A 完成，只把新 Turn 切给 B；只有 A 确认失效时，才将该 Turn 标记为中断并由产品层决定重试，而不是静默拼接两代输出。

## 6. 最小深 Module 与 Interface

不应把两个 Kernel 的调度细节泄漏到 Conversation、Memory 或 Channel。继续由 `GenerationManager` 隐藏角色、进程、切换和回退，并在内部增加两个深 Module：

```text
KernelFleet
  stage(CandidateArtifact) -> DeploymentId
  observe(DeploymentId) -> HealthReport
  promote(DeploymentId) -> PromotionResult
  drain(KernelInstanceId) -> DrainResult
  rollback() -> RollbackResult

TurnOwnership
  claim(TurnKey, KernelGenerationId) -> FencingToken
  complete(FencingToken, TurnCommit)
  abandon(FencingToken, FailureReason)
```

`KernelFleet` 是进程与版本轮换的 seam；本地进程、容器或远程节点只作为 Adapter。`TurnOwnership` 是正确性的 seam；首版可由同一个数据库事务实现，不需要引入分布式共识系统。

建议角色集合固定为：

```text
SERVING | STANDBY | CANDIDATE | DRAINING | QUARANTINED
```

`WITNESS` 只有引入第三个独立评测 Kernel 时再增加，避免先设计不会运行的抽象。

## 7. 多核能够解决什么

- Kernel 构建或启动失败时，稳定核继续服务；
- 候选行为退化时，可以切回上一代；
- 当前服务核损坏时，温备核可缩短恢复时间；
- 升级者和被升级者不再是同一活动进程；
- 两个不同代际并存，有利于发现版本特有回归；
- 整个 Kernel 均可替换，不局限于插件或外围代码。

## 8. 多核不能自动解决什么

- 共享数据库或状态仓库损坏；
- A/B 同时继承的错误需求、错误测试或共同代码缺陷；
- 不兼容 schema 已破坏旧代可读性；
- GenerationManager 自身错误；
- 操作系统、磁盘、凭据或外部模型同时不可用；
- 不可逆工具副作用已经发生。

两个相同副本主要提高可用性，不会自然提高判断正确性。要降低相关失效，需要保留不同代际、独立证据集、确定性协议检查、离线备份和延迟破坏性迁移，而不是简单再加一个会讨论的 Agent。

## 9. 为什么首版不应多活

当前是单用户系统，吞吐不是主要矛盾。多活要求至少处理：

- 同一 Conversation 的顺序一致性；
- 同一 Turn 的 leader lease 与 fencing；
- 重复 Message、重复 SSE 完成事件和重复工具副作用；
- N 与 N+1 对同一 Memory/schema 的不同解释；
- 网络分区下两个核心都以为自己是主核；
- 两个核心产生冲突计划时谁有最终决定权。

主备模式仍需要 TurnOwnership，但绝大部分时间只有一个写入者，测试和排错路径明显更短。若以后出现并发用户、多设备高吞吐或单核算力瓶颈，再把所有权从“全局 serving”细化到“按 Conversation 分片”，无需推翻身份与代际模型。

## 10. 对上一版方案的修正

[v02-self-hosting.md](./v02-self-hosting.md) 建议首版单 GenerationManager、stable/candidate/previous slot。多 Kernel 方向不推翻它，而是把 slot 变成可运行的角色：

```text
stable slot    -> SERVING Kernel
candidate slot -> CANDIDATE Kernel，可影子运行
previous slot  -> STANDBY Kernel，可保持温备或按需启动
```

物理上最少只需要两个运行槽：当前服务槽和非活动槽；`previous` 是逻辑角色，可复用刚刚退下来的服务槽，不要求第三个常驻进程。

GenerationManager 仍是唯一裁决者。若未来连它也要不停机自更新，再为 Manager 做 A/B launcher；不要让两个 Kernel 通过聊天投票选主，也不要在单机单用户阶段引入 Raft。

## 11. 推荐分阶段落地

### 阶段 1：冷备双槽

- 一个活动 Kernel；
- 一个不可变候选/回退 artifact；
- 切换时启动另一个进程；
- 单 GenerationManager；
- in-flight Turn drain；
- 原子指针与失败回退。

### 阶段 2：温备双核

- 备用 Kernel 常驻但无生产写权限；
- 定期 readiness 与最小影子验证；
- fencing token 与工具幂等完整接线；
- 更短的故障接管时间。

### 阶段 3：可选异构见证

- 独立评测模型或规则引擎检查候选；
- sealed cases 和历史故障回放；
- 见证只提供证据，不直接获得生产写权限。

### 阶段 4：有明确需求后才做多活

- 按 Conversation 分配所有权；
- 状态事件化、幂等消费和版本兼容窗口；
- 仅在多用户、吞吐或跨节点容灾收益足以覆盖复杂度时启动。

## 12. 当前建议确认项

建议把当前方向收敛为：

> 万年保持一个烟火身份和一份持久状态，使用两个可轮换的 Kernel 槽。任一时刻只有一个 Kernel 获得生产 Turn 与状态写入权；另一 Kernel 负责候选构建、影子验证、待命和回退。升级通过 drain、fencing epoch 和原子切换完成。首版不做多活、不做 Kernel 投票、不引入分布式共识；独立评测先由 GraduationHarness 承担，未来再按需要增加异构见证核。

该方案比“单核＋停止后替换”多出少量路由和所有权机制，却直接获得升级期间持续服务、快速回退和核心互助能力；同时仍保持 Kernel 整体可替换。
