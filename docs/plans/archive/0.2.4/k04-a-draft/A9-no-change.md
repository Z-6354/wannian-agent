# A9 · 契约接线结论（0.2.4-A）

`status`: **无需改生产文件** — 2026-09-24

## 结论

A8 仅更新 `ToolCallView` 注释；JSON 字段名仍为 `name` / `startedAt` / `finishedAt` / `argumentsJson` / `status` / `errorCode`。

| 文件 | 动作 |
|------|------|
| `TurnController.java` | 不改（仍 `toolCallProjector.listForTurn`） |
| `/chat/api.js` | 不改（仍读 `argumentsJson` 字符串） |

页面布局属 D，本阶段禁止改 `app.js`。
