# 07 · 记忆自动提升条件审核草案

`date`: 2026-09-17  
`status`: **partially-confirmed** — 证据数量规则已确认；其余敏感、冲突与 Reflection 边界仍待后续讨论，不授权实施。

## 1. 审核对象

审核 `06-discussion-decisions.md` 中已经确认的方向：普通、非敏感、高置信度且证据清楚的推断，允许从 `CANDIDATE` 自动提升为 `ACTIVE`。

本轮需要解决的不是选一个看似精确的分数，而是定义哪些内容有资格进入自动提升通道、需要什么证据，以及哪些情况必须硬性拦截。

## 2. 审核结论

不建议 MVP 使用单一 `confidence >= 0.8` 之类的阈值直接决定提升。模型给出的置信分数通常没有跨模型、跨提示词和跨内容类型的一致校准，而且高置信也可能只是高置信地误解用户。

推荐采用：

```text
硬门槛全部通过
+ 证据规则满足
+ 冲突检测通过
+ 内容价值达到最低要求
→ ACTIVE

否则
→ CANDIDATE / REJECTED
```

`confidence` 保留为排序和审阅信号，但不是唯一裁决者。

## 3. 自动提升硬门槛

候选必须同时满足：

1. `sensitivity = NORMAL`，不涉及健康诊断、财务、账号凭据、精确住址、身份证明、性/亲密隐私、重大关系判断等敏感内容。
2. 来源 Turn 已成功完成，完整 User/Assistant Message 已持久化，不从残缺流、失败 Turn 或取消 Turn 抽取。
3. 至少有一条可定位的 `sourceMessageId`；只有摘要、模型内部推理或无来源旧记忆不能自动提升。
4. 内容表达单一、可独立判断，不能把多个事实和推断塞进一条记忆。
5. 与现有 `ACTIVE`、`FORGOTTEN` 墓碑及权威 Profile 不冲突。
6. 不属于核心身份、关系承诺、Facet 权限或安全边界；这些只能由人工规则或明确用户指令改变。
7. 具有未来复用价值；只对当前 Turn 有用的临时状态留在 Conversation context。

任一硬门槛失败，都不得靠提高 confidence 绕过。

## 4. 按来源类型处理

### 4.1 EXPLICIT

- 用户明确说“记住”“以后都这样”且内容非敏感、无冲突：可直接 `ACTIVE`。
- 用户只是在叙述事实，但没有要求记住：仍可抽取，但应经过复用价值和冲突判断。
- 含否定、假设、玩笑、引用他人观点时，不因出现第一人称就视为明确事实。

### 4.2 OBSERVED

- 单条消息能够直接支持，且语义明确：可自动提升低风险事实，例如稳定称呼、明确工具偏好。
- 行为一次发生不能自动泛化为稳定偏好；“这次用中文”不等于“永远偏好中文”。
- 重复观察可以增加提升资格，但必须保留代表性来源，不把出现次数等同于真实性。

### 4.3 INFERRED

建议 MVP 只允许低风险、窄推断自动提升，例如从多次明确选择推断默认展示偏好。

以下推断必须留在 `CANDIDATE`：

- 性格、心理状态、价值观、政治立场、健康状况；
- 用户与烟火的关系阶段或情感承诺；
- 用户没有明确说出的现实身份、职业、收入或家庭关系；
- 会明显改变烟火称呼、语气、权限或主动行为的结论。

### 4.4 REFLECTION

MVP 中不建议自动提升为事实型 `ACTIVE`。Reflection 可作为带来源的关系理解或表达建议参与排序，但不能覆盖 EXPLICIT/OBSERVED 事实，也不能直接改变 `RelationshipState`。

## 5. 证据强度建议

以下数量规则已于 2026-09-17 确认：用户直接、明确、无歧义陈述的普通非敏感事实，一条来源 Message 即可；`INFERRED` 推断至少需要两个不同 Turn 的一致证据。同一 Turn 内的重复或 Assistant 转述不算独立证据。

自动提升满足下列任一模式即可进入最后裁决：

- **直接明确证据**：一条无歧义的用户陈述，内容为普通低风险事实；
- **重复一致证据**：至少两个不同 Turn 的一致观察，且不是同一次对话中的机械复述；
- **明确纠正证据**：用户纠正烟火先前认知时，新内容可替代旧内容，但必须建立 `SUPERSEDED` 链；
- **明确记忆指令**：用户直接要求记住，优先级最高，但仍不能绕过敏感信息与安全存储限制。

“assistant 曾经说过”不能单独作为 Owner 事实证据。Assistant Message 只能证明烟火做过什么或说过什么。

## 6. 推荐的 MVP 裁决输出

抽取器不要只返回文本和 confidence，至少输出：

```text
candidateContent
scope
kind
sourceMessageIds
evidenceMode
sensitivity
reuseValue
conflictTargetIds
promotionDecision
decisionReasons[]
extractorModel
extractorVersion
```

`promotionDecision` 建议限定为：

```text
AUTO_ACTIVATE
KEEP_CANDIDATE
REJECT_TRANSIENT
REJECT_SENSITIVE
REJECT_CONFLICT
REJECT_NO_EVIDENCE
```

这样可以测试每个门槛，也便于以后更换模型后比较决策漂移。

## 7. 仍需用户决定

1. 用户明确要求记住敏感内容时，是允许加密保存，还是 MVP 一律拒绝长期保存。
2. 自动提升后是否需要轻提示，例如“烟火记住了：你更偏好……”，还是只在记忆管理页可见。
3. `REFLECTION` 是否单独建立非事实存储，还是 MVP 暂时只保留为候选记录。

## 8. 禁止的简化方案

- 禁止仅凭模型 confidence 分数自动提升。
- 禁止从流式 delta、失败回复或未持久化消息抽取。
- 禁止把 Conversation summary 当作唯一证据。
- 禁止把 assistant 自己生成的内容反向当作用户事实。
- 禁止遗忘后仅删除向量而不保留排除墓碑。
- 禁止让 Reflection 覆盖用户明确陈述。
