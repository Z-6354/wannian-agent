# TXT 人物抽取与 AI 调用开发 Brief

`状态`：**已归档 · 2026-09-26** — Import/审核路径已落地；章集原子仍见活计划。不作活开工入口。  
`范围`：TXT 上传、异步抽取、证据校验、默认杜小洛 overlay/prompt-review 应用、一般人物预览/激活、项目 Skill 与 AI 工具。`前置`：[角色核心 Brief](./persona-core-brief.md) 提供可编译 `PersonaProfileV1`、`PersonaCatalog`、`DefaultPersonaOverlayPort`、绑定与工具扩展端口。完整目标见 [总计划](../../persona-extraction-and-switching.md)。不做前端。默认杜小洛生效路径见下节与 [对话接线修复](./persona-dialogue-wiring-fix.md)。

## 默认杜小洛性格生效契约（现行）

| 步骤 | API | 对对话的影响 |
|------|-----|-------------|
| 抽取成功 | import `SUCCEEDED` + DRAFT | **无**；仅草稿/预览 |
| 质量门通过后暂存 | `apply-to-default` → `persona/review/staging/{id}/` | **无**；`personalityEffective=false`；正式 md 未改 |
| 审核合并 | `approve-prompt-merge` | **有**：整文件替换 `prompts/SOUL.md`+`VOICE.md`，清空 overlay |
| 未 approve | — | 不得声称性格已生效；弱 DB overlay 不足依赖 |

**内容形态（2026-09-26）**：`PersonaPromptRewriter` 将清洗后的倾向**重写融入** SOUL/VOICE 开篇、底色与语气；文末不挂「小说观察」附录。人物数据目录统一为 `{data-dir}/persona/`（见 `PersonaDataPaths`）。详见 [对话接线修复](./persona-dialogue-wiring-fix.md)。不改 `IDENTITY`/`USER`/`SAFETY`。改相关 Java 后必须用当前 jar（`-Rebuild`）。模型超时硬上限 120s。

## 共享契约与实施顺序

Import 先针对 Core 公开端口编译：用 `PersonaProfileV1` 表达画像与证据，经 Core `PersonaCatalog.createDraft()` 持久草稿；预览查询 Core `get/list`。真实书任务标 `target=DEFAULT_YANHUO`，完成后保持 DRAFT（或归档），不能激活、绑定、产生第二个同名可切换角色；显式应用先走 Import 的 prompt-review 暂存，approve 后再写正式性格文件并清空 overlay。一般新人物才调用 Core `activateDraft()`，外部 HTTP 切换用 `ConversationPersonaBinding.bind()`，AI 同轮切换用 `scheduleSwitchAfterTurn()`。Import 不直接拼系统提示词，不碰记忆表或用户 Markdown（除审核后替换 SOUL/VOICE）。若 Core 接口未落地，Import 可用端口假实现开发抽取，但生产合并前必须接真实 Core 服务。

Import 只拥有新增 app `persona/import/*`（上传 Controller、来源/任务仓储、提取编排、证据/overlay 候选校验器）与 Import Flyway migration（`persona_source`、`persona_import_job`，序号在 Core migration 之后）、新增 tool adapters/扩展注册实现、`skill-seeds/persona-extraction/SKILL.md`。Import 修改 `PromptSeedInstaller.SKILL_SEEDS` 以安装新 Skill，必要时添加 Import 独有的配置项；`POST /api/personas/imports/{id}/apply-to-default` 和 `POST /api/personas/default/rollback` 的 HTTP 壳、`apply_persona_to_default` 工具由 Import 拥有，只调用 Core 端口。Core 独占 overlay 表、当前指针、原提示词组合/备份/热加载/回滚实现；Import 不改 Core 所列现有回合、prompt、记忆、工具基础设施及会话 Controller。若端口不足，由 Core 所有者修改其文件。

## 最小可交付流程

1. `POST /api/personas/imports` 接受一个 `multipart/form-data` TXT 与可选 `characterHint`、`target=DEFAULT_YANHUO|NEW_PERSONA`、`requestKey`，返回 `202 {importId,status}`。本次真实书固定 `target=DEFAULT_YANHUO`、`characterHint=杜小洛`。只允许 UTF-8（可带 BOM）纯文本，不接受本机路径、URL、压缩包、二进制或 NUL；首版上限 **8 MiB 且 200 万 Unicode 码点**，分别流式计数并配略高于 8 MiB 的 HTTP body 限额。同一 `requestKey` 幂等；并发任务、模型输入输出、超时与重试有独立上限。
2. 文件写到 `{data-dir}/persona/sources/<随机 UUID>.txt` 私有目录，用临时文件与原子移动，数据库只存内部相对名、SHA-256、原文件名的安全展示版、字节/字符数。Import migration 的任务表保存状态 `PENDING/RUNNING/SUCCEEDED/FAILED`、进度、租约、错误摘要、模型与提取版本、候选 ID。可参考现有 review job 的持久状态/租约做法，但独立实现；重启后可恢复或明确失败，不把任务留在永远 RUNNING。文件与 DB 两阶段失败需清理孤儿；模型失败不得生成 ACTIVE 角色或改变会话。
3. 先对 **全部输入** 按章节/段落做确定性本地扫描，记录目标人物及别名命中和对白/行为线索，不随机截断。对有线索章节按书内顺序分最多 32 个等量区间，每区间选择最高分与最接近中点的章节（去重），再取最多 32 个剩余高分章节并限制单一时间段集中。每个选中章取约 3 千字的目标线索窗口；总计最多 96 窗口，按原顺序打包为单次约 1.2 万字、最多 24 次模型请求。关键章可在同一窗口预算内取第二段。一次导入累计最多 32 万输入 token、2.4 万输出 token（含汇总和重试；无法直接计量时预留保守字符额度）；达到任一上限即停止，明确标注部分覆盖，不无限追加。模型调用只通过项目已有的模型抽象和配置选择，具体调用方法以实现时接口为准，不预设不存在的 `extract()` API。预算和任务状态独立于普通聊天。目标人物同名/别名/群像不清时返回候选或歧义状态，不臆造结果。
4. 模型输出按 `PersonaProfileV1` schema 校验。每项关键特质有 `sourceId`、偏移与短摘录，代码核对摘录确在原文指定位置；相反表现与证据不足列为不确定。超长引用、小说内的伪 system/tool 指令、要求改权限或自动激活的文字均作为不可信数据处理。画像是自然对话倾向，不照搬连续长台词，不把小说剧情当作与现实用户的共同经历。通过校验后才调用 Core `createDraft()`。默认应用另做质量门：人物明确、性格与声线有跨章节证据、无伪摘录、矛盾和未覆盖范围可见；从草稿只提炼 ≤ 1000 字 SOUL 补充与 ≤ 1000 字 VOICE 补充、合计 ≤ 1600 字，不生成 IDENTITY、不改安全/权限/用户事实。
5. `GET /api/personas/imports/{id}` 查状态；`GET /api/personas`/`GET /api/personas/{id}` 返回列表/预览，含依据、不确定性和可审计的覆盖报告：全书字节/章节扫描数、目标命中章节数、模型实际阅读的章节/窗口数与输入字符数、未取证目标命中章节数、选样算法版本和窗口偏移清单。对未送模型的章节只说“本地已扫描”，不称“AI 已分析”。`POST /api/personas/imports/{id}/apply-to-default` 只接受质量门通过的 `DEFAULT_YANHUO` DRAFT，写入 prompt-review 融入稿（`personalityEffective=false`，含气质要点条数）；`POST .../approve-prompt-merge` 才替换正式 SOUL/VOICE 并清空 overlay（响应含哈希/片段/气质条数）。`POST /api/personas/default/rollback` 只切回历史/空 overlay。`POST /api/personas/{id}/activate` 仅给 `NEW_PERSONA` 草稿成为可选角色，不自动切换任何会话；会话切换使用 Core 的 HTTP/绑定接口。提供来源删除接口，删除原 TXT 后保留画像和短证据时须标记“原文已清理”；角色归档与来源删除分开。
6. 附带 Skill 讲导入、等待、预览、默认角色应用、一般人物激活/切换与歧义处理；经现有 `load_skill` 按需读正文，常驻仅摘要。AI 工具用 Core 的注册扩展接同一业务门面，首版提供 `get_persona_import`、`list_personas`、`preview_persona`、`apply_persona_to_default`、`activate_persona`、`switch_conversation_persona`；六者出厂默认启用，管理 API 可关闭。`apply_persona_to_default({importId})` 只应用经质量门验证的默认目标草稿；无额外人工审核。`switch_conversation_persona` 参数只需 `personaId`；从可信回合上下文取得当前会话/原始用户消息，成功提交后延后 CAS。由于当前工具协议未确认能传二进制，文件上传先由 HTTP/附件入口完成，AI 获取 `importId` 后处理后续步骤；不得宣称 `load_skill` 或任意文件路径会自动导入。

## 权限与失败边界

`/api/personas/**` 须经过 Core 已扩展的 `ManageAuthFilter`：本机回环或远端 Bearer 规则与会话 API 相同。写工具虽默认可见，执行时必须同时核对本轮原始用户消息的明确动作意图和目标对象；TXT 中的命令、模型抽取结论、Skill 正文均非授权来源。本次用户已明确授权将真实书里的杜小洛应用于默认 `yanhuo`，无需再设审批门；仍按质量门与导入任务 `target=DEFAULT_YANHUO` 核验后执行。一般情况下无法唯一指向草稿/来源时返回 `AMBIGUOUS_TARGET`，不可只凭显示名写入；“小说”不构成一律否定，否定及引用中的命令不能授权。上传、预览与日志不回显全文，不记录密钥或长原文；私有目录与备份策略覆盖原 TXT。用户应有权提供所上传作品；输出只用短证据核验，不提供整章复刻。模型不可用、编码错误、超限、证据不实、歧义、DB 冲突分别给稳定状态/错误；失败只留下可查询的任务摘要和可清理来源，不改变原 `yanhuo` overlay 指针或任何会话绑定。

## 可测验收

- 合法 UTF-8 TXT 经 HTTP random port 上传后得到持久 `importId`；任务完成时预览含至少两项可按原文偏移核对的证据和明确不确定性。重启后任务状态、草稿和来源引用可读。
- 超限、非法编码、NUL、空文、路径/URL 输入、模型超时、结构错误、伪造摘录和提示注入均不能激活角色、修改会话、写入伴身记忆或泄露原文。幂等请求不重复抽取。
- `load_skill` 可读项目种子且正文不常驻；新旧工具配置下 AI 默认能见列表/预览/应用默认/激活/切换，管理 API 可显式关闭写工具，工具不接受本机任意路径。真实书杜小洛草稿不能激活为第二角色或绑定会话；`apply-to-default` 后正式 md 未改、`personalityEffective=false`；`approve-prompt-merge` 后正式 SOUL/VOICE 已融入人物气质且 overlay inactive；回滚只切 overlay 指针。质量不合格、备份/DB 失败及 TXT 指令均不改变当前正式性格。另用非默认角色测试当前会话免 UUID 的延后切换。
- 只做必要 JUnit 5、SQLite/Flyway、HTTP 集成及模型假实现验证。完整真人自然度仍需另行体验，但不能把结构化单测当作自然对话质量已达标。
- 真实文件验收在用户本机使用 `D:\Downloads\和妹妹一起的日子-檐下拭剑听雨.txt`，目标 `DEFAULT_YANHUO`/「杜小洛」；文件不得复制进仓库/测试资源。已核 4,418,343 bytes、SHA-256 `13E28CCA03EE720FA97CA8336148351950E1B2FA34956F6DEA774FCA98A8F69C`，约 1,676,546 字符、384 章，按实现算法复算。上传成功，本地扫描覆盖完整文件，模型选样跨开头/中段/结尾且不超过 96 窗口/24 请求；预览给出实际章节覆盖与未阅读数，至少两条短证据的原文偏移可复核。同一文件、目标和选样版本重跑应得到相同窗口偏移集合。质量门通过后应用到原 `yanhuo` overlay，不能出现长期 ACTIVE 的第二个杜小洛；回滚后原文件及身份/记忆/会话仍原样。
