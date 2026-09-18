# 19 · 三核环形继任讨论稿

`date`: 2026-09-17  
`status`: **brainstorm-draft** — 记录三核环形升级方向、正确性约束和降级条件；不表示已完成最终定案，不授权实施。

## 1. 核心结论

用户提出的循环成立：

```text
A 服务并审核，B 生成 C′，C′ 接班
C′ 服务并审核，A 生成 B′，B′ 接班
B′ 服务并审核，C′ 生成 A′，A′ 接班
```

三个物理 Kernel 槽没有永久主从关系，具有相同 Interface 和相同角色资格。每一轮动态持有三种互斥角色：

```text
SERVING_AUDITOR  当前服务用户，并审核候选
EVOLVER          读取父代和目标，生成候选 artifact
TARGET           只承载候选，验证通过后成为下一服务核
```

轮换规则为：

```text
旧 TARGET  -> 新 SERVING_AUDITOR
旧 SERVING -> 新 EVOLVER
旧 EVOLVER -> 新 TARGET
```

这实现的是核心资格平等和角色循环，不是三个核心同时拥有生产写入权。

## 2. 第一轮完整时序

初始状态：

```text
A = SERVING_AUDITOR，运行 Generation N
B = EVOLVER
C = TARGET
```

流程：

```text
1. A 根据用户需求或运行证据冻结 UpdateIntent
2. A/GenerationManager 冻结本轮 GraduationPlan 与 sealed cases
3. B 读取 A 的 ParentSnapshot，在独立工作区生成 Generation N+1
4. N+1 被打包为不可变 CandidateArtifact，部署到 C 槽
5. C 只以 CANDIDATE 身份启动，没有生产写入权
6. 独立 GraduationHarness 执行确定性验证
7. A 对差异、目标满足度和未知任务进行智能审核
8. A 停止领取新 Turn，并完成或明确中止在途流式输出
9. GenerationManager 增加 fencing epoch，将新 Turn 授予 C
10. C = SERVING_AUDITOR，A 保持上一稳定代，B 停止本轮工作
11. C 经过 probation window；失败则切回 A
12. 通过后 commit，下一轮 A = EVOLVER，B = TARGET
```

## 3. “B 升级 C”的准确含义

B 不能编辑 C 正在运行的目录，也不能把补丁直接写入 C 的活动进程。正确语义是：

```text
B 生成新的不可变 CandidateArtifact
→ GenerationManager 校验 hash 和 manifest
→ 将 artifact 安装到当前非活动的 C 槽
→ 以候选权限启动 C
```

因此 A/B/C 是可复用运行槽，Generation N/N+1 是不可变代码代际。槽可以被下一轮复用，已经提交或仍处于回退窗口的 artifact 不能被原地覆盖。

## 4. 服务与审核可以由 A 同时承担吗

可以，但应拆成两部分：

### A 负责智能审核

- 用户意图是否真正实现；
- 修改范围是否合理；
- 是否通过删除能力或绕过测试取得表面成功；
- 架构是否产生不必要复杂度；
- 候选对自身改动的预测是否与实测一致。

### GraduationHarness 负责确定性审核

- 干净构建和 artifact 完整性；
- 产品不变量和回归测试；
- SSE 流式输出；
- 状态 schema 兼容；
- crash/restart/rollback；
- 工具幂等和重复提交防护；
- sealed cases 未被候选改写。

A 可以做最终语义审核，但不能自行降低确定性门槛。这样即使 A 忙碌、判断偏差或与 B 共享模型盲点，候选仍不能绕过机械不变量。

## 5. A 接班后能否立即去升级 B

应先区分“开始准备”和“允许下一次生产切换”：

- C 刚接班时，A 的 Generation N artifact 必须保持不可变，以便回退；
- A 的进程可以作为 EVOLVER，在独立工作区为 B 生成候选，不等于改写 A 自己；
- C 的 probation 未通过前，下一轮候选可以准备，但不能再次切换生产；
- 若 C 回退，取消或重新基于恢复后的稳定代评估下一轮候选；
- 只有 C commit 后，B′ 才能进入正式毕业与接管流程。

这允许流水线准备，同时避免连续切换导致回退谱系不清。

## 6. 角色状态不等于进程名称

建议记录：

```text
KernelSlot
  slotId                  // A | B | C，仅表示部署位置
  installedGenerationId
  runtimeInstanceId?
  roleLease?
  health

RoleLease
  role                    // SERVING_AUDITOR | EVOLVER | TARGET
  holderSlotId
  epoch
  expiresAt
```

业务代码不应出现 `if A then serve` 或 `B upgrades C`。实现只认识角色租约：哪个槽当前持有 `SERVING_AUDITOR`，哪个槽持有 `EVOLVER`，哪个槽是下一 `TARGET`。

## 7. 切换不变量

1. 任一时刻最多一个有效 `SERVING` lease；
2. 任一 Turn 只接受当前 fencing epoch 的提交；
3. `EVOLVER` 无权写生产 Conversation、Memory、关系和工具结果；
4. `TARGET` 在 promote 前只能使用影子状态；
5. 构建者不能单独批准自己的候选；
6. 候选不能修改本轮 GraduationPlan、sealed cases 和审核日志；
7. 原服务 artifact 在 probation 结束前不可删除或覆盖；
8. probation 期间不得执行下一次 production promotion；
9. 外部工具副作用必须带稳定 operationId；
10. 所有角色变化必须由 GenerationManager 原子记录。

## 8. 故障处理

| 故障 | 处理 |
|---|---|
| B 生成失败 | A 继续服务；清理或隔离候选；本轮不切换 |
| C 启动或审核失败 | A 继续服务；C 进入 QUARANTINED |
| A 在审核期间崩溃 | 由上一稳定恢复路径重启 A；禁止 C 自动接管 |
| C 接管后观察失败 | fencing epoch 前移，回到不可变 A |
| GraduationHarness 不可用 | 暂停毕业；不能用 A 的主观判断替代 |
| 任一槽数据损坏 | 保留服务，暂停自主迭代并重建非服务槽 |
| 少于三个健康槽 | 允许维持服务；默认暂停自主升级 |

三核退化为双核时理论上仍可更新，但 MVP 为了守住生成、审核和目标隔离，建议健康核心不足三个时只服务、不自主晋升。

## 9. 三核并非多数投票

三核环形继任不使用 2/3 多数票：

- B 提供候选和实施证据；
- GraduationHarness 提供确定性结果；
- A 提供语义审核；
- GenerationManager 根据冻结规则执行或拒绝 promote。

三个核心来自相同代码、模型或训练分布时，其判断错误可能高度相关。增加票数不会自然形成独立故障域；角色隔离、不可变证据和可回退切换才是稳定性来源。

## 10. 推荐的 MVP 定义

```text
部署：三个同构 Kernel 槽 + 一个非智能 GenerationManager
角色：SERVING_AUDITOR / EVOLVER / TARGET
规则：角色环形轮换，生产写入保持单主
审核：服务核语义审核 + 独立 GraduationHarness 硬门槛
切换：drain + fencing epoch + atomic promote
恢复：上一稳定代在 probation 结束前保持不可变
降级：不足三核时继续对话，但暂停自主晋升
```

该结构比固定主备更对称，也让“一个核心工作、一个核心改造第三个核心、第三个核心接班”成为稳定的代际循环。其本质仍是一个烟火身份和一条生产状态历史，而不是三个烟火互相争夺控制权。
