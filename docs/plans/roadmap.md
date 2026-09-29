# 路线图 · 批次顺序

`status`: **canonical** — 2026-09-26（产品档 `v2`；实现周期 `2.x`）  
`owns`: 全仓唯一排期。勾选状态在 [实施清单](../guide/01-checklist.md)，不在本文件打勾。  
`versions`: [产品概览 §3](../product/01-overview.md) · [version-numbering.md](./version-numbering.md)

---

## v1 · 已封版

H1–H4 + `/chat/` 直接回答。无 Agent Loop。细节见清单「历史交付」。

---

## v2 · 单核 harness + 单核节点

一个烟火节点。不做世界树、不部署第二节点。

**实现周期（二级号）：`2.1`–`2.7`。** 括号内 `K0x` 为施工别名。旧三级号（`0.2.1`…）对照见 [version-numbering.md](./version-numbering.md)；归档目录名仍用旧号。

```text
2.1  Agent Loop + 错误码 + Turn 接线 + live/execute   （别名 K01）【已交付】
       施工单归档 → plans/archive/0.2.1-0.2.3/（k01-agent-loop.md）
       审计关闭 → reviews/04-reverify-0.2.1.md（快照 reviews/03-audit-0.2.1.md）
   ↓
2.2  ToolRuntime + ToolProfile（本节点可安全工具）   （别名 K02）【已交付 · 2026-09-22】
       施工单归档 → plans/archive/0.2.1-0.2.3/（k02-tools.md · k02-tool-impl-binding.md）
2.3  Memory + Relationship（成长核心）               （别名 K03）【已交付 · 2026-09-24】
       施工单归档 → plans/archive/0.2.1-0.2.3/（k03-memory.md 及 B/D/D+/R/L 子单）
       C schema → V010–V012 Review 账本 / generation / 水位
       设计 → research/memory-system-2.3.md §4 + §4.1
       附带：系统工具每工具上限 5、枚举可见、/chat/ 工具调用投影
2.4  统一行为账本加厚 → 提示词/Skill → 流式交付 → 完整会话系统 （别名 K04）【已交付 · 2026-09-25】
       施工单归档 → plans/archive/0.2.4/（含各阶段 draft）
       ├─ 2.4.1  行为账本加厚（MEMORY_WRITE / 与 Outbox 分工；turn_step 核心已由 2.3.6 提前）【已交付 · 2026-09-24】
       │            立项 → plans/archive/0.2.4/k04-behavior-journal.md
       │            施工单 → plans/archive/0.2.4/k04-a-behavior-journal-implementation.md
       ├─ 2.4.2  提示词治理 + Skill 最小（方案 C：骨架 + MD 分层 + 索引/load_skill）【已交付 · 2026-09-24】
       │            方向 → plans/archive/0.2.4/prompt-skill-direction-c.md
       │            施工单 → plans/archive/0.2.4/k04-p-prompt-skill-implementation.md
       ├─ 2.4.3  会话读写（列表/历史/生命周期/搜索 API）【已交付 · 2026-09-24】
       │            施工单 → plans/archive/0.2.4/k04-b-conversation-readwrite-implementation.md
       ├─ 2.4.4  异步执行 / 模型真流式 / SSE / Stop·撤队【已交付 · 2026-09-24】
       │            施工单 → plans/archive/0.2.4/k04-c-streaming-delivery-implementation.md
       ├─ 2.4.5  网页会话系统（侧栏/历史/SSE 流式 UI）【已交付 · 2026-09-24】
       │            施工单 → plans/archive/0.2.4/k04-d-chat-ui-implementation.md
       ├─ 2.4.6  followup/Stop/撤队 UI + 首轮 AI 标题【已交付 · 2026-09-24】
       │            施工单 → plans/archive/0.2.4/k04-e-followup-title-implementation.md
       ├─ 2.4.7  空会话清理 + LLM 自动归档【已交付】
       │            施工单 → plans/archive/0.2.4/k04-g-conversation-hygiene-implementation.md
       ├─ 2.4.8  流式 Markdown / 代码预览（对齐 DSH；逻辑与样式分轨）【已交付 · 2026-09-25】
       │            研究 → research/deepseek-harness-streaming-markdown.md
       │            施工单 → plans/archive/0.2.4/k04-m-streaming-markdown-implementation.md
       │            收口 → plans/archive/0.2.4/k04-m-draft/REVIEW.md
       └─ 2.4.9  窄测与真人验收（版本末段）【已交付 · 2026-09-25】
                    已审范围 → plans/archive/0.2.4/k04-conversation-system-draft.md
                    实施设计 → plans/archive/0.2.4/k04-conversation-system-design.md
                    收口 → plans/archive/0.2.4/k04-f-draft/REVIEW.md
2.5  Task / BackgroundTask + **用户向定时 Task**（WORLD_TICK 真调度仍不进；别名 K05）【已交付 · 历史】
       归档 → [archive/2.5/](./archive/2.5/README.md)
2.6  生命周期探针（含 WORLD_TICK / 世界侧调度相关，不对用户定时推）（别名 K06）【现行】
       活计划 → [k06-lifecycle-probe.md](./k06-lifecycle-probe.md)
2.7  故障、恢复与资源验收                             （别名 K07）
```

硬约束：

- **2.1** 已交付；**2.2** 已交付（含 ToolCalls continue 与次数打满单测回归）。
- **2.3** 已交付（Memory / Relationship / 召回 / Review / 运行日志；真人路径可记可搜）。
- **2.1** Loop 验收禁止 Fake；须 live 真实模型。
- **真人实机 / 产品路径**：默认 `mode=live`；本机能力以 harness `HostCapabilitySet` / 管理 API 自证为准，禁止 Agent shell 外挂探测冒充证据。见 [07-testing §1.1](../guide/07-testing.md)。
- **2.1** 只接 USER ingress；世界字段仅契约预留。
- **当前默认工作**：**2.6**（[k06-lifecycle-probe.md](./k06-lifecycle-probe.md)）；**2.5 历史归档**（[archive/2.5](./archive/2.5/README.md)）。**2.4（2.4.1–2.4.9）已交付**。
- **2.4.1 必须先于 2.4.3**：`turn_step` 核心由 **2.3.6** 先行；**2.4.1** 已加厚（MEMORY_WRITE 同事务 + 安全投影）。账本 = 事实；outbox = 交付；二者不互相冒充。
- **2.4.2** 已交付：提示词 MD + `load_skill`；不挡 2.4.3。
- **2.4.4～2.4.9** 均已交付（2026-09-24～25）。
- 未完成 **2.1–2.7** 不宣称 v2 完成。

后续施工单命名：`k0x-….md` 可保留别名；文首须写正式号 `2.x`。**2.1–2.3** 已迁入 [archive/0.2.1-0.2.3](./archive/0.2.1-0.2.3/README.md)。**2.5** 已迁入 [archive/2.5](./archive/2.5/README.md)。未建计划 = 不授权提前做。

---

## v3 · 世界树 + 多核节点

v2 跑通后再开。叙事 / 拓扑正文分别在研究稿，本表只定顺序。

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

宿主主备、Guardian、wn-agent、代际换代。讨论在 [research/history](../research/history/README.md)。**不是产品 v2。**

已并入 **2.4.2**（不再待排期）：

- **Skill 系统** + **提示词治理**：**方向 C** → [prompt-skill-direction-c.md](./archive/0.2.4/prompt-skill-direction-c.md)；施工 → [k04-p-prompt-skill-implementation.md](./archive/0.2.4/k04-p-prompt-skill-implementation.md)。
