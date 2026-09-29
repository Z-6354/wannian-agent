# 用户反馈：点踩 / 坏样本不入风格历史

`status`: **backlog** — 2026-09-26  
`owns`: 聊天反馈与风格卫生（未立项实施）  
`depends`: 2.4 会话 UI 已交付；本单不挡 2.5

## 背景

酒馆实践强调：不要让不喜欢的助手回复继续留在「已采纳历史」里当风格范本。本产品已有「换一种说法」（rewrite）会剔除上一句助手腔；**当前不做重新生成产品化**。

用户后续可能要 **点踩**：标记某条助手回复质量差，并降低其对后续风格/近讯的污染。

## 范围（待讨论后立项）

1. **点踩交互**：消息级 👎（可选短因：机械 / 太长 / 未听懂 / 其他）。
2. **风格卫生**：被踩消息在后续 `conversationExcerpt` / 风格近讯中降权或排除（对齐 rewrite 的 excludeLastAssistant 语义，可扩到显式标记）。
3. **不做**：本单不强制做「重新生成」按钮；若点踩后提供「换一种说法」入口即可复用现有 rewrite。
4. **可选后续**：踩数统计进管理页；不进入公开排行。

## 明确不做（本 backlog）

- 不在本阶段改 Turn 状态机或账本核心。
- 不引入酒馆 swipe 全套 UI。
- 不因点踩自动改写 `SOUL.md` / `VOICE.md`。

## 验收草案（立项时再细化）

- [ ] 点踩后同会话下一轮近讯不再把该助手句当句式范本。
- [ ] 点踩不删除用户可见历史（可灰显或角标），除非产品另定「隐藏」。
- [ ] 与现有「换一种说法」并存、不冲突。

## 参考

- [anti-mechanical-companion-prompts.md](../research/anti-mechanical-companion-prompts.md) §6 P3 / §9
- 现有：`VoiceNudge` rewrite 变体、`ContextAssembler` `excludeLastAssistant`
