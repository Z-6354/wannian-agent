# wannian（产品族计划 · 定稿）

`last_updated`: 2026-09-17  
`status`: **plan-only** — 本目录当前仅含计划文档；旧 Java 脚手架已清空，待按本定稿重建。

## 文档索引

| 文档 | 内容 |
|------|------|
| [README.md](./README.md) | 本页：总览与索引 |
| [01-product.md](./01-product.md) | 产品承诺、组件、依赖、不变量 |
| [02-architecture.md](./02-architecture.md) | 云脑 + 可选端、与 han-server/legacy 边界 |
| [03-mvp.md](./03-mvp.md) | MVP / v0.2 范围、验收、禁止项 |
| [04-rebuild.md](./04-rebuild.md) | 重建清单、仓内落位、分工 |
| [05-wn-server-modules.md](./05-wn-server-modules.md) | **wn-server 模块定稿**（Maven 三层 + 逻辑模块） |
| [06-discussion-decisions.md](./06-discussion-decisions.md) | **方案重构前讨论记录**：已确认决策、研究结论与待讨论项 |
| [07-memory-auto-promotion-review.md](./07-memory-auto-promotion-review.md) | **记忆自动提升审核草案**：硬门槛、证据规则、裁决输出与待决定项 |
| [08-sensitive-memory-review.md](./08-sensitive-memory-review.md) | **敏感记忆审核草案**：S0/S1/S2 分类、保存许可与禁止边界 |
| [09-memory-conflict-review.md](./09-memory-conflict-review.md) | **记忆冲突审核草案**：替代、并存、时间有效期与待确认冲突 |
| [10-relationship-state-review.md](./10-relationship-state-review.md) | **关系状态审核草案**：互动契约、共同历史、权限与成长边界 |
| [11-memory-job-reliability-review.md](./11-memory-job-reliability-review.md) | **记忆后台任务审核草案**：事务、幂等、lease、重试与崩溃恢复 |
| [12-memory-topic-archive.md](./12-memory-topic-archive.md) | **记忆专题存档**：已确认边界、未决问题和恢复点 |
| [13-self-evolution-review.md](./13-self-evolution-review.md) | **自我更新讨论稿**：通用计算、自修改层级、验证与回滚边界 |
| [14-self-update-survivability-review.md](./14-self-update-survivability-review.md) | **内核自主更新讨论稿**：外部 guardian、不可变版本、影子启动、A/B 切换与自动回退 |
| [15-generational-self-hosting-review.md](./15-generational-self-hosting-review.md) | **全内核代际自托管讨论稿**：单一身份、多代内核、guardian A/B 与最小 selector |
| [16-self-hosting-architecture-review.md](./16-self-hosting-architecture-review.md) | **全内核自托管架构审阅**：理论可行性、最小 Module、Interface、可替换 seam 与删减项 |
| [17-multi-kernel-architecture-review.md](./17-multi-kernel-architecture-review.md) | **多 Kernel 架构审阅**：单一烟火身份、双 Kernel 轮换、Turn 唯一所有权与升级接管 |
| [18-autonomous-evolution-loop.md](./18-autonomous-evolution-loop.md) | **自主迭代闭环讨论稿**：变更触发、候选生成、独立毕业、接管观察与代际学习 |
| [19-three-kernel-ring-succession.md](./19-three-kernel-ring-succession.md) | **三核环形继任讨论稿**：服务核审核、工作核生成、目标核接班及三角色循环轮换 |
| [20-three-kernel-failover-repair.md](./20-three-kernel-failover-repair.md) | **三核自动接管与修复讨论稿**：A 故障后 B fencing 接管、C 隔离修复、流式 Turn 恢复与故障域限制 |
| [21-composable-multi-device-nodes.md](./21-composable-multi-device-nodes.md) | **可组合多设备节点讨论稿**：单节点独立运行、组网协作、能力调度、状态权威与离线降级 |
| [22-node-capability-tiers.md](./22-node-capability-tiers.md) | **节点能力分层决策稿**：单节点普通 Agent、双节点高可用修复、三节点及以上自主更新 |

相关专项研究：

- [Agent 长期记忆实现模式调研](../../docs/architecture/research-2026-09-17-agent-memory-patterns.md)
- [Agent 自更新与“更新不自毁”机制调研](../../docs/architecture/research-2026-09-17-agent-self-update-survivability.md)

仓外指针（导航用）：[`docs/design/wannian-agent.md`](../../docs/design/wannian-agent.md) → 指向本目录。

## 一句话

个人单用户陪伴助手：**wn-server 为唯一大脑（云端）**；管理 / 网页 / 手机 / 电脑客户端均可缺席；MVP 对话入口 **内嵌在 wn-server**；电脑手脚 **wn-agent 放 v0.2**。

## wn-server 模块（摘要）

交付：`api` · `kernel` · `app`。  
MVP 逻辑模块：`session` · `turn` · `companion` · `memory` · `llm` · `channel` · `auth` · `http-api` · `web-embed` · `platform`。  
v0.2：`tool` · `node`。详见 [05-wn-server-modules.md](./05-wn-server-modules.md)。
