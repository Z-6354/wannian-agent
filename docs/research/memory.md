# 记忆与关系 · 已确认边界

`status`: **archived-summary** — 2026-09-17 讨论收口；2026-09-20 合并原 `memory-discussion` / `memory-auto-promotion` / `memory-sensitivity` / `memory-conflict` / `memory-relationship` / `memory-job` / `memory-archive`。  
`version`: 细节供 **v0.2 / 0.2.3** 实施；不授权按本文直接建表。  
`external`: [HANAGENT 记忆实现调研](../../../../docs/architecture/research-2026-09-17-agent-memory-patterns.md)

---

## 1. 产品边界

- 单用户；唯一 `OwnerProfile`；不做租户。
- **万年** = 产品；**烟火** = 唯一 `CompanionIdentity`。
- 多个 `Facet`（模式）共享身份与关系；专业记忆可按 Facet 隔离。
- 未经用户确认不得自动切换 Facet。第二套独立人格才建第二个 CompanionIdentity。

```text
OwnerProfile
└─ CompanionIdentity：烟火
   ├─ CoreProfile
   ├─ RelationshipState
   ├─ CompanionMemory
   ├─ Facet* → FacetMemory
   └─ Conversation* → Turn → Message
```

---

## 2. 记忆模型

### 2.1 作用域 / 来源 / 生命周期

| 轴 | 值 |
|----|-----|
| scope | `OWNER_SHARED` · `COMPANION_SHARED` · `FACET` · `CONVERSATION` |
| kind | `EXPLICIT` · `OBSERVED` · `INFERRED` · `REFLECTION` |
| lifecycle | `CANDIDATE` · `ACTIVE` · `SUPERSEDED` · `REJECTED` · `FORGOTTEN` |

`INFERRED` 与 `CANDIDATE` 不是同一字段。

最低字段意向：`scope`、`kind`、`lifecycle`、`content`、来源 Turn/Message、`facetId`、`confidence`、`sensitivity`、抽取器版本、时间戳。最终表名以后定。

### 2.2 写入时机

```text
流式完成 → 完整 Assistant Message 落库 → 后台抽 Candidate
→ 去重 / 冲突 / 敏感度 → AUTO_ACTIVATE 或留候选
```

不对每个 token 抽长期记忆。后台失败不改已完成 Turn。

### 2.3 自动提升

不用单一 `confidence` 裁决。硬门槛全过 + 证据规则 + 无冲突 + 有复用价值 → `ACTIVE`。

硬门槛：`sensitivity` 允许；来源 Turn 已完成；有 `sourceMessageId`；单条可判断；不冲 ACTIVE/墓碑/权威 Profile；不改核心身份/关系承诺/权限。

证据（已确认）：

- 普通明确陈述：一条 Message 即可
- `INFERRED`：至少两个不同 Turn 的一致证据
- 同 Turn 复述 / Assistant 转述不算独立证据

按来源：

- **EXPLICIT**「记住」且非敏感无冲突 → 可直接 ACTIVE
- **OBSERVED** 低风险明确事实可升；一次行为不泛化成偏好
- **INFERRED** 仅低风险窄推断；性格/关系/敏感现实身份留候选
- **REFLECTION** MVP 不自动升为事实；不得覆盖 EXPLICIT/OBSERVED，不得直接改 RelationshipState

裁决输出建议含：`promotionDecision`（`AUTO_ACTIVATE` / `KEEP_CANDIDATE` / `REJECT_*`）与 `decisionReasons`。

禁止：靠 confidence 绕门槛；从 delta/失败 Turn 抽取；用 summary 当唯一证据；Assistant 自述当 Owner 事实；遗忘不留墓碑；Reflection 盖用户陈述。

### 2.4 敏感度 S0 / S1 / S2

| 级 | 含义 | 规则 |
|----|------|------|
| **S0** | 普通 | 门槛通过后可 ACTIVE |
| **S1** | 私密可存 | 仅用户明确要求 + 可见提示 → 加密 ACTIVE；默认可删 |
| **S2** | 秘密 | 永不进长期记忆 / Candidate / 向量 / 摘要 / 日志 |

敏感度先于 confidence。S1 遗忘删正文与派生，只留无原文墓碑。S2 可在当前 Turn 最短受控上下文里用，不得落长期库。

### 2.5 冲突

| 结果 | 何时 |
|------|------|
| **SUPERSEDE** | 用户明确纠正；新 ACTIVE，旧 SUPERSEDED，保留链 |
| **TEMPORAL** | 事实随时间变；`validFrom`/`validTo`；旧非“错误” |
| **COEXIST** | 不同 Facet/情境；必须有可表达条件 |
| **HOLD** | 无法判断；新留 CANDIDATE，不静默盖 ACTIVE |

普通冲突不物理删除。权威序：当前纠正 > 明确记忆指令 > 直接陈述 > 多 Turn 观察 > INFERRED > REFLECTION > Assistant 历史。

召回：不注入 SUPERSEDED/REJECTED/FORGOTTEN；互斥多 ACTIVE 且无法消解 = 数据异常。

---

## 3. RelationshipState

定义：可解释的互动契约与共同历史索引，不是好感度分数。

组成：

- **RelationshipDefinition** — 用户确认的定位/称呼
- **InteractionContract** — 主动程度、称呼、边界
- **SharedRituals** — 共同习惯（需确认或多次后提议）
- **Milestones** — 指向 Memory/Turn 的索引
- **CalibratedPermissions** — 显式授权；不因“更亲密”自动扩大

不进入：短暂情绪、心理猜测、单一爱意分数、可刷经验条、S1 正文、S2、未确认的家人/伴侣标签。

用户侧：`初识 → 渐熟 → 默契 → 相伴` + 相伴天数 / 里程碑 / 习惯 / 变化原因。不显示好感度百分比或升级进度条。强关系称谓只能用户确认。阶段不因少聊倒退。

服务端持久化投影 + 事件；多入口只消费同一 API。Reflection 只能提候选，不能直接改状态。

---

## 4. Memory Job（草案，未确认）

目标：Turn 完成后后台抽取；失败可重试、可观测、不双写；不改聊天终态。

建议状态：`PENDING → RUNNING → SUCCEEDED | RETRY_WAIT | DEAD | CANCELLED`。

```text
同事务：完整 Assistant Message + Turn COMPLETED + MemoryJob(PENDING)
```

唯一键意向：`jobType + sourceTurnId + extractorVersion`。数据库 lease；有限重试；提交前再验遗忘墓碑。SSE `turn.completed` 不等待抽取。

**未确认**：表结构、lease 参数、崩溃恢复细则。恢复讨论从本节继续。

---

## 5. 借鉴（已定方向）

- 短期 → 摘要 → 反思；回退清记忆（LLS）
- 异步写队列、失败不挡聊天（Hermes）
- 候选区、来源门控、遗忘墓碑（OpenClaw）
- 小而稳的身份/主人档案（Letta / LangMem）
- Reflection ≠ 事实（Generative Agents）
- 人工规则层 ≠ 自动记忆层 ≠ 会话压缩层（Cursor / Codex / Claude Code）

不复制 OpenClaw dreaming；不把压缩摘要当长期真源。

---

## 6. 未决

1. Memory Job 库表、lease、重试与崩溃恢复（§4 草案）
2. S1 密钥、备份、导出、彻底删除实现
3. Reflection 是否独立非事实存储
4. 关系阶段迁移条件、API schema、SQLite 表
5. 自动提升后是否轻提示用户
6. 用户要求记住 S1 时：加密保存（已倾向）的实现细节

v0.2 实施以 [实施清单 K03](../guide/01-checklist.md) 与 [06 工作簿](../guide/06-memory.md) 为准；改产品语义只改本文件。
