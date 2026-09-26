# 0.2.4-B 草稿审阅入口

## 状态（2026-09-24）

- 核心 1–5：**已通过并入仓** `wn-server/`
- HTTP / api.js：**已入仓**（本阶段完成）
- 页面侧栏布局：仍属 **0.2.4-D**，未改 `app.js`

## 数量

| 档 | 数量 |
|----|------|
| 全量 MANIFEST | 14 |
| 核心（已审入仓） | 6 |
| HTTP 本批 | Controller + DTO + HistoryAssembler + api.js |

## 人工可测 API（重启服务后）

- `GET /api/conversations`
- `GET /api/conversations/recent`
- `GET /api/conversations/{id}`
- `GET /api/conversations/{id}/messages`
- `GET /api/conversations/search?q=...`
- `PATCH /api/conversations/{id}` body: `{op, expectedRevision, title?}`
- `POST /api/conversations/trash/empty` body: `{confirm:"EMPTY_TRASH"}`

侧栏 UI 下一阶段 D。
