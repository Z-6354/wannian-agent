# B7 · SqliteTurnCommitter 增量说明（核心审阅）

目标文件：`wn-server/app/src/main/java/com/wannian/server/app/persistence/SqliteTurnCommitter.java`  
不整文件重写；入仓时只打下列补丁。

## 1. receive 门禁（替换「仅存在」）

在 `receiveInTransaction` 开头，将：

```java
if (!conversationExists(...)) { CONVERSATION_NOT_FOUND }
```

改为：

```java
ConversationGate gate = loadConversationGate(connection, plan.conversationId().asString());
if (gate == null) {
  return Rejected(CONVERSATION_NOT_FOUND, ...);
}
if (!"ACTIVE".equals(gate.status())) {
  return Rejected(CONVERSATION_NOT_ACTIVE,
      "会话状态为 " + gate.status() + "，不能接收新消息");
}
```

`loadConversationGate`：`SELECT status, title FROM conversation WHERE id=?`。

新增错误码：`ErrorCodes.CONVERSATION_NOT_ACTIVE`（VALIDATION）。

## 2. 用户消息插入后 FTS

`insertUserMessage` 成功后（仍在同一 connection/事务）：

```java
ConversationSearchSync.upsertMessageRow(
    connection,
    conversationId,
    messageId,
    "ACTIVE",
    gate.title(),
    extractText(contentJson));
```

助手消息 `insertAssistantMessage` 同理（status 用当前会话 status，完成路径一般是 ACTIVE）。

`extractText`：解析 `{"v":1,"text":"..."}`，失败则 `""`（不得把整段 JSON 写入 FTS）。

## 3. 禁止

- 不改 claim/commit 状态机
- 不改 MEMORY_WRITE 事务边界
- FTS 失败 → 与正式提交同事务回滚（强一致；避免搜索落后于已提交消息）
