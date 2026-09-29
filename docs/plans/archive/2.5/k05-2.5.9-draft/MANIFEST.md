# 2.5.9 draft · MANIFEST

`status`: **已入仓 · 已验收（模型代审）** — 2026-09-28  
`compile`: `mvn -pl app -am compile` SUCCESS  
`spec`: ../k05-2.5.9-p2-spec.md  
`implementation`: ../k05-2.5.9-implementation.md  
`locked`: U1–U6=A  
`draft_gate`: 用户「全部通过，完成2.5.9」→ **整号放行**入仓  

| 序 | 路径 | 修改方式 | 目的 | 对话审 |
|----|------|----------|------|--------|
| 1 | `kernel/.../BackgroundTaskView.java` | 增加 | 只读投影 + cancelable/scheduled | 整号放行 |
| 2 | `kernel/.../BackgroundTaskRepository.java` | 修改 | listViews / findView | 整号放行 |
| 3 | `app/.../SqliteBackgroundTaskRepository.java` | 修改 | 投影 SQL + mapView | 整号放行 |
| 4 | `app/.../BackgroundTaskQueryService.java` | 增加 | list/detail 字段裁剪 | 整号放行 |
| 5 | `app/.../BackgroundTaskController.java` | 增加 | GET list/get + POST cancel | 整号放行 |
| 6 | `app/.../TaskRuntimeConfig.java` | 修改 | QueryService Bean | 整号放行 |
| 7 | `kernel/.../ErrorCodes.java` | 修改 | TASK_NOT_FOUND / TASK_ALREADY_TERMINAL | 整号放行 |
| 8 | `app/.../HttpMapping.java` | 修改 | 404/409 映射 | 整号放行 |
| 9 | `app/.../TaskDeliveryService.java` | 修改 | 虚线正文含「任务：uuid」+ outbox taskId | 整号放行 |
| 10 | `chat/task-sidebar.js` | 增加 | 侧栏列表/详情/取消/高亮 | 整号放行 |
| 11 | `chat/api.js` | 修改 | list/get/cancel + taskApiFetch | 整号放行 |
| 12 | `chat/app.js` | 修改 | 挂载侧栏；切会话/终态/outbox 刷新；虚线回调 | 整号放行 |
| 13 | `chat/render.js` | 修改 | taskDelivery 可点 + data-task-id | 整号放行 |
| 14 | `chat/state.js` | 修改 | deliveryTaskId / extractTaskIdFromText | 整号放行 |
| 15 | `chat/history.js` · `stream.js` · `index.html` | 修改 | 版本钉 + 侧栏宿主 | 整号放行 |
| 16 | `wannian-ui/.../chat.css` | 修改 | 任务分区 + 虚线可点样式 | 整号放行 |

禁止：消息中心；改交付/晋升/Gate；V024–V028 原文；测文件。
