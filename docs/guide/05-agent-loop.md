# 05 · Agent Loop 工作簿

本工作簿属于 **v0.2 / 0.2.1 单核 harness**，不是 v0.1。验收以 [0.2.1 施工单](../plans/k01-agent-loop.md) 为准：Loop 行为用真实模型，不用 Fake 当通过证据。

## 1. 你要亲自完成什么

你负责 `DefaultAgentLoop` 的核心实现：根据模型决定执行工具、继续思考、转为后台任务或形成最终回答。框架会提供类型、Adapter、预算器和 fake，但不会替你写最终决策循环。

### 开工前防复发提示

历史 H3/H4 已交付。约束来自 [缺陷定义](../reviews/01-defects.md)。排期见 [实施清单](01-checklist.md) 的 **0.2.1**。下文若仍写 Fake 练习，改按 0.2.1 计划改成同一断言、live `ModelPort`。

- TurnEngine 确认请求归属并持久化认领成功后才调用 Loop；合法请求重放不产生第二次模型执行。
- `executionId` 是本次尝试身份，不能用固定节点名替代；旧调用迟到返回时不能靠读新 revision 重新获得提交权。
- Loop 不拼 SQL、不分配 sequenceNo、不自行发完成事件；只产生 Outcome。持久化最终计划、进入 COMMITTING 与提交由 TurnEngine/TurnCommitter 协作实现，协议见 29 号。
- Prompt 的取舍不能修改原始 Message；包括用户的缩进和末尾换行。

## 2. 完成前先回答

请先用自己的话回答：

1. 为什么模型输出不能直接执行？
2. 为什么工具执行后需要把 observation 再交给模型？
3. 为什么循环必须有次数、时间和 token 预算？
4. 为什么 Agent Loop 不负责提交数据库？
5. 什么工作必须转为 BackgroundTask？

无法回答时先回看 [缺陷定义](../reviews/01-defects.md)。

## 3. 输入

`AgentInput` 建议包含不可变快照：

```text
turnId
conversationExcerpt
memoryContext
relationshipSnapshot
userMessage
toolDescriptors
systemInstructions
```

Loop 不应持有 Repository。调用前由 `ContextAssembler` 完成查询与裁剪。

## 4. 输出

```text
FinalResponse(text, modelUsage, trace)
BackgroundAccepted(taskProposal, acknowledgementText, trace)
ControlledFailure(errorCode, safeUserMessage, retryable, trace)
Cancelled(trace)
```

返回结果而不是直接写数据库。TurnEngine 把结果转换为 `CommitTurnPlan`，由 `TurnCommitter` 统一提交事务。

Loop 返回 FinalResponse 不等于已经向用户正式完成。TurnEngine 按 29 号协议校验 owner/revision/lease，在进入 COMMITTING 时保存完整最终计划；committer 保证正式消息与必需完成事件同事务。冻结后的计划用于恢复，不再调用 Loop 重新生成答案。

## 5. 循环伪代码

先把以下伪代码翻译成你自己的设计，再写 Java：

```text
messages = input 初始上下文

while budget 允许:
    检查取消
    decision = model.decide(messages, 可见工具, 剩余预算)

    if decision 是最终回答:
        校验与清理回答
        return FinalResponse

    if decision 是工具调用:
        if 没有调用:
            return ControlledFailure(INVALID_MODEL_OUTPUT)

        for 每个调用（v0.1 可顺序执行）:
            检查取消
            invocation = 形成 ToolInvocation
            result = toolRuntime.execute(invocation, context)
            保存到本次 trace
            把受限 observation 加入 messages

        continue

    if decision 是拒绝:
        return 受控的 FinalResponse 或 ControlledFailure

return ControlledFailure(BUDGET_EXHAUSTED)
```

## 6. Java 骨架预期

```java
final class DefaultAgentLoop implements AgentLoop {
    private final ModelPort model;
    private final ToolRuntime tools;
    private final BackgroundPolicy backgroundPolicy;

    @Override
    public AgentOutcome run(AgentInput input, AgentBudget budget) {
        // OWNER: USER
        // TODO(v0.2): 根据本工作簿第 5—12 节实现。
        throw new UnsupportedOperationException("OWNER: USER");
    }
}
```

按后续练习逐步替换异常，不要一次写完全部分支。

`ToolCallValidator`、`ToolPolicy`、`ToolOperationStore`、`OperationIdFactory` 与具体 ToolAdapter 都属于 ToolRuntime 内部实现，不能作为 Agent Loop 构造参数泄漏出来。

## 7. 练习一：只支持最终回答

FakeModel 固定返回 `FinalAnswer("你好")`：

- 模型只调用一次；
- 输出为 `FinalResponse`；
- 空白回答转为受控失败；
- Loop 不访问数据库；
- trace 记录模型步骤但不记录密钥。

## 8. 练习二：一次工具调用

FakeModel 第一次返回 `current_time`，第二次返回最终回答：

- 模型调用两次；
- 工具调用一次；
- 第二次模型输入含 ToolObservation；
- operationId 在恢复相同 step 时保持稳定；
- observation 超长时被截断并注明。

## 9. 练习三：非法工具

模型请求不存在的工具或非法参数：

- 不执行任何 Adapter；
- 将结构化工具错误作为 observation 返回模型一次；
- 模型仍无法修正时受控失败；
- 不把 Java 堆栈放进 observation。

## 10. 练习四：预算耗尽

FakeModel 永远请求工具。验证：

- 到最大 step 后停止；
- 不多执行一次工具；
- 返回 `BUDGET_EXHAUSTED`；
- trace 能看出每步消耗；
- 用户消息说明任务未完成，不伪造结果。

## 11. 练习五：取消

在模型调用前、工具执行前和工具返回后分别触发取消：

- 未开始的外部调用不再发出；
- 已经执行的工具结果仍记录在 trace；
- 有副作用且结果未知时不能声称“什么都没发生”；
- 返回 `Cancelled` 或明确 UNKNOWN。

补充 TurnEngine 接线测试：用可控执行器停在模型返回前，先取消或使原尝试失效，再释放模型结果。不得因收到 FinalResponse 又把已取消 Turn 完成。取消与 beginCommit 用持久化 CAS 决胜，合法 COMMITTING 不因稍后的普通取消或 lease 到期被改回 RUNNING。禁止用 sleep 猜时序。

## 12. 前台与后台分流

任一条件满足时考虑后台化：

```text
预计超过 15 秒
需要跨多个独立步骤
需要等待外部条件
需要失败重试
需要隔离 workspace
预计超过 Turn 硬预算
用户明确要求后台执行
```

模型只能提出 BackgroundTask，`BackgroundPolicy` 最终裁决。Loop 返回 `BackgroundAccepted`；TurnEngine 调用 `TaskRuntime.prepare` 获得 TaskDraft，再把它放入 `CommitTurnPlan`，由 TurnCommitter 与确认回复原子提交。

## 13. 错误处理

| 错误 | Loop 行为 |
|---|---|
| 模型超时 | 依据剩余预算有限重试或失败 |
| 模型限流 | 返回可重试依赖错误，不忙循环 |
| 模型格式错误 | 一次修复提示或失败 |
| 工具参数非法 | observation 告知模型修正 |
| 工具权限拒绝 | 不重试，告知受限 |
| 工具暂不可用 | 根据幂等性和预算处理 |
| 工具结果 UNKNOWN | 禁止盲重试，交由上层裁决 |

不要使用一个 `catch (Exception)` 后继续循环。

## 14. ContextAssembler

建议拼装顺序：

```text
系统身份与安全规则
当前关系摘要
相关长期记忆
受预算裁剪的近期消息
当前用户输入
可见工具描述
```

裁剪优先丢弃低相关旧消息，不截断当前用户输入或安全规则。

## 15. Trace

只记录：step、decision type、model、usage、duration、tool、operationId、result status 和 error code。不要保存 API key、Authorization header、完整敏感工具结果或模型隐藏推理。

## 16. 完成定义

- 五个练习全部通过；
- Loop 无具体 SDK、SQL、Controller import；
- 所有循环有明确上限；
- 取消与 UNKNOWN 语义正确；
- 返回值覆盖所有路径且不返回 null；
- 代码能由你逐行解释。

接线还必须通过 [测试工作簿](07-testing.md) R01—R05：重复请求不增加模型调用；错误 owner 不写正式结果；提交响应丢失只回放；失去内存 Outcome 后仍能从冻结计划完成；同会话串行、跨会话有界。单独 Loop 单测通过不等于整条 Turn 事务已通过（属 **0.2.1-C**）。
