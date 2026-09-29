# 2.5.12 draft · MANIFEST

`status`: **已入仓** — 2026-09-28  
`spec`: [k05-2.5.12-p2-spec.md](../k05-2.5.12-p2-spec.md)（**P2 对话放行**）  
`implementation`: [k05-2.5.12-implementation.md](../k05-2.5.12-implementation.md)  
`locked`: **D1=C · D2=N · D3=A · D4=A · D5=A**  
`draft_gate`: 用户对话「审核通过，直接执行」→ **整号豁免逐文件停顿**；草稿一次性拷生产

| 序 | 路径 | 修改方式 | 目的 | 对话审 |
|----|------|----------|------|--------|
| 1 | `wn-server/app/.../task/NotifyInput.java` | 增加 | 解析 message/reminder/title/delivery | **已入仓** |
| 2 | `wn-server/app/.../task/LocalTaskExecutor.java` | 修改 | placeholder→fired+message | **已入仓** |
| 3 | `wn-server/app/.../task/DefaultBackgroundPolicy.java` | 修改 | 无正文 Reject | **已入仓** |
| 4 | `wn-server/app/.../task/DefaultTaskRuntime.java` | 修改 | prepare 校验正文 | **已入仓** |
| 5 | `wn-server/app/.../notice/NoticeCenter.java` | 修改 | 提醒专用 publish | **已入仓** |
| 6 | `wn-server/app/.../task/TaskDeliveryService.java` | 修改 | 先中心再队列；payload.message | **已入仓** |
| 7 | `wn-server/app/.../task/IdleDeliveryWorker.java` | 修改 | 定时提醒报告块 | **已入仓** |
| 8 | `wn-server/app/.../stream/DurableTurnScheduler.java` | 修改 | 已发中心则跳过 Idle 重复 publish | **已入仓** |
| 9 | `wn-server/kernel/.../tool/BuiltinToolPool.java` | 修改 | 工具说明填 message/reminder | **已入仓** |
| 10 | `BackgroundTaskQueryService` + UI/时间戳 | 审查 | 已先行入仓 | 已先行 |

禁止：新 migration；测文件；直投 ASSISTANT 冒充提醒；WORLD_TICK；真微信。
