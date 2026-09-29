# 2.5.10 draft · MANIFEST

`status`: **已入仓 · 已验收（模型代审）** — 2026-09-28  
`compile`: `mvn -pl app -am compile` SUCCESS  
`spec`: ../k05-2.5.10-p2-spec.md（**P2 已对话确认**；M1–M6=A）  
`implementation`: ../k05-2.5.10-implementation.md  
`locked`: M1–M6=A  
`draft_gate`: 用户「开始执行」→ **整号放行**入仓  

| 序 | 路径 | 修改方式 | 目的 | 对话审 |
|----|------|----------|------|--------|
| 1 | `kernel/.../notice/NoticeKind.java` | 增加 | 通知种类枚举 | 整号放行 |
| 2 | `kernel/.../notice/UserNotice.java` | 增加 | 读投影 | 整号放行 |
| 3 | `app/.../notice/NoticeCenter.java` | 增加 | 进程内总线 + 幂等 | 整号放行 |
| 4 | `app/.../archive/ArchiveNoticeStore.java` | 修改 | 委托 NoticeCenter | 整号放行 |
| 5 | `app/.../http/NoticeController.java` | 增加 | GET/dismiss | 整号放行 |
| 6 | `app/.../http/ConversationHygieneController.java` | 修改 | 注释；archive 兼容 | 整号放行 |
| 7 | `app/.../task/TaskDeliveryService.java` | 修改 | Busy 交付后 publish | 整号放行 |
| 8 | `app/.../task/TaskRuntimeConfig.java` | 修改 | 注入 NoticeCenter | 整号放行 |
| 9 | `app/.../stream/DurableTurnScheduler.java` | 修改 | Idle 交付后 publish | 整号放行 |
| 10 | `chat/message-center.js` | 增加 | 铃铛面板 | 整号放行 |
| 11 | `chat/api.js` | 修改 | listNotices/dismissNotice | 整号放行 |
| 12 | `chat/app.js` · `index.html` · `shell/app.js` | 修改 | 顶栏入口；cache-bust | 整号放行 |
| 13 | `wannian-ui/.../chat.css` | 修改 | 面板/角标样式 | 整号放行 |

禁止：运营文案库；V024–V028；Gate/Promote/Busy·Idle CAS；侧栏读 API 重做；测文件；VERSION 生产者（M5=A）。
