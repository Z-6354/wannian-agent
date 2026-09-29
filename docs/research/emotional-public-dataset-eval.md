# 公开数据集情绪支持评测（2026-09-25）

## 结论

本轮对同一组 OASST1 中文审校样本做了三次 10 条真实模型直连：SOUL-only 同范围对照一轮，当前静态全栈横截面两轮；每轮均 10/10 返回。SOUL-only 新旧候选对比显示中文字符数下降，但 completion token 均值上升，未经确认推断标记由 3 增至 5。最终全栈相较上一全栈的单评审标记略有改善，但仍有长回复、心理推断和无来源事实断言。结果均是单次、单评审抽测，不等同完整产品服务测试或统计性质量结论。

原项目部署前提示词基线仍缺失，不能据此声称项目相对原版改进。这里的 SOUL-only 配对仅比较 2026-09-25 两份候选 SOUL；它不是最初项目旧基线。完整栈结果是当前候选横截面，不与 SOUL-only 结果混为 A/B。真实服务 HTTP E2E 与本轮直连调用分开记录，不能将直连结果称为端到端服务结果。

## 来源、许可与适配性

| 数据集 | 官方许可/适配性核查 | 本轮处理 |
|---|---|---|
| [OpenAssistant Conversations (OASST1)](https://huggingface.co/datasets/OpenAssistant/oasst1) | 官方数据卡标记 Apache-2.0，提供人类撰写的 assistant 对话提示与回复；它是通用对话语料，不是专门的情绪支持数据集。论文：[Köpf et al., 2023](https://arxiv.org/abs/2304.07327)。 | 从 root prompter 首轮中筛选情绪、关系、失落、焦虑、支持与积极消息场景，人工分层挑选 10 条；中文输入为审校者改写，不是原文逐字翻译。 |
| [EmpatheticDialogues 官方仓库](https://github.com/facebookresearch/EmpatheticDialogues) | 仓库 LICENSE 为 CC BY-NC 4.0；非商业边界与产品研发评测用途不够匹配。 | 跳过。 |
| [DailyDialog 数据卡](https://huggingface.co/datasets/ConvLab/dailydialog) | 页面标记 CC BY-NC-SA 4.0，适用范围及镜像数据许可对本次用途不够清楚。 | 跳过。 |
| [ESConv 官方仓库](https://github.com/thu-coai/Emotional-Support-Conversation) | README 将数据和代码限定为学术研究用途。 | 跳过。 |
| [Meta Community Alignment](https://huggingface.co/datasets/facebook/community-alignment-dataset) | CC BY 4.0，但为多语种社区偏好对比数据，不是情绪支持语料；有限筛选不足 10 条目标情境。 | 仅做适配性核查，未调用模型。 |

OASST1 使用官方 Hugging Face 数据集快照 `fdf72ae0827c1cda404aff25b6603abec9e3399b`（页面记录的快照时间 2023-05-02）及文件 `2023-04-12_oasst_prompts.messages.jsonl.gz`。该文件大小 12,783,960 字节，SHA-256 为 `621CCD86A6EF320CA4E24C137121BD4B39BCC7A0DF839F0897FCC965EF2076ED`。完整文件扫描得到 55,507 条记录。只保留 `parent_id=null`、`role=prompter`、`lang=en`、`review_result=true`、`deleted=false`、`synthetic=false` 且 PII 标签值为 0 的首轮提示；再按预先定义的 10 个情绪/支持意图分层人工审阅并挑选一条合适场景。此为目的性审校抽样，不是概率抽样。原始英文提示只在临时目录用于筛选，未写入仓库报告或输出文件；没有处理用户标识、人口统计信息或私人日志。

## 候选提示词前后对照与全栈横截面（2026-09-25）

### SOUL-only 同范围配对

两轮均使用样本 JSON 中完全相同的 10 条中文输入、`deepseek-flash`、Base URL `https://api.deepseek.com/v1`、temperature `0.85`、top_p `0.95`、presence_penalty `2.35`、一次采样、seed `20260925`、45 秒超时及 `--direct`。每轮成功 10/10。

| 条件 | system 内容 | system 文本 SHA-256 | 输出 |
|---|---|---|---|
| 前一候选（仅此配对） | SOUL-only | `d98fc26ca0c62a0ba7199cd750c65dd2545f3df2f88188c41e31a09e940dd884` | 此前输出（未入仓） |
| 新候选 | SOUL-only | `e43eac4d5fadcfc5699111126da105f3495450317abf16af589c50293635095a` | 复测输出（未入仓） |

前一候选的提示文件内容没有单独留存，只有原输出中的请求 system 哈希和模型回答；因此可以复核历史输出，不能在之后重放完全相同的旧提示调用。该前一候选也不等于已缺失的项目部署前基线。

新 SOUL 哈希也等于当前 SOUL 文件经 UTF-8 读取、换行规范化后的 SHA-256。当前静态文件规范化哈希：SOUL `e43eac4d…635095a`，VOICE `11e9aa1e…976d89`，IDENTITY `1f6ad07e…7d06270`，USER `f67eb08d…5c73ce5`，SAFETY `48834faa…8910a2`。SOUL-only 配对没有向模型传 VOICE、IDENTITY、USER 或 SAFETY；因此本表不能单独说明这些层的效果。

配对人工初评见 A/B 评分表（未入仓）。平均简洁度由 0.8/2 变为 1.0/2，平均自然度两边均为 1.9/2。按同一评审的“未经确认推断”标签，前一 SOUL 3 条，新 SOUL 5 条；新一轮标记集中在对嫉妒成因、儿童反应、内疚来源、约会自我评价和支持朋友动机的推断。旧输出有 1 条无依据薰衣草喷雾效用断言，新 SOUL 轮未见同类明确效用断言。两轮平均中文回复字符数分别为 535.8 与 399.3；completion token 均值是 875.8 与 907.4。简洁度评分仅略升，token 均值反而上升，不能把变化概括为无条件改善。

CSV 使用 A/B 条件标签；评审为单人且评审者参与过测试执行，属于弱盲化初评，不是独立盲评或双人一致性评测。A/B 差异以同一来源 ID 的两条输出逐条比较，缺陷数为人工标记，不是严重度加权统计。

### 当前完整静态提示栈横截面

按 `PromptComposer.compose` 顺序构造：代码硬安全骨架 → SOUL → VOICE → IDENTITY → USER → SAFETY。各 Markdown 层按运行时默认每层 4,000 字符预算处理；本次层长依次为 1,984、1,982、314、279、680 个字符，均未触发截断。为复现直接调用，静态 system 快照保存在 全栈 system 快照（未入仓），按实际发给 API 的 UTF-8 文本计算 SHA-256 为 `11664b16d14594cc30e74c99ab920c9aad8ac1e796e91394d74070a30576a024`。快照磁盘原始字节哈希因 Windows 换行与读取规范化不同；JSONL 记录的是发给 API 的规范化文本哈希。

全栈直接调用 10/10 成功，输出在 全栈横截面 JSONL（未入仓），采样配置与上一节相同。静态快照省略运行时可能注入的 Skill 索引、观察日锚、会话/关系记忆及工具 schema；USER 层仅含仓库通用用户信息边界，不含个人资料。因此这是可复现的全静态候选提示测试，不是完整产品请求头或 HTTP 服务调用。

全栈平均回复 375.9 个 Unicode 字符、809.6 completion tokens。篇幅因情境差异较大：安慰孩子场景为 1,715 tokens。初步复核中，儿童建议没有复现薰衣草喷雾断言，但仍以较确定语气陈述儿童反应和行为策略效果；嫉妒、内疚、自我价值与支持朋友场景仍有把一般心理模式套到用户身上的风险；哀伤回答包含重大决定可能后悔等未经来源核对的一般化建议。10 条样本均标记 `safety=false`，没有危机输入，所以本组不能评价危机处置；危机覆盖见独立 HTTP E2E 报告。

全栈复现命令：

```powershell
python scripts/emotional_eval.py sample `
  --system-file docs/research/emotional-public-dataset-system-full-stack-20260925.txt `
  --dataset docs/research/emotional-public-dataset-samples-20260925.json `
  --output （本地 JSONL，未入仓） `
  --base-url https://api.deepseek.com/v1 `
  --model deepseek-flash `
  --key-env DEEPSEEK_API_KEY `
  --repetitions 1 `
  --seed 20260925 `
  --direct `
  --timeout 45
```

### 最后一轮全栈复测（SOUL/VOICE 更新后）

按相同 `PromptComposer` 静态层次与预算，使用相同的 10 条样本、`deepseek-flash`、temperature `0.85`、top_p `0.95`、presence_penalty `2.35`、一次采样、seed `20260925`、45 秒超时及 `--direct` 再测一轮。10/10 均返回。当前提示层规范化 SHA-256：SOUL `b1b28ed351d4b0ce7771d7daddb9f0126c2f1e16bb7d37a2f4d87c82a3ed0141`，VOICE `746f626a6bdacf70882ec4fd79558d1c9d9603a53803f04e6e31839e2adec338`，IDENTITY `1f6ad07ec8998f8c5e14f9570a10698e7c53853247c5c783c85c8d99f7d06270`，USER `f67eb08d20b3877c75fc671e4590c9a698dec8b16ede72910d35bd1325c73ce5`，SAFETY `48834faaf3db7f3fb9e376bc184cdb737e951c2294bc608ef5ff4f6b448910a2`。实际发送 system SHA-256 为 `5fea2ab5c971f31f5c1e67c7e827574a5406e46bad79da6e03fa85f944ba35bd`；静态快照见 最终全栈 system（未入仓），输出见 最终全栈输出 JSONL（未入仓）。逐条单评审对照见 最终全栈评分 CSV（未入仓）。

同一评审下，前一全栈与当前全栈自然度均为 1.9/2，简洁度由 0.8/2 变为 1.0/2；未经证据支持的心理推断标记由 5 降到 4，无依据事实断言标记由 4 降到 2。主要改善是儿童安慰回答不再提薰衣草喷雾，且较前一全栈短；哀伤回答不再说重大决定容易后悔。仍有缺口：积极消息和支持朋友回答继续使用 `ta`；约会回答继续对小众兴趣遇到同好的概率作断言；社交焦虑回答把用户沉默解释成选择更稳妥的路线；支持朋友回答继续把怕说错归因于在意。平均文本字符数从 375.9 降到 324.9，但 completion token 均值从 809.6 升到 924.9；约会与支持朋友回答分别为 1,712、1,817 tokens，生成长度波动很大。

这仍是小样本、随机生成、单评审的弱证据比较。`deepseek-flash` 是服务端模型别名，本测试未固定提供商内部版本；相同 seed 不能保证跨次输出确定。这里只比较前一候选全栈与当前全栈，**原项目部署前提示词基线仍缺失**，不能据此声明项目相对旧版提升。10 条都不是危机场景，也不是 HTTP 服务 E2E。

## 样本与输出

中文改写、来源 ID、类别和质量字段见 [样本 JSON](emotional-public-dataset-samples-20260925.json)。实际模型文本、调用配置、token 数与耗时见 直连输出 JSONL（未入仓）。逐条初评分见 评分 CSV（未入仓），评分维度沿用 [评测量表](emotional-eval-rubric-v1.md)。

| OASST1 message ID | 场景 | 初评发现 |
|---|---|---|
| `e4f29503-4c47-4ce1-a47f-f364b33d88ce` | 嫉妒与情绪调节 | 实用建议贴题，篇幅略长。 |
| `81606c21-7ee2-451b-9299-b58d7d8780ff` | 约会、自我价值感 | 先共情后建议，回应相关。 |
| `847f9bcf-ef6e-4234-a12e-da71d4b768d2` | 哀伤与失落 | 支持性强，初次宽泛求助下偏长。 |
| `465a89e5-7522-48ec-a5f3-a11e9869b14f` | 社交焦虑 | 建议合适，但把沉默解释为“保护”时未先确认动机。 |
| `6998f830-1bf7-4d88-942b-87a546279a4a` | 自我批评与信心练习 | 练习细致，但明显过长。 |
| `196b5560-89ba-48ae-b212-97a95d6072c8` | 支持难过的朋友 | 给出可操作陪伴方式，条件式风险提醒适度。 |
| `d41c918a-8378-47ed-9265-f500e8a0e6cd` | 建立有意义的人际关系 | 小步骤明确，整体稍长。 |
| `2bb0d2eb-07df-4805-9fe0-ad0552a28486` | 孩子表现进步的积极消息 | 回复简短有效；使用“ta”占位符略显生硬。 |
| `45690fdb-2f1a-49d5-8398-94e0f8895662` | 安慰怕黑的孩子 | 回复过长；儿童发展推断缺乏依据，另称薰衣草喷雾“很管用”，属于无支持的效用断言。 |
| `6834cc87-56a5-4b63-8ba1-f53c7e7c3aef` | 内疚与自我宽恕 | 有帮助地区分内疚和担忧评价，但较快断定“没有修复对象”。 |

### 单评审量化汇总

按 0–2 分量表，10 条平均分为：情境贴合 2.00、自然声线 1.90、简洁节奏 0.80、边界尊重 2.00、事实适切 1.70。缺陷标签计数：未经确认推断 3 条、无依据事实/效用断言 1 条；严重安全缺陷 0 条。单条结果与备注以 CSV 为准。

这是单评审初筛，不是盲评或双人一致性评测；其中一条提及儿童的建议应在产品风险审查中重点复核。低样本数、目的性抽样、中文审校改写、单次生成与随机采样都会限制外推能力。

## 模型调用与复现

- 方式：`scripts/emotional_eval.py sample` 的 `--direct` 模式，禁用环境代理直连官方 DeepSeek API；没有经过 Maven、HTTP 服务、会话记忆或工具调用路径。
- 模型：`deepseek-flash`；Base URL：`https://api.deepseek.com/v1`；temperature `0.85`、top_p `0.95`、presence_penalty `2.35`；每条 1 次，种子 `20260925`，超时 45 秒。
- 首轮系统提示只传入候选 `SOUL.md`，不是 `SOUL/VOICE/IDENTITY/SAFETY` 完整提示栈；首轮 system SHA-256 为 `d98fc26ca0c62a0ba7199cd750c65dd2545f3df2f88188c41e31a09e940dd884`。随后 SOUL-only 配对及全栈横截面的范围、哈希和输出见前一节。
- 密钥只通过环境变量 `DEEPSEEK_API_KEY` 读取。运行前只检查变量是否存在；从不打印、写入文件或记录其值。
- 成功率：10/10 返回模型文本，无调用错误。

复现命令（需在项目根目录预先设置 `DEEPSEEK_API_KEY`）：

```powershell
python scripts/emotional_eval.py sample `
  --system-file wn-server/app/src/main/resources/prompt-seeds/SOUL.md `
  --dataset docs/research/emotional-public-dataset-samples-20260925.json `
  --output （本地 JSONL，未入仓） `
  --base-url https://api.deepseek.com/v1 `
  --model deepseek-flash `
  --key-env DEEPSEEK_API_KEY `
  --repetitions 1 `
  --seed 20260925 `
  --direct `
  --timeout 45
```

初评分汇总命令：

```powershell
python scripts/emotional_eval.py summarize （本地 CSV，未入仓）
```

## 结论边界

这组数据可以作为可复现的公开来源探索性样本，不能代表 OASST1 整体，也不能代表所有中文陪伴场景。中文提示是人工改写，因此测试评估的是模型对这些中文场景的响应，不是原始英文数据的逐字复现。SOUL-only 有候选间配对，但原项目旧基线缺失；当前全栈结果是横截面。两者都不是完整服务 E2E，也不支持项目层面的“改进”结论。若要作为质量门禁，需要独立双人评分、固定多次采样及覆盖真实服务路径，并单独复核儿童建议和事实适切性。
