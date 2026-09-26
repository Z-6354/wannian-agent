# 提示词治理 + Skill 系统 · 方向锁定（方案 C）

`status`: **用户已选定方案 C** — 2026-09-24  
`scope`: wannian-agent 单核烟火；**已插入 0.2.4-P**（与 B～F 会话交付交错，不挡 B）  
`authority`: [路线图](../../roadmap.md) · [阶段施工单](./k04-p-prompt-skill-implementation.md) · 对标 OpenClaw / Hermes  
`decision`: **1 代码骨架 + 2 可编辑 Markdown 分层 + 3 Skill 渐进加载（索引 ≠ 正文）**

---

## 1. 选定含义

| 层 | 内容 | 真源 |
|----|------|------|
| **① 代码骨架** | 固定分区与装配顺序（安全、工具约定、截断规则、缓存边界） | Java（`ContextAssembler` / PromptAssembler）；可单测快照 |
| **② Markdown 分层** | 人设 / 用户说明 / 伴身可编辑文案 | 数据目录下 Markdown（见 §3）；热加载或进程启动加载，**不**靠改 jar |
| **③ Skill 目录** | 系统提示只注入 **可用 Skill 摘要列表**；完整 `SKILL.md` 经工具按需读取 | Skill 包目录；与常驻提示词分机制 |

**明确不选（本版）：**

- 不以 Codex/Cursor 式「cwd 层级规则树」为主（伴身不是 IDE cwd 中心）。
- 不做 Letta 满血 MemFS（agent 随意改提示 + git 合并）作为第一版。
- 不做「只靠工具 description 当 Policy」（Policy 仍独立）。

---

## 2. 与现有代码的衔接

已有拼装思想（[wannian-loop-modules §2.5](../../../research/wannian-loop-modules.md)）：

```text
安全与人设 → 关系摘要 → 相关长期记忆 → 裁剪近讯 → 当前用户句 → 可见工具描述
```

方案 C 落点：

- **安全块 + 工具约定** → ① 骨架（不可被用户 Markdown 覆盖安全红线）。
- **人设 / 口癖 / 禁忌** → ② Markdown（替代散落硬编码字符串）。
- **关系 / 记忆** → 仍走现有 Memory/Relationship 注入，不是 Skill。
- **Skill 列表** → 插在工具描述附近或独立「Skills」分区；正文不进常驻 system。

---

## 3. 建议目录（实施时可微调，选型已钉）

相对 `wannian.data-dir`（或仓内种子 + 数据目录覆盖）：

```text
prompts/                          # ② 可编辑分层（常驻，每轮注入）
  SOUL.md                         # 伴身灵魂 / 判断原则（必有默认种子）
  VOICE.md                        # 声线、示例、禁助手腔（Hermes：人格≠Skill）
  IDENTITY.md                     # 身份自称等（可选）
  USER.md                         # 对用户的稳定说明（可选；与记忆解耦）
  SAFETY.md                       # 仅运营可改的补充；不得削弱代码硬安全

skills/                           # ③ Skill 包（索引常驻，正文按需 load_skill）
  <skill-id>/
    SKILL.md                      # 正文 + frontmatter（name/description/version）
    …                             # 可选资源；第一版可只要求 SKILL.md
```

**边界（对标 Hermes）：** 语气/人设进 `prompts/` 常驻；Skill 只放按需流程（如 `how-to-remember`）。不要把「每轮都要生效的陪伴说法」做成 Skill。
代码内保留只读默认种子（classpath），首次启动拷到 data-dir；已存在用户文件则不覆盖。

---

## 4. Skill 边界（与提示词分立）

| 能力 | 提示词治理 | Skill 系统 |
|------|------------|------------|
| 发现 | — | 扫 `skills/*/SKILL.md`，解析 frontmatter |
| 授权 | — | 角色 × 配置允许列表；未授权不进索引 |
| 装载 | 每轮/启动读分层 MD | **索引进 system**；正文仅 `load_skill`（名待定）工具 |
| 执行 | 无「执行」 | Skill 描述流程；真正副作用仍走 ToolRuntime |
| 版本 | 文件 mtime / 可选 frontmatter | frontmatter `version`；不可变修订可后补 |

第一版 Skill **不做**：任意脚本执行、MCP 拉包、agent 自改 Skill 写回（可后挂）。

---

## 5. 建议分期（确认后开施工单）

| 阶段 | 交付 | 验收要点 |
|------|------|----------|
| **P0 盘点** | 列出当前硬编码提示词位置与分类（安全/人设/工具/标题…） | 清单完整；标「迁 / 留代码」 |
| **P1 提示词文件化** | PromptAssembler + data-dir MD + 种子；Assembler 接线 | 改 SOUL.md 无需重编译即影响下轮；安全红线仍在代码 |
| **P2 Skill 最小** | 发现 + 索引注入 + `load_skill` 只读正文 | 未授权不出现；正文不进常驻 prompt；2C2G 下索引有界 |
| **P3 治理** | 变量约定、长度上限、威胁扫描（可选）、回归快照 | 超限截断可测；快照防无意识漂变 |

**与 0.2.4：** 正式挂靠 **0.2.4-P**；可与 B 交错，避免与 B 同改会话/SSE 面。施工单 → [k04-p-prompt-skill-implementation.md](./k04-p-prompt-skill-implementation.md)。

---

## 6. 硬约束

1. 用户/运营 Markdown **不能**关闭或覆盖代码级安全分区。  
2. Skill 正文默认不进每轮 system；禁止「把所有 SKILL.md 拼进 prompt」。  
3. 2C2G：分层 MD 与 Skill 索引均有字符/条数上限。  
4. kernel 不绑 Spring 读盘细节；读文件在 app，kernel 吃已解析的 PromptBundle / SkillIndex。  
5. 不借本需求提前做 0.2.4 会话 UI / SSE。

---

## 7. 已拍板（2026-09-24）

1. **顺序**：P0 → P1 → P2；P3 后挂。  
2. **编辑入口**：只要改 data-dir 文件生效；**不要**管理页。  
3. **排期**：插入 **0.2.4** 为阶段 **0.2.4-P**（非独立号、非 0.2.5）。
