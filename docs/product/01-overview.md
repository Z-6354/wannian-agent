# 01 · 产品概览

2026-09-20：版本边界已重置。**v0.1 只认当前已交付进度**；单核 harness 与单核节点是 **v0.2**；世界树与多核节点是 **v0.3**。进度细节在 [实施清单](../guide/01-checklist.md)。不要按旧合同把 Agent Loop 算进 v0.1，也不要回头重建空工程。

## 1. 承诺与组件

- **个人使用**（单用户，不做多租户）
- **情感向可成长陪伴**（关系状态与长期记忆在核心，而非堆工具）
- **大脑在云端**：随时可聊、多端同一会话真相
- **本地是手脚**（v0.3 之后）：文件 / shell / 截图等能力节点，不跑第二套主脑

| 名称 | 角色 | 依赖 | 可缺席 |
|------|------|------|--------|
| **wn-server** | Session Owner：Turn、陪伴、记忆、LLM、鉴权、通道 SPI、**可复用内核**、**内嵌管理页与对话页** | 样式包 `wannian-ui` | **否（核心）** |
| **wn-manage** | 管理端（配置 / 会话 / 节点 / 人设） | 只调 wn-server API | 是 |
| **wn-app** | 手机端 | wn-server API | 是 |
| **wn-agent** | Windows 电脑客户端（能力节点） | wn-server 派发 / 心跳 | 是（**v0.3 之后**） |

### 不变量

1. 聊天记录与陪伴状态 **只在 wn-server** 一份；各端不私藏主会话库。
2. 任一 Client（含 wn-manage）关闭，其它 Client 仍可用，只要 wn-server 在。
3. Client **不实现第二套 Agent 主循环**；只消费 API / 执行被派发的工具。
4. Bot / IM 等是 Channel，写入同一会话库，不是平行大脑。

### 与历史遗产

- **han-server**、旧 Boss/Notify 矩阵：仅作实现参考，**不背兼容包袱**；主交付是 wannian 这一套。
- **legacy/han-agent**：不解冻、不复制巨石；可借鉴 Turn 管道 / 预算 / propose→confirm 等概念。
- 不把 Turn / 陪伴写进 han-server 许可业务。

## 2. 架构

```text
[内嵌管理页 / 对话页] [wn-manage] [wn-app] [Bot通道] [wn-agent]
      └────────────────────┬────────────────────────────────┘
                           │  外部 Client 可缺席；内嵌页属于 wn-server
                           ▼
                     wn-server（云 · Session Owner）
                     · 领域内核与可复用契约
                     · HTTP/SSE（及日后 WS）API
                     · 同源页面在 app 内；样式来自 wannian-ui
                     · v0.2：单核节点上的 harness
                     · v0.3：世界树 + 多核节点
```

大脑在云：一个会话主人，多端只是客户端。手机 / 网页能续聊，是因为都连同一个 Owner。本地执行可后挂：Owner 在云，节点出站连接。亲密数据落在自控服务器上。

模块定稿见 [02-modules.md](./02-modules.md)。`wn-server` reactor 聚合 `api`、`kernel`、`app`，以及同级独立样式包 `wannian-ui`。

| 端 | 做 | 不做 |
|----|----|------|
| wn-server | 真相、编排、HTTP API、装配，以及同源管理页与对话页 | 不依赖任何 UI 进程；不把样式源码写进 app |
| wn-manage | 运维配置 UI | 不存主历史 |
| wn-app | 移动对话 | MVP 不建 |
| wn-agent | 本机工具执行 | 不当 Session Owner |

技术栈：wn-server 为 Java 21 + Spring Boot；页面是 app 内的静态资源，`wannian-ui` 是不引入 Node/Vite/框架的样式包。管理页与对话页只消费 API。wn-server 部署在自控云主机；页面与 API 同源。

## 3. 版本范围

这三档是现行边界。旧文若写「v0.1 必须含 Agent Loop」或「v0.2 = Guardian / wn-agent」，以本节为准。

### v0.1 · 当前进度（已交付）

单进程 `wn-server`，还不是 Agent harness：

- 三层工程、SQLite 会话 / 消息 / Turn、可重启的已提交状态
- 管理页、模型适配器；已启用模型可 `probe`
- `/chat/`：已启用模型时，同一次接收请求里直接回答；未启用则只收下并说明原因
- 不做工具循环、不替换成 Loop、不做记忆策略、不做 Outbox/SSE、不多节点

### v0.2 · 单核 harness + 单核节点（下一步）

**单核** = 一个节点、一个角色（烟火）。**harness** = 这一个节点上的 Agent 运行时，不是再包一层空接口。

正式小版本（须全部完成才标 v0.2 完成）：

| 号 | 内容 |
|----|------|
| **0.2.1** | Agent Loop + 错误码 + Turn 接线 + live/execute |
| **0.2.2** | ToolRuntime + 本节点安全工具 |
| **0.2.3** | Memory + Relationship |
| **0.2.4** | Outbox / SSE |
| **0.2.5** | Task / BackgroundTask |
| **0.2.6** | 生命周期探针 |
| **0.2.7** | 故障、恢复与资源验收 |

- 一个 `AgentLoop`：预算、取消、真实模型、Turn 认领与提交
- 本节点可安全执行的工具、记忆与关系、Outbox/SSE、长任务不占住聊天 Turn
- 节点身份可以存在，但只部署并验收这一个节点
- 不做世界树、不部署第二个节点、不做 Guardian

施工从 [0.2.1](../plans/k01-agent-loop.md) 起；全档顺序见 [路线图](../plans/roadmap.md)。勾选在 [实施清单](../guide/01-checklist.md)。

### v0.3 · 世界树 + 多核节点

- **世界树**：只向烟火投事件；烟火可选分享；不对用户说话
- **多核节点**：设备 / 节点 / 角色解耦；一台设备可多个节点；一份数据
- 同机「烟火 + 世界树」与跨机分角色都合法
- 宿主主备、Guardian、wn-agent 不在本档

拓扑只维护在 [多核心](../research/multi-node-companion-world.md)，叙事只维护在 [世界演进](../research/world-evolution-and-extensions.md)。

### 其后

wn-agent、Guardian、代际换代、wn-app、独立网页。讨论见 [research/history](../research/history/README.md)，**不是**产品 v0.2。

**明确不做（任何当前档）**

- 多租户；未定版本就并行开工五端
- 写入 han-server；把参考实现改成许可/Notify 主路径；解冻 legacy/han-agent；复活微信 Sidecar
- 各端私藏主会话库；Client 里再写一套主循环
