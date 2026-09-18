# 02 · 架构

## 总图

```text
[内嵌对话页] [wn-manage] [wn-web] [wn-app] [Bot通道] [wn-agent]
      └────────────────────┬────────────────────────────────┘
                           │  全是 Client（可缺席）
                           ▼
                     wn-server（云 · Session Owner）
                     · 领域内核与可复用契约
                     · HTTP/SSE（及日后 WS）API
                     · 内嵌 static 对话页（MVP 入口）
                     · v0.2：节点调度 → wn-agent
```

## 为什么大脑在云

对标 Codex Remote / OpenClaw Gateway 的共性：

- **一个会话主人** + 多端只是客户端；不是两端各写聊天再合并。  
- 手机 / 网页能续聊，是因为都连 **同一个 Owner**。  
- 本地执行（手脚）可后挂：Owner 在云，节点出站连接，NAT 友好。

情感陪伴选择云脑：优先「人在哪都能找到万年」；亲密数据落在自控服务器上（个人部署）。

## wn-server 模块

定稿见 **[05-wn-server-modules.md](./05-wn-server-modules.md)**。

- 交付单元：`api` · `kernel` · `app`  
- MVP：`session` · `turn` · `companion` · `memory` · `llm` · `channel` · `auth` · `http-api` · `web-embed` · `platform`  
- v0.2：`tool` · `node`

## 各端职责（摘要）

| 端 | 做 | 不做 |
|----|----|------|
| wn-server | 真相、编排、内嵌聊 | 不依赖任何 UI 进程 |
| wn-manage | 运维配置 UI | 不存主历史 |
| wn-web | 独立对话壳 | MVP 可不建 |
| wn-app | 移动对话 | MVP 不建 |
| wn-agent | 本机工具执行 | 不当 Session Owner |

## 技术栈（定稿倾向）

- **wn-server**：Java 21 + Spring Boot（与仓内习惯一致；han-server 仅参考）  
- **wn-manage / wn-web**：静态或轻前端，消费 API（形态重建时定）  
- **wn-app / wn-agent**：后期定；agent 倾向本机常驻进程 + 出站连 server  

## 部署（个人）

- wn-server 部署在自控云主机（可与旧服务同机不同进程/域名，例如独立 `agent.*`）。  
- 内嵌页与 API 同源，降低 CORS 与鉴权复杂度。
