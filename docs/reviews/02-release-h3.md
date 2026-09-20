# 02 · H3 放行复核

这是 K03-P 的现行关闭记录。过程稿 28—31 已吸收进 [审查索引](./README.md)，不再单独保留。

日期：2026-09-19。对象：当前工作树。性质：对照实施清单放行条件和测试工作簿 R01—R07、R10 做本机执行与关闭判断。**不改缺陷定义原文。本轮当时不接真实模型。** 现行：v0.1 已有直接回答；Loop 属 v0.2。

## 1. 结论

实施清单六条放行条件可以勾上。T1—T7 有对应动态证据。T8 的标题策略和备份摘要已在；重复索引的 `EXPLAIN` 明确延期到相关 migration 或最迟清单 **K07**，不改已发布 V001。

R03 仍是存储级重加载，不是进程强杀（属清单 K07）。R10 的受限堆峰值留到 K07。R08、R09、R11、R12 不属于这次放行。

## 2. 证据

退出码 **0**。命令：

```powershell
& D:/0HAN/HANAGENT/mvnw.cmd `
  -s D:/0HAN/HANAGENT/.mvn/settings.xml `
  -f products/wannian-agent/wn-server/pom.xml `
  '-Dtest=OwnedBackupTest,ModuleDependencyRulesTest,RecoverableCommitPlanTest,ReceiveTurnIdempotencyTest,ConversationHttpTest,TurnCommitterAtomicityTest,TurnTransitionPersistenceTest,OutboxSequenceUpgradeTest,ConsistencyBackupTest,TurnTransitionTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test -q
```

| 测试类 | tests | 失败 / 错误 |
|---|---:|---:|
| OwnedBackupTest | 10 | 0 / 0 |
| ModuleDependencyRulesTest | 2 | 0 / 0 |
| RecoverableCommitPlanTest | 7 | 0 / 0 |
| ReceiveTurnIdempotencyTest | 7 | 0 / 0 |
| ConversationHttpTest | 4 | 0 / 0 |
| TurnCommitterAtomicityTest | 9 | 0 / 0 |
| TurnTransitionPersistenceTest | 6 | 0 / 0 |
| OutboxSequenceUpgradeTest | 3 | 0 / 0 |
| ConsistencyBackupTest | 4 | 0 / 0 |
| TurnTransitionTest | 12 | 0 / 0 |

ModuleDependencyRulesTest 的根属性不含 ArchUnit 的 `@ArchTest`，不能把根属性加总后称为全部用例数。本轮没有跑全量 package、真实模型、进程强杀、资源峰值或远端部署。

## 3. T1—T8

| 项 | 判断 |
|---|---|
| T1 请求身份 | 关闭。跨会话同键冲突且不写第二会话；同键重试换新 ID 仍回放原 Turn；并发屏障后仍只有一份请求事实 |
| T2 执行身份与提交临界区 | 关闭。过期认领不落库；错误 owner 拒绝且三表不变；取消与 `freezeCommit` 屏障竞争只有一个胜者；合法 `COMMITTING` 后租约改到过去仍可提交 |
| T3 完成事件与序号 | 关闭。错配完成事件拒绝；提交器写入匹配的 `TurnCompleted`；后提交事件按游标可见；升级回填不降低已有序号；重试不写第二份答案 |
| T4 备份清理 | 关闭。手工目录与残缺快照不淘汰；校验失败不迁移、不删已有快照；空 history 与空白 version 拒绝快照；无 history 的首次启动仍执行迁移；junction 指向备份根外时不可淘汰 |
| T5 原文 | 关闭。HTTP 缩进/换行原样保存，改写后冲突 |
| T6 依赖负例 | 关闭。JDBC、Spring Context、SDK、api→kernel、api→app、kernel→app 隔离类触发门禁；合法 `Turn` / `TurnId` / 标题策略通过 |
| T7 快照绕过 | 关闭。终态不能经 `reconstitute` + `save` 复活；通用 `save` 不能写成 `COMMITTING` |
| T8 | 标题策略在 kernel。manifest 可往返引号、换行、中文和控制字符；流式摘要与全量 SHA-256 一致，缓冲 8192。重复索引的查询计划未跑，延期到相关 migration 或最迟 K09，不改 V001 |

## 4. R01—R07、R10

| 编号 | 判断 |
|---|---|
| R01 | 已执行。见 T1 / T5 |
| R02 | 已执行。含 `cancelAndFreezeHaveASingleWinner` |
| R03 | 存储级已执行。进程强杀仍属 K09 |
| R04 | 已执行。见 T3；序号不要求连续，由升级回填用例覆盖不复用 |
| R05 | 已执行。见 T7 |
| R06 | 已执行。见 T4 |
| R07 | 已执行。见 T6 |
| R10 | 控制字符、固定缓冲摘要已执行。整个备份的堆峰值留到 K09 |

`TurnCommitterAtomicityTest.runningStatusCannotCommitDirectly` 仍在本轮命令内通过。本轮新增拒绝路径（计划不一致、缺计划、取消竞争失败、备份失败）都断言没有把助手消息、完成事件或迁移写成成功。
