# 人物抽取 → 性格对话：已知问题与修复计划

`状态`：**已归档 · 阶段 A+B 已完成 · 2026-09-26** — 不作活开工入口；后续章节原子见活计划。  
`范围`：抽取结果进入对话性格；**正文融入重写**（非「小说观察」附录）  
`相关`：[章节原子导入](../../persona-chapter-atomic-import.md) · [提取总计划](../../persona-extraction-and-switching.md) · [Import Brief](./persona-import-brief.md)  
`样例任务`：`importId=2df1c776-b70f-43db-8907-bd13cafd4ea2` / `personaId=persona_cd6521a3-…`

## 产品口径

| 不要 | 要 |
|------|----|
| 文末 `## 小说人物补充` / `### 小说观察` | 开篇气质句 + `## 稳定的底色` / VOICE 语气段 **已改写** |
| 旁观笔记（「片段中」「叙述者推测」） | 第二人称可执行倾向 |
| 未授权固定称呼（大叔等） | 丢弃 |
| 枚举短句堆进正文 | 枚举仅 REVIEW 参考 |

## 根因与修复

| ID | 问题 | 状态 |
|----|------|------|
| P1–P4 | 接线、清洗、契约、质量门文案 | ✅ 阶段 A |
| P5 | 附录 ≠ 融入 | ✅ 阶段 B：`PersonaPromptRewriter` 确定性融入 |

## 分阶段进度

### 阶段 A — 接线与契约 ✅

approve → 正式 md、overlay 清空、`personalityEffective`、清洗单测、文档/启动脚本。

### 阶段 B — 融入重写 ✅（2026-09-26）

- [x] `PersonaPromptRewriter`：清洗 → 倾向蒸馏 → 写入开篇 / 底色 / VOICE 语气；无观察附录  
- [x] `PersonaPromptReviewService.stage` 改走 rewriter；`REVIEW.md` 为改写摘要  
- [x] 单测：正文含活泼/俏皮；**不**含 `### 小说观察`；重复 stage 不叠气质条  
- [x] 样例 `2df1c776-…` restage+写入正式 prompts；开篇可读活泼/玩笑/撒娇边界；无「流氓大叔」  
- [x] Brief / 总计划 / README 口径改为融入重写  

### 阶段 C — 不做

章节原子、IDENTITY/USER/SAFETY、整本灌 prompt、本阶段真人测。

## 验收断言

### 阶段 A ✅

- [x] 未 approve 不声称生效；approve 替换正式文件  
- [x] 短句保留 / 证据不足丢弃  
- [x] 文档一致  

### 阶段 B ✅

- [x] SOUL 开篇/底色含人物气质（活泼、亲近、玩笑等）  
- [x] VOICE 语气段含俏皮/直率等  
- [x] 无旁观笔记、无未授权昵称、无「小说观察」节  
- [x] 样例正式文件已对照  
- [x] **审核清稿（2026-09-26）**：二次 restage 叠写已修；从种子恢复危机节；`REVIEW.md` 记 `PASSED_WITH_NOTES` 

## 实现要点

- 主路径：`PersonaPromptRewriter.rewrite` ← `PersonaPromptReviewService.stage`  
- 附录 API（`mergeSupplement` / `synthesize`）已删除；`stripNovelSection` 仍用于清掉历史附录残留  
- 文末仅保留 `<!-- persona-import integrated … -->` 审计标记  
- 改 Java 后须 `scripts/start-wn-server.ps1 -Rebuild`  

## 依赖与授权

- 不 commit / push，除非用户另授。  
- 下一建议：章节原子导入（另案）。
