# Wannian 指导文档

`status`: **v0.1 已封版** — 手册不授权 commit、push 或发布。下一步是 **v0.2**（正式小版本 **0.2.1–0.2.7**），看 [实施清单](01-checklist.md)、[路线图](../plans/roadmap.md) 与 [0.2.1 施工单](../plans/k01-agent-loop.md)。版本定义在 [产品概览](../product/01-overview.md)。

这里是编程手册，不得反向改写版本边界。历史交付（骨架、持久化、管理页、直接回答）已合并。v0.3 的世界树与多核不在本目录开工。

## 1. 编写分工

| 内容 | 谁负责 |
|---|---|
| Maven 工程骨架、类型、Interface、Adapter 壳、详细注释、TODO、测试骨架 | 授权实施后由 Agent 生成 |
| Agent Loop、Memory、Relationship | **用户亲自实现**（v0.2 · **0.2.1** / **0.2.3**） |
| 代码审查、错误解释、测试补充和逐阶段验收 | Agent |
| 世界树、多核节点 | v0.3 |
| Guardian、wn-agent、自主升级 | 更后 |

核心代码将统一使用：

```java
// OWNER: USER
// TODO(v0.2): 按指定工作簿章节完成。单核 harness，不是补 v0.1。
```

Codex 可以解释、审查和提供更小的示例，但未经用户改变分工，不直接补全这些核心 TODO。

## 3. 文档导航

| 文档 | 用途 |
|---|---|
| [01-checklist.md](./01-checklist.md) | 唯一进度：v0.1 已交付；v0.2 为 **0.2.1–0.2.7** |
| [02-tutorial.md](./02-tutorial.md) | 从空目录到可运行内核的学习路线 |
| [03-architecture.md](./03-architecture.md) | 三层架构、深 Module；扩展点勿提前做 v0.3 |
| [04-kernel-reference.md](./04-kernel-reference.md) | 包、类型、Interface、状态机与持久化 |
| [05-agent-loop.md](./05-agent-loop.md) | **0.2.1**：用户亲自实现 Agent Loop |
| [06-memory.md](./06-memory.md) | **0.2.3**：用户亲自实现记忆与关系 |
| [07-testing.md](./07-testing.md) | 测试与故障练习；回归矩阵 R01—R12；**§1.1 真人实机禁 Fake、能力由 harness 自证** |
| [history/08-manage-adapter.md](./history/08-manage-adapter.md) | 历史 H4：管理页与模型适配器批次记录 |

## 4. 使用方法

1. 先通读教程和架构，不急着写代码。
2. 实现时到内核参考查契约。
3. 遇到 `OWNER: USER` 时，打开对应工作簿。
4. 每写完一个小功能，立即执行测试工作簿中对应测试。
5. 只在实施清单的验收断言通过后勾选任务。
6. 不为了提前做 v0.3 建多节点或世界树；不为了更后建 Guardian 或 Generation。
7. 每阶段开工读清单中的防复发提示；旧审查报告不随修复改写。

## 5. 术语

- **Module**：通过一个 Interface 隐藏一组实现复杂度的模块。
- **Interface**：调用者必须了解的全部使用规则，不只是 Java `interface`。
- **Seam**：无需修改调用者即可替换行为的位置。
- **Adapter**：放在 seam 上的一种具体实现。
- **Turn**：一次用户输入到正式结果提交的业务事务。
- **BackgroundTask**：脱离原 Turn 持续存在的工作单。
- **SubAgentRun**：执行 BackgroundTask 的一次尝试。
- **Outbox**：业务提交后可靠交付事件的持久队列。
