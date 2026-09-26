# 烟火性格构建：角色参考资料

`status`: 资料整合，非烟火人设定稿。检索日期：2026-09-24。角色草案见 [yanhuo-character-draft.md](../plans/archive/emotional/yanhuo-character-draft.md)。

## 阅读方法

本文件把**原作品信息**、**社区人格文档**和**面向烟火的推断**分开。证据优先级：创作者／出版社／动画官方资料；游戏内台词与音频（这里经玩家 Wiki 索引）；社区作者公开的提示词原文；社区角色卡和二手概括。官方资料说明原角色，社区提示词说明作者如何写人格，两者不能混作同一套设定。下文仅短述所见内容，不复制角色卡或大段台词。

## 项目边界

- 烟火是唯一的 `CompanionIdentity`，聊天、工作、科研模式共享身份与关系；见 [memory.md](./memory.md)。
- 常驻人设放 `prompts/SOUL.md` 与 `IDENTITY.md`；`SKILL.md` 通过摘要索引和 `load_skill` 按需加载，适合特定场景的处理流程；见 [提示词与 Skill 方向](../plans/archive/0.2.4/prompt-skill-direction-c.md)。
- 当前种子含常驻 `SOUL.md` + `VOICE.md`（声线示例与禁助手腔）；陪伴说法**不是**按需 Skill。见 [anti-mechanical-companion-prompts.md](./anti-mechanical-companion-prompts.md)。

## 角色资料与可借鉴机制

| 参考角色 | 可核验资料 | 可借鉴的行为机制（归纳，并非复制角色） | 证据限制 |
|---|---|---|---|
| DeepSeek 娘／鲸鱼娘 | [社区人设插件](https://github.com/Kaalia0912/dsh-whale-musume-persona)、[DeepSeek Whale-chan 项目](https://github.com/Neko3000/deepseek-whalechan)、[DS 酱 Agent](https://github.com/YOUYYU/deepseek-girl-agent) | 轻微嘴硬与善意行动形成反差；角色风格不妨碍认真完成任务；用场景和示例对话控制语气 | 均为不同作者的社区创作；未查到 DeepSeek 官方统一人格或官方性格 Skill。Whale-chan 的 Skills 主要面向图像创作。 |
| 柴郡（《碧蓝航线》） | [角色台词索引](https://wiki.biligame.com/blhx/%E6%9F%B4%E9%83%A1) | 活泼、主动表达喜欢、频繁亲昵称呼与邀约 | 玩家 Wiki 收录游戏台词，性格概括是基于台词的推断；不能直接照搬对指挥官的亲密关系。 |
| 安克雷奇（《碧蓝航线》） | [角色台词索引](https://wiki.biligame.com/blhx/%E5%AE%89%E5%85%8B%E9%9B%B7%E5%A5%87) | 短句、停顿、直接表达不安与信任，并愿意学习 | 「老师」和依赖关系是原角色设定；烟火不应自动继承其称谓、能力缺口或照护关系。 |
| 椎名真昼（《邻家的天使大人》） | [角色官网](https://otonarino-tenshisama.jp/)、[声优访谈](https://www.joqr.co.jp/ag/article/175249/) | 外在温柔、会照顾人；把优秀表现建立在努力上；亲近时仍有普通人的犹豫与需求 | 访谈是演者理解，角色与周的恋爱关系不能直接映射到烟火与用户。 |
| 椎名ましろ／椎名真白（《樱花庄的宠物女孩》） | [MBS 官方作品介绍](https://www.mbs.jp/sakurasou/) | 寡言、直率、专注创作的气质可参考 | 官方明确其天才画家与生活能力欠缺；后者不宜当作烟火的能力或依赖设定。 |

用户提及的「稚名真昼」「稚名真白」在所查官方资料中分别写作**椎名真昼**和**椎名ましろ**；「真白」是后者常见中文写法。

## 现成文档的可用程度

- [鲸鱼娘完整 persona 示例](https://raw.githubusercontent.com/Mochabafey/whale-notify/main/examples/agent.cordis.whale.yml) 在 `persona.config.text` 中写了身份、形象、语气和工作准则；[preset 元数据](https://raw.githubusercontent.com/Mochabafey/whale-notify/main/examples/preset.yml) 有角色摘要。这是可直接阅读的社区提示词原文，不是官方设定。
- [「阿蓝」插件原始提示词](https://raw.githubusercontent.com/keeshakulbida948-tech/dsh-plugin-fun/main/index.js) 分开定义性格、说话习惯、严肃任务的收敛条件和表情包使用规则。阿蓝与鲸鱼娘设定不同，不能合并成同一角色。
- [DSH SOUL 卡插件](https://github.com/Scorp1o117/dsh-soul-md) 有自定义人格卡机制，但没有内置的 DeepSeek 娘卡片原文。
- [OpenPersona 的人格 Skill](https://github.com/acnlabs/OpenPersona/blob/main/skills/open-persona/SKILL.md) 提供身份、性格、说话方式、边界的结构参考；它是通用框架，不是上述角色的官方设定。
- [character-card-author](https://github.com/foreverse-app/character-card-skills/blob/main/skills/character-card-author/SKILL.en.md) 可借鉴角色卡字段与语言指纹的写法。
- [真昼社区角色卡](https://cards.sillytavern.one/card/rikprmna-mahiru-shiina-b7ba8c78ac22)、[真白社区角色卡](https://character-tavern.com/character/sfw_b/mashiro_shiina_the_pet_girl_of_sakurasou) 是用户生成内容，可参考字段组织，不能作为原作性格证据。
- 本轮未找到可核验的柴郡、安克雷奇、真昼或真白专属官方 `SKILL.md`。这只是本轮检索结果，不代表它们不存在。

## 创作者与作品方的补充材料

### 椎名真昼

- [动画官网刊载的原作者与插画师访谈](https://otonarino-tenshisama.jp/special/interview0326/)：原作者称真昼和周起初都有些冷淡，逐步变得亲近；插画师谈到她柔和、细腻的一面。这是创作者一手说明。
- [GA 文库作品专题](https://ga.sbcr.jp/sp/otonari/)：分卷简介呈现真昼从维持完美的「天使」形象，到在被真诚对待后逐渐显露真实情绪、主动表达亲密的过程。这是出版社资料，适合判断不同故事阶段的表现。
- [动画官网公开短篇](https://otonarino-tenshisama.jp/special_postcard/)：能直接观察她在亲近关系中的礼貌措辞、轻微反问、害羞和偶尔主动调侃。短篇属于特定故事阶段，不能把熟恋时期的语气套到初识时期。
- [动画官网声优访谈](https://otonarino-tenshisama.jp/special/interview_part2_0325/)：提供维护周、被夸后害羞、在周面前哭、接受被照顾等情境解释。是表演者的一手理解。

### 椎名ましろ／真白

- [电击在线声优访谈](https://dengekionline.com/elem/000/000/476/476487/)：声优茅野爱衣描述真白的低情绪起伏、天然与冷静吐槽的反差；这比单一的「面无表情」标签更能说明其台词效果。
- [电击文库作品官网](https://sakurasou.dengeki.com/)及[官方对话专题](https://sakurasou.dengeki.com/record_of_proceedings/044.php)：有原作语境中的对话材料，部分以图片呈现，文字提取不完整。
- [JannyAI 社区角色卡](https://jannyai.com/characters/e9788504-ac0c-4141-b049-b3f005e166a9_character-mashiro-shiina-i-kuudere)提供完整角色卡字段，但夹杂未经官方支持的诊断、关系和背景设定；仅能当二创文档样式样本。

### 柴郡与安克雷奇

- [柴郡台词与语音索引](https://wiki.biligame.com/blhx/%E6%9F%B4%E9%83%A1)除了亲昵台词，还收录她对其他舰船的好奇、学习端红茶、主动询问是否需要帮忙等场景。部分触摸台词表达「不允许偷袭」；外放性格仍有边界。换装中会尝试更得体的说法，说明语气会随情境调整。页面是玩家整理，附游戏语音，以上为台词归纳。
- [安克雷奇台词与语音索引](https://wiki.biligame.com/blhx/%E5%AE%89%E5%85%8B%E9%9B%B7%E5%A5%87)包含她害怕但仍鼓起勇气、失败后愿意继续努力、主动想参与委托和帮助老师的表现。她的依赖与行动能力同时存在；不能只用「胆小」或「需要照顾」概括。
- 本轮仍未找到两角可核验的完整人格 Skill 或公开长角色卡；搜索出现的「角色卡」多为收藏卡、图片或 3D 模型，并无聊天人设文本。

## 整合结论

| 可复用的人格机制 | 主要资料支撑 | 整合时的限制 |
|---|---|---|
| 亲近可以主动表达，也要能感知对方边界 | 柴郡游戏台词 | 原角色的称呼、身体接触和恋爱关系不移植 |
| 害怕、需要帮助与主动行动可以并存 | 安克雷奇游戏台词 | 不移植「老师」称谓或全局能力缺失 |
| 关系有阶段变化，外在得体和真实感受可以并存 | 真昼原作者访谈、出版社分卷简介 | 不预设用户与烟火是恋人 |
| 简短直率、低调的幽默比反复卖萌更耐久 | 真白的官方作品介绍、声优访谈 | 不移植生活不能自理的设定 |
| 人格表达要服从任务和用户反馈 | 鲸鱼娘及阿蓝社区提示词原文 | 两者是二创示例；不照抄固定口癖或外观 |

检索局限：本轮未发现上述四位原作角色的官方性格 `SKILL.md`；公开社区卡有设定混杂或质量问题。后续如需高保真角色还原，应再核对原作全文或游戏实际台词；当前材料足以支持一个**原创烟火角色草案**，不支持宣称烟火等同任何参考角色。
