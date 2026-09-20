# 调研 · 微信通道实现 & 世界树 vs 用户对话时序冲突

`status`: **reference** — 2026-09-20  
`purpose`:（1）其他 Agent 如何接微信；（2）世界树事件与用户聊天时间撞车时怎么排。  
`related`: [in-flight-user-message](./in-flight-user-message.md) · [world-evolution](./world-evolution-and-extensions.md) · [multi-node](./multi-node-companion-world.md) · [roadmap v0.3](../plans/roadmap.md)

**档位提醒：** 微信通道与世界树均属 **v0.3 及更后 / 通道接入时**；本文定语义与借鉴，不授权 v0.2 开工接微信或跑世界树。

---

## 1. 其他 Agent 怎么接微信

### 1.1 共同架构（几乎所有正经实现）

```text
微信传输层（iLink / 企微回调 / 客服 / 桌面自动化）
        │  归一成统一入站消息
        ▼
Gateway / Channel 适配器（登录、去重、媒体、回执、typing）
        │  交给「选中的 Agent」
        ▼
Agent Loop / Session（与通道无关）
        │
        ▼
Outbound 再经同一适配器发回微信
```

**要点：核心不写微信 SDK；微信是插件式 Channel。** 与 wannian「Channel SPI + 一份会话真相」同构。

### 1.2 主流路径对照

| 方案 | 传输 | 登录 / 部署 | 群聊 | 与核心关系 | 对 wannian 的启示 |
|------|------|-------------|------|------------|-------------------|
| **OpenClaw + `@tencent-weixin/openclaw-weixin`** | 腾讯 iLink；插件侧 monitor | `plugins install` + QR `channels login`；凭据在 `~/.openclaw` | 插件能力声明以私聊为主 | **核无微信码**；插件管 iLink/媒体/账号 | 最干净：wn-server 只收归一化 ingress |
| **Hermes Weixin adapter** | 同系 iLink；**HTTP 长轮询**（无需公网 webhook） | `hermes gateway setup` 扫码；token **单实例锁** | 默认关；iLink bot 身份常收不到普通群事件 | 内置 messaging 适配器；有 **文本防抖合并**、typing、去重、context_token | 个人号友好；**防抖**可借（连发碎句合成一轮） |
| **扣子托管 OpenClaw 微信** | ClawBot 私密通道 | 扫码绑定；微信 ≥8.0.70 | 不支持群；不自动化操作微信 | 托管侧接好通道 | 产品形态：一人一 bot，非客服矩阵 |
| **OpenClaw China / 企微系** | 企微长连接、自建应用、**微信客服 webhook** | 需 corpId/Secret、公网回调（客服） | 视产品线 | 面向中国 IM 的渠道包 | 若「对外任意微信用户」走客服，不走个人 iLink |
| **wxauto 类桌面自动化** | 本机操控微信客户端 | 昵称/群监听；易掉线 | 可做群 | 绕过官方 bot API | **个人项目慎用**：风控、稳定性差；wannian 不推荐作主路径 |
| **HermesClaw 代理** | 独占一个 iLink poller，再本地代理给 Hermes/OpenClaw/OpenCode | 解决「一号不能双 gateway」 | — | 多脑抢同一微信令牌会 403 | 证明：**传输层单写者**；wannian 也应一账号一 poller，多 Agent 在 Owner 内排队而非多进程抢 iLink |

### 1.3 Hermes 细节（值得抄的通道行为）

一手文档：[Hermes Weixin](https://hermes-agent.nousresearch.com/docs/user-guide/messaging/weixin)

| 机制 | 做什么 | wannian 映射 |
|------|--------|--------------|
| Long-poll `getupdates` | 无公网回调也能收消息 | Channel 适配器部署在能出站的机器即可 |
| Token lock | 同 token 只许一个 gateway | 禁止双 poller；多端用户话进 **同一 Owner 队列** |
| `text_batch_delay_seconds` | 短时间内多条碎句合并成一次 agent 调用 | Channel 层 debounce → 一次 `receive` 或一条用户 Message |
| Dedup 5 min | 按消息 ID 去重 | 与 `clientRequestId` 互补（通道原生 ID → 映射幂等键） |
| Typing | 处理中显示「对方正在输入」 | 通道能力；**不**等于队列 UI |
| `context_token` 落盘 | 重启后仍能回同一 peer | Outbox/通道会话态；与 Turn 库分开 |
| Allowlist | 谁可私聊 bot | 单用户产品可极简：只绑 Owner |
| Home channel | cron/通知落到指定 chat | 世界树**分享**若走微信，投到绑定 peer，不是世界树直发 |

OpenClaw 官方微信说明：[docs.openclaw.ai/channels/wechat](https://docs.openclaw.ai/channels/wechat) —— 同样强调 **core channel-agnostic + 外部插件**。

### 1.4 对 wannian 微信通道的建议形状（未开工）

```text
wn-server（Session Owner）
  · Turn / Loop / Outbox / 同一 conversation
app.channel.weixin（或独立 sidecar 只做传输）
  · iLink long-poll 或企微/客服回调
  · 归一化 → POST 内部 receive（带 channelMessageId → clientRequestId）
  · 订阅 Outbox / 完成事件 → send 回微信
  · 可选：debounce、typing、短回执「先记着」
禁止
  · kernel 依赖微信 SDK
  · 微信进程内再跑第二套 Loop
  · 多 poller 抢同一 iLink token
```

插话语义仍见 [in-flight §6.5.4](./in-flight-user-message.md)：不拒收、FIFO、IM 默认可静默排队。

---

## 2. 「世界树的对话」和「用户的对话」到底在抢什么

### 2.1 先澄清：不是两路微信抢麦

按已定叙事（[world-evolution](./world-evolution-and-extensions.md)）：

```text
世界树 ──事件──► 烟火（TurnSource=WORLD）──可选分享──► Outbox ──► 用户通道（微信/网页…）
用户   ──消息──► 烟火（TurnSource=USER） ──回复──────────► Outbox ──► 同一通道
```

- 世界树 **不对用户说话**，也 **不占用户通道的「对话角色」**。  
- 用户眼里若出现「早安」，那是 **烟火分享**，不是世界树。  
- 因此冲突 **不是**「世界树微信 vs 用户微信」，而是：

| 冲突面 | 含义 |
|--------|------|
| **C1 烟火算力 / 单 RUNNING** | WORLD Turn 与 USER Turn 都要跑烟火 Loop，同会话不能双跑 |
| **C2 用户注意力 / 出站顺序** | 分享气泡与用户问答回复都进同一 peer 的 Outbox |
| **C3 上下文语义** | 用户正在聊 A，世界事件硬插分享会显得答非所问 |

### 2.2 开源对照（生活模拟类）

| 项目 | 做法 | 启示 |
|------|------|------|
| **Tanya** | 心跳多数 **沉默**；不是每拍都烦用户 | 世界事件默认可不分享 → 大幅减少 C2/C3 |
| **Pulse** | Heartbeat 可选联系 / 沉默 / 日记；`/quiet` | 用户可关主动；冲突时优先安静 |
| **Hermes cron** | 定时结果推到 **home channel** | 主动出站有固定投递点，仍应经会话策略 |
| **theNPC director** | 世界导演与角色分引擎 | 世界树与烟火分角色，与本仓一致 |

没有项目把「世界引擎」和「用户私聊」做成两个并行对用户说话的脑而不打架；普遍是 **一个对用户出口 + 内部事件可积压/可静默**。

---

## 3. 推荐冲突策略（v0.3 语义草案）

### 3.1 总规则

```text
1. 世界事件 → 永远先变成「给烟火的 WORLD 入站」（可落 world_event / Turn RECEIVED），不直达微信。
2. 烟火对用户通道：同一 conversation（或同一 Owner 绑定会话）仍然单 RUNNING + FIFO（与用户插话专题一致）。
3. USER 优先于 WORLD 的「可延后工作」：
   · 用户正在聊 / 队列里已有 USER Turn → WORLD 的「分享」延后或改沉默。
   · WORLD 的「只经历、不分享」仍可在缝隙执行（写记忆），尽量不堵用户。
4. 多数世界心跳默认沉默（Tanya/Pulse）→ 冲突本来就少。
```

### 3.2 时序场景

**场景 U：用户 A 正在 RUNNING，世界树到点**

```text
USER-A: RUNNING
WORLD-E: 事件到达 → 入队为 WORLD Turn（RECEIVED）或先写入 world_event 待调度
推荐：
  · 不打断 USER-A
  · USER-A 结束后再考虑 WORLD
  · 若决策为「分享」且此时又有新 USER 在队 → 分享再延后，或本轮强制沉默只记记忆
```

**场景 W：WORLD 正在跑（评估是否分享），用户突然发话**

```text
WORLD-W: RUNNING（可能即将 Outbox 分享）
USER-B: receive → RECEIVED
推荐：
  · 不拒收 USER-B
  · 若尚未 COMMITTING：可 cancel WORLD-W 的「分享路径」或让其以沉默结束（经历仍可记）
  · 优先 claim USER-B（用户优先）
  · 禁止：WORLD 分享与 USER 回复双 RUNNING
```

**场景 S：WORLD 决定分享，Outbox 已写入，用户同时在打字**

```text
分享消息已 commit → 按 Outbox 序投递（可到微信）
用户消息按 FIFO 在其后的 Turn 处理
用户可能看到「先收到一句早安，再收到对上一问的回答」——可接受；
若要更顺：分享策略在「会话空闲」才 deliverToUser=true（空闲=无非终态 USER Turn）
```

### 3.3 优先级表（建议写进 v0.3）

| 优先级 | 工作 | 说明 |
|--------|------|------|
| P0 | USER Turn 执行与提交 | 真人说话优先 |
| P1 | USER 已排队的后续句 | FIFO |
| P2 | WORLD「仅内化」（沉默+记忆） | 不占用户注意力；可在 USER 空档跑 |
| P3 | WORLD「分享」Outbox | 仅当会话空闲（无待处理 USER）且 DecisionPort 允许 |
| — | 世界树直发用户 | **永久禁止** |

### 3.4 与微信通道叠加时

```text
世界分享 ──Outbox──► weixin adapter ──► 用户微信
用户消息 ──weixin──► receive(USER) ──► 烟火
```

- 两边都经 **同一 Outbox/同一 peer**，顺序由 commit 序保证。  
- Hermes 的 typing 只表示「烟火在处理」，不区分 WORLD/USER。  
- 世界分享 **不要**另开第二条 iLink 连接；仍单 poller。

### 3.5 和「生成中新话」专题的关系

| 专题 | 管什么 |
|------|--------|
| [in-flight](./in-flight-user-message.md) | 同为 USER（或同通道）连发时的 FIFO |
| 本文 §3 | USER vs WORLD 两种 **TurnSource** 的优先级与沉默 |

两者叠加：队列里可以同时有 `USER, USER, WORLD, USER`；调度器按 **优先级+FIFO** 取下一项，而不是纯时间戳不顾来源。

---

## 4. wannian 落地映射

| 能力 | 最早档 | 动作 |
|------|--------|------|
| `TurnSource` 预留 WORLD | 0.2.1 契约缝 | 已规划；不实现 |
| 用户 FIFO / 不拒收 | 0.2.1–0.2.4 | 插话专题 |
| 微信 Channel | 通道接入时（非 v0.2 必达） | 插件式；iLink 或企微/客服择一主路径 |
| 世界树 + 冲突策略 | **v0.3** | WorldAgent 只投事件；烟火调度 §3 |
| Jev / DecisionPort | v0.3 可选 | 默认不分享，进一步减少冲突 |

---

## 5. 结论（直接回答）

**微信：** 业界主流是「Gateway 插件 + 官方 iLink/企微/客服」，核心无微信码；个人号注意单 token、防抖、去重；不要用桌面 hook 当主路径；多 Agent 不要多进程抢微信，应在 Owner 内统一排队。

**世界树 vs 用户：** 不是两路对用户对话冲突，而是烟火一个脑上的 **USER Turn vs WORLD Turn**。处理方式：世界永不直达用户；用户优先；世界默认多沉默；分享仅在会话空闲时走 Outbox；与用户插话同一套单 RUNNING，加上来源优先级。

---

## 6. 来源

- [OpenClaw WeChat](https://docs.openclaw.ai/channels/wechat)  
- [Hermes Weixin](https://hermes-agent.nousresearch.com/docs/user-guide/messaging/weixin)  
- [扣子 · OpenClaw 微信](https://docs.coze.cn/tutorial_openclaw_wechat)  
- [OpenClaw China](https://github.com/hxhlb/openclaw-china) / wecom-kf  
- [HermesClaw](https://github.com/AaronWong1999/hermesclaw)（一号多脑代理）  
- 本仓：[world-evolution](./world-evolution-and-extensions.md)、[multi-node](./multi-node-companion-world.md)、[in-flight](./in-flight-user-message.md)
