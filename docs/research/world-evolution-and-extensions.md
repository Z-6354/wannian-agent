# 调研 · 世界演进、工具画像、成长记忆与 Jev DLC

`status`: **reference** — 2026-09-20（澄清：世界只喂事件给烟火；烟火像跟朋友分享一样**可选**开口）  
`purpose`: 世界叙事与开源证据。工具与成长记忆在 **v0.2** 单核 harness 里做实；世界树与多核在 **v0.3**。v0.2 只预留类型，不实现世界路径。  
`not-authority`: 版本以 [产品概览 §3](../product/01-overview.md) 为准。排期见 [roadmap](../plans/roadmap.md)。  
`owns`: 世界叙事、工具画像、Jev、开源证据。拓扑见 [multi-node](./multi-node-companion-world.md)。

---

## 0. 交互模型（已澄清 · 产品叙事）

烟火**平时就是普通 Agent**：用户不说话，她**不会**自己烦用户。

世界侧是**另一个外部 Agent**：做好定时任务，到点只把**事件信息发给烟火**（不是发给用户）。烟火经历这件事之后，**自己决定**要不要跟用户讲一句——像「刚经历了一件事，想不想跟朋友分享」。

```text
  ┌─ 外部 WorldAgent ─────────────────────┐
  │  排日程 / 到点触发                      │
  │  只产出：给烟火的事件（起床、考试、下雨…）│
  │  ❌ 不对用户说话、不推用户通道            │
  └──────────────────┬────────────────────┘
                     │ 「你经历了：六点该起床了」
                     ▼
  ┌─ 烟火（主 Agent · 默认被动）────────────┐
  │  收到世界事件（WORLD Turn）              │
  │  可选：写进记忆 / 改心情                  │
  │  再决定：                                │
  │    · 分享给用户 → 生成短句 → Outbox 投递  │
  │    · 不分享 → 安静经历，用户侧无消息      │
  │  ❌ 没有世界事件时，不主动骚扰用户         │
  └─────────────────────────────────────────┘
```

类比：闹钟/班主任（世界）只通知学生「该起床了」；学生（烟火）可以跟好友说「我起床啦」，也可以一声不吭先洗漱。

---

## 1. 产品意图（用户已述）

| 意图 | 要点 |
|------|------|
| 工具 | 多数 Turn **0 次工具**，但**有一定占比**：识图 MCP、定时任务、日后微信/QQ/应用 API、游戏内工具集（与普通对话工具集**不同**） |
| 成长 | **可成长是核心**；学习与记忆系统是关键，不是装饰 |
| Jev DLC | TypeSafe **System One** 决策模型（非文本生成）；**可加可不加**——不加系统仍完整可跑，加上则换效果（更快门控/路由/评分） |
| 世界演进 | 外部世界 Agent 定时向**烟火**投递事件；烟火**可选**像跟朋友分享一样开口；烟火默认不主动私信；需双模型（世界规划 ≠ 烟火对话）+ 可选 Jev 帮烟火做「说不说」；主 Agent 后实现，须预留框架 |

多核心只写在 [multi-node-companion-world.md](./multi-node-companion-world.md)。排期只写在 [roadmap.md](../plans/roadmap.md)。世界树只向烟火投事件；烟火可选分享给用户（§0）。

---
## 2. 开源与本机对照

### 2.1 世界日程 + 主动私信（最贴近）

| 项目 | 证据 | 可借 | 勿整搬 |
|------|------|------|--------|
| [Yuralume](https://github.com/Yuralume/yuralume-core) | README：LLM 规划日程；活动留下情绪残渣与情节记忆；**三门主动私信**（启发式 → LLM 意图 → LLM 决断）+ 日上限 + 通道 opt-in；社交 feed | 日程→记忆→主动三门；「有自己的一天」叙事 | BSL 许可；全栈陪伴平台过重 |
| [Pulse](https://github.com/elliejayliquid/pulse) | Heartbeat 周期「自由思考」：联系用户 / 沉默 / 日记 / 自订日程（含 `daily 8:00`）；`/quiet` 关主动 | Heartbeat + 自调度 + 静默权 | 偏 Telegram daemon，非 Session-Owner |
| [Tanya](https://github.com/opxiahub/tanya) | 60min heartbeat；后台 job：起床/白天/发现/夜间反思；多数心跳沉默 | 「生活模拟 ≠ 每拍都烦用户」 | Telegram 单通道产品 |
| [Sage Core](https://github.com/sinxisterrr/sage-core) | Heartbeat、午夜/正午反思、时区、后台写记忆 | 概率心跳 + 记忆蒸馏对照 | Discord 产品耦合 |
| [Conscious-Pebble](https://github.com/Ghost-13lade/Conscious-Pebble) | Open loops 主动跟进；4AM Dream 巩固记忆；自然语言提醒 | Dream/巩固 ↔ 本仓 Memory Job | 本地伴侣框架，非云 Owner |

### 2.2 世界 / NPC 引擎（世界侧）

| 项目 | 证据 | 可借 | 勿整搬 |
|------|------|------|--------|
| [theNPC](https://github.com/Rainytroy/theNPC) | `director_engine` 世界事件；NPC 日程/关系/反思；图像可空仍跑核心 | **世界导演 vs 角色** 分引擎 | 漫画/游戏向；非单用户烟火 |
| [Agent-Worlds](https://github.com/hjl2004-10/Agent-Worlds) | 自主游荡、碰撞社交、MCP/技能、多 LLM | 多模型/MCP；世界持续运转 | 像素多人世界，过重 |
| [ai-npc-world](https://github.com/ugonfor/ai-npc-world) | 现实时区作息；不在场世界仍转；关系无数值条 | 「世界不因用户离线停」 | 浏览器村子 + Firebase |
| [OpenGameAgent](https://github.com/EricSun0218/OpenGameAgent) | C#；游戏时间轴、多 NPC、工具权威在游戏侧 | **游戏工具集与对话工具集分离**；引擎权威状态 | 游戏运行时，非 wn-server 主脑 |

### 2.3 调度 / 主动投递（本机可借管道）

| 来源 | 路径 / 文档 | 可借 |
|------|-------------|------|
| OpenClaw | `docs/automation/cron-jobs.md`、`docs/gateway/heartbeat.md`；Teams proactive conversation store | 持久 cron 唤醒 Agent、主动投递 |
| Hermes | `cron/scheduler.py`、daily-briefing 指南 | 定时 → 调研 → 推送到 home channel |
| DSH | `schedule_create` / follow-up inject | 会话内定时回注 |
| wannian | BackgroundTask / Outbox（合同已有缝） | **正式投递必须经 Outbox**，禁止 Loop 直推 |

### 2.4 Jev（System One）DLC

| 来源 | 证据 |
|------|------|
| 官方 | [TypeSafe 介绍](https://typesafe.ai/blog/introducing-system-one-models-and-jev)：结构化决策 + 概率，**不生成文本** |
| LangChain | [Building a Harness with Jev](https://www.langchain.com/blog/building-a-harness-with-jev)：与 LLM 分工——Jev 分类/门控，LLM 生成 |
| 本仓 | `typesafe-probe/`：`POST .../v1/systemone`，模型 `jev-latest`；已有 Choice/Score/Noul 用例 |

**DLC 定义（本项目）：**

```text
未配置 TYPESAFE_API_KEY / 未启用 JevAdapter
  → DecisionPort 回落到「规则默认」或「偶发 LLM 小调用」或「静默保守」
  → 主对话、记忆、Turn、世界骨架仍全部可用

启用 JevAdapter
  → 同一 DecisionPort 走 Jev：烟火侧「说不说」、工具风险门、路由、评分等
  → 效果更换（更快、更稳、更便宜），契约形状不变
```

### 2.5 成长与记忆（已有本仓研究，开源仅对照）

本仓 [memory.md](./memory.md) 已定：烟火身份、Facet、敏感分级、冲突策略、Memory Job 草案。开源侧 Yuralume/Sage/Pebble 的「日程→情节记忆→蒸馏」可作 **K03/世界线** 对照，不覆盖已确认边界。

**本机结论：** 无现成「世界只喂角色、角色再决定是否分享」的完整产品；属绿野。调度管道可借 OpenClaw/Hermes；生活叙事可借 Yuralume/Pulse；世界导演可借 theNPC；「多数心跳沉默」对照 Tanya；游戏工具边界可借 OpenGameAgent；Jev 已有 probe。

---

## 3. 目标架构（主 Agent 之后实现，现在预留）

```text
                    ┌─ WorldAgent（外部 · 可用独立模型）──────────┐
                    │  角色卡 → 日计划 / 事件点 / cron              │
                    │  到点只产出：给烟火的 WorldEvent               │
                    │  ❌ 不写用户通道、不替烟火说话                 │
                    └──────────────────┬───────────────────────────┘
                                       │ 事件投递给烟火
                                       ▼
┌─ TurnEngine（TurnSource=WORLD）──────────────────────────────────┐
│  Assembler：人设 + 关系 + 记忆 + 「刚发生的世界事件」             │
│  DecisionPort?（Jev DLC · 烟火侧）：要不要跟用户分享？            │
│        │ 沉默 → Outcome 无用户投递（可仍写记忆）                  │
│        │ 分享 → AgentLoop 生成短句                               │
│  AgentLoop（烟火 · 主对话模型）                                   │
│  Commit；若分享 → Outbox → 用户通道                               │
└──────────────────────────────────────────────────────────────────┘

用户主动发话：TurnSource=USER（与现在一样；无世界事件也不开口）
```

**不变量**

1. **默认被动**：无 WORLD 事件、无用户消息时，烟火不对用户发信。  
2. **世界只喂烟火**：WorldAgent **永不**直达用户通道。  
3. **分享权在烟火**：是否 Outbox 到用户，由烟火侧决策（LLM 和/或 DecisionPort），不是世界定时器直接推文案。  
4. 分享出去的主动消息 = 合法 Turn + Outbox，不是 Loop 偷偷 `send`。  
5. 即使选择沉默，世界事件仍可进入记忆（「经历了」≠「说出去了」）。  
6. 游戏桥 / ToolProfile / Jev 缺席规则同前。

**Outcome 语义（WORLD Turn 建议）**

```text
FinalResponse + deliverToUser=true   → 像跟朋友分享；用户可见
FinalResponse + deliverToUser=false  → 或 ControlledSilence / InternalOnly
                                       → 用户无新消息；可写记忆/关系
Cancelled / ControlledFailure        → 同现有语义
```

（类型名可调；K01 只需预留「可不出站」的结果形状，不必实现世界路径。）

---

## 4. 主 Agent 必须提前预留的框架（K01–K05）

下列在 **主 Agent 批次写入契约/空缝**，实现可 stub；**禁止**等世界线上线再挖接口。

| 预留点 | 放哪 | K01 最小动作 | 日后填什么 |
|--------|------|--------------|------------|
| `TurnSource` / `IngressKind` | api + Turn | 枚举：`USER`（必做）；预留 `WORLD` / `SCHEDULE` / `GAME` / `SYSTEM` | 世界事件入站（仍进烟火，不进用户） |
| `ToolCatalog` / `ToolProfileId` | AgentInput + ToolRuntime | Input 带 `toolDescriptors`；预留 `profileId` | 聊天 / MCP / 游戏 多目录 |
| `DecisionPort` | kernel | 接口 + `NoopDecisionPort`（默认保守=不分享） | Jev：烟火「说不说」 |
| `AgentOutcome` 可静默 | agent | 注释/占位：允许无用户投递的封闭结果 | WORLD Turn 沉默路径 |
| `WorldEvent` 类型 | api / kernel.world 空包 | 仅类型 +「未实现」 | 外部 Agent 投递载荷 |
| Outbox | 已有 | 注释：仅当烟火选择分享时投递用户 | Channel deliver |
| Memory 来源 | Memory 契约 | 预留 `WORLD_EVENT`；分享与否可分标记 | 经历 vs 已分享 |
| Companion 角色卡 | Companion | 作息/角色字段可空 | 「高三学生」等 |
| BackgroundTask | Task | 预留 `WORLD_TICK`（世界调度，不是对用户推送） | 外部 Agent 定时 |
| 多模型绑定 | manage | 注释 chat / world / decision 可分 | 世界模型 ≠ 烟火模型 ≠ Jev |

**K01 明确不做：** WorldAgent、日计划、WORLD ingress 接线、对用户主动投递产品路径、Jev 强制依赖、游戏桥。

---
## 5. 工具画像（修正「多数 0 次」表述）

| 画像 | 示例工具 | 可见时机 |
|------|----------|----------|
| `chat.default` | 少而稳：时间、只读查询… | 普通陪伴问答 |
| `chat.mcp` | 识图等 MCP | 用户附件 / 显式视觉意图 |
| `schedule` | 定时任务 CRUD | 用户约定提醒；世界线也可写任务 |
| `im.bridge` | 微信/QQ（后） | 通道授权后；高危 + 确认 |
| `app.api` | 应用 API | 按连接器启用 |
| `game.*` | 游戏内动作 | 仅 Game 会话 / 节点；与 chat 目录互斥或严格子集 |

Loop **不**知道微信还是游戏；只看见当前 `toolDescriptors`。换目录是 Assembler / TurnEngine / 通道策略的事。

预算：有工具的 Turn 仍受 `maxModelDecisions` 约束；识图等可占 1–2 步，不默认放大到「无限 ReAct」。

---

## 6. 成长与记忆（核心）

| 原则 | 落位 |
|------|------|
| 可成长优先于堆工具 | Relationship + Memory 在 Context 主路径；工具是增强 |
| 世界经历也是成长 | 世界事件 → 可选记忆；主动私信 Turn 可沉淀「今天对用户说了早安」 |
| 学习 | Memory Job / 冲突 / 敏感分级按已有 research；世界线增加「日程残渣→情节」管道（对照 Yuralume） |
| 主 Agent | K03 必须按关键路径排期，不能长期 stub 假装陪伴已完成 |

---

## 7. Jev DLC 细则

| 项 | 规定 |
|----|------|
| 接口 | `DecisionPort.evaluate(state, questions) → answers+confidence` |
| 默认实现 | `Noop` / `Heuristic`（无外网、无 Key） |
| DLC 实现 | `JevDecisionAdapter`（`typesafe-probe` 同端点） |
| 典型用法 | **烟火**是否把世界经历分享给用户；工具危险分级；路由 |
| 失败 | 超时/无 Key → 等同未启用；默认偏向**不分享**（保守） |
| 非用法 | 不生成用户可见正文；不替代 ModelPort；**不是**世界 Agent 用来「决定吵用户」 |

---

## 8. 世界演进最小故事（验收草案 · 后置）

```text
1. 配置烟火角色卡：高三学生，时区 Asia/Shanghai
2. 外部 WorldAgent 生成事件点：06:00「该起床了」（只发给烟火）
3. 06:00 → WORLD ingress → TurnEngine 认领（用户通道尚无动静）
4. Assembler：人设 + 世界事件「你该起床了」
5. 烟火侧 DecisionPort / Loop：
     · 选择沉默 → 可记记忆「早上起床了」；用户收件箱为空
     · 选择分享 → 生成「早上好，我要起床了」→ Outbox → 用户看到
6. 用户若回复 → 普通 USER Turn（烟火仍是被动应答）
```

默认：没有世界事件时，烟火**不会**自己找用户聊天。

---

## 9. 已吸收进活计划的更正

步骤与批次只维护 [roadmap.md](../plans/roadmap.md)。这里只留被纠正过的语义。

| 旧说法 | 现行 |
|--------|------|
| 「默认 0 工具」 | 常 0 工具，工具路径一等公民；`ToolProfile` |
| 世界直接推用户 | 世界只喂烟火；烟火可选分享 |
| 烟火会主动骚扰 | 默认被动；仅处理世界事件后才可能开口 |
| 记忆可后拖 | K03 是成长核心 |
| 无 Jev | `DecisionPort` DLC；默认保守 = 不分享 |
| K05 定时推用户 | 预留 `WORLD_TICK`，喂的是烟火 |

---
## 10. 证据索引

- Yuralume：https://github.com/Yuralume/yuralume-core  
- Pulse：https://github.com/elliejayliquid/pulse  
- Tanya：https://github.com/opxiahub/tanya  
- Sage Core：https://github.com/sinxisterrr/sage-core  
- Conscious-Pebble：https://github.com/Ghost-13lade/Conscious-Pebble  
- theNPC：https://github.com/Rainytroy/theNPC  
- Agent-Worlds：https://github.com/hjl2004-10/Agent-Worlds  
- ai-npc-world：https://github.com/ugonfor/ai-npc-world  
- OpenGameAgent：https://github.com/EricSun0218/OpenGameAgent  
- Jev：https://typesafe.ai/blog/introducing-system-one-models-and-jev · https://www.langchain.com/blog/building-a-harness-with-jev  
- 本仓：`typesafe-probe/`；OpenClaw cron/heartbeat；Hermes cron；[history/v02-multi-kernel.md](./history/v02-multi-kernel.md)
