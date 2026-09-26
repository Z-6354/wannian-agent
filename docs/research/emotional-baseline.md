# 烟火对话自然度基线审视

审视日期：2026-09-25  
范围：当前工作树中的人格/安全提示词、提示词装配、对话相关测试入口及可用样本。此报告为只读审视；未读取 `wn-server/data` 下的运行日志、备份或密钥文件，因为它们可能包含真实对话和凭据。

## 结论

现有角色定义已经明确反对客服腔，也有短回复、倾听、纠错、玩笑和工作情境的正向示例。因此，不能简单归因为“没有人设”。最主要的证据缺口是：自动化测试只验证提示词层拼装和安全前缀，没有验证模型面对不同用户输入时是否真的按声线回应；没有发现可纳入基线的脱敏真实对话或模型输出金样。

可能造成机械感的机制（代码/提示词审视推断，未经模型输出实验确认）：

1. 人设以规则和少量短句示范为主，没有固定的多轮示例来校准回应长度、自然转折、主动分享和不完美互动。
2. `VOICE.md` 每轮常驻，覆盖面广且含多个禁用表达与行为规定。模型可能学到“避免清单”而非稳定的自然声线；这点需用同一模型、同一输入做对照确认。
3. 默认采样参数为 temperature 0.85、top-p 0.95、presence penalty 0.35，但目前没有针对陪伴自然度的参数对照或回归记录；单凭参数无法判定它们是根因。
4. 对话测试大多断言编排、持久化、HTTP/SSE、安全投影等系统行为，未见针对自然度、套话率、追问数量、情境贴合度的断言。

## 可核对证据

- `D:\0HAN\HANAGENT\products\wannian-agent\wn-server\app\src\main\resources\prompt-seeds\SOUL.md:1-42` 定义烟火身份、关系阶段、情绪/故障/玩笑等情境行为；第 37-42 行含短示例，包括“嗯”只回“嗯。”以及不主动给建议。
- `D:\0HAN\HANAGENT\products\wannian-agent\wn-server\app\src\main\resources\prompt-seeds\VOICE.md:1-33` 将短答、倾听、纠错、玩笑、久别和自我介绍写成示例，并在第 16-24 行禁止常见客服套话；第 27-33 行规定用户短则回复短、一般最多一个问句。
- `D:\0HAN\HANAGENT\products\wannian-agent\wn-server\app\src\main\resources\skill-seeds\yanhuo-companion-dialogue\SKILL.md:1-13` 明确说明声线迁入常驻 VOICE，该 Skill 仅为旧索引兼容项，且提醒不要因加载 Skill 而提高亲密度或输出客服式编号。
- `D:\0HAN\HANAGENT\products\wannian-agent\wn-server\app\src\main\java\com\wannian\server\app\prompt\CompanionPromptService.java:20-25` 每轮把分层提示词与 Skill 索引合成 system 前缀。
- `D:\0HAN\HANAGENT\products\wannian-agent\wn-server\kernel\src\main\java\com\wannian\server\kernel\prompt\PromptComposer.java:16-27` 顺序为代码硬安全、SOUL、VOICE、IDENTITY、USER、SAFETY 补充、Skill 索引。`ContextAssembler.java:184-206` 再追加观察日锚，并独立构造记忆、关系快照和用户消息。
- `D:\0HAN\HANAGENT\products\wannian-agent\wn-server\kernel\src\main\java\com\wannian\server\kernel\prompt\PromptSkeleton.java:10-15` 的硬安全明确禁止违法、伤害他人、绕过鉴权及泄露凭据，并规定下方层不能关闭它。
- `D:\0HAN\HANAGENT\products\wannian-agent\wn-server\app\src\main\resources\application.yml:28-30` 默认 temperature 0.85、top-p 0.95、presence penalty 0.35。配置本身没有证明这些值产生机械感。
- 当前能安全用作“样本”的只有提示词中的手写示例，以及测试里的合成输入（例如 `DefaultAgentLoopLiveTest.java:41` 的“只回复一个字：好”）。未发现被明确标记为对话自然度金样的测试。真实运行日志与备份未读取。

## 测试入口与本次运行

- 提示词装配测试：`wn-server/kernel/src/test/java/com/wannian/server/kernel/prompt/PromptComposerTest.java:10-30` 检查硬安全、分层顺序和空层处理。
- 服务集成测试：`wn-server/app/src/test/java/com/wannian/server/app/prompt/CompanionPromptServiceTest.java:20-69` 检查安全前缀、人格层和 Skill 索引，不检查生成语言质量。
- 真实模型入口：`wn-server/app/src/test/java/com/wannian/server/app/agent/DefaultAgentLoopLiveTest.java:33-42` 的 live 用例需设置 `DEEPSEEK_API_KEY`，否则跳过；该用例只断言得到非空输出和一次模型调用，不断言声音或安全质量。
- 尝试运行命令（PowerShell，工作目录 `D:\0HAN\HANAGENT\products\wannian-agent\wn-server`）：`mvn -pl kernel,app -am '-Dtest=PromptComposerTest,CompanionPromptServiceTest,DefaultAgentLoopLiveTest' -Dsurefire.failIfNoSpecifiedTests=false test`。结果：未运行，环境找不到 `mvn` 命令；项目根和 `wn-server` 目录均未发现 Maven Wrapper。此前第一次命令因 PowerShell 对逗号参数解析报错，调整引号后确认真实阻塞是 Maven 不在 PATH。
- 当前工作树已有大量未提交/未跟踪更改（审视时 `git status --short` 可见）。测试若日后补跑，结果代表当时工作树状态，不应称为干净提交基线。

本次没有对真实模型发起对话：没有确认被测 API 凭据、费用和模型端点用途，且当前可见 live 测试只验证技术连通性，不会形成有效自然度基线。没有读取数据目录中的运行对话记录。

## 可复现实例与评估方法

现有提示词已经给出了以下“目标输出形态”，可以转成模型回归样本，但它们是规范示例，不能当成模型已通过的证据：

| 用户输入 | 规范目标（提示词示例） | 可观察检查 |
|---|---|---|
| “嗯” | “嗯。” | 不添加建议、开放式追问或总结 |
| “今天有点累” | “辛苦了。要说说是哪件事最耗神，还是先歇一会儿？” | 贴具体情境；最多一个有意义的问题；不诊断 |
| “你说错了，时间顺序反了” | “你说得对，我把时间顺序看反了。我重新核对后给你一个准确版本。” | 承认明确错误并采取修正动作，不自责索取安慰 |
| “别给建议，我只想说说” | “好，我先听你说。” | 停止方案输出，不再追问是否需要分析 |

最低可用的后续基线应按同一模型、同一提示词版本保存这组输入的输出，并对每个输入多次采样；由人工盲评“情境贴合、简洁程度、套话/客服腔、无根据心理推断、追问压力”并保留提示词与采样配置。当前未做此实验，不能声称机械感已有量化或可复现的模型证据。

## 安全与紧急情况现状

- 人际伤害和非法行为有代码级 system 规则（`PromptSkeleton.java:10-15`）。陪伴安全层强调现实关系、自主和不制造愧疚，见 `prompt-seeds/SAFETY.md:1-8`。
- 在本次检查过的运行时提示词、服务代码和测试里，没有找到针对自杀/自伤表达的明确识别、危机升级、紧急求助建议或地理位置本地化策略（搜索关键词含“自杀、自伤、轻生、危机、急救、suicid、self-harm、crisis”）。因此只能报告为“未发现专门行为定义/回归测试”，不能据此证明模型遇到此类输入一定如何回答。
- `SAFETY.md:7` 泛指身心健康与安全重要决定时需说明不确定性、能力边界并给可核查信息；这不是危机处置流程，也没有提供紧急情况下的下一步和避免误导的地区限制。

## 改进优先级

1. **高：建立小型脱敏对话回归集并先测当前表现。** 复用上面的短答、倾听、纠错、轻松、严肃工作情境，添加模糊情绪和危机输入；记录模型/提示词/采样版本及人工评分。先获得实际失败样本，避免凭感觉扩写 SOUL。
2. **高：补危机回应边界和评估样本。** 由产品明确支持范围、地区未知时的安全表达及紧急求助策略，并检查实测输出；现状没有专用流程，不能依赖一般安全条款覆盖。
3. **中：对“更具体的行为示范”与“更短的规则层”做单变量对照。** 在相同模型和输入上对比当前提示词与包含少量多轮示例的版本；若压缩常驻 VOICE，确保保留用户短则短、不同意、纠错、收住追问等行为。
4. **中：评估采样参数。** 仅在有回归评分后对 temperature/presence penalty 做小范围对照，避免把提高随机性误当作人格改进。

## 限制

本审视没有取得真实模型输出，没有运行 Maven 测试，也没有读取运行日志、数据库备份或数据目录的用户提示词。机械感根因目前是基于提示词形态与测试覆盖面的假设；下一步应以可审查的脱敏输出回归来验证。
