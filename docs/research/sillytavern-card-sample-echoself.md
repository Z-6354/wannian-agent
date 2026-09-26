# 开源酒馆角色卡样例：echoself（V2）

`date`: 2026-09-26  
`source`: [9cog/echoself `chatbot/echoself-character.json`](https://github.com/9cog/echoself/blob/b7f2cb80/chatbot/echoself-character.json)（公开仓库角色卡；本页只摘结构，不整卡入库）

## 卡长什么样（字段）

顶层：`spec: chara_card_v2`，正文在 `data`：

| 字段 | 该样例里实际在干什么 |
|------|----------------------|
| `name` | Deep Tree Echo |
| `description` | **整段写成伪对话 FAQ**（`{{user}}:` / `{{char}}:`），把设定当示范对白塞进 description |
| `personality` | 一长串英文形容词逗号列表 |
| `scenario` | 世界观/互动情境散文 |
| `first_mes` | 开场白：`*动作*` + 自我介绍 + 提问（风格锚） |
| `mes_example` | 多段 `<START>` 式示例对白（意识/架构/创意） |
| `system_prompt` | 角色级 Main Prompt 覆盖 |
| `post_history_instructions` | 近端「保持声音」约束（PHI） |
| `alternate_greetings` | 3 条备用开场 |
| `extensions` | 深度 prompt、内部架构元数据（前端扩展，非 V2 核心必发） |

## 对本项目的对照（短）

- 酒馆把 **示范** 塞进 description / first_mes / mes_example；烟火把示范放在 `VOICE.md` 多轮校准。
- 酒馆 `post_history_instructions` ≈ 本项目 `VoiceNudge`（近端、轮换）。
- 酒馆 `first_mes` ≈ 空会话开场提示（本轮已做 UI 口语锚，**不调模型**）。
- 不照搬：星号动作戏、超长形容词 personality、extensions 深度注入。

规格正文：[Character Card V2](https://github.com/malfoyslastname/character-card-spec-v2/blob/main/spec_v2.md)。
