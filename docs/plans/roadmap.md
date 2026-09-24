# 路线图 · 批次顺序

`status`: **canonical** — 2026-09-22  
`owns`: 全仓唯一排期。勾选状态在 [实施清单](../guide/01-checklist.md)，不在本文件打勾。  
`versions`: [产品概览 §3](../product/01-overview.md)

---

## v0.1 · 已封版

H1–H4 + `/chat/` 直接回答。无 Agent Loop。细节见清单「历史交付」。

---

## v0.2 · 单核 harness + 单核节点

一个烟火节点。不做世界树、不部署第二节点。

**正式小版本号：`0.2.1`–`0.2.7`。** 括号内 `K0x` 为施工别名（文件名 / 旧对照仍可用）。

```text
0.2.1  Agent Loop + 错误码 + Turn 接线 + live/execute   （别名 K01）【已交付】
       施工单 → plans/k01-agent-loop.md
       审计关闭 → reviews/04-reverify-0.2.1.md（快照 reviews/03-audit-0.2.1.md）
   ↓
0.2.2  ToolRuntime + ToolProfile（本节点可安全工具）   （别名 K02）【已交付 · 2026-09-22】
       施工单 → plans/k02-tools.md（Role×Facet×Host 绑定）
       重构计划 → plans/k02-tool-impl-binding.md（能力标签求交 · 多 id · **已实施**）
0.2.3  Memory + Relationship（成长核心）               （别名 K03）【已交付 · 2026-09-24】
       施工单 → plans/k03-memory.md（含 importance / 衰减召回 / 弱 B / claim 规范化）
       B 收口 → plans/k03-b-hotpath-impl.md
       C schema → V010–V012 Review 账本 / generation / 水位
       D/D+ → 召回 / 弱 B / HTTP / search_memory
       L → 运行日志 / turn_step 窄版；plans/k03-l-run-journal.md
       设计 → research/memory-system-0.2.3.md §4 + §4.1
       附带：系统工具每工具上限 5、枚举可见、/chat/ 工具调用投影
0.2.4  统一行为账本加厚 → Outbox / SSE → `/chat/` 历史恢复 （别名 K04）【下一默认工作】
       ├─ 0.2.4-A  行为账本加厚（MEMORY_WRITE / 与 Outbox 分工；turn_step 核心已由 0.2.3-L 提前）
       │            立项 → plans/k04-behavior-journal.md
       └─ 0.2.4-B  Outbox / SSE + 历史自动恢复（依赖 A；分享时才投用户）
0.2.5  Task / BackgroundTask（可留 WORLD_TICK 类型名，不实现世界树）（别名 K05）
0.2.6  生命周期探针                                     （别名 K06）
0.2.7  故障、恢复与资源验收                             （别名 K07）
```

硬约束：

- **0.2.1** 已交付；**0.2.2** 已交付（含 ToolCalls continue 与次数打满单测回归）。
- **0.2.3** 已交付（Memory / Relationship / 召回 / Review / 运行日志；真人路径可记可搜）。
- **0.2.1** Loop 验收禁止 Fake；须 live 真实模型。
- **真人实机 / 产品路径**：默认 `mode=live`；本机能力以 harness `HostCapabilitySet` / 管理 API 自证为准，禁止 Agent shell 外挂探测冒充证据。见 [07-testing §1.1](../guide/07-testing.md)。
- **0.2.1** 只接 USER ingress；世界字段仅契约预留。
- **当前默认工作**：**0.2.4-A** 行为账本加厚（MEMORY_WRITE / Outbox 分工），再进入 **0.2.4-B**。
- **0.2.4-A 必须先于 0.2.4-B**：`turn_step` 核心由 **0.2.3-L** 先行；0.2.4-A 加厚后方可宣称账本与 Outbox 分工完备。账本 = 事实；outbox = 交付；二者不互相冒充。
- `/chat/` 已能投影本回合工具名/时间/参数；**会话历史恢复与 SSE** 仍属 **0.2.4-B**。
- 未完成 **0.2.1–0.2.7** 不宣称 v0.2 完成。

后续施工单命名：`k02-tools.md` 等可保留；文首须写正式号 `0.2.x`。未建计划 = 不授权提前做。

---

## v0.3 · 世界树 + 多核节点

v0.2 跑通后再开。叙事 / 拓扑正文分别在研究稿，本表只定顺序。

```text
N01  设备 / 节点 / 角色注册与绑定；一份数据
     至少验收：同机双节点（烟火+世界树）或等价跨机
     正文 → research/multi-node-companion-world.md
N02  世界树 → 烟火 事件通道（可强制沉默）
W01  世界时钟 + 事件存储
W02  世界树日计划（轻量 / 独立模型）
W03  WORLD Turn → 烟火可选 Outbox 分享
W04  JevDecisionAdapter（可选 DLC）
W05  GameProfile 工具目录（若需要）
```

硬约束：

- 世界树不对用户说话。
- 绑定是配置，不定死哪台机器。
- 不在本档做宿主主备 / Guardian / wn-agent。

---

## 其后

宿主主备、Guardian、wn-agent、代际换代。讨论在 [research/history](../research/history/README.md)。**不是产品 v0.2。**
