# GitHub 角色扮演项目：提示词组织方式

`status`: 2026-09-24 检索；用于完善烟火提示词，不作为角色原作资料。

## 可核对的项目

| 项目原文 | 实际组织方式 | 对烟火的取用 |
|---|---|---|
| [SillyTavern 角色设计文档](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/Usage/Characters/characterdesign.md)、[提示词结构](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/Usage/Prompts/index.md) | 常驻角色描述、性格与场景；首条消息和示例对话提供声音范例；可触发的 World Info 提供动态信息。文档也提醒常驻定义会占用聊天上下文。 | 常驻 `SOUL.md` 写稳定行为与少量短示例；用户事实和关系变化由本项目记忆/关系系统注入，避免写死在角色卡。 |
| [Hermes Agent 的 SOUL.md 文档](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/personality.md) | SOUL 放持续的身份、语气、直率程度、不确定性与分歧处理；临时任务或仓库规则分开。已有 SOUL 文件不会被种子覆盖。 | 补足烟火在不确定、纠错、不同意用户时的行为；保持四层提示词职责明确。 |
| [ACGN-character-skill 的人格生成模板](https://raw.githubusercontent.com/AusertDream/ACGN-character-skill/main/prompts/persona_builder.md)与[分析模板](https://raw.githubusercontent.com/AusertDream/ACGN-character-skill/main/prompts/persona_analyzer.md) | 从表达、情感、决策、关系和阶段变化提取可执行规则；强调不能只写抽象形容词，并给各场景短台词示例。原项目面向既有虚构角色还原。 | 把“细心”“偶尔俏皮”转换为触发条件、具体回应动作和收敛条件；烟火是原创角色，不导入原作故事。 |
| [AI Town 架构](https://github.com/a16z-infra/ai-town/blob/main/ARCHITECTURE.md) | 对话层把人物信息和检索到的相关记忆加入提示词；长期记忆按需召回。 | 不在静态 SOUL 中伪造用户历史；让真实记忆影响熟悉感。 |
| [OpenPersona 的人格 Skill](https://github.com/acnlabs/OpenPersona/blob/main/skills/open-persona/SKILL.md) | 把身份、性格、说话风格、边界分字段组织，能力与人格分层。 | `SOUL` 稳定性格、`IDENTITY` 名称称谓、`USER` 信息边界、`SAFETY` 陪伴限制、Skill 场景流程。 |

## 本项目的具体改法

1. 常驻规则从标签改成可观察的行为：面对初识、渐熟、错误、分歧、情绪、任务，各有明确的回应方式。
2. 写少量短示例作为语气校准，不要求逐字重复，也不占用整个常驻预算。
3. 把关系阶段视为运行时证据，不由提示词根据回合数自行推断；缺记忆时保持初识分寸。
4. Skill 保留需要判断场景和选择回应动作的流程；技术问答不触发陪伴流程。
5. 每层受 `wannian.prompt.layer-char-budget` 默认 4000 字符限制；超过会被截断，因此增加信息要优先选对行为有影响的内容。

以上是根据项目文档和源文件作出的设计归纳，不代表这些项目认可烟火的人设内容。
