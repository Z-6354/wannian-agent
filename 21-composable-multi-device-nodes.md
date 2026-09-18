# 21 · 可组合多设备节点讨论稿

`date`: 2026-09-17  
`status`: **brainstorm-draft** — 讨论单体独立运行、多个节点组合及多设备部署；不表示方案已确认，不授权实施。

## 1. 结论

可以，而且三核 A/B/C 不应被限制为同一进程或同一机器内的固定槽。更一般的抽象是：

> 每个 `WannianNode` 都是可独立启动、对话、保存状态和恢复的最小个体；多个节点加入同一个 `CompanionCluster` 后，共享同一个烟火身份，并根据能力和租约动态承担服务、演化、审核、存储或设备工具角色。

```text
单独时：
WannianNode A → 独立烟火运行实例

组合时：
CompanionIdentity（烟火，唯一）
└─ CompanionCluster
   ├─ Node A：云服务器
   ├─ Node B：个人电脑
   ├─ Node C：手机
   └─ Node D：家庭设备
```

组合增加能力和容灾，不创建多个烟火人格。

## 2. 两种“独立个体”必须区分

### 推荐：独立运行节点

节点具有完整最小运行能力，可以离开集群单独启动；加入集群后仍代表同一个 `CompanionIdentity`。它们的差异是计算位置、设备能力和当前角色，不是不同人格。

### 不推荐自动合并：独立成长的身份

如果 A、B 离线数月，各自拥有不同 Owner 关系、长期记忆、价值判断和对话历史，它们已经是两个身份分支。重新连接时不能简单宣称无冲突合并，需要专门的身份分叉和历史裁决模型。

MVP 应支持“节点离合”，不支持“多个独立人格自动融合”。

## 3. 单节点最小结构

```text
WannianNode
├─ NodeSupervisor
├─ KernelRuntime
├─ LocalStateStore
├─ GenerationCache
├─ ChannelAdapter
├─ DeviceToolAdapters
├─ SyncEngine
└─ NodeIdentity / credentials
```

### 独立运行时必须具备

- 启动并处理基本对话；
- 保持 SSE 流式输出；
- 保存 Conversation、Message 和本地事件；
- 使用该设备允许的工具；
- 检测 Kernel 故障并重启；
- 保存已认证稳定代；
- 导出诊断、状态和待同步事件；
- 在无法联系其他节点时进入明确的 standalone/offline 策略。

单节点可以独立生活，但按当前产品决策只提供普通 Agent 能力，不生成或晋升自身代码候选。它等待第二节点加入后获得高可用恢复能力，等待第三节点加入后才开放自主迭代。

## 4. 组合时不改变 Kernel Interface

Kernel 不应知道同伴位于本机还是远程设备。节点通过能力声明加入：

```text
NodeDescriptor
  nodeId
  companionIdentityId
  runtimeVersion
  certifiedGenerations[]
  capabilities[]
  resources
  trustLevel
  connectivity
  failureDomain
```

典型能力：

```text
CHAT_SERVING
EVOLUTION_BUILD
AUDIT_EXECUTION
GPU_INFERENCE
LONG_TERM_STORAGE
DESKTOP_CONTROL
MOBILE_SENSORS
CAMERA
MICROPHONE
NOTIFICATION
```

调度器依据能力、健康、负载、信任等级和故障域分配角色。新增设备只增加新的 Node Adapter 和能力，不修改 Conversation、Memory 或自主演化流程。

## 5. 三核环在多设备上的映射

```text
电脑 A：SERVING_AUDITOR
云端 B：STANDBY_EVOLVER
手机 C：STANDBY_TARGET
```

也可以按资源调整：

```text
云端 A：SERVING
高性能电脑 B：EVOLVER
另一设备 C：AUDITOR/TARGET
```

角色属于租约，不属于设备类型。手机资源不足时可以只承担入口、传感器或通知角色，而不强迫它完成大型构建。

## 6. 组合后的统一与本地性

### 集群统一

- `CompanionIdentity`；
- 已提交 Conversation/Message 历史；
- 长期 Memory 和关系状态；
- Kernel 代际谱系；
- 当前 serving epoch；
- 已确认工具副作用账本；
- 安全和隐私策略。

### 设备本地

- 摄像头、麦克风、桌面控制等设备工具；
- 临时缓存和未上传媒体；
- 硬件状态与资源指标；
- 本地凭据句柄；
- 尚未同步的 outbox 事件；
- 仅允许留在设备上的隐私数据。

集群共享的是事实和身份，不应复制设备密钥或让所有节点自动获得所有工具权限。

## 7. 状态权威：不能所有设备离线双写

多个设备同时在线时，可以通过 lease 和 fencing 保证同一 Conversation/Turn 只有一个写入者。真正困难的是网络断开：如果 A、B 都允许离线修改同一份长期状态，再连接时不可能总是自动合并正确。

建议把数据分为三类：

| 数据 | 离线策略 |
|---|---|
| 可追加事实 | 写入本地 outbox，联网后去重合并 |
| 可交换/可合并数据 | 使用版本向量、集合并集或明确 merge 规则 |
| 强一致语义状态 | 只有持有 authority lease 的节点可最终提交 |

强一致状态包括：

- 当前生产 Kernel 和 serving epoch；
- 同一 Turn 的最终结果；
- 关系阶段和关键长期记忆裁决；
- 不可逆工具副作用；
- 代际 promote/rollback；
- 删除、撤销和权限状态。

离线节点可以记录用户输入和提出候选解释，但不能在没有权威租约时静默改写这些最终状态。

## 8. 推荐离线模式

```text
ONLINE_AUTHORITY
  完整对话、状态提交和工具能力

OFFLINE_DEGRADED
  本地对话、读取最近快照、追加 outbox
  禁止关键状态裁决和高风险远程副作用

REJOINING
  校验身份、同步事件、检测冲突
  在完成前不参与生产选举

ONLINE_REPLICA
  完成追赶，可领取新的角色租约
```

离线期间的回复应明确记录其依据的 `stateRevision`。重新联网后，SyncEngine 可以合并追加事件；语义冲突进入 Memory/关系冲突裁决，不假装它们从未分叉。

## 9. 多设备故障切换

若 A/B/C 位于不同故障域：

```text
设备 A 故障
→ 协调存储使 A 的 lease 过期
→ B 取得更高 fencing epoch
→ 入口定位 B
→ C 修复 A 或准备替代节点
→ A 恢复后先进入 REJOINING
→ 追赶状态并通过验证后成为 ONLINE_REPLICA
```

A 恢复时不能依据本地旧状态宣布自己仍是服务核。所有生产提交必须由权威存储校验 epoch。

单机可以由 GenerationManager 和事务数据库完成；跨设备需要一个具备 lease/CAS 的协调位置。MVP 可使用固定 Home Node 或云端协调存储，不必自行实现 Raft。

## 10. 组合与拆分

### 加入集群

1. 节点生成独立 NodeIdentity；
2. 用户将其绑定到现有 CompanionIdentity；
3. 双方进行认证和密钥交换；
4. 同步最小身份契约、代际 manifest 和状态快照；
5. 节点声明能力和隐私限制；
6. 完成兼容性检查后进入 `ONLINE_REPLICA`；
7. 调度器才可向其授予角色。

### 离开集群

1. 停止授予新 Turn 和工具任务；
2. drain 在途任务；
3. 上传 outbox 和操作账本；
4. 撤销角色租约和集群凭据；
5. 根据用户选择保留只读快照、独立运行副本或清除集群数据。

如果用户选择“独立运行副本”，应生成新的身份分支标识；以后重新合并不能被当作普通副本同步。

## 11. 自主演化如何利用多设备

```text
Node A 继续服务
Node B 使用高性能环境生成候选
Node C 在不同设备/系统上审核
Node D 执行兼容性或故障注入
```

收益包括：

- 构建负载不影响对话设备；
- 不同操作系统发现环境相关缺陷；
- 候选和审核位于不同故障域；
- 某设备离线时其他节点继续运行；
- 升级可以逐节点滚动，而不是同时替换全部核心。

推广顺序应是：先更新一个非服务节点，完成审核，再切换 serving lease，最后逐个重建其余节点。禁止一次更新全部设备后才发现共同故障。

## 12. 安全与信任

能够加入集群不等于获得所有能力：

- 每个节点使用独立凭据，可单独撤销；
- 节点间通信双向认证和加密；
- artifact 通过 hash/signature 校验；
- 工具能力按节点和用途授权；
- 敏感原始数据可保持本地，只同步摘要或引用；
- EVOLVER 生成的代码不能直接获得生产密钥；
- AUDITOR 使用只读证据和隔离执行环境；
- 丢失设备撤销凭据后不能继续同步或领取 lease。

## 13. MVP 收敛建议

MVP 不需要同时完成任意网络拓扑和完全离线合并。建议：

```text
运行单元：WannianNode，可独立启动
身份模型：一个 CompanionIdentity 可绑定多个 Node
协调方式：一个固定 Home Node / Coordinator
一致性：每个 Turn 单写；关键状态由 Home Node 裁决
离线能力：最近快照读取 + 本地 outbox
三核部署：可同机三个 Node，也可分布到三台设备
节点发现：显式配对，不做开放式自动发现
升级：逐节点候选、审核、切换、回退
```

后续再增加协调器高可用、复杂离线合并、跨地域副本和按 Conversation 分片。

## 14. 推荐定义

> 万年的基本部署单元是可独立运行的 `WannianNode`。节点独处时以最近已认证状态提供降级或完整服务；多个节点绑定同一个 `CompanionIdentity` 后组成 `CompanionCluster`，通过能力声明和互斥租约组合为更强的烟火。身份与长期历史只有一条权威提交链，节点可以离合，角色可以轮换，设备能力保持本地，离线分叉必须显式同步和裁决。

该模型把本地三核和多设备部署统一成同一个架构：A/B/C 只是三个节点实例，区别只在所处设备、能力、故障域和当前租约。
