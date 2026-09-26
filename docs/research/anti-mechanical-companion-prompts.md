# 反机械感：陪伴 / 角色聊天如何显得「像人」

`status`: **reference** — 对照研究，不是施工单  
`date`: 2026-09-25  
`question`: 用户反馈：即便有 soul.md 风格 Skill，聊天 AI 仍偏机械、像客服助手。同类项目（尤其 SillyTavern / 角色卡生态）用什么**可核验**手段降低机械感？对烟火 / wannian-agent 有何可落地启示？

相关已有文：[github-roleplay-prompt-patterns.md](./github-roleplay-prompt-patterns.md)、[yanhuo-personality-references.md](./yanhuo-personality-references.md)、[yanhuo-character-draft.md](../plans/archive/emotional/yanhuo-character-draft.md)、[prompt-skill-direction-c.md](../plans/archive/0.2.4/prompt-skill-direction-c.md)、当前种子 [`SOUL.md`](../../wn-server/app/src/main/resources/prompt-seeds/SOUL.md)。

---

## Executive summary（什么真正治「机械感」——按效力排序）

下列排序综合 **官方/规范文档与源码字段职责**，以及角色卡社区对「有声感」的共识提炼。后一类标为 **社区归纳**，不是单一厂商声明。

| 秩 | 手段 | 为何有效 | 证据层级 |
|---:|------|----------|----------|
| 1 | **Few-shot 示例对话 + 首条消息定调** | 模型从「已发生的发言」学长度、句式、是否写动作；比形容词人格更稳。ST 官方写明首条消息比其它定义更能约束风格与长度；示例块可挤出上下文。C.AI 官方 Definition 主用途即示例对话。 | **一手**：ST Character Design、C.AI Dialog Definitions |
| 2 | **近端指令（PHI / Author's Note / depth prompt）** | 长历史后，远系统提示对风格影响变弱；靠近生成点的指令覆盖力更高。 | **一手**：ST Prompts / Author's Note |
| 3 | **可观察行为规则 + 语言指纹，禁纯形容词堆叠** | 「温柔」不约束下一句；「被夸时用自嘲挡开」可执行。ACGN persona_builder、character-card-author 均强调此点。 | **一手模板** + **社区技能** |
| 4 | **动态上下文（World Info / Lorebook / Memory Book）** | 细节按关键词注入，既省常驻 token，又避免每轮复读同一段设定导致「公文腔」。 | **一手**：ST World Info、Agnai Memory、V2 `character_book` |
| 5 | **反助手 / 反谄媚 / 可冲突人格** | RLHF 默认「乐于助人」会盖过人设；需明确「可不同意、不全盘迎合、不每轮道歉」。Hermes SOUL、鲸鱼娘工作准则、卡作者 Pushback Law 同向。 | **一手**（Hermes/社区 persona）+ **社区归纳** |
| 6 | **情绪惯性与认知边界（不知用户内心）** | 「猜错 / 只根据可见信息」比全知助手更像人；禁止替用户写内心。 | **社区技能**（epistemic firewall）；ST 主提示也建议尊重用户自主 |
| 7 | **采样（温度 / 重复惩罚等）** | 低温 + 助手微调模型 → 模板句与重复结构；升温与抗重复减轻「同一句型」。不能单独替代人设。 | **一手**：ST Common Settings |
| 8 | **UI 纠偏（Swipe / Regenerate / Impersonate / Prefill）** | 不让坏样本进历史；Impersonate 可生成「用户侧」参考语感。 | **一手**：ST Prompt Manager |

**一句话**：机械感主要来自「**只有抽象人格条文 + 助手默认语气 + 历史里尽是工整回复**」，而不是「缺一份更长的 soul.md」。治本路径是 **示范说话（示例/首条）→ 近端强化 → 记忆/关系动态注入 → 允许冲突与不完美 → 采样与纠偏**。

---

## 1. SillyTavern（酒馆）

### 1.1 角色卡字段与 V2 / V3

**V2 规范**（[malfoyslastname/character-card-spec-v2 `spec_v2.md`](https://github.com/malfoyslastname/character-card-spec-v2/blob/main/spec_v2.md)）核心进提示词字段：

| 字段 | 规范职责 | 与「有声感」的关系 |
|------|----------|-------------------|
| `description` / `personality` / `scenario` | 常驻角色与场景 | 永久占上下文；过长会挤掉历史记忆（ST 文档警告） |
| `first_mes` | 首条角色消息 | **风格与长度的最强示范** |
| `mes_example` | 示例对话 | 有空位才插入，可被挤出；用 `<START>` 分块 |
| `system_prompt` | 覆盖全局 Main Prompt | 角色级「写什么类型的下一句」 |
| `post_history_instructions` | 覆盖全局 UJB/jailbreak 类 PHI | **生成前最后指令**，优先级通常高于远系统提示 |
| `alternate_greetings` | 首条消息的额外 swipe | 避免每次开场同一套 |
| `character_book` | 嵌入 lorebook | 关键词触发细节，不常驻灌满 |

**V3**（[kwaroran/character-card-spec-v3 `SPEC_V3.md`](https://github.com/kwaroran/character-card-spec-v3/blob/main/SPEC_V3.md)）是 V2 超集：`assets`、`nickname`、多语言 creator notes、`group_only_greetings`、日期等；**活力机制仍落在 first_mes / mes_example / PHI / lorebook**，不靠新字段单独治机械感。

### 1.2 Character Design 文档要点（一手）

来源：[SillyTavern-Docs `characterdesign.md`](https://raw.githubusercontent.com/SillyTavern/SillyTavern-Docs/main/Usage/Characters/characterdesign.md)

- **Description**：始终包含；写世界、外貌、性格、背景；格式自由（散文 / PList / Ali:Chat 等）。
- **永久 token**：Name + Description + Personality + Scenario —— **每轮必发**。
- **非永久**：First message 仅开场一次；Example messages 在上下文有空时保留，否则挤出（可强制保留）。
- **First message**：官方明确 —— *「模型更可能从首条消息学到风格与长度约束，而非其它任何内容」*。示例使用 `*动作*` + `"对白"` 混排。
- **Examples of dialogue**：`<START>` 分隔；`{{char}}:` / `{{user}}:`；示范「怎么说」，不是再堆形容词。
- **Character's Note**：按 **depth** 注入聊天历史（0 = 紧贴最新）；用于反复强化某特质 —— 与「远系统提示被历史淹没」问题直接对应。
- **Prompt Overrides**：角色级 Main Prompt / Post-History Instructions；`{{original}}` 可拼回全局默认。

### 1.3 Prompt 栈（一手）

来源：[SillyTavern-Docs `Prompts/index.md`](https://raw.githubusercontent.com/SillyTavern/SillyTavern-Docs/main/Usage/Prompts/index.md)

默认 Main Prompt：

> Write `{{char}}`'s next reply in a fictional chat between `{{char}}` and `{{user}}`.

文档强调：

1. **历史本身就是风格指南** —— 改 Main Prompt 对已有长聊影响有限；应纠历史、用 Author's Note、或 PHI。
2. **正向指令优于单纯否定**；「尊重用户自主」有时比「不要替用户说话」更有效（文档表述）。
3. **Show, don't only tell**：用 example messages 展示想要的回复。
4. **PHI**：用户消息之后、生成之前；通常优先级更高。
5. **World Info**：任意位置条件插入 lore / 临时指令 / 记忆。

「Jailbreak」在生态里常指 **PHI / UJB** 槽位（V2 规范把 `post_history_instructions` 定义为替换用户理解的 jailbreak 设置），**不一定**等于越狱违法内容；常见用途是格式、长度、OOC、反助手语气等**近端写作约束**。本产品若借鉴，应只取「近端写作约束」语义，不引入越狱叙事。

### 1.4 Author's Note（一手）

来源：[Author's-Note.md](https://raw.githubusercontent.com/SillyTavern/SillyTavern-Docs/main/Usage/Characters/Author's-Note.md)

- 可放在 Scenario 后，或 **In-chat @ depth**（越靠近底部影响越大）。
- **Frequency**：每 N 次用户输入插入一次，避免每轮刷同一条导致新机械感。
- 常见用法：长度、文体、`*动作*`/`"对白"` 格式、临时场景状态。

### 1.5 World Info（一手）

来源：[worldinfo.md](https://raw.githubusercontent.com/SillyTavern/SillyTavern-Docs/main/Usage/worldinfo.md)

- 关键词（含 regex）触发；仅 **Content** 进提示词。
- 可挂 Character / Persona / Chat；与全局 lore 合并策略可选。
- 可递归激活；有 token budget。
- **Pro tip**：不只写设定百科，也可插「此刻应如何回应」类指令。

对陪伴产品：等价于「关系事实 / 用户偏好 / 场景流程」的 **按需注入**，而不是把一切写进常驻 SOUL。

### 1.6 Sampling（一手）

来源：[Common-Settings.md](https://raw.githubusercontent.com/SillyTavern/SillyTavern-Docs/main/Usage/Common-Settings.md)

| 参数 | 文档说法 | 对机械感 |
|------|----------|----------|
| Temperature | &lt;1 更可预测；&gt;1 更多样 | 过低 → 模板句 |
| Repetition Penalty / DRY | 抑制上下文中已出现序列 | 减轻口头禅与三段式复读 |
| Top-p / Min-p / XTC 等 | 截断低/高概率尾 | 调「无聊 vs 跑偏」 |

**推断（非官方因果声明）**：陪伴向模型若固定极低温度 + 强助手 system，机械感会叠加；调采样是辅助，不能替代示例与近端指令。

### 1.7 Group chat / Impersonate / Prefill（一手）

- **Group**：[groupchats.md](https://raw.githubusercontent.com/SillyTavern/SillyTavern-Docs/main/Usage/Characters/groupchats.md) —— Talkativeness、Natural/List/Pooled 发言；Swap vs Join 卡。多角色「抢话」带来节奏变化；单核陪伴可借鉴的是 **不要每轮同等长度、同等结构**，而非真做群聊。
- **Impersonate**：Prompt Manager 的 generation trigger；按用户角色生成下一句，用于示范用户侧语气或推进（见 [prompt-manager.md](https://raw.githubusercontent.com/SillyTavern/SillyTavern-Docs/main/Usage/Prompts/prompt-manager.md)）。
- **Continue Prefill**：把 Continue 提示做成 **Assistant 角色消息**，用「已写开头」引导续写 —— 经典 prefill 技巧；可锚定句首语气，减少从「好的，我来帮你」起笔。

### 1.8 酒馆实践里「活」vs「机」差在哪（归纳，标清）

| 更有声感 | 更机械 | 依据 |
|----------|--------|------|
| 强 first_mes + 多场景 mes_example | 只有长 description、无示例 | ST 官方 |
| PHI/AN 管长度与格式 | 只改远 Main Prompt | ST 官方 |
| lore 按需；常驻短 | 设定全书每轮塞满 | ST / V2 / Agnai |
| 可拒绝、可猜错、句长波动 | 每轮「理解+共情+三建议+反问」 | 社区技能 + 助手默认行为 |
| 坏回复 swipe 掉 | 接受坏样本进历史 | ST「别放任不想要的行为」 |

---

## 2. 相关开源 / 官方项目

### 2.1 Agnai

- 角色 API 字段：`persona`、`sampleChat`、`greeting`、`scenario`、`systemPrompt`、`postHistoryInstructions`、`characterBook` 等（[character.ts](https://github.com/agnaistic/agnai/blob/75abbd5b0f5e48ddecc805365cf1574d05ee1ce5/srv/api/character.ts)）。
- **Memory Books**（[instructions/memory.md](https://github.com/agnaistic/agnai/blob/dev/instructions/memory.md)）：关键词触发 entry；depth 扫描近期消息；priority/weight 控制预算与靠近底部的权重 —— 明确动机是 **别把 Scenario/Persona/Sample 撑满上下文**。
- 与 ST 同属「卡 + lore + PHI」家族；治机械感机制同源。

### 2.2 RisuAI

- Lorebook wiki：[Lorebook](https://github.com/kwaroran/RisuAI/wiki/Lorebook) —— activation keys、insertion order、prompt。
- 源码/文案（[en.ts](https://github.com/kwaroran/RisuAI/blob/b126db93/src/lang/en.ts)）：区分 Main / Jailbreak / Author Note / Lorebook / Example Messages；**Example conversations affect output but don't use tokens permanently**（与 ST 示例可挤出一致）。
- 进阶：`@@depth` / `@@role` 等把指令钉在近端（文档与源码注释；属实现细节）。

### 2.3 TavernAI → SillyTavern

TavernAI 为前身；现行字段与文档以 **SillyTavern + Character Card V2/V3** 为准。V1 仅 name/description/personality/scenario/first_mes/mes_example；V2 补 system_prompt、PHI、book、alternate greetings。

### 2.4 Character.AI（官方文档）

- [Definition](https://book.character.ai/character-guide/character-attributes/definition)：大自由字段，**最常见用途是示例对话**；`name: message` 格式；`{{char}}` / `{{user}}` / `{{random_user_n}}`；长 Definition **重要内容放开头**（尾部可能被截断）。
- [Dialog Definitions](https://book.character.ai/character-guide/advanced-creation/dialog-definitions)：示例同时建模 **用词** 与 **话题**；用 random_user 避免把示例用户名当成当前用户事实。
- [Scene Creation](https://support.character.ai/hc/en-us/articles/41918454359451-Scene-Creation-Quickstart-Guide)：场景描写强调 **可观察动作**，避免替 `{{char}}` 规定内心（用户所选角色自带人格）—— 与「show don't tell / 不替用户写心」同族。

### 2.5 NovelAI / Kobold 风格

- ST 文档在 Character tokens 中列出 NovelAI 上下文档位；采样面板对接 Text Completion（Kobold/oobabooga 等）生态。
- **Lorebook** 一词来自 NovelAI 生态（V2 规范 explainer 亦提及）；机制 = 关键词动态插入。
- 经典 Text Completion「Story String + 示例 + 续写 header」依赖 **prefill 式句首**；Chat Completions 用 Prompt Manager 模拟。wannian 若走 Chat Completions API，对应物是 **system 分层 + 可选 assistant prefill**，不是 NovelAI 专有语法。

### 2.6 Open WebUI Personas

来源：[Models workspace docs](https://docs.openwebui.com/features/workspace/models/)

- 同一基座模型上挂不同 **system prompt + 参数 + 知识/工具** = 多 persona。
- 支持 `{{USER_NAME}}`、`{{CURRENT_DATE}}` 等动态变量；可关 Memory 注入以做「无个人上下文」模型。
- **局限（文档隐含）**：主要是「换 system + 参数」，**没有** ST 级 first_mes/mes_example/PHI/depth 工具链；单靠一篇长 system 仍易机械 —— 与烟火现状更接近，也说明 **仅 SOUL 不够**。

### 2.7 Hermes Agent `SOUL.md`

来源：[personality.md](https://raw.githubusercontent.com/NousResearch/hermes-agent/main/website/docs/user-guide/features/personality.md)

- SOUL = 系统提示 **slot #1** 身份；不写仓库临时规则（归 `AGENTS.md`）。
- 好 SOUL：语气、直接程度、不确定与分歧、**避免谄媚与空话**。
- `/personality` 为会话级叠加；内置含 concise/technical 与夸张角色皮（kawaii 等）—— 角色皮证明 **短叠加可改声**，但 Hermes 定位是工程师 Agent，不是陪伴卡。
- 对烟火：SOUL 应继续管 **稳定判断与反助手底色**；「这一句怎么念」仍需示例与记忆。

### 2.8 DeepSeek Harness / 社区 persona 插件

一手示例：[whale-notify `agent.cordis.whale.yml`](https://raw.githubusercontent.com/Mochabafey/whale-notify/main/examples/agent.cordis.whale.yml)

- 分开写：形象、说话风格、**工作准则优先于卖萌**、渠道格式。
- 「卖萌是调味品」= 显式 **场景收敛**，防止角色表演压过任务 —— 烟火草案已吸收同类机制。
- 其它社区插件（阿蓝、dsh-soul-md 等）见 [yanhuo-personality-references.md](./yanhuo-personality-references.md)；**非官方统一人格**。

### 2.9 其它显式「反 AI 味」开源技能

| 项目 | 可核验要点 | URL |
|------|------------|-----|
| ACGN-character-skill | Layer0 必须是可执行行为；Layer2 **直接给台词例子**；禁抽象形容词 | [persona_builder.md](https://raw.githubusercontent.com/AusertDream/ACGN-character-skill/main/prompts/persona_builder.md) |
| character-card-author | 缺陷/不顺从/私生活；认知防火墙；情绪惯性；四条输出节奏法（短、不翻译情绪、反击、碎片句）；禁套话清单 | [SKILL.en.md](https://raw.githubusercontent.com/foreverse-app/character-card-skills/main/skills/character-card-author/SKILL.en.md) |
| OpenPersona | 身份/性格/说话/边界分字段；能力与人格分层 | [SKILL.md](https://github.com/acnlabs/OpenPersona/blob/main/skills/open-persona/SKILL.md) |

**注意**：character-card-author 含对社区卡的统计与竞品对比，属 **作者宣称的实证归纳**，不是 ST 官方规范；移植到烟火时应筛掉恋爱卡专用套路，保留「短句、可观察动作、可推回、不每轮征求」等通用律。

---

## 3. Prompt engineering 模式清单（降机械感）

| 模式 | 做法 | 机制 |
|------|------|------|
| Few-shot / mes_example | 2–5 段不同情境短对话 | 分布匹配：长度、标点、是否动作 |
| 首条定调 | first_mes 即目标文体 | ST：风格学习最强信号 |
| `*动作*` + 对白 | 可选；陪伴产品可弱化 RP 星号，保留「先处境后话」 | 格式锚定 |
| 长度变化规则 | 用户短→你短；禁止每轮四段式 | 破「客服模板」 |
| 反公司助手腔 | 禁「作为 AI」「很高兴为您」「总结如下」；可不同意 | 对抗 RLHF 默认 |
| 人格冲突 | 明确「何时说不」+ 示例拒绝轮 | Pushback / ACGN Layer3 |
| 不完美言语 | 省略主语、顿一下、半句转移；忌完美议论文 | Fragment Law（社区） |
| 情绪连续 | 注入上一轮情绪残留 / 关系摘要；禁止每轮情绪清零 | AN frequency + 记忆 |
| OOC | 约定括号 OOC 或「出戏问设定」如何处理 | 减少破功后回助手模式 |
| 认知边界 | 只能根据对话与可见记忆推断；鼓励猜错 | Epistemic firewall（社区） |
| 正向替代否定 | 「写短句」优于长篇「不要……」 | ST Prompts 文档 |
| 近端重复关键约束 | PHI/depth，而非加长 SOUL | ST Author's Note / PHI |

---

## 4. 通用技术表（technique → 为何有效 → ST/他者 → wannian 适用性）

| 技术 | 为何有效 | ST / 他者实现 | wannian 适用性 |
|------|----------|---------------|----------------|
| 示例对话 | 示范 > 描述 | `mes_example`、C.AI Definition | **高**：扩 SOUL「语气校准」为多情境、含拒绝/短答/俏皮各 1 条；或独立 `VOICE_EXAMPLES.md`（注意 layer 字符预算） |
| 首条/开场消息 | 定长度与声 | `first_mes`、alternate greetings | **中**：新会话问候可由 UI/种子发出「烟火式」开场，避免通用「有什么可以帮你」 |
| 近端写作指令 | 压过长历史里的助手腔 | PHI、Author's Note @ depth、Continue Prefill | **高**：Assembler 在 user 消息后加短「本轮声音」块；或 turn 级 soft reminder（勿每轮全文复读 SOUL） |
| 行为化人设 | 可执行 | ACGN Layer0；烟火草案已部分采用 | **已有**：继续把剩余形容词改成触发→动作→收敛 |
| 语言指纹 | 可辨认声线 | Personality 口癖/标点；卡作者 fingerprint | **中**：烟火刻意少口癖；可用「节奏」指纹（短段、少感叹号）代替叠词 |
| 动态 lore/记忆 | 具体事实 → 熟悉感 | World Info、Agnai Memory、V2 book | **高且已规划**：关系/记忆注入；缺记忆时保持初识（草案已写） |
| 反谄媚 / 可分歧 | 破 yes-man | Hermes SOUL；鲸鱼工作准则 | **已有条文**：需用**拒绝示例**加固，否则模型仍偏迎合 |
| 采样 | 降模板概率 | ST Temperature / rep penalty | **待查产品线**：本仓 `wn-server` 检索未见明确 temperature 暴露；若 API 固定低温，应允许聊天模式略升 |
| Swipe / 编辑历史 | 坏样本污染风格 | ST swipe、regenerate | **中**：UI 已有会话；可强调「不满意就重生成且勿基于坏回复续聊」 |
| Prefill | 锚定句首 | Continue Prefill | **低–中**：工具通话可能冲突；仅纯陪伴轮可试 |
| Impersonate | 用户侧示范 | ST Impersonate trigger | **低**：单核陪伴非必需 |
| 群聊 talkativeness | 节奏不齐 | Group Natural Order | **低**：可隐喻为「不是每轮都主动追问」 |
| Skill 场景流程 | 情境步骤 | 烟火 companion Skill | **已有**：治的是「场景应对」，不治「句式像助手」 |

---

## 5. Gap analysis：烟火当前 SOUL + Skill vs 上表

对照文件：

- [`prompt-seeds/SOUL.md`](../../wn-server/app/src/main/resources/prompt-seeds/SOUL.md)
- [`skill-seeds/yanhuo-companion-dialogue/SKILL.md`](../../wn-server/app/src/main/resources/skill-seeds/yanhuo-companion-dialogue/SKILL.md)
- [yanhuo-character-draft.md](../plans/archive/emotional/yanhuo-character-draft.md)
- [prompt-skill-direction-c.md](../plans/archive/0.2.4/prompt-skill-direction-c.md)（常驻 MD + Skill 索引；层字符预算）

| 维度 | 烟火现状 | 酒馆/卡生态常见做法 | 缺口 |
|------|----------|---------------------|------|
| 人设形态 | 行为规则 + 判断原则，质量高 | 行为规则 + **大量示范台词** | 示例偏少（SOUL 约 4 条；Skill 表约 5 行），且偏「正确应对」，缺 **短促、推回、沉默、接梗失败** |
| 声音锚定 | 规则描述「自然短段」 | first_mes + mes_example 强锚定 | **无独立 first_mes 机制**；新聊若系统/UI 用助手开场，会污染风格 |
| 近端强化 | 主要靠常驻 system 分区 | PHI / AN @ depth | **无**「紧贴 user 消息」的短声音约束；长会话后 SOUL 效力被历史助手腔稀释（ST 已描述此现象） |
| 动态信息 | Memory / Relationship 设计方向正确 | lorebook 关键词 | 若注入偏摘要公文体，仍可能「正确但不像人」——需 **口语化关系摘要** |
| 反助手 | 有「不讨好、可不同意」 | 显式 ban 助手套话 + 拒绝 few-shot | 缺 **禁止句式清单** 与 **拒绝轮示例** |
| 采样 | 未见产品内文档化调参 | 聊天常用中高 temp + 抗重复 | **缺口待工程确认** |
| Skill | 场景流程优秀 | 流程 ≠ 声线 | Skill 加载后仍可能用「步骤感」造句；需强调「流程约束意图，不约束五段论排版」 |
| 预算 | `layer-char-budget` 默认 4000 | ST 警告永久定义过半上下文 | 继续加长 SOUL 会挤记忆；应 **加示例要换出空话，或把示例放到可挤出/按需层** |

**结论**：烟火不是「没人设」，而是 **示范密度、近端约束、坏样本治理、采样** 相对酒馆生态偏薄；机械感与「Skill/SOUL 写得像产品说明书」同构 —— 条文正确，但下一 token 仍落在助手分布上。

---

## 6. Recommendations for wannian（优先、可执行；区分层）

### P0 — 提示词内容（改 MD，不动架构也可试）

1. **扩写「语气校准」为多轴 few-shot**（SOUL 或独立示例文件）：至少覆盖  
   - 用户极短（「嗯」「还好」）→ 烟火也短  
   - 用户胡说/危险简化 → 温和但明确不同意  
   - 轻松接梗一次后收回  
   - 被指出错误（已有）  
   - 用户只要倾听、不要建议（Skill 已有，升为常驻短例）  
2. **加「禁止的助手腔」短清单**（正向改写优先）：如不使用「作为 AI」「我理解您的感受，以下是建议」「总结一下您的需求」。用「你会怎么说」一句替代。  
3. **每条性格保留「收敛条件」**（草案已有）——审 SOUL 是否每条都有；缺则补。  
4. **Skill 末尾加反步骤腔**：明确禁止「1.2.3. 共情段落 + 建议段落 + 开放问题」固定骨架；允许一句结束。

### P1 — 运行时注入（Assembler / 记忆）

1. **Turn 近端「Voice nudge」**（≤200–400 字）：放在 user 消息之后或紧前，轮换 3–5 条变体（长度匹配、可观察关心、可不同意、本轮最多一问），**Frequency 式**不必每轮全文相同（学 Author's Note）。  
2. **关系/记忆注入口语化**：写「你上次说周一要交方案，还没提结果」而非「用户目标：方案交付；状态：未知」。  
3. **开场消息**：新会话第一条助手消息用烟火声（可配置），避免通用欢迎助手句。  
4. **可选**：用户消息极短时，注入「匹配长度」提示（对应 Brevity Law，去恋爱卡包装）。

### P2 — Sampling

1. 查清当前 Chat Completions 默认 `temperature` / `top_p` / presence-penalty；聊天模式与工作/科研模式 **分档**（聊天略高、抗重复略开）。  
2. 文档化推荐档，避免「人设改了采样没动」。

### P3 — UI / 产品行为

1. 重生成：默认不把弃用回复留在「已采纳历史」里（或标记不计风格）。  
2. （可选）提供「换一种说法」= swipe，降低用户接受机械句后的路径依赖。  
3. 不优先做 Impersonate/群聊；ROI 低于示例与近端 nudge。

### 明确不做或慎做

- 不把酒馆恋爱卡的星号 RP、高强度推拉、每轮内心 OS 原样搬进烟火。  
- 不靠无限加长 SOUL 超过 layer budget。  
- 不把「jailbreak」话术当产品能力宣传；只用 PHI **写作约束**语义。  
- 不在无记忆时假装熟悉（草案已禁）——假熟悉是另一种机械假人感。

---

## 7. Sources

### SillyTavern / 规格（一手）

- [Character Design](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/Usage/Characters/characterdesign.md) · [raw](https://raw.githubusercontent.com/SillyTavern/SillyTavern-Docs/main/Usage/Characters/characterdesign.md)
- [Prompts index](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/Usage/Prompts/index.md) · [raw](https://raw.githubusercontent.com/SillyTavern/SillyTavern-Docs/main/Usage/Prompts/index.md)
- [Author's Note](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/Usage/Characters/Author's-Note.md)
- [World Info](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/Usage/worldinfo.md)
- [Common Settings (sampling)](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/Usage/Common-Settings.md)
- [Prompt Manager](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/Usage/Prompts/prompt-manager.md)（Impersonate / Continue Prefill）
- [Group Chats](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/Usage/Characters/groupchats.md)
- [Character Card V2 spec](https://github.com/malfoyslastname/character-card-spec-v2/blob/main/spec_v2.md)
- [Character Card V3 SPEC](https://github.com/kwaroran/character-card-spec-v3/blob/main/SPEC_V3.md)

### 其它一手

- [Hermes Personality & SOUL.md](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/personality.md)
- [Agnai Memory Books](https://github.com/agnaistic/agnai/blob/dev/instructions/memory.md)
- [Agnai character API fields](https://github.com/agnaistic/agnai/blob/75abbd5b0f5e48ddecc805365cf1574d05ee1ce5/srv/api/character.ts)
- [RisuAI Lorebook wiki](https://github.com/kwaroran/RisuAI/wiki/Lorebook)
- [Character.AI Definition](https://book.character.ai/character-guide/character-attributes/definition)
- [Character.AI Dialog Definitions](https://book.character.ai/character-guide/advanced-creation/dialog-definitions)
- [Character.AI Scene Creation](https://support.character.ai/hc/en-us/articles/41918454359451-Scene-Creation-Quickstart-Guide)
- [Open WebUI Models / personas](https://docs.openwebui.com/features/workspace/models/)
- [ACGN persona_builder.md](https://raw.githubusercontent.com/AusertDream/ACGN-character-skill/main/prompts/persona_builder.md)
- [鲸鱼娘 persona 示例 YAML](https://raw.githubusercontent.com/Mochabafey/whale-notify/main/examples/agent.cordis.whale.yml)

### 社区技能（标明非厂商规范）

- [character-card-author SKILL.en.md](https://raw.githubusercontent.com/foreverse-app/character-card-skills/main/skills/character-card-author/SKILL.en.md) — 反 AI 味节奏法、认知防火墙等
- [OpenPersona SKILL](https://github.com/acnlabs/OpenPersona/blob/main/skills/open-persona/SKILL.md)

### 本仓库上下文

- [github-roleplay-prompt-patterns.md](./github-roleplay-prompt-patterns.md)
- [yanhuo-personality-references.md](./yanhuo-personality-references.md)
- [yanhuo-character-draft.md](../plans/archive/emotional/yanhuo-character-draft.md)
- [prompt-skill-direction-c.md](../plans/archive/0.2.4/prompt-skill-direction-c.md)
- [`SOUL.md` 种子](../../wn-server/app/src/main/resources/prompt-seeds/SOUL.md)
- [`yanhuo-companion-dialogue` Skill](../../wn-server/app/src/main/resources/skill-seeds/yanhuo-companion-dialogue/SKILL.md)

---

## 9. 已落地（wannian · 2026-09-25）

对标 Hermes「人格常驻 / Skill 按需」后已入仓（非独立版本号，挂在既有提示词层）：

| 项 | 做法 |
|----|------|
| 常驻声线 | 新增 `prompts/VOICE.md`；陪伴说法迁出 Skill |
| 按需 Skill | 种子仅保留 `how-to-remember`；旧 companion Skill 降级为兼容指针 |
| 近端 nudge | `VoiceNudge` 紧贴 user 消息后（PHI 语义） |
| 历史助手腔 | 近讯标签「烟火:」+ 卫生注；`rewrite:` 剔除上一句助手 |
| 采样 | `wannian.model.sampling.*` 默认温 0.85 / top_p 0.95 / presence 0.35 |
| UI | 末条助手「换一种说法」；空态开场更口语 |

既有 data-dir：下次启动会**补缺** `VOICE.md`，不覆盖已改 SOUL；若要吃新 SOUL 种子，需自行合并或删后重装种子。