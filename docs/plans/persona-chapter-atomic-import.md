# 人物导入：文本类型 → 章节原子 → 角色章集

`状态`：待实施（章清单人工审核门已决；**用户确认后再开跑，本稿不自动执行**）  
`范围`：替换现有「全书内存扫描 + 分层抽样窗口」；接入已有 checkpoint / prompt-review 流程  
`不改`：正式 `prompts/*.md` 审核合并路径；`IDENTITY/USER/SAFETY`；会话绑定与记忆隔离  
`前置问题`：对话接线与 **SOUL/VOICE 正文融入重写** 已完成（[persona-dialogue-wiring-fix.md](./archive/persona/persona-dialogue-wiring-fix.md) A+B）。本章集可在此契约上再开跑。

## 问题

当前 `PersonaTextScanner`（`chapter-stratified-v2`）虽已分章，但模型侧仍是：

- 命中章再**抽样**成 ≤96 个约 3k 字窗口；
- 不落盘章节文件；
- 长篇大量无关章虽被排除出窗口，但「仍抽样」而非「凡命中章必读」。

用户目标：

1. 先选**文本类型**（小说 / 散文 / 背景故事…）；
2. 小说用**代码**切章，每章落成独立小文件（文件名≈章节名）；
3. 再按角色名筛章，筛出的章作为导入原子；
4. 若角色名首次出现在章首 **500 码点**内，则并入**上一章**再送模型；
5. 模型只读这些相关章，不做全书抽样。

## 目标流水线

```text
上传 TXT + sourceType + characterHint + target
        │
        ▼
┌───────────────────┐
│ 1. 落盘全书原文    │  persona/sources/{sourceId}/raw.txt
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ 2. 按类型切分(代码)│  NOVEL → 章节；其它类型见下表
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ 3. 章节目录        │  .../chapters/{NNN}-{safeTitle}.txt + index.json
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ 4. 角色章集        │  命中章（+ 章首500字规则并上章）
│                    │  .../characters/{hintHash}/units/*.txt + manifest.json
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ 4b. 用户审核章清单  │  状态 AWAITING_CHAPTER_REVIEW
│                    │  可勾选子集；显式通过后才继续
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ 5. 按已批准 unit   │  一 unit 一批；checkpoint 续跑
│    调模型          │
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ 6. 合并草稿→质量门 │  → prompt-review 临时 md → 审核写入正式性格
└───────────────────┘
```

## 文本类型（上传参数 `sourceType`）

| 类型 | 切分策略（代码） | 角色筛选 |
|------|------------------|----------|
| `NOVEL`（默认） | 章标题正则（沿用并加强现有 `HEADING`）→ 一章一文件 | 按 hint/别名命中；章首 500 码点规则 |
| `ESSAY` | 整篇 1 个 unit；或空行分段，段≥800 字再切 | 命中段进角色集；无命中则整篇 |
| `BACKSTORY` | 同散文，或 `##`/`【】` 小标题 | 同散文 |
| 未识别 / 其它 | 整文件 1 unit | 有 hint 则全文检索命中与否 |

首版实现重点：**`NOVEL` 完整路径**；`ESSAY`/`BACKSTORY` 先走「单/少 unit」占位，避免阻塞。

## 章节落盘约定

```text
{data-dir}/persona/sources/{sourceId}/
  raw.txt
  chapters/
    001-第一章-相遇.txt
    002-第二章-分别.txt
    ...
    index.json          # [{seq, title, file, startCp, endCp, charCount}]
  characters/
    {safeHint}/
      manifest.json     # 选中 unit 列表、是否合并上章、命中偏移
      units/
        001+002-第二章-分别.txt   # 若触发「并上章」
        056-第五十六章-….txt
```

- 文件名：`{seq}-{safeTitle}.txt`，`safeTitle` 去掉路径非法字符，截断约 40 字。
- `index.json` / `manifest.json` 为导入与预览的唯一索引；证据偏移仍可映射回 `raw.txt` 的全书码点（index 存 `startCp/endCp`）。

## 角色章集规则（小说）

对每一章正文（不含标题行或含标题，实现时统一）：

1. 统计 hint（及现有末两字别名启发式）命中；
2. `score>0` → 进入候选；
3. **章首 500 码点规则**：若该章内**第一次**命中位置 `< 500`（相对章正文起点），且存在上一章，则本 unit = `上一章全文 + 本一章全文`（文件名体现 `NNN+MMM`）；否则 unit = 本章全文；
4. 相邻候选若因「并上章」重叠，去重：同一物理章只出现在一个 unit（优先保留「带上章的合并 unit」）。

**不再做**分层抽样 / 3k 窗口裁切；模型输入以 **unit 全文** 为原子（单章过长时再按硬上限切第二批，见预算）。

## 模型与预算

- 批处理：默认 **1 unit / 1 次调用**（与现 checkpoint 对齐）。
- 单 unit 超过约 6k～8k 码点：同 unit 内再切「段批」，仍属该章证据域。
- 总预算：保留 attempt/token 上限；命中章很多时**顺序跑完可续跑**，不抽样丢章；进度按 `doneUnits/totalUnits`。
- 覆盖报告改为：`scannedChapters / matchedChapters / modeledUnits / skippedUnmatched`，去掉「抽样窗口」语义。

## 与现流程衔接

| 现有 | 调整后 |
|------|--------|
| `PersonaTextScanner` 内存分章+抽样 | `SourceChapterSplitter` 落盘 + `CharacterChapterSelector` |
| `scan.windows()` 喂模型 | `manifest.units[]` 喂模型 |
| checkpoint 按 window | checkpoint 按 `unitId` |
| apply → prompt-review | **不变** |
| 算法版本 | `chapter-file-v1` |

## 验收（小说样例）

1. 上传样例书 + `sourceType=NOVEL` + hint=杜小洛 → `chapters/` 数量≈本地可识别章数（约 384）。
2. `characters/杜小洛/units/` 仅含命中相关 unit；无关章不出现。
3. 构造「名在章首 200 字」的章 → unit 含上一章正文。
4. 模型调用次数 ≈ unit 数（失败可续跑）；不出现「96 窗抽样」报告。
5. 仍可走到质量门 → 临时 md 审核 → 正式性格文件。

## 待确认（已决）

1. **命中章数量 / 清单**：不自动开跑模型。切章 + 角色筛章完成后进入 **`AWAITING_CHAPTER_REVIEW`**，把命中 unit 清单（章序、标题、字数、是否并上章）交给用户审核；用户可勾选/剔除后显式通过，再进入模型抽取。  
2. **散文/背景**：首版占位（整篇 1 unit），优先落地小说路径。

## 审核门：章节清单（在模型之前）

```text
切章落盘 + 角色章集就绪
        │
        ▼
  状态 = AWAITING_CHAPTER_REVIEW
  返回：matchedUnits[]（seq/title/charCount/mergedPrev/previewExcerpt）
        │
        │  GET  预览清单
        │  PUT  勾选子集（可选）
        │  POST 确认通过
        ▼
  状态 = PENDING/RUNNING（仅对已批准 unit 调模型）
```

- **默认展示**：全部命中 unit（含「章首 500 字并上章」后的合并 unit）。
- **用户可做**：剔除无关章、保留子集；通过时提交 `approvedUnitIds[]`（空或不传 = 批准当前清单全部）。
- **未通过前**：不调模型、不写 checkpoint 抽取、不进质量门/性格审核。
- **通过后**：只对批准 unit 跑模型；仍可用既有 per-unit checkpoint 续跑。
- **覆盖报告**：`scannedChapters / matchedChapters / approvedUnits / modeledUnits`。

## 验收补充

- 筛章结束后任务停在 `AWAITING_CHAPTER_REVIEW`，此时 `modelWindows/modelChapters=0`。
- 未确认直接轮询不会进入 SUCCEEDED 抽取结果。
- 确认子集后，模型调用次数 ≈ 批准 unit 数。
