# 0.2.4-F · REVIEW（窄测 + 真人收口）

`date`: 2026-09-25  
`status`: **F 已交付** — 用户确认真人 live 通过；窄测回归 BUILD SUCCESS。

## 真人

用户 2026-09-25：真人验证没问题（含 Markdown / 会话主路径）。

## 窄测命令与结果

```text
mvn -pl app,kernel -am test
  -Dtest=ConversationSearchSyncTest,SqliteConversationStoreLifecycleTest,
    SqliteTurnTerminalWriterCommittingFailTest,TurnTransitionTest,RunEventBusTest,
    ConversationAutoTitleServiceTest,ConversationSseControllerTest,ConversationHttpTest,
    ReceiveTurnIdempotencyTest,ChatPageTest,ConversationEmptyPurgeTest,
    LlmConversationArchiveEvaluatorTest,CreateConversationTest
  -Dsurefire.failIfNoSpecifiedTests=false
→ BUILD SUCCESS（2026-09-25）
```

## 本轮缺陷修（测中发现）

| 问题 | 修法 |
|------|------|
| `create()` 先 `purgeEmpty` 再插：重复 id 的空会话被清掉后变成 `Created` | 先 `conversationExists` → `AlreadyExists`，再 purge 其它空壳 |
| 旧测假设「连续两个空会话都保留」与 G 冲突 | 测改为：有消息才累加「会话N」；另测确认空壳被清 |

## 已知未扩（不挡 0.2.4 交付）

- 同会话 poll/claim FIFO 竞态、Stop×claim HTTP IT
- 搜索 cursor
- 工具执行中途打断
- 毒丸 / lease-vs-deadline 专用 HTTP IT（逻辑已在，见 F 前备忘）

## 清单

`docs/guide/01-checklist.md` · 0.2.4-B～F 项已勾。
