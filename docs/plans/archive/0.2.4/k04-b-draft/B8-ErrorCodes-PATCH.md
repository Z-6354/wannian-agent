# B8 · ErrorCodes 增量

文件：`wn-server/kernel/src/main/java/com/wannian/server/kernel/error/ErrorCodes.java`

在校验/冲突区追加并登记 CATEGORY：

```java
/** 会话非 ACTIVE，拒绝接收新 Turn。 */
public static final String CONVERSATION_NOT_ACTIVE = "CONVERSATION_NOT_ACTIVE";

/** 会话仍有进行中/排队 Turn，拒绝归档或移入回收站。 */
public static final String CONVERSATION_BUSY = "CONVERSATION_BUSY";

/** 标题清洗后为空或非法。 */
public static final String INVALID_TITLE = "INVALID_TITLE";

/** 操作要求会话在回收站，但当前不是。 */
public static final String NOT_TRASHED = "NOT_TRASHED";
```

登记：
- CONVERSATION_NOT_ACTIVE → VALIDATION
- CONVERSATION_BUSY → CONFLICT
- INVALID_TITLE → VALIDATION
- NOT_TRASHED → VALIDATION

`REVISION_CONFLICT` 已存在，复用。
