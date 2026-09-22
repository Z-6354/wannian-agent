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
0.2.2  ToolRuntime + ToolProfile（本节点可安全工具）   （别名 K02）【下一步】
0.2.3  Memory + Relationship（成长核心 · 高优先级）   （别名 K03）
       边界 → research/memory.md
0.2.4  Outbox / SSE + `/chat/` 历史自动恢复（分享时才投用户） （别名 K04）
0.2.5  Task / BackgroundTask（可留 WORLD_TICK 类型名，不实现世界树）（别名 K05）
0.2.6  生命周期探针                                     （别名 K06）
0.2.7  故障、恢复与资源验收                             （别名 K07）
```

硬约束：

- **0.2.1** 已交付；开工 **0.2.2** 前须保留会话公平锁与 `AgentBudgetGate`；ToolCalls `continue` 后强制回归次数打满。
- **0.2.1** Loop 验收禁止 Fake；须 live 真实模型。
- **0.2.1** 只接 USER ingress；世界字段仅契约预留。
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
