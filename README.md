# wannian

`last_updated`: 2026-09-20  
`status`: **v0.1 已封版** — 直接回答已交付。下一步 v0.2。进度：[清单](./docs/guide/01-checklist.md) · 排期：[路线图](./docs/plans/roadmap.md)。

## 一句话

个人单用户陪伴助手：**wn-server 为唯一大脑**。v0.2 在一个节点上做成 harness。v0.3 才加世界树和多核。电脑手脚 **wn-agent 更后**。

## 文档

| 入口 | 说明 |
|------|------|
| [docs/README.md](./docs/README.md) | 文档总索引 |
| [产品概览](./docs/product/01-overview.md) | **v0.1 / v0.2 / v0.3** |
| [内核合同](./docs/decisions/01-contract.md) | Turn 与提交形状；版本档次以概览为准 |
| [实施清单](./docs/guide/01-checklist.md) | 唯一进度勾选 |
| [路线图](./docs/plans/roadmap.md) | 唯一批次顺序 |
| [K01 施工单](./docs/plans/k01-agent-loop.md) | v0.2 第一步 |
| [补修放行](./docs/reviews/02-release-h3.md) | 历史 H3 关闭证据 |

## 代码

`wn-server/`：`api` · `kernel` · `app`。依赖方向 `app → kernel → api`。构建与冒烟见 [wn-server/README.md](./wn-server/README.md)。
