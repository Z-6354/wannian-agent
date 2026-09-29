# 2.5 · 定时规则同业检索（Schedule DSL）

`status`: **历史 · 已交付（决议保留）** — 2026-09-29 · 归档 [archive/2.5](../../plans/archive/2.5/README.md)  
`purpose`: 回答「每周一 / 每月10号 / 两天后 / 每年1月1日」等规则在同业如何表示与计算；供 2.5 Task 时间字段定稿。  
`plan`: [k05-task-background.md](../../plans/archive/2.5/k05-task-background.md)  
`peers`: [background-task-peers-2.5.md](./background-task-peers-2.5.md)

---

## 1. 结论（给计划用）

1. **同业主流不是「两种 Task」**，而是 **执行时机 × 重复策略** 的组合，外加一套 **schedule DSL**（相对延迟 / 绝对时刻 / 固定间隔 / cron 或 RRULE）。  
2. **OpenClaw / Hermes / Claude session** 落地以 **5 字段 cron + 相对时长 + every** 为主；**Codex App / ChatGPT** 高级档用 **RFC5545 RRULE**；**Codex CLI 无官方调度**。  
3. **本仓推荐：** 同一 Task 账本上存 **判别联合 `schedule_spec`**（`relative` | `at` | `every` | `cron`）+ 物化 **`next_fire_at`**（Hermes 思路）+ 行级 **`timezone`**（OpenClaw 思路）；**勿**主存 RRULE；**勿**另建 Cron 表。  
4. 自然语言由模型起草 → **用户审核确认**后再落库（本仓新增门，同业多为「问一句就建」，陪伴产品宜更严）。

---

## 2. 自然语言 → 同业表达式

默认墙钟 `Asia/Shanghai`；未写时刻时表中用常见 09:00 / 00:00 示例。

| 自然语言 | OpenClaw | Hermes | Codex App / ChatGPT | Claude Code（session） |
|----------|----------|--------|---------------------|------------------------|
| 每周一 09:00 | `--cron "0 9 * * 1" --tz Asia/Shanghai` | `"0 9 * * 1"` | `RRULE:FREQ=WEEKLY;BYDAY=MO;BYHOUR=9;BYMINUTE=0` | cron `"0 9 * * 1"` + recurring |
| 每月 10 号 09:00 | `"0 9 10 * *"` + tz | 同左 | `FREQ=MONTHLY;BYMONTHDAY=10;BYHOUR=9;BYMINUTE=0` | `"0 9 10 * *"` |
| 两天后 | `--at 2d` 或 ISO；无 offset 的 ISO 当 **UTC** | `in 2d` / `2d`（文档与源码对裸时长有歧义） | 一次提醒 / one-shot（非 RRULE 主路径） | 钉到下一匹配分的 one-shot cron，或 Routines one-off |
| 每年 1 月 1 日 00:00 | `"0 0 1 1 *"` + tz | 同左 | `FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=1;…` | `"0 0 1 1 *"` |
| 每 2 小时 | `--every 2h` | `every 2h` → interval | `FREQ=MINUTELY;INTERVAL=120` 或 hourly UI | `/loop 2h` → 转 cron |

---

## 3. 各家机制摘要

### 3.1 OpenClaw（优先对标 · 官方 docs）

时间类 schedule **三种 kind**（另有 `on-exit` / `stream` 事件类，2.5 不做）：

| Kind | 含义 | 例 |
|------|------|-----|
| `at` | 单次：ISO 或相对（`20m`） | 两天后、今晚 8 点 |
| `every` | 固定间隔 | `10m` / `1h` / `1d` |
| `cron` | 5/6 字段 + 可选 `--tz` | 每周一、每月 10 号、每年 1/1 |

要点：

- 解析库 **croner**；**日-of-month 与 日-of-week 同时非 `*` 时是 OR（Vixie）**，不是 AND；AND 需 `+` 修饰或拆条件。  
- `at` 无时区 → **UTC**；`cron` 无 `--tz` → Gateway host；`--tz` 不用于 `every`。  
- 整点 cron 默认可 stagger；可用 `--exact`。  
- Task 账本与 Automations **分层**（本仓 Q9：Scheduler 模块服务同一 Task 表，不另建实体表）。

源：https://docs.openclaw.ai/automation/cron-jobs/schedules

### 3.2 Hermes Agent（优先对标 · 官方 docs + cron 内部）

对外 **单字符串** 解析为 `once | interval | cron`：

| 格式 | 例 | 行为 |
|------|-----|------|
| 相对延迟 | `30m` / `in 30m` / `2d` | 单次 |
| 间隔 | `every 2h` | 多次 |
| cron | `0 9 * * 1` | 多次日历 |
| ISO | `2025-06-15T09:00:00` | 单次绝对点 |

运行时：`jobs.json` + **`next_run_at`**；~60s tick；跑完 `compute_next_run`；可选 `repeat` 次数。库：**croniter**。

注意：裸 `30m` 在文档/源码演进中曾与 interval 歧义 → **本仓禁止歧义裸串，必须显式 `relative` vs `every`。**

源：https://hermes-agent.nousresearch.com/docs/user-guide/features/cron · cron-internals

### 3.3 Codex

| 面 | 事实 |
|----|------|
| **App / ChatGPT Desktop·Web** | Automations / Scheduled；高级自定义 **RRULE**；thread 内可用分钟间隔 heartbeat |
| **CLI** | **无官方 Scheduled 管理**；需外挂 OS cron / 社区 `codex-cron` / 实验 fork |
| 可抄 | 用户自然语言描述 → 系统结构化；测试 prompt 再调度 |
| 勿抄 | 以 RRULE 为主存；CLI「假装有原生调度」 |

源：https://developers.openai.com/codex/app/automations · https://learn.chatgpt.com/docs/automations · openai/codex#8317

### 3.4 Claude Code

- Session：`CronCreate` 标准 **5 字段 cron** + `recurring`；默认会话级；durable 可选；** recurring 约 7 天自毁**。  
- `/loop`：间隔转 cron 或自步长 wakeup。  
- 云端 **Routines**：preset（hourly/daily/weekdays/weekly）或 custom cron（≥1h）。  
- 陪伴长驻任务勿抄 7 天自毁。

源：https://code.claude.com/docs/en/scheduled-tasks · routines

---

## 4. 本仓推荐落点（待计划 P1/P2 采纳）

### 4.1 产品轴（正交 · 非「两种 Task」）

| 轴 | 取值 |
|----|------|
| 执行时机 | **立即** / **定时（未来某点或日历）** |
| 重复 | **单次** / **多次** |
| 合法组合 | 立即⇒通常单次结束；定时⇐单次或多次；禁止「立即+多次」除非产品另开（本版不做） |

创建时：模型起草 Task 摘要（含 schedule 人话 + 结构化草稿）→ **发给用户审核** → 通过后才 Commit 落库。

### 4.2 `schedule_spec`（JSON 判别联合）

```jsonc
{ "type": "relative", "offset": "2d", "resolved_at": "…" }  // 两天后；入库可 resolve 成 at
{ "type": "at", "at": "2026-09-28T12:00:00+08:00" }
{ "type": "every", "every_ms": 7200000, "anchor_at": "…" }
{ "type": "cron", "expr": "0 9 * * 1", "tz": "Asia/Shanghai" }
```

| 场景 | type |
|------|------|
| 两天后 | `relative` → resolve `at` |
| 某日某时一次 | `at` |
| 每 N 小时 | `every` |
| 每周一 / 每月10号 / 每年1月1日 | `cron` |

行级：`timezone`、`next_fire_at`、`last_fired_at`；Ticker 只比较 `next_fire_at`，开火后由 **ScheduleResolver** 推进。

### 4.3 可吸收 / 勿抄

| 吸收 | 勿抄 |
|------|------|
| OpenClaw：at/every/cron 三分；显式 tz；文档化 OR | 独立 Automations 表与 Task 双账本；on-exit/stream/pacing |
| Hermes：物化 next_fire；tick；repeat 可选 | 裸时长歧义 DSL |
| Codex：自然语言起草 + 用户侧确认意识 | RRULE 主存；CLI 无原生却假装有 |
| Claude：relative loop vs 日历 cron 分层 | 7 天强制自毁 |

---

## 5. 引用

- OpenClaw schedules：https://docs.openclaw.ai/automation/cron-jobs/schedules  
- OpenClaw cron jobs：https://docs.openclaw.ai/automation/cron-jobs  
- Hermes cron：https://hermes-agent.nousresearch.com/docs/user-guide/features/cron  
- Hermes internals：https://hermes-agent.nousresearch.com/docs/developer-guide/cron-internals  
- Codex automations：https://developers.openai.com/codex/app/automations  
- ChatGPT scheduled：https://learn.chatgpt.com/docs/automations  
- Codex CLI 无原生：https://github.com/openai/codex/issues/8317  
- Claude scheduled tasks：https://code.claude.com/docs/en/scheduled-tasks  
- Claude routines：https://code.claude.com/docs/en/routines  
