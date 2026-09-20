# 02 · wn-server 模块

`last_updated`: 2026-09-20  
`status`: **定稿** — `wn-server` 已按三层建立。未列模块仍不建。

三层 Maven 和包名仍然有效。版本以 [产品概览 §3](./01-overview.md) 为准：**v0.1** 已有会话、直接回答和管理页；**v0.2** 在单节点上补 harness（Loop、工具、记忆、Outbox）；**v0.3** 才加世界树与多核。Guardian 与 wn-agent 更后。落地进度看 [实施清单](../guide/01-checklist.md)。

## 1. 交付单元（Maven）

避免 legacy/han-agent 那种十几个 jar。服务端保持三层；页面在 `app` 内。样式单独成包，日后要给别的 Client 复用时不必再拆页面。

```text
wn-server/
  api/       ← wn-server-api      契约（Client / 通道共用语义）
  kernel/    ← wn-server-kernel  领域内核（可复用；含 OWNER: YOU 实现点）
  app/       ← wn-server-app     Boot 宿主 + HTTP/SSE + 装配 + /manage/、/chat/
../wannian-ui/                      独立样式包：/ui/ tokens、themes、components、layouts
```

| 交付单元 | 依赖 | 职责 |
|----------|------|------|
| **api** | 无 | 类型与接口：Turn / Companion / Memory / Llm / Channel / Auth / Session；**无** Spring、无 IO 实现 |
| **kernel** | api | 编排与领域实现（可 stub）；**无** Web Controller；尽量无 Spring（便于单测） |
| **app** | api + kernel + wannian-ui | `SpringBoot`、Controller、鉴权滤镜、kernel Bean 装配；并拥有 `/manage/`、`/chat/` 页面与浏览器端 API adapter |
| **wannian-ui** | 无业务依赖 | 独立样式包，仅暴露 `/ui/` 下的 tokens、themes、components、layouts |

各端（manage / web / app / agent）**只依赖 HTTP API（及日后 OpenAPI）**，不直接依赖 kernel jar（个人项目若同仓引用 api 契约亦可，但运行时真相仍在 server 进程）。

---

## 2. 逻辑模块（kernel + app 内）

### 2.1 v0.1 已有 / v0.2 补齐

| 模块 ID | 所在层 | 职责 | 不做 |
|---------|--------|------|------|
| **session** | kernel | 会话身份、会话元数据、transcript 归属 | 多租户 |
| **turn** | kernel | 认领、提交、状态机；v0.2 接到 Loop | 完整 Plan DAG |
| **companion** | kernel | 情感成长快照、人设注入（v0.2 做实） | 复杂心理学模型 |
| **memory** | kernel | 短长期记忆（v0.2 做实） | 大规模向量库 |
| **llm / model** | kernel | 模型调用；v0.1 已有直接回答路径 | 绑死单一厂商 UI |
| **channel** | kernel SPI + app | 入站统一；embedded-web 为首个 | 具体 Bot |
| **auth** | app | 个人 Token / 口令 | OAuth 多用户 |
| **http-api** | app | health / turns / companion | 业务写进 Controller |
| **web-embed** | app | `/chat/`、`/manage/` | 独立网页进程 |
| **platform** | app | 配置、日志、版本、关闭 | 许可/Boss/Notify |

### 2.2 v0.2 增加（仍是这一个节点）

| 模块 ID | 所在层 | 职责 |
|---------|--------|------|
| **agent loop** | kernel | 单核 harness：预算、工具观察、取消、真实模型 |
| **tool** | kernel | 本节点可安全执行的工具；不派到第二台机器 |
| **memory / relationship** | kernel | 烟火的成长与记忆，算法可简单，不能只有空接口 |

### 2.3 v0.3 增加

| 模块 ID | 所在层 | 职责 |
|---------|--------|------|
| **world** | 世界树角色所在节点 | 只向烟火投事件 |
| **node registry** | 共享数据面 | 设备 / 节点 / 角色绑定；一份真源 |

### 2.4 其后（占位，不建空工程）

| 模块 ID | 说明 |
|---------|------|
| **wn-agent / worker** | 电脑手脚；出站心跳与本机工具 |
| **guardian** | 与 Kernel 分开的版本恢复 |
| **skill** | SKILL.md 加载 |
| **mcp** | MCP 客户端 |
| **notify-bridge** | 可选外部推送 |

---

## 3. 模块关系

```text
                    http-api / web-embed / auth          (app)
                              │
                              ▼
                           turn  ←── companion
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
           session        memory           llm
              │
              └──── channel SPI ←── embedded-web adapter

        v0.2:  turn ──► loop ──► tool（仍在这一个节点）
        v0.3:  世界树节点 ──事件──► 烟火节点；多节点共一份库
```

**调用方向**：`wn-server-app → wannian-ui`（样式装配）与 `app → kernel → api`（服务端代码）；页面与 adapter 在 `app` 内，不另成模块。kernel 模块之间 turn 为中枢；companion/memory/llm 不直接依赖 HTTP。

---

## 4. 包名建议（重建时）

```text
com.wannian.server.api.*
com.wannian.server.kernel.session|turn|companion|memory|llm|channel|tool|node
com.wannian.server.app.http|auth|web|config
```

（若坚持 `com.hanagent.wannian` 亦可，定重建时二选一，不阻塞模块表。）

---

## 5. 分工对照

| 模块 | 框架可搭 | 维护者主写 |
|------|----------|------------|
| api 契约、http-api、web-embed、platform、auth 壳 | 是 | 策略细节可共商 |
| turn 流水线骨架 | 是（注释桩） | Agent 循环细节 |
| companion / memory / llm 实现 | stub | **是** |
| channel SPI + embedded-web | 是 | Bot 适配后期 |
| tool / loop | v0.2 单核再搭 | 工具策略可共商 |
| world / 多节点 | v0.3 | 见研究稿，不在本表展开 |

---

## 6. 与产品族关系

- **可复用内容** = `api` + `kernel`（及 app 中与通道无关的装配约定）。  
- **wn-manage / wn-app / wn-agent** 不复制上表领域模块，只当 Client。当前 `/manage/` 与 `/chat/` 的静态资源在 `wn-server/app` 内。  
- 本表变更须同步改 [01-overview.md](./01-overview.md) 的验收项。
