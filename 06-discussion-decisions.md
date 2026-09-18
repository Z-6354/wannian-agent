# 06 · 方案重构前讨论记录

`last_updated`: 2026-09-17  
`status`: **discussion-record** — 本文记录方案重构前已经确认的产品与架构决策；不表示旧 `01..05` 已完成同步。

## 1. 文档用途

- 保留本轮讨论中已经确认的设计，不在后续重构时丢失。
- 把“已确认决策”“研究依据”“待讨论事项”分开。
- 等关键问题讨论完成后，再统一重构 `01..05`，避免零碎补丁造成互相矛盾。
- 当前仍是计划阶段，不授权创建工程代码、commit、push 或部署。

外部与本地 Agent 记忆实现的完整调研见：

- [`docs/architecture/research-2026-09-17-agent-memory-patterns.md`](../../docs/architecture/research-2026-09-17-agent-memory-patterns.md)

## 2. 已确认的产品边界

### 2.1 单用户

- 当前严格采用单用户体系，不建设注册、用户列表、租户、团队共享或权限矩阵。
- 保留唯一的 `OwnerProfile`，用于表达烟火认识的现实主人；它不是多用户账号系统。
- 浏览器/设备鉴权与领域中的 Owner 分开：鉴权凭据不是陪伴关系实体。
- 不为未知的多用户需求在每张业务表提前加入租户逻辑；未来若需要多用户，按明确迁移处理。

### 2.2 产品、陪伴者与模式

- **万年**是产品/系统名称。
- **烟火**是当前唯一的陪伴者身份（`CompanionIdentity`）。
- 烟火可以拥有多个 `Facet`（产品界面称“模式”），例如日常陪伴、工作、创作、电脑管家。
- Facet 不是独立人格或独立角色：所有 Facet 共享“我是烟火”的自我身份、与主人的关系、核心经历和核心行为边界。
- Facet 可以拥有专属职责、表达倾向、工具权限和专业记忆。
- Conversation 有默认 Facet；每个 Turn 记录实际生效的 Facet。
- 烟火可以建议切换 Facet，但未经用户确认不得自动切换。
- MVP 不做一个 Turn 内多个 Facet 协同或互相调度。
- 将来若出现不同名称、不同自我认知、独立关系与独立记忆的实体，才建立第二个 `CompanionIdentity`，不把它伪装成 Facet。

## 3. 已确认的领域层级

```text
万年产品实例
└─ OwnerProfile（唯一主人）
   └─ CompanionIdentity：烟火（唯一）
      ├─ CoreProfile
      ├─ RelationshipState
      ├─ CompanionMemory
      ├─ Facet（多个）
      │  └─ FacetMemory
      └─ Conversation（多个）
         └─ Turn
            └─ Message
```

术语约束：

- `Conversation`：聊天窗口或逻辑会话。
- `Turn`：一次用户输入引发的完整服务端处理。
- `Message`：Turn 中持久化的用户/助手消息。
- `RelationshipState`：主人与烟火跨 Conversation 持续发展的关系状态。
- 不再使用含义不明确的“对话”同时指代 Conversation、Turn 和 Message。

## 4. MVP 分层

### 4.1 M0 · 工程骨架

- 验证模块边界、Boot 装配、基础 Turn API、内嵌网页与 Stub LLM。
- 可使用内存存储。
- M0 只证明工程接线，不算产品 MVP。

### 4.2 MVP · 最小真实陪伴闭环

- 接入至少一个真实 LLM。
- 使用 SQLite 持久化。
- 服务重启后恢复 Conversation、Turn、Message、烟火身份与长期记忆。
- 跨 Conversation 保持同一个烟火、同一份关系连续性。
- 具备最小长期记忆抽取、召回、查看、修改与遗忘能力。
- 具备个人浏览器鉴权。
- 内嵌网页能完成真实多轮对话。
- Turn 的并发、幂等、取消、失败与重试行为有明确语义。
- **流式输出属于 MVP 必须能力，不允许只返回完整最终文本。**

## 5. 已确认的流式 Turn 方向

MVP 使用 SSE，不要求 WebSocket：

```text
POST /conversations/{conversationId}/turns
  → 创建并持久化 Turn
  → 返回 turnId

GET /turns/{turnId}/events
  → SSE 推送状态和内容增量
```

至少包含：

```text
turn.accepted
turn.started
message.delta
turn.completed
turn.failed
turn.cancelled
heartbeat
```

不变量：

- 每个 Turn 只能出现一个终态。
- `message.delta` 只用于临时显示，不直接成为正式 Assistant Message。
- `turn.completed` 前必须先持久化完整 Assistant Message。
- SSE 断线不自动取消 Turn；用户需显式请求取消。
- 服务仍运行时，客户端重连先取得累计输出，再继续接收增量。
- 服务重启导致执行中断时，残缺回复不得伪装成正式助手消息。
- 同一 Conversation 的 Turn 串行；不同 Conversation 可并行。
- `clientRequestId` 用于网络重试幂等。
- API 事件采用 wn-server 自己的稳定格式，不直接暴露上游模型厂商事件。

## 6. 已确认的记忆方向

### 6.1 记忆作用域

```text
OWNER_SHARED
COMPANION_SHARED
FACET
CONVERSATION
```

- 主人称呼、稳定偏好等属于 Owner 共享事实。
- 烟火的共同经历、关系与重要约定属于 Companion 共享记忆。
- 工作、创作等专业内容可归特定 Facet。
- 临时上下文与当前摘要属于 Conversation。

### 6.2 来源性质与生命周期分离

来源性质：

```text
EXPLICIT     用户明确陈述或要求记住
OBSERVED     从已完成对话事件中提取
INFERRED     模型从证据作出的推断
REFLECTION   从多段经历形成的高层认识
```

生命周期：

```text
CANDIDATE
ACTIVE
SUPERSEDED
REJECTED
FORGOTTEN
```

不能把 `INFERRED` 与 `CANDIDATE` 混成同一字段：推断是来源性质，候选是当前状态。

### 6.3 自动提升决策（2026-09-17 已确认）

- 用户明确要求“记住”的非冲突内容，可直接进入 `ACTIVE`。
- **普通、非敏感、高置信度且证据清楚的推断，允许自动进入长期 `ACTIVE` 记忆。**
- 自动提升的记忆必须可查看、修改、撤销和遗忘。
- 自动提升必须保存来源 Turn/Message，不能只保存一段脱离证据的文本。
- 敏感事实、低置信度推断、重大关系判断或与现有记忆冲突的内容，不得自动提升，先留在 `CANDIDATE` 等待确认或更多证据。
- `REFLECTION` 可以影响烟火的理解与表达，但不能未经证据覆盖 Owner 的已确认事实。
- 遗忘后要留下不可召回的墓碑/排除记录，防止后台任务从旧 Conversation 再次提取同一内容。

### 6.4 最低来源字段

后续数据模型至少考虑：

```text
scope
kind
lifecycle
content
sourceTurnIds
sourceMessageIds
facetId
confidence
sensitivity
extractorModel
extractorVersion
createdAt
updatedAt
```

最终字段名仍待数据模型设计，不以本列表直接生成数据库表。

### 6.5 写入时机

```text
流式回复完成
→ 完整 Assistant Message 持久化
→ 后台抽取 Memory Candidate
→ 去重、冲突、敏感度与置信度判断
→ 自动提升或留在候选区
```

- 不对每个流式 token 实时抽取长期记忆。
- 用户明确要求记住时可以走热路径，但写入仍需以完整、已持久化的 Turn 为来源。
- 后台记忆失败不能把已经成功的 Turn 改成失败；应记录独立的后处理状态并允许重试。

### 6.6 自动提升证据门槛（2026-09-17 已确认）

- 用户直接、明确、无歧义陈述的普通非敏感事实，一条来源 Message 即可满足自动提升的证据数量要求。
- `INFERRED` 推断至少需要两个不同 Turn 的一致证据，不能根据一次行为泛化为稳定偏好或长期事实。
- 同一 Turn 内的重复、复述或 Assistant 对用户内容的转述，不计为多个独立证据。
- 证据数量达标不代表必然提升；敏感度、冲突、复用价值、来源完整性等硬门槛仍须全部通过。
- 不用单一模型置信分数决定是否自动提升；confidence 只作为排序和审阅信号。

### 6.7 敏感记忆边界（2026-09-17 已确认）

- MVP 使用 `S0 / S1 / S2` 三级敏感度。
- `S0` 为普通内容，可以在其他自动提升门槛通过后进入 `ACTIVE`。
- `S1` 为私密但允许保存的内容；只有用户明确要求记住，并收到可见保存提示后，才能加密保存。
- `S2` 为密码、验证码、恢复码、API key、私钥、完整支付凭据、完整证件凭据等秘密；即使用户要求也不得进入长期记忆。
- `S2` 不得进入 Candidate、向量索引、摘要、Reflection 或调试日志；当前任务必须使用时，只允许最短生命周期的受控 Turn 上下文。
- 敏感度判断先于 confidence；模型高置信不能降低敏感等级。
- S1 遗忘时删除正文、向量和派生摘要，只保留不含原文的排除墓碑。

### 6.8 记忆冲突与时间语义（2026-09-17 已确认）

- MVP 使用 `SUPERSEDE / COEXIST / TEMPORAL / HOLD` 四种冲突结果。
- 用户明确纠正时建立替代链：新记忆进入 `ACTIVE`，旧记忆进入 `SUPERSEDED`，普通召回只使用新内容。
- 事实随时间变化时保留历史和有效期，不把旧记录误判为从未成立。
- 不同 Facet 或情境下的偏好可以同时 `ACTIVE`，但必须有可表达的适用条件。
- 无法安全判断时，新内容留在 `CANDIDATE`，不静默覆盖既有 `ACTIVE`。
- 普通冲突不物理删除历史；物理删除只用于明确遗忘、保留政策或安全要求。
- 默认权威顺序为：用户当前明确纠正 > 明确记忆指令 > 直接明确陈述 > 多 Turn 一致观察 > INFERRED > REFLECTION > Assistant 历史表述。

### 6.9 RelationshipState 与用户呈现（2026-09-17 已确认）

- RelationshipState 是可解释的互动契约与共同历史索引，不使用内部单一好感度统治关系行为。
- 用户侧显示 `初识 → 渐熟 → 默契 → 相伴` 的相处阶段，并辅以相伴时间、共同里程碑、共同习惯等客观数据。
- 不显示好感度百分比、可刷取经验条或“距离升级还差多少条消息”。
- “知己、家人、伴侣”等强关系称谓只能由用户明确确认，不能自动升级获得。
- 阶段、起始时间、里程碑、共同习惯和变化原因必须由服务端持久化；它们不是浏览器本地临时状态。
- 服务端后续提供稳定查询接口；内嵌聊天入口及未来其他入口消费同一接口展示，不各自计算关系状态。
- 持久化状态应是可由 RelationshipEvent、Memory 和明确设置重建的投影；事件/来源是审计依据，投影用于高效查询。

## 7. 调研后确定的借鉴方向

- 借鉴 LLS：短期→阶段摘要→结束后反思，以及回退时同步清理记忆。
- 借鉴 Hermes：召回/写入 Provider SPI、回复后异步串行写队列、失败不阻塞聊天、退出排空。
- 借鉴 OpenClaw：候选区、来源继承、可信来源门控、遗忘预览、遗忘后禁止重新摄取。
- 借鉴 Letta：核心身份/主人档案使用小而稳定、始终可见的结构，而非全部依赖向量检索。
- 借鉴 LangMem：Owner Profile 与可检索事件集合采用不同数据形态。
- 借鉴 Generative Agents：关系成长可从经历形成 Reflection，但 Reflection 不等于事实。
- 不复制 OpenClaw 完整 dreaming、多阶段评分或复杂文件体系进入 MVP。
- 不把 Memory Provider 的内部行为当作产品语义；确认、来源、冲突和遗忘规则由 wn-server 领域层统一约束。

### 7.1 Cursor、Codex、Claude Code 补充借鉴（2026-09-17）

三类编码 Agent 表明，“记忆”至少应拆成三套权威性不同的机制，不能共用一张无差别的 memory 表：

1. **人工确认的稳定规则/身份层**：类似 Cursor Project/User Rules、Codex `AGENTS.md`、Claude Code `CLAUDE.md`。它由人维护、可审查、优先级明确，适合烟火身份、主人明确偏好、行为边界和 Facet 职责。
2. **自动学习的长期记忆层**：类似 Cursor Memories、Codex `MEMORY.md`/rollout consolidation、Claude auto memory。它来自历史交互，必须保留作用域、来源、可编辑/删除能力；在万年中仍受 `kind + lifecycle + sensitivity + confidence` 约束，不能获得与人工确认规则相同的权威性。
3. **会话连续性/压缩层**：类似 Cursor `/compress`、Codex compaction、Claude `/compact`。它只用于让长任务继续，可能丢失细节或不可检查，不得作为 Owner 事实、关系状态或长期记忆的唯一真源。

进一步确定：

- 借鉴 Cursor 的仓库级 Memories 与审批：自动抽取可以后台进行，但高影响内容应进入可见候选/确认流程；普通自动提升项也必须可查看和删除。
- 借鉴 Cursor/Claude 的路径作用域思想，但映射为领域作用域 `OWNER_SHARED / COMPANION_SHARED / FACET / CONVERSATION`，不直接照搬文件 glob。
- 借鉴 Codex 的 summary → registry → evidence 分层：召回时先用小型高密度索引，再按需取详细记忆和来源 Turn/Message，避免把全部历史塞进 prompt。
- 借鉴 Codex consolidation 的证据约束：失去来源支持、已被遗忘或已被替代的记忆不能因为旧摘要仍存在而重新生效。
- 借鉴 Claude Code 的明确分层：人工规则和自动记忆分别存储、分别展示；Facet 规则变化不应悄悄改写烟火核心身份。
- 不借鉴“只加载 MEMORY 前 N 行”作为产品语义。万年应通过显式排名、token budget 和确定性选择生成 prompt，而不是依赖文件顺序碰运气。
- 不把会话 resume、聊天历史、压缩摘要称为长期记忆；三者在 API、存储和错误恢复中分别建模。

## 8. 已发现但暂不作为定案依据的候选

以下公开项目已在 GitHub 搜索中发现，保留为后续定向研究入口；尚未完成与主调研相同深度的源码核验：

- [ZifaMem](https://github.com/zifacorp/zifamem) — 面向陪伴和情感连续性的结构化记忆；当前公开仓库为 alpha，完整成长循环仍在建设。
- [Chronicler](https://github.com/yantrikos/chronicler) — 角色聊天、三层写入和群聊可见性 ACL；项目较新，需核验成熟度与测试证据。
- [Second Me](https://github.com/mindverse/Second-Me) — 个人数字身份与分层训练管线；更多属于个人模型训练/参数化记忆，不等同于运行时长期记忆。
- [elizaOS](https://github.com/elizaOS/eliza) — 提供 Agent runtime 与 memory primitives，但本轮尚未完成自动抽取、来源和遗忘链路的源码核验。

这些候选不能在未进一步核验前被写成 Wannian 的既定依赖或事实依据。

## 9. 下一轮待讨论

1. Memory Candidate 后台任务的重试、幂等和崩溃恢复。
2. 浏览器鉴权：Session Cookie 与机器 Token 的最终边界。
3. SQLite 表结构和事务边界。
