# wn-server

Wannian 服务端。**v0.1 已封**（会话 + 直接回答）；下一步是 **v0.2 单核 harness**。依赖方向固定：

```text
wn-server-app → kernel → api
wn-server-app → wannian-ui
```

进度：[实施清单](../docs/guide/01-checklist.md) · 排期：[路线图](../docs/plans/roadmap.md)
## Modules

| Module | Artifact | Role |
|--------|----------|------|
| `api` | `wn-server-api` | Contracts and value types |
| `kernel` | `wn-server-kernel` | Domain behavior |
| `app` | `wn-server-app` | Spring Boot host, adapters, and embedded `/manage/`, `/chat/` pages |
| `../wannian-ui` | `wannian-ui` | Independent style package: shared `/ui/` visual resources |

## Build

使用仓内国内镜像（当前测速首选阿里云 central）：

```text
# 在 HANAGENT 仓根
.\mvnw.cmd -s .mvn/settings.xml -f products/wannian-agent/wn-server/pom.xml package
```

镜像配置见仓库 `.mvn/settings.xml`（含测速记录）。若 Central 镜像缺构件，可改回 `repository/public`。

## Smoke

After `app` starts: `GET /internal/live` → `{"status":"live","service":"wn-server"}`.
