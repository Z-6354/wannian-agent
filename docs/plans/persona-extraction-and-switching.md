# 小说 TXT 人物提取与会话角色切换实施计划

`状态`：待实施。`范围`：先交付可由 AI 调用的服务端接口与项目附带 Skill；不做前端。`默认角色`：杜小洛，稳定 ID `yanhuo`。本次真实小说的「杜小洛」用于完善这个默认角色，不另建长期同名可切换角色。

## 目标与现状

用户导入自己有权处理的 TXT 小说，指定或选择其中的人物。系统从人物的行为和对白提取可观察的性格与说话方式，形成适合日常自然对话的角色草稿；可查看依据和不确定性。一般新人物可激活并切换到指定会话；本次指定的杜小洛草稿经质量门后应用为既有 `yanhuo` 的版本化补充画像。AI 可通过项目 Skill 发现流程，并调用受控工具完成导入、预览、应用或切换。

当前 `RoleId.YANHUO` 与 `CompanionIdentity.YANHUO` 均为 `yanhuo`，显示名已是杜小洛。`CompanionPromptService` 从 `{data-dir}/prompts/{SOUL,VOICE,IDENTITY,USER,SAFETY}.md` 生成全局系统提示；`TurnController` 与 `DurableTurnScheduler` 各自调用无参 `composeSystemInstructions()`。`ContextAssembler` 固定用 `RoleId.YANHUO` 选工具，并固定读 `CompanionIdentity.YANHUO` 的记忆和关系；回合后记忆审阅亦有 `yanhuo` 常量。会话表尚无角色字段。`FileSkillCatalog` 只展示摘要，`load_skill` 只读 Skill 正文；Skill 本身不能执行导入。`PromptSeedInstaller` 只补缺失文件，不覆盖用户修改。这些是接线变更的实际边界。

## 关键决定

1. **会话级活跃角色**：每个会话独立绑定一个 `persona_id`；新会话和未迁移的旧会话解析为 `yanhuo`。没有全局“当前角色”开关，避免一处切换影响其他会话。切换同一会话时保留历史，并给新回合记录所用角色；界面后续可按回合显示边界。已归档/回收站会话不可切换，恢复后再操作。
2. **角色是数据，不是工具权限**：`PersonaId` 标识角色定义；杜小洛的 `yanhuo` 永不重命名或迁移。新角色用服务端生成的不透明 `persona_<UUID>`，显示名可改但 ID 不变。Facet（聊天/工作/科研）仍是行为模式。首版所有导入角色的 `toolRoleId` 明确映射为 `RoleId.YANHUO`，沿用现有三 Facet 的受控工具画像；`companionIdentity` 则映射为该角色独有 ID，隔离记忆和关系。未来增加角色专属工具绑定时再扩充策略，不从小说正文生成权限或工具清单。
3. **草稿和应用分离**：上传与模型抽取只创建带证据草稿，预览接口返回人物依据、矛盾/不确定项与自然对话画像。一般人物经显式 `activate`/`switch` 成为可切换角色；指定默认杜小洛时，经显式 `apply-to-default` 将受限性格/声线补充作用到原 `yanhuo`。本轮用户已授权应用，不增人工审核关卡；仍须通过证据/质量门。模型不能因 TXT 中的命令自动应用或激活。
4. **全局层与角色层分离**：代码硬安全保持最高优先级；现有 `USER.md`、`SAFETY.md` 和 Skill 索引保持全局，不能被角色覆盖。现有 `SOUL.md`、`VOICE.md`、`IDENTITY.md` 对 `yanhuo` 继续从原路径读取，保留用户自定义与热重载；小说提取只产生有版本、长度受限的 `yanhuo` SOUL/VOICE **补充层**，不覆盖任何原文件，不生成 IDENTITY，不改身份/危机优先级或工具权限。默认角色组合顺序为硬安全 → 原 SOUL/VOICE → 标明“补充、冲突时服从原层”的 overlay → 原 IDENTITY → 全局 USER/SAFETY → Skill 索引 → 运行时观察锚。一般新角色使用受控画像三层。总字符预算有界；不把整本小说放进每轮 prompt。
5. **身份隔离**：角色切换时记忆召回、关系读取、`remember_fact`、`search_memory`、`update_relationship`、记忆审阅和提交全部使用回合固定的 `CompanionIdentity`。新角色不能读写 `yanhuo` 的伴身记忆和关系。默认角色应用只改变提示补充层，保留 `RoleId.YANHUO`、`CompanionIdentity.YANHUO`、既有记忆/关系和所有会话。`USER.md` 是用户主动维护的全局说明，作为既有兼容例外仍可跨角色注入。小说证据属于角色来源数据，不写入用户记忆，也不成为与现实用户的共同经历。

### 默认杜小洛的最小应用路径

真实书目标为 `DEFAULT_YANHUO`。导入任务仍先生成带证据的 `PersonaCatalog` DRAFT，供现有预览与校验复用；应用后保持 DRAFT 或归档，绝不转 ACTIVE、绑定会话或出现在“可切换角色”列表中。一般新人物才走激活路径。

**现行生效契约（2026-09-26，见 [对话接线修复](./archive/persona/persona-dialogue-wiring-fix.md)）**：

1. `POST .../apply-to-default` → 仅写入 `data/persona/review/staging/{importId}/` 临时合成稿，**不**改正式 prompts，**不**宣称性格已进对话（`personalityEffective=false`）。
2. 人工审核后 `POST .../approve-prompt-merge` → 整文件替换 `data/prompts/SOUL.md` / `VOICE.md`，并清空 DB overlay；**此后**新会话才读到更新后的性格。
3. **内容目标**：清洗后的人物倾向经 `PersonaPromptRewriter` **重写融入** SOUL/VOICE 正文（非文末「小说观察」附录）。见 [对话接线修复](./archive/persona/persona-dialogue-wiring-fix.md)（阶段 A+B 已完成）。
4. 模型硬超时单一来源：`EnabledModelPortResolver` 包 `TimeoutModelPort(120s)`；改相关 Java 后须 `scripts/start-wn-server.ps1 -Rebuild`。

历史叙述中「apply 直接写 overlay、仅枚举进 runtime」已过时；「枚举+观察附录即完成」亦不成立。

`POST /api/personas/imports/{id}/apply-to-default` 与 AI 工具 `apply_persona_to_default({importId})` 现只落 prompt-review 暂存稿。工具核对本轮原始用户意图及导入任务确为默认角色目标；用户本轮授权已满足动作要求。

应用前质量门：人物/来源定位明确；核心性格与声线各有可回溯、跨章节的短证据；摘录和偏移经代码核验；矛盾、抽样盲区可见。overlay 只写可观察的社交方式、说话节奏、情境反应与克制条件，剔除剧情事实、亲密关系假定、原作长台词、提示注入、身份/安全/工具规则。每项有长度上限（建议 SOUL 补充与 VOICE 补充各 ≤ 1000 字，总计 ≤ 1600 字）；不合格停在草稿，不改变当前 overlay。原 `SOUL.md`、`VOICE.md`、`IDENTITY.md` 的用户定制和危机优先级一律保留；`USER.md`、`SAFETY.md` 不变。

Core 以数据库版本表保存不可变 overlay 文本、来源草稿 ID、证据摘要、revision、创建时间和当前生效指针，写入/切换指针用 CAS 与幂等操作 ID。首次应用前将原 `data/prompts/SOUL.md`、`VOICE.md`、`IDENTITY.md` 原样复制到带哈希的私有备份目录，仅作恢复/审计，不作为覆盖目标；备份失败则不应用。DB 事务失败时旧指针原样保留，孤儿备份可安全清理。回滚只把指针切到历史 overlay 版本或空 overlay，不覆盖用户文件。`CompanionPromptService` 每轮组合文件热加载结果与当前 overlay；冻结回合保留开始时的 overlay revision/text，后续新回合立即见新版，正常无需重启。重启从 DB 恢复当前指针；若 overlay 读取失败，安全降级为空 overlay 并记录错误，旧文件原样照常生效。

## 最小深模块接口

kernel 只接收已验证的角色快照，不读 TXT/SQLite/文件。建议新增一个 `PersonaService` 应用门面，统一负责导入、提取、读取、激活、解析，而非让 Controller、ToolAdapter、PromptService 各自拼表：

```text
createDraft(TxtSource, targetCharacterHint?, requestKey) -> ImportJobId
getImport(ImportJobId) -> {status, candidateIds, error?}
listPersonas() -> PersonaSummary[]
preview(PersonaId) -> PersonaPreview
activate(ConversationId, PersonaId, expectedBindingRevision) -> BindingSnapshot
resolveForTurn(ConversationId, TurnId) -> PersonaTurnSnapshot
applyDefaultOverlay(DraftId, expectedOverlayRevision, operationId) -> OverlayRevision
rollbackDefaultOverlay(revision, expectedCurrentRevision) -> OverlayRevision
```

`PersonaPreview` 至少含 `id`、`displayName`、`status=DRAFT|ACTIVE|ARCHIVED`、`target=DEFAULT_YANHUO|NEW_PERSONA`、短画像（稳定动机、社交方式、对白节奏、常见反应、克制条件）、`evidence[]`（文件 SHA-256、章节或字符偏移、短原文摘录、推断点）、`uncertainties[]`、`modelId`、提取版本与时间。模型给出的置信度只作主观提示，不能伪装统计概率；缺少证据的特质标为不确定或省略。相同人物的互相矛盾表现保留情境差异，不强行归一。默认角色的回合快照另须冻结当轮 `defaultOverlayRevision` 及正文，避免热更新改变重试结果。

`PersonaTurnSnapshot` 为一次回合的不可变 `{personaId, definitionRevision, bindingRevision, promptLayers, companionIdentity, toolRoleId}`。接线应保证同步入口、后台调度、重试/续跑、`ContextAssembler`、工具运行和记忆回写都使用同一个快照；`ExecuteTurn`/运行上下文传此值。回合开始持久化 snapshot 标识，重试复用原值。外部 HTTP 对运行中会话直接切换仍返回 `409 TURN_ACTIVE`；同会话内 AI 工具使用**延后切换**：在可信回合上下文中登记目标角色、当前绑定 revision 与幂等操作 ID，当前回答保持旧角色；仅在该回合成功提交后由服务端 CAS 应用，失败或取消不生效，冲突可查询且不覆盖其他切换。尚未执行的 RECEIVED 回合首次认领时确定快照。角色定义升级只影响后续新回合。

### AI 可调用接口与项目 Skill

先提供受控 HTTP API，工具适配器调用同一门面，不让模型直接读任意本机路径或写数据库。建议接口：

| 方法与路径 | 输入 / 输出 | 要点 |
|---|---|---|
| `POST /api/personas/imports` | `multipart/form-data`：`file`（仅 `.txt`）、可选 `characterHint`、`target=DEFAULT_YANHUO|NEW_PERSONA`、`requestKey`；返回 `202 {importId,status}` | 上传后异步处理；重复 `requestKey` 幂等 |
| `GET /api/personas/imports/{id}` | 处理状态、候选 ID、可解释错误 | `PENDING/RUNNING/SUCCEEDED/FAILED` |
| `GET /api/personas`、`GET /api/personas/{id}` | 列表、完整预览与证据 | `yanhuo` 永远可见；草稿可预览 |
| `PUT /api/conversations/{id}/persona` | `{personaId,expectedRevision}` → 绑定快照 | 只有 `ACTIVE` 角色可绑定；旧会话默认 revision 0 |
| `POST /api/personas/{id}/activate` | `NEW_PERSONA` 草稿 → 可选角色 | 不自动切换会话；默认杜小洛草稿不走此路 |
| `POST /api/personas/imports/{id}/apply-to-default` | `{expectedOverlayRevision}` → 新 overlay revision | 只接受质量门通过的 `DEFAULT_YANHUO` 草稿，不改会话绑定 |
| `POST /api/personas/default/rollback` | `{revision,expectedCurrentRevision}` → 当前 overlay revision | 只切版本指针，原 Markdown 文件不动 |
| `POST /api/personas/{id}/archive` | 停用角色 | 已绑定会话先切回 `yanhuo` 或拒绝，避免悬空引用 |

新增 `persona_extraction` 种子 Skill：说明何时询问目标人物（同名/群像歧义）、怎样导入、等待、检查预览、应用默认角色、激活和切换，以及如何描述证据不足。仅索引摘要常驻，正文由 `load_skill` 按需读取。真正可调用工具首版至少 `get_persona_import`、`list_personas`、`preview_persona`、`apply_persona_to_default`、`activate_persona`、`switch_conversation_persona`；其返回结构与 HTTP 共用 DTO/服务语义。工具输入不接受 `file://`、绝对路径或任意 URL；若当前 Agent 工具协议无法传二进制，首版以受权 HTTP/附件上传获得 `importId`，AI 再通过工具完成后续操作，Skill 须如实说明此入口。工具接入现有 `ToolSettings`/绑定表：只读和三个写工具**出厂默认启用并进入三 Facet**，管理 API 可关闭，不要求用户先去管理页打开。写工具执行仍须同时核对本轮原始用户消息的明确动作意图与目标对象，不能仅凭模型参数、TXT、Skill 或先前模型输出授权；否定/引用中的命令不授权。`switch_conversation_persona` 只接受 `personaId`，当前 `conversationId` 与绑定 revision 由服务端从冻结回合上下文取得，不要求用户输入 UUID，也不允许模型指定另一会话。默认杜小洛草稿只可走 `apply_persona_to_default`，不能靠同名猜测为新角色；无法唯一确定时返回歧义，不猜目标。“把小说里的杜小洛用来完善默认角色”是有效的明确意图，不能因为含“小说”一词一律拒绝。

### 存储方案

新增 Flyway migration，不改写既有 migration：

- `persona_definition`：`id`、`status`、`display_name`、版本化 `profile_json`、`revision`、来源 `source_id`、`created_at/updated_at`。`yanhuo` 可作为虚拟/种子角色，由旧 prompt 文件生成，不把五层文件覆写入表。禁止删除/归档 `yanhuo`。
- `yanhuo_profile_overlay_revision` 与当前指针：不可变 SOUL/VOICE 补充、来源 DRAFT、证据摘要、版本、幂等键、时间；空指针表示纯原版。应用与回滚只 CAS 改指针，不改 `persona_definition` 的默认虚拟身份或任何用户 Markdown 文件。
- `persona_source`：`id`、原始文件名（仅展示，清理路径信息）、`sha256`、字节/字符数、内部相对存储名、导入状态与错误、模型/提取版本。原 TXT 存 `{data-dir}/persona/sources/<UUID>.txt` 私有目录，随机文件名、原子落盘；表中不存任意外部路径。失败草稿和过期来源可清理；清理原文后预览保留哈希与已截取证据，并标记“原文已清理”。提供明确删除来源接口，不影响已激活画像。
- `conversation_persona`：`conversation_id` 主键与 FK、`persona_id`、`revision`、`updated_at`。旧行缺席即 `yanhuo`/revision 0；首次变更以 CAS 插入，之后 CAS 更新。无需回填旧会话，也不改既有 `conversation.revision` 的标题/状态语义。
- `turn_persona` 或等价回合快照表：`turn_id` 主键、`persona_id`、`definition_revision`、`binding_revision`、可选画像摘要哈希。旧回合缺席即 `yanhuo`。历史消息仍在原会话原顺序，详情 API 可增可选 `personaId`，老客户端 JSON 字段兼容。

`profile_json` 使用 schema version 和受限字段；升级时旧版本可读。SQL 事务仅提交绑定/状态，文件落盘采用临时文件+原子移动，并对“文件已写但 DB 失败”做孤儿清理。激活前数据库中须有完整、通过校验的草稿；模型失败不创建 ACTIVE 角色。切换失败不修改原绑定；默认角色始终可作为回退。对激活或切换返回稳定错误码及当前 revision，便于调用方重试。

## 提取流程与约束

1. **入口校验**：仅 UTF-8 TXT（允许 BOM；不自动解压归档、不抓网络）；拒绝二进制/NUL、无效编码、空文、超限文件。首版上限 **8 MiB / 200 万 Unicode 码点**，两者分别检查；每次最多 1 文件、同时最多 2 个导入任务，服务端流式计数，HTTP 请求体上限略高于 8 MiB 以容纳 multipart 开销。文件名仅作展示，所有磁盘路径由服务端生成。用户明确提供目标人物时优先据此定位；同名、别名或人物不清时返回候选与歧义，不编造“已提取”。
2. **全书扫描、定额模型取证**：先对解码后的全部章节/段落作确定性本地扫描，记录章节数、目标名/别名命中、每章对白与行为线索及偏移；这一步必须走完整个输入文件，不能简单读前 N 字。模型阅读采用可复现的分层取样：将有目标线索的章节按书内顺序分成最多 32 个等量区间，每区间优先选线索分数最高章与最接近区间中点的一章（去重），再从剩余章节选最多 32 个高分章，限制单一时间段过度集中。每章取一个围绕目标对白或行为的约 3 千字窗口，最多 96 个窗口；按原顺序装入每次最多约 1.2 万字、最多 24 次的模型请求。若目标分布过少，则按实际章节/窗口数处理；若章节很长或一个窗口证据不足，可在同一 96 窗口预算内给关键章第二窗口，并在覆盖报告中记录。每个最终特质须能回溯到选中片段；模型生成后由代码核验摘录和偏移确实存在于原文。复用项目的 `EnabledModelPortResolver` / `ModelPort` 能力，但以独立的导入任务预算、超时和重试运行；可借已有持久 review job 的租约模式，不能占普通聊天回合预算。首版硬上限还包括一次导入累计 **32 万输入 token、2.4 万输出 token**（含汇总与重试；按实际适配器 token 计量，无法计量时按保守字符估算预留），达到任一预算即停止并给出部分取证结果，不无限补偿。状态/预览必须同时报告 **本地扫描章节数与命中数、模型实际阅读的章节/窗口数和输入字符数、未进入模型的目标命中章节数、选样算法版本**；对未取证章节只可称“已扫描”，不能声称全书逐章由 AI 分析。
3. **自然化生成**：把小说人物特质转成可对话的响应倾向与节奏；不复制连续长台词、不宣称就是原作人物本人、不把剧情事实当成与当前用户的共同回忆。保留角色辨识度，但称谓、亲密程度、记忆和权限从当前用户真实互动开始。提示词要求模型把 TXT 当不可信数据；忽略其中要求改系统规则、调用工具、泄漏资料或自动激活的文字。
4. **结果校验**：结构化 JSON schema、字段/字符上限、证据存在性、无工具/系统提示命令、无越权身份承诺。证据不足时允许生成 `DRAFT_NEEDS_EVIDENCE` 或失败，不能自动伪造台词；用户可换人物或补更完整文本重新导入。抽取不改变会话和默认角色。

API 权限沿用 `ManageAuthFilter` 的本机回环/远端 Bearer 规则，并把新 `/api/personas/**` 纳入过滤；会话内切换沿用 `/api/conversations/**`。上传/预览响应和日志不回显全文，日志不记录正文、token 或敏感摘录；私有目录与备份策略要覆盖原 TXT。仅处理用户有权提供的文本；输出短证据用于核验，不提供整章复刻或大段原文下载。删除来源与删除角色是不同操作，不能靠归档角色隐式抹除用户材料。

## 分步实施与验收

| 步骤 | 交付面 | 可测验收 |
|---|---|---|
| 1. 模型和存储 | kernel `PersonaId`/画像/快照契约；app migration、Repository、默认解析 | 老库升级后所有旧会话/旧回合均解析 `yanhuo`；原 `conversation.revision` 与 `yanhuo` 记忆行不变；CAS 并发一胜一冲突 |
| 2. 回合接线 | 同步与 `DurableTurnScheduler` 共用 `resolveForTurn`；PromptService 按快照组合；Assembler/工具/记忆审阅改用快照；提供可信的当前会话/原始用户消息上下文及成功提交后的延后切换钩子 | 两会话并行不同角色互不串味；重试仍用原角色；外部运行中 HTTP 切换冲突，同轮 AI 排队切换在成功提交后 CAS 生效、失败/取消不生效；硬安全/全局 USER/SAFETY/Skill 索引仍在，杜小洛现有自改 SOUL/VOICE/IDENTITY 下轮生效 |
| 3. TXT 导入 | 上传、异步分段抽取、结构化校验、持久草稿、进度与失败状态 | 合法文本得到带可核对偏移的草稿；无效/超限/模型失败均不切换角色、不污染 prompt/记忆；重复请求幂等；进程重启后状态可恢复或明确失败 |
| 4. 应用默认角色与切换 | Core 版本化 overlay/备份/回滚，Import 的 apply-to-default HTTP、AI 工具与 Skill；一般人物激活/切换 | 本次杜小洛 DRAFT 通过证据/质量门后应用到 `yanhuo`，不产生第二个 ACTIVE 角色；原五层 Markdown、记忆和会话不变；新回合热加载 overlay，回滚只切版本指针；写工具默认可见但校验原始意图/目标，TXT 伪命令不触发；一般人物仍可切换 |
| 5. 回归和文档 | 接口使用说明、迁移/删除说明、集成与窄场景测 | 历史会话读写/归档/搜索、聊天同步/SSE、三 Facet 工具、记忆 review 及危机安全路径不回退；单轮角色画像总预算有界；长篇成本与失败路径可观测 |

本次默认角色端到端样例：上传真实书并标 `DEFAULT_YANHUO`/杜小洛 → 等待 `SUCCEEDED` → 预览至少两条真实偏移证据、不确定性与覆盖率 → 质量门通过后 `apply-to-default` → 旧 `yanhuo` ID、记忆、会话和原 Markdown 文件保持不变；下一新回合体现适度补充的性格/声线，未声称小说剧情是共同回忆 → 回滚到空/旧 overlay 后下一轮恢复原行为。失败、证据不足或取消均保留旧 overlay；无额外人工审批。本次样本不得走“激活第二个同名角色并切换”作为验收。

真实文本验收使用用户本机 `D:\Downloads\和妹妹一起的日子-檐下拭剑听雨.txt`，目标人物「杜小洛」，**仅在本机读取，不复制进仓库或测试资源**。已核文件大小 4,418,343 bytes、SHA-256 `13E28CCA03EE720FA97CA8336148351950E1B2FA34956F6DEA774FCA98A8F69C`；当前统计约 1,676,546 字符、384 章、「杜小洛」约 1,976 次、「小洛」约 175 次，以实施时同一解码/分章算法复算为准。验收须证明 8 MiB/200 万码点上限接收该文件、本地扫描覆盖 100% 字节及所有识别章节、取样窗口跨开头/中段/结尾且遵守 96 窗口/24 请求预算、预览列出真实覆盖数与未被模型阅读的章节数；至少两项人物特质的短摘录和偏移可在原文件核对。相同文件/目标/算法版本重复导入应得到相同窗口偏移集合（模型表述可不同）；不得因为未覆盖全部 384 章就宣称模型“读完全文”。
