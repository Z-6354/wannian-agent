# 06 · Memory 与 Relationship 工作簿

已确认产品边界见 [memory.md](../research/memory.md)。本工作簿是 **v0.2 / 0.2.3** 的实现练习。

## 1. 目标

Memory 让烟火在未来 Turn 中记得有依据的信息；Relationship 描述烟火与用户之间可解释、可版本化的互动状态。两者都不能等同于把完整聊天无限塞回 Prompt。

## 2. 三类上下文

```text
近期消息：Conversation 中最近发生的原始互动
长期记忆：跨会话仍有价值且通过规则保存的信息
关系状态：相处方式、共同历史摘要和允许的成长状态
```

原始消息是证据，Memory 和 Relationship 是经过规则产生的派生状态。

### 0.2.3 防复发提示

依据 [缺陷定义 T1/T2/T7 与 §6.3](../reviews/01-defects.md)。K03-P（历史 H3）已放行，这些约束仍然有效：

- ID 存在不等于绑定正确。来源 Turn 必须对应预期身份、事实与作用域；允许的跨会话长期记忆按许可共享，不能简单要求来源会话永远等于当前会话。
- 不 trim/改写原始证据以便通过比较；归一化只发生在派生候选中，并保留来源。
- 历史 H2 的 `List<?>/Object` 是拒绝非空的临时占位。**0.2.3** 换成具体不可变 ApprovedChange，包含来源、作用域与需要的 expected revision，不以强制类型转换长期保留占位。
- 统一错误 code 不代表统一成无类型 Result；策略决策保留 ACCEPT/REJECT/NEEDS_CONFIRMATION/COEXIST 等具名结果。

## 3. Memory 生命周期

```text
模型或规则提出 MemoryCandidate
→ 校验来源
→ 敏感性分类
→ 查找已有冲突
→ ACCEPT / REJECT / NEEDS_CONFIRMATION / COEXIST
→ 返回 ApprovedMemoryChange，交由 TurnCommitter 提交
```

Candidate 最少包含 subject、claim、kind、sourceTurnId、confidence、observedAt 和 sensitivityHint。模型给出的 confidence 只是参考，不能替代规则。

## 4. Memory 用户实现区域

```java
// OWNER: USER
// 目的：根据来源、敏感等级、已有记忆和冲突规则裁决候选。
// 输入保证：candidate 已完成格式校验，sourceTurnId 存在。
// 输出保证：返回明确 decision，不直接写 Repository。
// 禁止：仅因为模型 confidence 高就自动接受敏感记忆。
```

## 5. 最小记忆分类

```text
USER_PREFERENCE   用户明确偏好
USER_FACT         用户明确陈述且未来有帮助的事实
SHARED_HISTORY    双方真实发生的重要事件摘要
TASK_CONTEXT      仅在任务期间有效的信息
```

暂不自动保存推断出的疾病、政治立场、财务秘密、密码、Token 或第三方隐私。

## 6. 冲突处理

- 用户明确纠正：新记录 `supersedes` 旧记录；
- 时间变化：允许带有效期并存；
- 两个来源不确定：标为冲突，等待确认；
- 只是措辞不同：可归一化为同一事实；
- 敏感冲突：要求确认，不自动提升。

练习：为“我住在上海”→“我已经搬到杭州”设计两个记录及有效期。

## 7. Recall

Recall 输入当前问题、Conversation、预算和时间；输出结构化 `MemoryContext`。排序至少考虑相关性、明确性、时间有效性、来源可信度和敏感使用许可。不要只按最新时间或字符串包含排序。

## 8. Relationship 状态

首版保持小型、可解释：

```text
preferredAddress       用户希望如何被称呼
interactionStyle       简洁、耐心、主动程度等明确偏好
trustSignals           有证据的有限状态，不做心理诊断
sharedMilestones       重要共同经历的引用
boundaries              用户明确提出的互动边界
revision
```

不要设计隐藏的“好感度游戏数值”操纵回复。

## 9. beforeTurn 与 afterTurn

`beforeTurn` 读取当前 snapshot，生成受预算限制的提示上下文，不修改状态。

`afterTurn` 接收本轮待提交事实，形成候选变化并经过规则裁决，只返回 `ApprovedRelationshipChange`。TurnEngine 将 change 和 expected revision 放入 `CommitTurnPlan`，由 TurnCommitter 与 Turn 最终结果一起提交。模型生成回答时不能暗中修改正式 Relationship。

## 10. Relationship 用户实现区域

```java
// OWNER: USER
// 目的：判断本轮是否产生了可解释、允许保存的关系变化。
// 必须：每个变化带 reason 与 sourceTurnId。
// 输出：ApprovedRelationshipChange 或明确的不变决定，不自行 commit。
// 禁止：从单次情绪化表达推断永久关系变化。
// 禁止：绕过用户明确边界或敏感记忆规则。
```

## 11. 练习

### 记忆接受

用户说“以后回答尽量简短”。形成偏好候选，并在后续 Turn 可检索。

### 记忆拒绝

模型猜测用户收入。即使 confidence 很高，也不自动保存。

### 冲突

用户先说偏好中文，后明确要求某个项目用英文。区分全局偏好与局部范围，而不是互相覆盖。

### 关系边界

用户说“不要主动提醒我睡觉”。后续不能因为模型认为关心用户就忽略边界。

### revision 冲突

两个 Turn 基于同一旧 snapshot 提交更新时，第二个必须重新评估，不能最后写入者直接覆盖。

补充原子性练习：让第二个 Turn 在助手消息已准备、Relationship revision 已过期时提交；断言 Message、Turn、Memory、Relationship、Outbox 均无部分变化。再用错误 executionId 和重复计划分别提交，前者拒绝，后者不重复应用变化。禁止只把 expected revision 更新成最新值后重放旧 ApprovedChange。

## 12. 测试要求

- 接受、拒绝、确认、并存四种决策；
- 来源 Turn 不存在时拒绝提交；
- 敏感候选默认不自动保存；
- supersedes 链不会形成环；
- 过期记忆不进入默认 Recall；
- Relationship revision 冲突可复现；
- Prompt 不输出未获许可的敏感内容。
- 来源存在但身份/作用域错配时拒绝，合法跨会话来源能通过；
- 任一 change revision/owner 失败时整笔不写；恢复与重试不重复应用 change；
- 加载/恢复接口不能绕过 supersedes、来源和生命周期约束。

## 13. 完成定义

- 规则能用普通语言解释；
- 每个正式状态都有来源；
- 不依赖模型自评分直接提交；
- 冲突不静默覆盖；
- 用户可以查看和纠正正式记忆；
- 核心策略代码能由你逐行解释。
