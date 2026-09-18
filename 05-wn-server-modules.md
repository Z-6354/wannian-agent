# 05 · wn-server 模块定稿

`last_updated`: 2026-09-17  
`status`: **定稿** — 重建按本表拆分；未列模块默认不做进 MVP。

## 1. 交付单元（Maven，三个）

避免 legacy/han-agent 那种十几个 jar；先三层，逻辑模块用包边界，日后要独立演进再拆 jar。

```text
wn-server/
  api/       ← wn-server-api      契约（Client / 通道共用语义）
  kernel/    ← wn-server-kernel  领域内核（可复用；含 OWNER: YOU 实现点）
  app/       ← wn-server-app     Boot 宿主 + HTTP/SSE + 内嵌对话页 + 装配
```

| 交付单元 | 依赖 | 职责 |
|----------|------|------|
| **api** | 无 | 类型与接口：Turn / Companion / Memory / Llm / Channel / Auth / Session；**无** Spring、无 IO 实现 |
| **kernel** | api | 编排与领域实现（可 stub）；**无** Web Controller；尽量无 Spring（便于单测） |
| **app** | api + kernel | `SpringBoot`、Controller、鉴权滤镜、内嵌 `static/`、把 kernel Bean 装配起来 |

各端（manage / web / app / agent）**只依赖 HTTP API（及日后 OpenAPI）**，不直接依赖 kernel jar（个人项目若同仓引用 api 契约亦可，但运行时真相仍在 server 进程）。

---

## 2. 逻辑模块（kernel + app 内）

### 2.1 MVP 必有

| 模块 ID | 所在层 | 职责 | 不做 |
|---------|--------|------|------|
| **session** | kernel | 会话身份、会话元数据、transcript 归属；Session 为陪伴与记忆的主键空间 | 多租户、团队共享会话 |
| **turn** | kernel | `AgentRuntime`：before companion → memory → llm/agent 循环 → persist → after companion；chat 必做，agent 循环可先薄封装 | 完整 Plan DAG、子 Agent 巨石 |
| **companion** | kernel | 情感成长：快照、before/after Turn、人设提示注入 | 复杂心理学模型（可后演进） |
| **memory** | kernel | 短期（会话消息）+ 长期（可检索事实）接口与存储 | 大规模向量库（MVP 可用文件/SQLite） |
| **llm** | kernel | 模型调用契约与实现（可 stub → 真上游） | 绑死单一厂商 UI |
| **channel** | kernel SPI + app 适配 | 入站统一成 TurnRequest；出站 `deliver`；**embedded-web** 为首个 Channel | 具体 Bot 实现（只留 SPI） |
| **auth** | app（+ api 模型） | 个人 Token / 口令；保护 API 与内嵌页 | OAuth 多用户、权限矩阵 |
| **http-api** | app | `/health` `/turns` `/companion`（及管理只读面预留） | 把业务写进 Controller |
| **web-embed** | app | 内嵌对话静态页（MVP 唯一对话入口） | 独立 wn-web 工程 |
| **platform** | app | 配置、日志、版本号、优雅关闭 | 许可/Boss/Notify 业务 |

### 2.2 v0.2 增加

| 模块 ID | 所在层 | 职责 |
|---------|--------|------|
| **tool** | kernel | 工具注册表、预算、观察压缩；本地工具经 node 执行 |
| **node** | kernel + app | wn-agent 配对、心跳、任务派发、审批/超时；PC 离线时的降级策略 |

### 2.3 其后（占位，不建空工程）

| 模块 ID | 说明 |
|---------|------|
| **skill** | SKILL.md 加载（可参考 agentskills / legacy skill-engine） |
| **mcp** | MCP 客户端；优先挂在 node 或 server 侧策略再定 |
| **notify-bridge** | 可选调用外部推送；非核心 |

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

        v0.2:  turn ──► tool ──► node ──► wn-agent
```

**调用方向**：app → kernel；kernel 模块之间 turn 为中枢；companion/memory/llm 不直接依赖 HTTP。

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
| tool / node | v0.2 再搭 | 工具策略可共商 |

---

## 6. 与产品族关系

- **可复用内容** = `api` + `kernel`（及 app 中与通道无关的装配约定）。  
- **wn-manage / wn-web / wn-app / wn-agent** 不复制上表领域模块，只当 Client。  
- 本表变更须同步改 [03-mvp.md](./03-mvp.md) 验收项。
