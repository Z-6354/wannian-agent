# 角色核心与会话切换开发 Brief

`状态`：**已归档 · 2026-09-26** — Core 端口已落地；不作活开工入口。活工作见 [提取总计划](../../persona-extraction-and-switching.md) / [章节原子](../../persona-chapter-atomic-import.md)。  
`范围`：角色定义、会话绑定、回合快照、提示词/记忆隔离、HTTP 切换，以及默认杜小洛的版本化补充画像端口。`依赖`：无新前端、无 TXT 抽取。完整产品目标见 [总计划](../../persona-extraction-and-switching.md)。本 brief 先交付可用的角色内核与受测试的草稿、overlay 端口，供导入工作接入。

## 交付边界

杜小洛仍为默认，稳定 ID `yanhuo`；既有 `data-dir/prompts/{SOUL,VOICE,IDENTITY,USER,SAFETY}.md` 修改继续生效，不迁移、不覆盖。老会话没有绑定行时解析为 `yanhuo`/revision 0；老回合没有快照行时解析为 `yanhuo`。会话 A 切换不影响会话 B 或新会话。历史消息保留原样，并可按回合查到当时角色。

新增 `PersonaId`、`PersonaDefinition`、`PersonaProfileV1`、`PersonaTurnSnapshot` 与 `PersonaCatalog`/`ConversationPersonaBinding` 端口。`PersonaProfileV1` 约定短的 SOUL/VOICE/IDENTITY 画像字段、来源引用和证据项的稳定 JSON schema；证据项含 `sourceId`、字符偏移、短摘录、对应推断、不确定标记。Profile 解析及字段预算由 Core 校验，TXT 中如何得出它由 Import 决定。`PersonaCatalog` 至少提供 `createDraft(validatedProfile, requestKey)`、`activateDraft(personaId, expectedRevision)`、`get/list` 与 `archive`；这些方法是 Import 的共享入口，写库规则只在 Core 一处实现。

`ConversationPersonaBinding` 提供 `bind(conversationId, activePersonaId, expectedBindingRevision)` 与 `resolveForTurn(conversationId, turnId)`。前者用独立绑定 revision 做 CAS；草稿不能绑定，**外部 HTTP** 在回合运行时不能直接切换，已归档/回收站会话不能切换。Core 还须提供 `scheduleSwitchAfterTurn(turnId, currentConversationId, activePersonaId, expectedBindingRevision, operationId)`：同轮 AI 工具只持久登记意图，当前回合保持旧角色；回合成功提交后由 Core 的幂等钩子 CAS 应用，失败/取消不应用，冲突可查询而不覆盖其他切换。`resolveForTurn` 首次认领时持久化不可变快照，重试复用已存快照。切换失败保持原绑定；请求切回 `yanhuo` 始终可用。暂存但尚未认领的 RECEIVED 回合按首次执行时的绑定确定身份。

工具权限与角色人设分离。首版所有导入角色沿用 `RoleId.YANHUO` 的 CHAT/WORK/RESEARCH 工具画像，`CompanionIdentity` 则为该角色独有 ID。`yanhuo` 仍对应 `CompanionIdentity.YANHUO`。回合快照必须贯通同步 `TurnController`、异步 `DurableTurnScheduler`、`TurnEngine`/`ContextAssembler`、工具调用、记忆召回/回写、关系读写、完成后 review 调度；不能只有 prompt 换人而继续读写杜小洛记忆。危机硬安全路径保持优先。

提示词装配由按会话解析的快照驱动。杜小洛：硬安全 → 原文件 SOUL/VOICE → 当前 `yanhuo` 受限 SOUL/VOICE overlay → 原文件 IDENTITY → 全局 USER/SAFETY → Skill 索引 → 观察锚；overlay 与原层冲突时服从原层，不得包含身份、危机、权限或剧情共同记忆。原五层继续原路径、保留用户修改和热加载；一般导入角色三层读受控 `profile_json`。各层及整体有界，回合快照冻结 overlay revision/正文，重试不漂移。旧 `composeSystemInstructions()` 如仍被测试使用，可保留委托到 `yanhuo`，生产回合必须改用带快照入口。

Core 新增 `DefaultPersonaOverlayPort.apply(validatedDraft, expectedOverlayRevision, operationId)`、`current()`、`rollback(revision, expectedCurrentRevision)`。使用 Core migration 的不可变 overlay revision 表及当前指针，CAS 与幂等键在单次 DB 事务内完成；失败保留旧指针。首次应用前将当前 `data/prompts/SOUL.md`、`VOICE.md`、`IDENTITY.md` 原样复制到带哈希的私有备份目录，仅留恢复/审计，不覆盖任何 Markdown；备份失败不应用。回滚只切历史 revision 或空指针，用户文件仍原样。新回合即时读新指针/热加载用户文件，进程重启从 DB 恢复指针；overlay 读取失败安全退回空 overlay 并记录错误。`yanhuo` ID、记忆、会话、USER/SAFETY 不变。

## Core 拥有的文件与接口

- 独占新增 kernel `persona/*` 契约和 app `persona/core/*` 存储/服务、Core Flyway migration（排在现有 V016 之后，具体序号以合并时最大值为准）。Core migration 建 `persona_definition`、`conversation_persona`、`turn_persona` 与 `yanhuo` overlay revision/当前指针；不建来源文件或导入任务表，不修改旧 migration。`persona_definition` 的来源引用为可空字符串，不对尚不存在的 Import 表建 FK。
- 独占修改现有 `CompanionPromptService`、`PromptComposer`/其输入契约、`ContextAssembler`、`TurnController`、`DurableTurnScheduler`、`TurnEngine` 和所需回合上下文、记忆/关系工具与 review 接线、`ToolRuntimeConfig`、`ManageAuthFilter`、会话详情 DTO。只增兼容字段；旧 JSON 调用不要求新参数。
- Core 拥有 `PUT /api/conversations/{conversationId}/persona` 和 `GET /api/conversations/{conversationId}/persona`。新 `/api/personas/**` 由 Import 实现，但 Core 应在认证过滤器中提前覆盖这个前缀。
- Core 在工具装配处提供一个明确的扩展端口（描述符、适配器和可见性由受控注册器提供）；Import 只实现新扩展，不直接改 Core 已改动的 `BuiltinToolPool`、`YanhuoToolBindings`、`ToolUsePolicy`、`ToolRuntimeConfig`。`get_persona_import`、`list_personas`、`preview_persona`、`apply_persona_to_default`、`activate_persona`、`switch_conversation_persona` 出厂默认启用并进入三 Facet；旧 `wannian.json` 缺新增键时补默认值，已有显式 off 保持 off，管理 API 可关闭。可见不等于授权：写工具仍由 Core 的受控注册器核对本轮原始用户消息的动作意图，再由 Import 核对目标对象；TXT/Skill/模型输出不能授权。禁止仅因“小说”一词否决“用小说里的杜小洛完善默认角色”。
- Core 将冻结的当前 `ConversationId`、`TurnId` 和原始用户消息传给工具执行上下文（可扩展 `TurnMemoryPending` 或等价可信结构）；不得从模型参数推断会话，也不要求用户在聊天中输入 UUID。Import 的 switch 工具只可对该当前会话调用延后切换端口；跨会话仍由经过认证的 HTTP API 按 UUID/revision 操作。

Core 不修改 `PromptSeedInstaller`、`FileSkillCatalog`、`skill-seeds`、上传/抽取 Controller、来源文件目录或 Import 的 migration。双方唯一共享的是上述已编译端口与 Profile schema，不共享编辑同一生产文件。

## 验收与交接

1. SQLite/Flyway 从旧库升级后，旧会话/旧回合仍显示杜小洛；`yanhuo` 记忆和关系行不改。并发绑定 CAS 一胜一冲突，外部 HTTP 运行中切换返回稳定 `409` 与当前 revision；同轮 AI 切换在成功提交后生效，失败/取消不生效，幂等重试不重复切换。
2. 两会话分别用 `yanhuo` 和测试创建的角色发回合，同步、异步与重试均使用固定角色；记忆/关系/工具回写不串库。切回杜小洛后，原自定义 SOUL/VOICE/IDENTITY 与既有记忆恢复可见。
3. Skill 索引、全局 USER/SAFETY、代码硬安全与危机路径仍存在；导入角色不能夹带系统指令、工具授权或原文长段。历史列表、详情、SSE 和三 Facet 回归通过。
4. 交给 Import 的可编译契约、错误码与最小示例明确：`createDraft` 接受已验证 `PersonaProfileV1`；默认目标 DRAFT 不转 ACTIVE，`DefaultPersonaOverlayPort.apply` 只写受限 SOUL/VOICE overlay，`rollback` 只切版本指针；一般人物才 `activateDraft`/`bind`。Core 使用假画像独立验证应用前文件备份、原五层哈希不变、质量不合格拒绝、事务失败旧指针不变、热加载/重启/回滚、已冻结回合不漂移及 `yanhuo` 记忆/会话不变。默认配置和旧配置迁移后工具可见，显式 off 仍可关闭。
