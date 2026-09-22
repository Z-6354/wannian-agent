# 审查记录

历史证据保留，不改写关闭结论。v0.1 已含直接回答；Agent Loop 属 **v0.2**，见 [实施清单](../guide/01-checklist.md)。

| 保留 | 为什么 |
|------|--------|
| [01-defects.md](./01-defects.md) | T1—T8 定义；文内当时判断以页眉为准 |
| [02-release-h3.md](./02-release-h3.md) | 历史 H3 关闭证据 |
| [03-audit-0.2.1.md](./03-audit-0.2.1.md) | **0.2.1** 工作树审计快照（2026-09-22）；缺陷登记原文不改写 |
| [04-reverify-0.2.1.md](./04-reverify-0.2.1.md) | **0.2.1** 对照 03 的关闭复核（P1～P3）；另建版本报告 |

过程稿已吸收删除，见下表。

## 已吸收的过程结论

| 旧稿 | 当时唯一新事实 | 现在 |
|------|----------------|------|
| 旧 K01/K02 验收 | K01 通过；K02 有条件通过。当时把“RUNNING 直接 commit”记成 F-01 | F-01 已修好。交付在 [实施清单](../guide/01-checklist.md) |
| 修复复核 | 会话归属、原文、空完成事件、终态复活、手工目录误删主路径已堵住。V002 把 outbox 计数器写死成 1 | V003 抬到 `max(next_value, MAX(sequence_no)+1)`。见放行 T3 |
| 补修复核 | 序号回填和 lease 围栏关闭。`save` 用库内 `claim_expires_at` | 计划由 V004 `turn_commit_plan` 与 `freezeCommit` 关闭 |
| 可恢复计划 | 丢掉内存后只凭 `turnId` 重加载再提交成立 | 动态用例已纳入放行 T2 |
| 覆盖补测 | `beginCommit` 只改内存；落库必须走 `freezeCommit` | 仍有效 |

## 仍成立的边界

- `probe` 不是会话入口。v0.2 才接 Loop。
- R03 强杀、R10 堆峰值属清单 **K07**。
- 重复索引 `EXPLAIN` 延期到相关 migration 或最迟 K07。
- 全局 `clientRequestId`；`executionId` 每次不同；COMMITTING 写冻结计划。
