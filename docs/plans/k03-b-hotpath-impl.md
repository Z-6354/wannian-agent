# 0.2.3-B · 热路径收口实施单（工具锁死 / byName / Mem0 锚 / Freeze）

`status`: **已交付**（2026-09-24；验收见 [实施清单 · 0.2.3](../guide/01-checklist.md)）  
`version`: **0.2.3** 阶段 B 收口  
`parent`: [k03-memory.md](./k03-memory.md)  
`design-anchor`: Mem0 Observation Date；**无** IP/geo 地点工具  
`code-gate`: 已入仓；后续改动对照本单与清单  
`compat`: **不兼容**旧 `wannian.json` `tools.enabled[]`；无 `byName` 则按 defaults 重写

---

## 0. 已定摘要

```text
工具存盘三态     locked | on | off（无 default_on/off）
锁死（不可关）   list_tools, current_time, remember_fact, update_relationship
可选（出厂 on）  calculate, http_read, powershell_resolve_5, powershell_resolve_7
配置真源         wannian.json tools.byName + tools.yanhuo（不写旧 enabled）
时间锚           宿主注入 Observation Date（Asia/Shanghai 墙钟）；对齐 Mem0
地点锚           固定「未说明」；禁止 IP 反查；禁止瞎编城市
claim 规范化     模型按锚改写；禁止正则替换「今天」
写缝             SqliteMemoryCommitWriter / SqliteRelationshipCommitWriter
Freeze           plan_json v2；importance 必往返；缺 importance 读错勿默成 0.0
```

---

## 1. 分文件审核对照（已通过 · 实施须对齐）

| # | 文件 | 决议 |
|---|------|------|
| 1 | `YanhuoToolBindings.java` | 默认种子含锁死四名（见 §3.5）；硬编码出厂，非配置文件 |
| 2 | `ToolUsePolicy.java` | 三态 + 锁死集 + coerce / 有效启用 / clamp 强制并入锁死 |
| 3 | `ToolSettings.java` | `byName` 读写；删 ensureListTools / migrateLegacyPowershell；不兼容旧 enabled |
| 4 | `ManageBodies.java` + `ToolManageController.java` | API 用 byName + configState；锁死 selectable=false |
| 5 | `tools-page.js` | 锁死勾选禁用且 checked；保存 byName；facet 强制含锁死 |
| 6 | （并入 #1）种子与锁死对齐 | chat 轻量；work/research + http/PS |
| 7 | `ContextAssembler.java` | Mem0 观察日 + 地点未说明 → 追加 systemInstructions |
| 8 | `SqliteMemoryCommitWriter.java` | importance / SUPERSEDE CAS / tool_remember；不改 claim |
| 9 | `SqliteRelationshipCommitWriter.java` | null=不改合并；revision CAS；与 memory 解耦 |
| 10 | `SqliteTurnCommitter.java`（仅 plan 编解码段） | v2 memory/rel 往返；缺 importance 抛错 |

作废决议：IP 地点工具；每次加载软补「关了再开」；`default_on`/`default_off`。

---

## 2. 依赖顺序（必须按序）

```text
ToolUsePolicy
  → ToolSettings
  → ManageBodies + ToolManageController
  → tools-page.js
  → YanhuoToolBindings
  → ContextAssembler（可与 Bindings 并行，不依赖管理页）
  → Memory/Rel Writer 核对（半成品已存在则只补缺）
  → SqliteTurnCommitter plan 编解码硬化
```

---

## 3. 分步实施

### 3.1 `ToolUsePolicy.java`

路径：`wn-server/kernel/src/main/java/com/wannian/server/kernel/tool/ToolUsePolicy.java`

- 新增配置态：`LOCKED` / `ON` / `OFF`（存盘小写 `locked`/`on`/`off`）。
- `isLocked(name)`：四名锁死。
- `productDefault(name)`：锁死→`LOCKED`；池内其余可选→`ON`；未知名非法。
- `coerce(name, raw)`：锁死强制 `LOCKED`；可选非法→`ON`。
- `isEffectivelyEnabled(state, host, name)`：`locked`/`on` 且本机可用。
- `selectable`：锁死或 UNAVAILABLE → false。
- `clampEnabled`（或等价）：末尾强制并入所有锁死且本机可用名。

### 3.2 `ToolSettings.java`

路径：`wn-server/app/src/main/java/com/wannian/server/app/manage/ToolSettings.java`

**存盘形状：**

```json
"tools": {
  "byName": {
    "list_tools": "locked",
    "current_time": "locked",
    "remember_fact": "locked",
    "update_relationship": "locked",
    "calculate": "on",
    "http_read": "on",
    "powershell_resolve_5": "on",
    "powershell_resolve_7": "on"
  },
  "yanhuo": { "chat": [...], "work": [...], "research": [...] }
}
```

- `Snapshot` 持有 `byName`；`enabled()` **派生**自有效开启名（供 `registerEnabled`）。
- `readTools`：无 `byName` → 视为无配置，返回 null 或走 defaults 重写（**不要**从旧 `enabled[]` 推断）。
- `writeTools`：只写 `byName` + `yanhuo`；**不写** `enabled`。
- **删除** `ensureListTools`、`migrateLegacyPowershellNames`。
- `validate`/`update`：锁死名忽略客户端 `off`；facet **强制并入**全部锁死名。
- `defaults`：每池名 `productDefault`；facet 来自 `YanhuoToolBindings.create()` 后再并入锁死。

### 3.3 管理 API

路径：

- `wn-server/app/src/main/java/com/wannian/server/app/manage/ManageBodies.java`
- `wn-server/app/src/main/java/com/wannian/server/app/manage/ToolManageController.java`

- `ToolPoolEntryBody` 增加 `configState`（`locked`|`on`|`off`）。
- `ToolsBody` / `UpdateToolsRequest`：以 `byName` 为真源；**删除**对旧 `enabled[]` 的依赖（不兼容上版本）。
- `selectable = !isLocked && isAvailable`。
- PUT：经 `coerce` 后写盘。

### 3.4 `tools-page.js`

路径：`wn-server/app/src/main/resources/META-INF/resources/manage/tools-page.js`

- `locked`：`checked=true`，`disabled=true`（保存时仍须写入 `byName.locked`，勿因 disabled 漏传——组装 byName 时对锁死名写死 `locked`）。
- 提交 body：`{ byName, yanhuo }`，无 `enabled`。
- 三模式 facet：锁死名强制 checked+disabled 并计入提交列表。
- 文案：核心锁定不可关；可选可开/关；PS 互斥保留。
- 可更新 import 的 `?v=` 缓存戳。

### 3.5 `YanhuoToolBindings.java`

路径：`wn-server/kernel/src/main/java/com/wannian/server/kernel/tool/YanhuoToolBindings.java`

三面共同前缀（顺序）：

1. `LIST_TOOLS` 2. `CURRENT_TIME` 3. `REMEMBER_FACT` 4. `UPDATE_RELATIONSHIP` 5. `CALCULATE`

- **chat：** 仅上列。  
- **work / research：** 上列 + `HTTP_READ` + `POWERSHELL_RESOLVE_5` + `_7`（互斥仍由 Policy 处理）。

### 3.6 `ContextAssembler.java`（Mem0 锚）

路径：`wn-server/kernel/src/main/java/com/wannian/server/kernel/agent/ContextAssembler.java`

- 时区 `Asia/Shanghai`；建议可注入 `Clock`（默认 system）。
- 锚块示例：
  - `观察日（Observation Date）：yyyy-MM-dd`（可附 ISO 时刻一行）
  - `地点：未说明。禁止把「这里」写成臆测地名；未知则不记或写明地点未说明。`
- 追加进 `systemInstructions`（空则仅锚块）。
- `memoryContext` / `relationshipSnapshot` 本项仍 null（召回属阶段 D）。
- **禁止：** IP 工具；正则换「今天」；从近讯抽地名。

### 3.7 Writer 核对

- `SqliteMemoryCommitWriter.java`：importance 落列；SUPERSEDE CAS；`propose_id=tool_remember`；`triage_id=validate`；不改 claim 正文。
- `SqliteRelationshipCommitWriter.java`：null 合并旧字段；CAS；不写 memory 表。

半成品已齐则只补注释/缺口；禁止改短事务 `MemoryCommand`（C/S11）。

### 3.8 `SqliteTurnCommitter` plan 段

路径：同文件内 `insertFrozenPlan` / `loadFrozenPlan` / `writeMemoryChange` / `readMemoryChange` / rel 对称 / `plansMatchFrozen`。

- `COMMIT_PLAN_FORMAT = 2`；含 memory/rel。
- `readMemoryChange`：缺 `importance` 或非有限数 → **抛错**（禁止 `asDouble()` 默成 0.0）。
- **禁止**本项整文件拆分（见 [k03-turn-committer-split.md](./k03-turn-committer-split.md)）。

---

## 4. 禁止

- IP/geo 地点工具；宿主代调公网查城市。  
- 正则替换「今天」/夹具词拒写「这里」。  
- 旧 `enabled[]` 迁移；`ensureListTools` 式「关了下次加载再补」。  
- 阶段 C/D/E；HTTP memory API；向量；第二次 importance LLM。  
- 本批写/改测试（含 `MigrationSmokeTest`）——**0.2.3 总收口**再做。  
- commit / push（未授权）。  
- 扩大改 `SqliteTurnCommitter` 非 plan 段。

---

## 5. 验收（实施完成后自检；窄测延后）

```text
[x] byName 落盘；重启后锁死仍为 locked；可选 off 保持 off
[x] 管理页锁死不可取消勾选；保存后 JSON 正确（前端+API 已改）
[x] 新装/defaults：chat 可见 remember_fact + update_relationship
[x] AgentInput.systemInstructions 含 Observation Date 与地点未说明
[x] remember → pending → freeze → commit 同行写入 memory_record.importance（Writer 半成品已齐）
[x] update_relationship → relationship_state CAS（Writer 半成品已齐）
[x] plan_json 缺 importance 无法静默以 0 恢复提交（readMemoryChange 抛错）
```

交付格式：

```text
批次：0.2.3-B 热路径收口
实际 diff 文件列表：
未完成 / 指令缺口：
（测试：本批跳过，留总收口）
```

---

## 6. 与父单关系

- 勾选进度：本单实施完成后，回写 [k03-memory.md](./k03-memory.md) 阶段 B 相关项（测试项仍空到总收口）。  
- 清单总状态：[01-checklist · 0.2.3](../guide/01-checklist.md)。  
- 召回 Top-N / Review / 弱 B / HTTP：**不**在本单。
