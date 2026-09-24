# 0.2.4-A · 统一行为账本（立项）

`status`: **下一默认工作 · 加厚未开工**（`turn_step` 核心已由 **0.2.3-L** 交付；见 [k03-l-run-journal.md](./k03-l-run-journal.md)）  
`version`: **0.2.4** 内部阶段 A（别名 K04-A）  
`authority`: [roadmap](./roadmap.md) · [实施清单 · 0.2.4](../guide/01-checklist.md) · [04-kernel turn_step](../guide/04-kernel-reference.md)  
`prerequisite`: **0.2.2** 工具路径可用；**0.2.3** Memory 已交付；**0.2.3-L** 已落表与 MODEL/TOOL/启停写入

---

## 1. 为何此刻立项

用户期望：**统一日志系统**——用户 / 系统 / agent 每次行为有可查询落库记录。  
现有 Message、Turn、`outbox_event`、`AgentTrace`、ErrorCodes **都不能**单独承担该职责（见路线图说明）。

## 2. 排期裁定（2026-09-22）

| 候选 | 结论 |
|------|------|
| 插在 0.2.2 与 0.2.3 之间 | **否** — 拖延成长核心 Memory |
| 拖到 0.2.7 恢复批 | **否** — Outbox / 恢复都需要先有逐步事实 |
| 独立新号并顺延 0.2.4–0.2.7 | **不取** — 文档与测试矩阵改号成本高 |
| **并入 0.2.4，且必须先于 Outbox/SSE** | **是** — 同事务账本 → 再交付；编号不变 |

顺序硬约束：

```text
0.2.3 Memory
   ↓
0.2.4-A 统一行为账本（turn_step + 单一写入路径）
   ↓
0.2.4-B Outbox / SSE / /chat/ 历史恢复
```

未完成 0.2.4-A，不得勾选 0.2.4 完成，也不得宣称 SSE「有完整行为事件」。

## 3. 目标（立项级）

1. **一张账本**：以文档已设计的 `turn_step` 为持久主体（migration 新建；V001 刻意未建）。
2. **一条写入路径**：Turn 执行/提交路径追加步骤；禁止各模块各自 `log.info` 冒充审计。
3. **统一记录形态**（字段可在开工施工单细化）：
   - actor：`user` / `system` / `agent`（及后续可扩展）
   - kind：至少 `USER_INPUT`、`MODEL_CALL`、`TOOL_CALL`、`MEMORY_WRITE`、`FINALIZE`（与现有 kind 示例对齐）
   - 关联：`conversation_id` / `turn_id` / `step_no`
   - 结果：`status` + 稳定 `error_code`（引用 ErrorCodes，不另开码表）
   - 脱敏后的 request/result 摘要（禁止密钥、完整敏感工具结果、隐藏推理）
4. **与 Outbox 分工**：账本 = 事实；outbox = 已提交后的可靠交付。SSE 只暴露策略允许的子集。

## 4. 非目标（本阶段）

- 替代 ErrorCodes / `SafeErrorLog`（运维错误通道仍独立）
- 多会话审计 UI / 跨设备同步 / 开放检索产品
- 把未提交 / 执行中步骤伪造成已完成对用户可见历史
- ~~在 0.2.3 开工前抢做本批实现~~ → **已由用户改口**：0.2.3-L 先行窄版；本文件保留加厚与验收全集

## 5. 验收（立项草案；开工施工单可加细）

```text
[ ] V00x migration 建立 turn_step（及必要索引）；冒烟不再断言「表不存在」
[ ] 一次成功 Turn：至少落 USER / MODEL（或等价）/ FINALIZE 步骤，step_no 单调
[ ] 一次工具 Turn：含 TOOL_CALL，与 tool 名 / operationId（若有）可关联
[ ] 0.2.3 记忆正式写入：可追溯 MEMORY_WRITE（或等价 kind），与 Turn 同事务边界一致
[ ] 回滚 Turn 时步骤不残留半提交
[ ] 查询 API 或仓储接口可按 turn_id 列出步骤（管理/调试用即可；不必先做华丽 UI）
[ ] 脱敏：密钥形态、Authorization、完整敏感正文不进 request_json/result_json
[ ] AgentTrace 可视为内存投影；不得成为唯一真相源
```

## 6. 执行者交付（开工后）

- 施工单正文（可拆 `k04-behavior-journal.md` 实施段 + `k04-outbox-sse.md`）
- migration + 写入接线 + 窄测证据
- 清单 0.2.4-A 勾选与路线图状态更新

未建实施段 = **不授权写生产代码**。
