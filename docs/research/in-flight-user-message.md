# 专题 · 生成中新话（In-flight user message）

`status`: **decided** — 2026-09-20  
`purpose`: 执行中新输入的会话调度策略。  
`decision`: **followup（FIFO）**；不拒收；不做默认 Steer；不用 Jev 做调度。  
`authority`: 本决策已定；实施写入 [0.2.1 施工单](../plans/k01-agent-loop.md) 与 [内核合同](../decisions/01-contract.md)。细节论证仍供查阅。  
`related`: [agent-loop-survey](./agent-loop-survey.md) · [wannian-loop-modules §2.8](./wannian-loop-modules.md) · [缺陷 §6.1](../reviews/01-defects.md) · [Turn 状态机](../guide/04-kernel-reference.md) · OpenClaw `followup` 模式

---

## 0. 已定策略（2026-09-20）

**本质：** 流程正在执行时又有新输入 → 先入队，不并行第二 run；默认不硬打断。

**烟火（日常情感 Agent）选用 OpenClaw 所称的 `followup`：**

```text
不拒收
  + followup（FIFO）：当前 Turn/Run 整轮终态后再跑下一条
  + 同会话单 RUNNING（单 flight）
  + 显式「停止」才 cancel 当前 RUNNING（新话不自动打断、不 Steer）
  + 队列 = 库内非终态 Turn（通常 RECEIVED）；可单独取消排队项
  + 不做默认 Steer；不用 Jev / DecisionPort 裁决排队 vs 打断
  + Jev 仍只用于「说不说 / 门控」等（世界分享等），与调度无关
```

**对照业界：** 等于 Cursor Queue / Codex Tab / OpenClaw `messages.queue.mode=followup`；**不是** OpenClaw 默认的 `steer`。情感场景刻意选 followup。

**通道：** 网页可画队列；微信/游戏同样入库排队，表现不同而已（见 §6.5）。

---

## 0b. 一句话（机制）

**「生成中新话」是两层决策：**

1. **接收层**：新话要不要立刻落成用户消息事实？（已定：要，不拒收）  
2. **执行层**：与当前 flight 的关系？（已定：followup，不 Steer）

另已钉死：**COMMITTING 后不可普通取消**；**同键重试是幂等，不是插话**。
---

## 1. 现象与触发面

### 1.1 用户眼里看到的

```text
烟火正在回复（或正在调工具）……
用户又想说一句：「不对，我是说……」/「顺便再问……」/「停一下」
```

常见动机：

| 动机 | 期望 | 典型产品对应 |
|------|------|--------------|
| 说错了改口 | 立刻停，按新意思答 | ChatGPT Stop → 再发 |
| 补充约束 | 最好让当前轮「听见」补充 | Steer / soft interrupt |
| 预写下一步 | 等这轮做完再自动做下一件 | FIFO 队列（Codex Tab、Cursor Queue） |
| 双端同时打字 | 两句都别丢，顺序可解释 | 双端 FIFO |
| 误触连发 | 别开两条并行脑 | 单 flight |

### 1.2 技术上何时真的会发生

| 触发面 | 今日 wannian（v0.1） | 0.2.1+ Loop | 0.2.4 SSE 异步页 |
|--------|----------------------|-------------|------------------|
| 单页同步 HTTP | 少见：请求未返回前浏览器通常发不出第二帖 | 仍可能被长耗时卡住；双标签可并发 POST | 常见：流式中可再点发送 |
| 双标签 / 手机+网页 | 已可能两条 `receive` | 同左 | 同左 |
| Bot / IM 通道 | 通道可连收 | 同左 | 同左 |
| 「停止」按钮 | 无产品路径 | `cancelToken` 可接 | UI 显式 Stop |

**结论：** 哪怕 0.2.1 的 `/chat/` 仍同步，**服务端协议也必须先定义同会话插话规则**，否则双端与日后 SSE 会各写各的。

---

## 2. 先排除：什么不是「生成中新话」

| 场景 | 处理 | 是否本专题 |
|------|------|------------|
| 同一 `clientRequestId` + 同正文再 POST | 幂等回放已完成结果；不增模型调用 | **否**（T1 / R01） |
| 同一 key + **异正文** | Conflict；不写第二事实 | **否** |
| 跨会话同 key | Conflict | **否** |
| 网络丢响应后客户端重试同 key | 回放 | **否** |
| A 已 COMPLETED，用户再发新 key | 普通下一回合 | **否**（正常串行） |
| A 在 COMMITTING，进程崩溃后恢复提交 | 冻结计划提交，不重跑 Loop | **否**（恢复专题） |
| 后台 Task 跑着，用户继续聊天 | 0.2.5：长任务不占聊 | **相邻**（见 §8） |

本专题只讨论：

```text
会话内已有非终态 Turn A（尤其 CLAIMED / RUNNING），
同时又出现「新」用户意图 Turn B（新 clientRequestId）。
```

---

## 3. 两层模型（专题核心）

```text
┌─────────────────────────────────────────────────────────┐
│  Channel / HTTP / 页面                                    │
│    POST turns(B)                                         │
└───────────────────────────┬─────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────┐
│  接收层 Receive                                           │
│    · 幂等？冲突？                                         │
│    · 会话忙时：拒收 busy / 收下排队 / 强制 cancel-A？      │
│    · 输出：用户 Message + Turn B 行（常为 RECEIVED）        │
└───────────────────────────┬─────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────┐
│  执行层 Execute（同会话调度器 + TurnEngine + AgentLoop）   │
│    · 同会话最多一个 RUNNING                                │
│    · A 跑完 / 取消后才 claim B，或在 A 缝里 Steer          │
│    · cancelToken 与 beginCommit 的 CAS                    │
└─────────────────────────────────────────────────────────┘
```

**关键分离：**

- 「用户消息有没有进库」≠「模型有没有开始答这句」。  
- Claude Code 的坑大多出在：**UI 以为排队成功，执行层没消费** → 接收与执行不同步。  
- wannian 应用 Turn 状态机把两边钉在库里：`RECEIVED` = 已收未跑；`RUNNING` = 正在跑。

---

## 4. 与状态机的交界

```text
RECEIVED → CLAIMED → RUNNING → COMMITTING → COMPLETED
                ↘︎ cancel → CANCELLED   （仅 COMMITTING 前）
                ↘︎ fail   → FAILED
```

| A 的状态 | B 能否 receive（产品可选） | B 能否立刻 claim | 能否 cancel A |
|----------|---------------------------|------------------|---------------|
| RECEIVED（尚未执行） | 通常可 | 否（应先 A） | 可 |
| CLAIMED / RUNNING | 可或 busy | **否**（单 flight） | **可**（与 beginCommit 竞态） |
| COMMITTING | 可或 busy | 否 | **否**（普通取消） |
| COMPLETED / FAILED / CANCELLED | 可 | 可（下一 flight） | 不适用 |

不变量（已定）：

1. 同一 Turn 不能有两个有效 `executionId`。  
2. 取消与 `freezeCommit` 在持久化 CAS 上**只能一个胜**。  
3. 合法 COMMITTING 后：只提交冻结计划，不因新话打回 RUNNING。  
4. B 的上下文只应看见**已提交**历史；不得把 A 内存里的半截助手句当正式事实喂给 B（除非产品明确做「停止后保留可见草稿」——那是另一决策）。

---

## 5. 四种产品策略（详细）

下面四种都假设：**永不双 RUNNING**。差异只在 receive 与调度。

### 5.1 策略 Busy：生成中拒收

```text
A: RUNNING
B: receive → Rejected(TURN_BUSY)   // 用户消息不落库（或仅提示）
UI: 发送键禁用 / 「等这轮说完」
```

| 优点 | 缺点 |
|------|------|
| 实现最简单；语义清晰 | 双端/弱网时用户话可能「发出去失败」；要靠客户端重试 |
| 接近 ChatGPT「一次一条」 | 长回答期间无法「先记下下一句」 |

**适用：** 单端内嵌页早期；想强制用户先 Stop。

### 5.2 策略 FIFO：收下排队（Post-turn queue）

```text
A: RUNNING
B: receive → Accepted，Turn B = RECEIVED，用户消息已落库
调度：A 到达终态后，按创建顺序 claim 下一个 RECEIVED
B 的 ContextAssembler：包含已 COMPLETED 的 A（若 A 成功），不含 A 半成品
```

时序：

```text
t0  receive(A) → claim(A) → Loop(A) …
t1  receive(B) → B 入库 RECEIVED（A 仍 RUNNING）
t2  A → COMMITTING → COMPLETED（助手句正式落库）
t3  claim(B) → Loop(B)  // 上下文已含 A 的正式回复
```

| 优点 | 缺点 |
|------|------|
| 不丢话；双端友好 | 需要会话级「执行队列 / 锁」 |
| 对齐 Codex Queue、Cursor Queue | 若用户其实想改口，会「先听完错的再答新的」 |
| 队列状态可用 Turn 行表达（可见） | 队列过长要上限（拒收或合并策略） |

**业界对照：** Cursor Queue；Codex `Tab` 显式排队。  
**坑教训（Claude）：** 队列必须**持久可见**；要能「只删队列项、不打断当前 A」。

### 5.3 策略 Stop-then-send：硬打断后再发

```text
A: RUNNING
用户点「停止」→ cancel(A) → CANCELLED（若尚未 COMMITTING）
然后 receive(B) → 正常执行 B
```

或合并手势：「发送即停止上一轮」（**不推荐作默认**，Cursor 用户会当成队列坏了）。

| 优点 | 缺点 |
|------|------|
| 改口心智清晰 | 半截工具可能已副作用（UNKNOWN 语义） |
| 接近 ChatGPT | 必须保证 Stop = **服务端真取消**，否则 busy 幽灵 |

**与 COMMITTING：** 若 A 已冻结，Stop 只能失败或变成「等提交完再发」；UI 要诚实。

取消粒度（Agents SDK 启发）：

| 粒度 | 含义 | wannian |
|------|------|---------|
| 立即停 | 打断当前 `decide` / 流式 | `cancelToken` 在 decide 前检查 → `Cancelled` |
| 本步后停 | 跑完当前工具再停 | 可后做 |
| 本 turn 后停 | 等同「不要排队下一句」 | 产品少用 |

### 5.4 策略 Steer：边界软打断（中途改道）

```text
A: RUNNING（Loop 内多轮 decide ↔ tools）
B: 已 receive，进入「steering 缓冲」
Loop 在工具结束后 / 下次 decide 前 drain steering：
  把 B 的用户文本注入上下文，继续同一 Turn A 或重定向
```

| 优点 | 缺点 |
|------|------|
| 长任务中补充约束很爽（编码 Agent） | 半截助手输出、工具观察、关系 revision 难解释 |
| Cursor Steer / L5 `_drain_steering` | 陪伴场景「改口」常被理解成打断，不是改道 |
| | 实现重；易焊进 Loop（Hermes 反面） |

**wannian 建议：** 明确 **不做进 0.2.1**；若日后再做，Steer 必须是 Loop **外**模块，经明确缝注入，且默认关闭。

---

## 6. 完整时序图（FIFO + 可选 Stop）

推荐陪伴默认组合：**FIFO 收下 + 显式 Stop（可取消当前 RUNNING）+ 不做静默 Steer**。

```text
用户A ──receive──► TurnA RECEIVED ──claim──► RUNNING ──Loop──┐
                                                              │
用户B ──receive──► TurnB RECEIVED（排队）                      │
     （A 仍 RUNNING；B 不 claim）                               │
                                                              ▼
                         A FinalResponse → freeze → COMMITTING → COMPLETED
                                                              │
                         调度器取下一 RECEIVED ────────────────┘
                                              claim(B) → RUNNING → …
```

改口路径：

```text
A RUNNING
用户点停止 → cancel(A) 与 beginCommit 竞态
  · cancel 胜 → A CANCELLED；半截不写正式助手句（或写「已停止」系统可见态——产品另定）
  · commit 胜 → A 仍会完成；UI 提示「来不及停」
然后 receive(B) 或释放已排队的 B
```

---

## 6.5 分通道适配：内核一种策略，外壳不同表情

**原则：插话策略在 Session Owner（wn-server）里只有一套；Channel 只决定「怎么告诉用户」和「怎么表达停止」。**

产品已定：大脑一份会话真相；网页 / 微信 / 游戏都是 Channel，**不**各自再实现一套排队脑。  
因此 A 在 Loop 里跑时，任意通道再来的用户话都走同一套：

```text
任意 Channel
  → 统一 receive（不拒收）→ Turn B = RECEIVED（进同一 conversation 队列）
  → 同会话单 RUNNING；A 终态后再 claim B
  → 正式回复经该 Channel 的 Outbox/投递回用户
```

网页能画队列，不代表微信/游戏要「没有队列」——**队列在库里**；缺的只是可见控件。

### 6.5.1 对照表

| | 内嵌网页 `/chat/` | 微信 / QQ 等 IM | 游戏接口 / 游戏内聊 |
|--|-------------------|-----------------|---------------------|
| **receive** | Accepted，可显示「已排队」 | Accepted；**不**因生成中拒收回调 | Accepted；API 返回 `queued` + `turnId` + 可选 `position` |
| **执行** | 同会话 FIFO | 同左（可与网页共用同一会话） | 同左（该 Game 会话或绑定的陪伴会话） |
| **队列可见性** | UI 列表 / 「还有 N 句」 | **通常不可见**；靠事后按序回复体现 | 由游戏 HUD 决定是否展示；协议层必有结构化状态 |
| **停止 / 改口** | 「停止」按钮 → cancel API | 见下：指令词或等排到再说 | `POST .../cancel` 或游戏 UI 按钮调同一 API |
| **即时回执** | 可选 toast「已记下」 | **强烈建议**短回执（通道允许时） | 返回 JSON 即可，不必再推一条 NPC 台词 |
| **ToolProfile** | `chat.default` 等 | 日后 `im.bridge` | `game.*`（与 chat 目录隔离） |
| **Loop** | 不知道通道 | 不知道通道 | 不知道通道 |

### 6.5.2 A 正在循环时，用户又发 B —— 统一内核时序

```text
t0  任意通道 receive(A) → claim → Loop（decide ↔ tools …）
t1  另一通道或同通道 receive(B) → B 落库 RECEIVED（A 仍 RUNNING）
t2  A → COMMITTING → COMPLETED → 经「A 的来源通道或会话默认通道」投递回复
t3  claim(B) → Loop(B) → 投递 B 的回复
```

跨通道例子：网页开了 A，微信又发了 B → **同一 conversation 队列里 B 排在 A 后**；不是两个脑并行。这正是「一份数据」的价值。

### 6.5.3 网页

- 适合做：**队列条、停止、撤掉某条未跑排队**。  
- HTTP 可同步等 A（v0.1），或 0.2.4 起 SSE 推 A 完成再自动拉 B 的结果。  
- 表现可以花，**语义仍等于库里的 RECEIVED 列表**。

### 6.5.4 微信 / IM（没有队列 UI 时）

IM 做不到「侧边排队列表」，但策略不变：

1. **照样收下**进同一会话 Turn 队列（不拒收）。  
2. **静默排队是合法默认**：用户连发两句 → 烟火先答完第一句，再答第二句（两条独立助手回复，顺序 FIFO）。多数用户能理解「回得慢一点」。  
3. **可选短回执**（通道策略，非 Loop）：若 IM 允许「非最终回复」或系统提示，可在 B 入队时推一句极短话，例如「先记着，说完这句再回你」——**这不是第二轮模型循环**，是 Channel 适配器的回执模板，避免用户以为消息丢了。  
4. **停止在 IM 上更难**：  
   - **推荐 MVP**：不提供可靠「停止」；改口 = 再发一句排在后面（或等 A 说完）。  
   - **进阶**：通道识别少量显式指令（如「停」「算了」）→ 调同一 `cancel`，**不**当普通聊天 Turn 进 FIFO；指令词表属 Channel 策略，可关。  
   - **不要**把任意新消息当成自动打断（与网页默认一致）。  
5. 微信侧**不要**为了「像有队列」去拒收或丢消息。

### 6.5.5 游戏接口

游戏往往**有**协议、**不一定有**聊天式队列 UI：

1. **入站**：`receive` 成功返回结构化结果，例如  
   `{ "result":"accepted", "turnId":"…", "execution":"queued"|"started", "queuePosition":2 }`  
   游戏客户端用这个更新 HUD；**不要**用 HTTP 409 busy 当产品语义。  
2. **出站**：A/B 完成事件经游戏约定的回调 / 轮询 / WS；顺序与库内 commit 序一致。  
3. **停止**：游戏 UI 有按钮就调 cancel API；没有就与 IM 一样靠后续消息排队。  
4. **ToolProfile `game.*`**：只影响工具目录，**不**另搞一套插话状态机。  
5. 游戏权威状态仍在游戏侧（OpenGameAgent 对照）；wannian 只保证「这句用户话何时被烟火处理」的 FIFO。

### 6.5.6 通道差异只允许动这些旋钮

| 允许按通道配置 | 禁止按通道分叉 |
|----------------|----------------|
| 入队是否发短回执文案 | 拒收 / 双 RUNNING / 默认 Steer |
| 是否启用「停」指令词 | 另建第二套会话库 |
| 回执语言 / 是否显示 queuePosition | Loop 内写微信 SDK / 游戏 SDK |
| 投递失败重试（Outbox） | 网页排队、微信丢、游戏另算 |

### 6.5.7 分阶段（通道视角）

| 阶段 | 网页 | 微信 / 游戏 |
|------|------|-------------|
| **0.2.1** | 串行 + cancel 能力（可先测夹具） | 协议层已 FIFO；通道未接也不改内核 |
| **0.2.4** | 队列 UI + Stop + SSE | — |
| **通道接入时** | — | IM：默认静默 FIFO + 可选回执；游戏：结构化 `queued`；指令 cancel 可选 |

---

## 7. 边界与失败模式（专题必测）

### 7.1 与提交屏障

| 竞态 | 必须结果 |
|------|----------|
| cancel vs `freezeCommit` | 仅一方 `Saved`；另一方冲突；三表/计划一致 |
| B 在 A 的 COMMITTING 期间 receive | 允许排队；**禁止** cancel A |
| A COMPLETED 后 B 才 claim | B 上下文含 A 助手句 |

### 7.2 与工具（0.2.2）

| 情况 | 处理 |
|------|------|
| A 工具已派出、结果未归 | cancel 不保证撤销外部副作用；观察可审计；不越权写正式记忆 |
| FIFO：B 等 A 终态 | B 不看见未提交的 tool observation |

### 7.3 与记忆 / 关系（0.2.3）

| 情况 | 处理 |
|------|------|
| A 取消 | 不写基于半截回复的 `ApprovedChange` |
| A 完成再 B | B 的 recall 可含 A 已提交记忆 |
| Steer（若未来启用） | 禁止在未提交助手句上抽长期记忆 |

### 7.4 双端 / 双标签 / 跨通道

| 情况 | FIFO 下的结果 |
|------|----------------|
| 端1 发 A，端2 发 B（含网页+微信） | 两次 receive 都成功；**同一会话**执行按库内顺序 |
| 两端同 key | 幂等或冲突（已有规则） |
| 端1 Stop，端2 仍显示「生成中」 | 0.2.4 SSE / IM 终态事件对齐；此前至少以库为准 |
| IM 无队列 UI | 仍入库排队；靠有序回复或短回执，不拒收 |

### 7.5 队列脏数据（Claude 教训）

必须避免：

- UI 显示「已排队」但库无 Turn 行；  
- Turn 行存在但调度器永不 claim；  
- 只能「打断一切」才能丢掉错误的排队句。

验收意向：

```text
[ ] 排队项 = 持久化 RECEIVED Turn + 用户 Message
[ ] 可列出会话内非终态 Turn 顺序
[ ] 可取消「尚未 claim 的 B」而不影响 RUNNING 的 A
[ ] 取消 RUNNING 的 A 不自动删除已排队的 B（除非产品定「停止并清空队列」）
```

---

## 8. 与相邻专题的边界

| 专题 | 关系 |
|------|------|
| **幂等 / clientRequestId** | 同键不是插话 |
| **BackgroundTask（0.2.5）** | 长任务不占聊天 flight；聊天插话规则仍管对话 Turn |
| **世界树 WORLD ingress（0.3）** | 另一 `TurnSource`；与用户抢的是烟火单 RUNNING / 出站序，不是两路微信。处理见 [wechat-and-world-timing.md](./wechat-and-world-timing.md) |
| **Outbox/SSE（0.2.4）** | 插话体验的主战场；协议先定、UI 后做 |
| **多核（更后）** | 取消决胜仍在持久化 CAS，不在各核内存各判 |

---

## 9. 业界对照（压缩表）

| 产品 | 默认 | 可切换 / 手势 | 主要坑 |
|------|------|----------------|--------|
| Cursor | 可设 Queue 或 Steer | 设置项 | Steer 被当成「队列坏了」 |
| Codex CLI | Post-turn FIFO | Tab=排队 / Enter=立即 | — |
| Claude Code | 隐式边界排队 | Esc=打断 | 队列丢、不能只清队列 |
| ChatGPT | 单 flight + Stop | Stop 再发 | Stop 未真正停 → busy 幽灵 |
| Agents SDK | 宿主自定 | `cancel` / `after_turn` / `add_input` | 勿并行开第二 run |
| 仓内 L5 | Cancel + Steer 分函数 | drain 在工具前 | — |
| 仓内 L2 | Abort + pendingMessages | 核壳分离 | — |

陪伴产品更应抄 **ChatGPT 心智 + Codex 队列可见性**，而不是默认打开 Cursor Steer。

---

## 10. 与当前代码的差距（事实）

- v0.1：`TurnController` 同请求内 `receive` + 直接回答；**无**会话执行队列、**无**对外 cancel API。  
- SQLite `busy` 是库锁重试，**不是**「会话正在生成」的产品 busy。  
- Turn 状态机已支持 `cancel`（COMMITTING 前）；缺的是产品路径与调度器。  
- `/chat/` 同步 UI 掩盖了问题；**双 POST 已能打到服务端**。

---

## 11. 已定：followup（FIFO）+ 显式 Stop（不能拒收）

> **2026-09-20 用户定稿。** 调度模式正式名：**followup**（对齐 OpenClaw）。Jev 不参与调度。Steer 不做默认。

### 11.1 为什么选 followup（情感 Agent）

| 选项 | 对烟火 | 结论 |
|------|--------|------|
| **Busy 拒收** | 出局 | 已否定 |
| **发送即硬打断** | 差 | 误触伤陪伴 |
| **默认 Steer** | 差（现阶段） | 半截回复/记忆难解释 |
| **Jev 动态选模式** | 不采用 | 延迟与竞态；Jev 只管分享/门控 |
| **followup（FIFO）+ 显式 Stop** | **已定** | 不丢话、顺序清；改口靠停止 |

### 11.2 行为规格（合同级）

`	ext
1. receive(B) 在 A 非终态时仍 Accepted；B 落库 RECEIVED。
2. 同会话单 RUNNING；followup：A 终态后再 claim B（FIFO）。
3. 新话不 cancel A、不注入当前 Loop（禁止默认 Steer）。
4. 改口仅显式 cancel；COMMITTING 前；与 freezeCommit CAS 决胜。
5. 可取消尚未 claim 的排队项。
6. B 上下文只含已提交历史。
7. DecisionPort / Jev 不裁决排队 vs 打断。
8. 满队是容量保护；0.2.1 可先不设硬顶。
`

### 11.3 分阶段

| 阶段 | 交付 | 不做 |
|------|------|------|
| **0.2.1** | 同会话串行 claim（followup）；cancelToken；双 POST 不双跑；最小 cancel | Steer；Jev 调度；发送即取消；busy 拒收 |
| **0.2.4** | 队列 UI、「停止」、可撤排队项；SSE | — |
| **更后** | 可选 Steer（默认仍关） | 默认 Hard interrupt / Jev 调度 |

### 11.4 已收敛

| 项 | 状态 |
|----|------|
| 不能拒收 | **已定** |
| 默认 **followup（FIFO）** | **已定** |
| 不做默认 Steer；不用 Jev 调度 | **已定** |
| 双端严格 FIFO | **已定** |


## 12. 来源

- 仓内 Loop 十方表与 Cancel/Steer 落点：[agent-loop-survey.md](./agent-loop-survey.md)  
- [Solmaz · Agentic message queueing](https://solmaz.io/agentic-coding-tools-message-queueing)  
- [Cursor Forum · Queue vs Steer](https://forum.cursor.com/t/critical-regression-task-queues-no-longer-work-new-messages-cancel-previous-tasks/171529)  
- [OpenAI Agents SDK · Streaming / cancel](https://openai.github.io/openai-agents-python/streaming/)  
- Claude Code [#50246](https://github.com/anthropics/claude-code/issues/50246)、[#55737](https://github.com/anthropics/claude-code/issues/55737)  
- ChatGPT「Only one message at a time」社区报告（反面教材）  
- 缺陷审阅 §6.1：同会话串行建议  
- 内核参考：Turn 状态机与 cancel / COMMITTING