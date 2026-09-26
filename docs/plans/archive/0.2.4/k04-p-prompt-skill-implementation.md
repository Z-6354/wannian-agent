# 0.2.4-P · 提示词治理 + Skill 最小系统（阶段施工单）

`status`: **代码已入仓 · 2026-09-24**（P0–P2；P3 后挂；F 窄测/真人仍按版本末段）  
`note`: 2026-09-24 审阅修补 — `stripCommentOnlySeed` 保留 MD 标题；Skill 白名单/`index-char-budget`/超索引仍可 load；启动写回缺锁死工具的 `wannian.json`。  
`upstream`: [方向 C](./prompt-skill-direction-c.md) · [roadmap](../../roadmap.md) · [工作流](../../version-stage-workflow.md)  
`alias`: K04-P

## 0. 执行者先读

本阶段把方案 C 落入 **0.2.4**：代码骨架安全段 + data-dir Markdown 人设层 + Skill 索引进 system、正文仅 `load_skill`。

- **不做**：管理页编辑、SSE/会话 UI、agent 自改 Skill、MCP 拉包、P3 威胁扫描/快照回归（留给后段或 F）。
- **不挡** 0.2.4-B：本阶段可改 Assembler/TurnController 提示词入口与工具池；禁止改会话列表/Outbox/SSE。
- 生产文件先写 `docs/plans/archive/0.2.4/k04-p-draft/`，逐文件审过再入 `wn-server/`。普通阶段不要求新测文件；禁止故意破坏现有编译。

## 1. P0 盘点（已核实）

| 位置 | 内容 | 分类 | 处置 |
|------|------|------|------|
| `TurnController.SYSTEM_INSTRUCTIONS` | `你是万年，一个有帮助的助手。` | 人设 | **迁** → `prompts/SOUL.md` 种子 |
| `ContextAssembler.observationAnchorBlock` | 观察日 / ISO 时刻 / 地点未说明 | 运行时锚 | **留代码**（每轮动态） |
| `ContextAssembler.mergeSystemInstructions` | 拼接人设+锚 | 骨架 | **留**；上层改由 PromptComposer 供给人设 |
| `BuiltinToolPool` 各工具 description / schema | 工具约定 | 骨架 | **留代码** |
| Live/单测硬编码同一人设串 | 测试夹具 | 测试 | **留**（可继续写死短串；不强制读盘） |
| Memory Review / Fake LLM 提示 | 冷路径专用 | 非常驻人设 | **本阶段不迁** |

## 2. 不变量

1. **硬安全段只在代码**（`PromptSkeleton`）；MD 只能追加，不能删除或覆盖。
2. 拼装顺序（进 `ExecuteTurn.systemInstructions`，再由 Assembler 拼观察锚）：
   ```text
   硬安全 → SOUL → IDENTITY → USER → SAFETY补充 → Skill索引
   ```
3. Skill：**仅摘要索引**进常驻 system；正文只经 `load_skill`；未授权/不存在 → 稳定错误，不拼假正文。
4. 2C2G：每层 MD、索引条数/描述长、Skill 正文均有字符上限（yml，kernel 吃已截断文本）。
5. kernel 不读盘；app 负责种子拷贝、读 MD、扫 `skills/`，向 kernel 注入 `PromptLayerTexts` / `SkillCatalog` 端口。
6. 首次启动：classpath 种子拷到 `data-dir/prompts/` 与 `data-dir/skills/`；**已存在用户文件不覆盖**。
7. 无管理页：改文件 + 进程内按 mtime 重载即可影响下轮。

## 3. 目录与种子

```text
{wannian.data-dir}/
  prompts/
    SOUL.md IDENTITY.md USER.md SAFETY.md
  skills/
    how-to-remember/SKILL.md   # 示例；可删
```

classpath：`prompt-seeds/*.md`、`skill-seeds/how-to-remember/SKILL.md`。

## 4. MANIFEST 与审序

| 序 | 文件 | 目的 |
|----|------|------|
| P1 | `kernel/.../prompt/PromptSkeleton.java` | 硬安全常量 |
| P2 | `kernel/.../prompt/PromptLayerTexts.java` | 四层已截断文本 record |
| P3 | `kernel/.../prompt/PromptComposer.java` | 合并人设+Skill 索引（纯函数） |
| P4 | `kernel/.../skill/SkillSummary.java` + `SkillCatalog.java` | 索引端口 |
| P5 | `kernel/.../tool/BuiltinToolNames.java` | `LOAD_SKILL` |
| P6 | `kernel/.../tool/builtin/LoadSkillToolAdapter.java` | 只读正文 |
| P7 | `kernel/.../tool/BuiltinToolPool.java` + `BuiltinToolRegistrar.java` | 登记 |
| P8 | `kernel/.../tool/YanhuoToolBindings.java` + `ToolUsePolicy.java` | 锁死并入三面 |
| P9 | `app/.../prompt/*` + classpath 种子 | 读盘/种子/装配 |
| P10 | `app/.../skill/*` | 扫包 + frontmatter |
| P11 | `TurnController.java` | 用人设服务替换常量 |
| P12 | `ToolSettings` / `ToolRuntimeConfig` / `TurnToolCallProjector` | 接线与投影 |

**禁止触及**：V001–V013、会话/Outbox/SSE、Memory Policy、chat 页面布局、BackgroundTask。

## 5. 验收（本阶段）

- [x] 改 `data-dir/prompts/SOUL.md` 后下一轮 system 含新文案（无需重编译）。
- [x] system 始终含硬安全段；仅清空 SOUL 不能去掉硬安全。
- [x] Skill 索引有界；`load_skill` 返回正文；未知 id 失败；正文不进常驻 system。
- [x] 现有窄测/编译不被故意破坏（ToolUsePolicy / Visibility / ToolSettings / ManagePage / ToolManageHttp / Projector）。

## 6. 拍板记录

| 项 | 决议 |
|----|------|
| 版本 | 插入 **0.2.4-P**（A 后、与 B 可交错） |
| 顺序 | P0 盘点（本文 §1）→ P1 文件化 → P2 Skill；P3 后挂 |
| 管理页 | **不要**（本阶段） |
| 完成线 | 用户要求本阶段代码交付；测文件仍留给 0.2.4-F |
