# 10 · RelationshipState 审核草案

`date`: 2026-09-17  
`status`: **confirmed** — 关系状态组成、用户呈现与服务端持久化方向已于 2026-09-17 确认；具体表结构和接口仍待设计，不授权实施。

## 1. 审核结论

`RelationshipState` 不应是单一好感度、亲密度或经验值，也不应让模型仅凭聊天次数自动宣布关系升级。

建议把它定义为：

> 烟火与主人之间当前有效、可解释、可追溯的互动契约和共同历史索引。

它描述“双方如何相处”，而不是声称精确测量用户或烟火的内心感情。

## 2. 与其他领域对象的边界

```text
OwnerProfile
  用户相对稳定的事实与偏好

CompanionIdentity / CoreProfile
  烟火是谁、不会因关系变化而改变的核心边界

Memory
  带来源的具体事实、经历、推断与 Reflection

RelationshipState
  从明确约定与有效记忆投影出的当前相处方式
```

RelationshipState 不复制全部记忆正文；它保存当前有效状态及其来源引用。重新构建时，必须能够从明确约定和有效记忆解释每一项状态为何存在。

## 3. 推荐组成

### 3.1 RelationshipDefinition

用户明确确认的关系定位和称呼，例如“烟火是我的长期个人陪伴助手”。

- 只能由用户明确陈述或确认改变。
- 模型不得根据聊天频率、情绪表达或使用时长自行升级为朋友、家人、伴侣等身份。
- 变化应建立版本和替代链。

### 3.2 InteractionContract

双方当前有效的互动偏好与边界：

- 主动程度；
- 提醒和追问频率；
- 称呼；
- 幽默、直率、温柔等表达边界；
- 哪些话题可主动提起；
- 哪些行为必须先确认。

它优先来自用户明确设置，其次才是满足自动提升条件的普通偏好。

### 3.3 SharedRituals

双方稳定形成的共同习惯，例如每日问候、周末复盘、创作前固定准备。一次行为不构成仪式；建议至少由用户确认，或多次发生后由烟火提出确认。

### 3.4 Milestones

可追溯的重要共同事件索引，例如第一次完成某个长期项目、用户明确认可某个纪念日。Milestone 指向来源 Memory/Turn，不在 RelationshipState 中复制完整故事。

### 3.5 CalibratedPermissions

烟火在不同领域可采取的主动行为边界，例如：

- 可以主动提醒普通待办；
- 涉及对外发送、付费、删除、权限和生产环境仍必须确认；
- 工作 Facet 可以主动整理草稿，但不能自行发布。

权限来自显式授权，不由“关系更亲密”自动扩大。

## 4. 不进入 RelationshipState 的内容

- 当前一轮的短暂情绪；
- 模型对用户心理状态的猜测；
- 单一数值的爱意、忠诚、信任或亲密度；
- 为了提高留存而制造的连续签到、经验条或关系等级；
- S1 私密内容的正文；
- S2 秘密；
- 未经确认的伴侣、家人等关系标签；
- 工具权限、对外操作权限的隐式升级。

## 5. 成长机制

推荐采用事件驱动、字段级更新，而不是每轮重新让 LLM 生成整份状态：

```text
已完成 Turn / 用户设置
→ 产生带来源的 RelationshipEvent
→ 规则校验敏感度、证据和授权
→ 更新一个明确字段或增加 Milestone
→ 保存 before / after / reason / source
```

建议事件类型：

```text
DEFINITION_CONFIRMED
INTERACTION_PREFERENCE_SET
BOUNDARY_SET
RITUAL_CONFIRMED
MILESTONE_RECORDED
PERMISSION_GRANTED
PERMISSION_REVOKED
STATE_CORRECTED
```

禁止使用 `RELATIONSHIP_LEVEL_UP` 这类模糊事件。

## 6. 可解释性与撤销

- 每个当前字段必须能指向一个或多个来源 Message/Memory/Event。
- 用户修改或撤销后，旧值保留历史但不再注入 Prompt。
- 如果来源记忆被遗忘，对应投影必须同步失效或进入待确认，不能继续作为“无来源关系状态”存在。
- UI 应展示自然语言状态和来源，而不是只显示内部枚举或分数。
- Reflection 可以提出“可能形成某种相处习惯”，但只能生成候选建议，不能直接改 RelationshipState。

## 7. Facet 关系

- `RelationshipDefinition` 和核心边界由所有 Facet 共享。
- `InteractionContract` 可以有共享默认值和 Facet 覆盖值，例如工作模式更简洁、陪伴模式更舒缓。
- Facet 覆盖不能突破共享安全边界和权限要求。
- Milestone 默认属于烟火整体；只有明显专业性的事件才额外标注 Facet。

## 8. MVP 建议范围

MVP 只实现：

- 一个用户确认的 `RelationshipDefinition`；
- 可编辑的 `InteractionContract`；
- 少量 `SharedRituals`；
- 带来源的 `Milestones`；
- 显式 `CalibratedPermissions`，且不能随关系自动升级；
- 字段级事件历史与撤销。

MVP 不实现情感分数、关系等级、复杂自动成长曲线或模型自发关系定性。

## 9. 用户侧呈现建议

内部不使用单一好感度，不代表用户界面不能呈现明确的关系成长。推荐使用“阶段 + 可核验事实 + 变化说明”，而不是显示 `好感度 87/100`。

建议关系卡展示：

```text
当前相处阶段：默契
相伴时间：126 天
共同里程碑：18 个
形成的共同习惯：3 个
最近变化：确认了工作模式下优先简洁回复
```

阶段候选：

```text
初识 → 渐熟 → 默契 → 相伴
```

- 阶段表达相处积累，不自动声明朋友、家人、恋人等现实关系身份。
- 不显示距离下一阶段还差多少消息，不提供可刷取的经验条。
- 不因用户离开、少聊天或拒绝互动而倒退。
- 阶段变化必须能列出相关 Milestone、共同习惯和明确约定。
- 用户可以隐藏阶段展示；隐藏不影响记忆和互动契约。
- “知己”、伴侣等强关系称谓不作为自动阶段，只能由用户明确确认后成为 RelationshipDefinition。

如果产品确实需要数值，优先展示可验证的客观统计，例如相伴天数、共同里程碑数量、已确认习惯数量；不展示声称测量感情的好感度百分比。

### 9.1 存储、接口与入口（已确认）

上述数据必须由 wn-server 持久化并作为跨入口统一真源，不能由网页根据本地聊天记录临时计算。

建议概念结构：

```text
RelationshipProjection
  stage
  companionshipStartedAt
  milestoneCount
  ritualCount
  lastChangedAt
  lastChangeReason
  projectionVersion

RelationshipEvent
  eventType
  sourceTurnId / sourceMemoryId
  before
  after
  reason
  occurredAt
```

- Projection 用于快速读取和 UI 展示，Event/Memory/用户设置用于审计与重建。
- `相伴天数` 由服务端保存的 `companionshipStartedAt` 和当前日期计算，不保存每天递增的计数。
- milestone/ritual 数量来自当前有效记录，不能用聊天消息数量代替。
- 阶段变化与投影更新应在同一业务事务内落盘，避免接口读到阶段已变化但原因尚未保存。
- 后续提供稳定 API；内嵌网页、手机或其他入口只消费 API，不复制阶段计算规则。
- API 必须同时返回 `stage`、客观统计和可展示的变化原因；不只返回一个脱离解释的枚举。

## 10. 建议确认的决策

> RelationshipState 是可解释的互动契约与共同历史索引，由 RelationshipDefinition、InteractionContract、SharedRituals、Milestones 和 CalibratedPermissions 组成；不使用好感度或关系等级，关系身份和权限只能显式确认，Reflection 只能提出候选建议。

用户侧可以显示 `初识 → 渐熟 → 默契 → 相伴` 的相处阶段，并配合相伴时间、里程碑、共同习惯和变化原因；不显示好感度百分比或可刷取经验条。用户可以关闭阶段展示。
