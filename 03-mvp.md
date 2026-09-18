# 03 · MVP 与版本

## MVP（必须）

**wn-server 单独可运行**，并包含：

1. 健康检查  
2. 会话 Turn API（同步；流式可占位）  
3. 陪伴快照读写钩子（实现可先 stub，契约要有）  
4. 个人鉴权（最小可用）  
5. **内嵌 Web 对话页**（唯一正式对话入口）  
6. Channel SPI（内嵌 Web 为第一个实现）

**明确不做（MVP）**

- wn-agent / wn-app / 独立 wn-web  
- 多租户、复杂 Bot 接线  
- 写入 han-server、解冻 legacy/han-agent  
- 复活微信 Sidecar  

**wn-manage**：MVP 可缺席；若做则只调 API，关掉后内嵌对话仍可用。

## v0.2

- **wn-agent**：Windows 能力节点（出站连接、心跳、工具执行、危险操作确认）  
- server 侧节点调度与策略  

## 其后

- wn-app、Bot 通道、wn-web 从内嵌页拆分、wn-manage 增强  

## MVP 验收草案

1. 只启动 wn-server，浏览器打开内嵌页，可多轮对话（允许 stub LLM）。  
2. 不启动 wn-manage，对话仍可用。  
3. 持久化策略写死并验收：要么重启可恢复 session，要么文档标明「MVP 仅内存」且行为符合。  
4. API 语义稳定：`health` / `turns` / `companion`（路径名重建时可微调，角色不变）。  
5. 本目录计划与实现边界一致；无第二套主会话库。

## 禁止项

- 各端私藏主会话 / 主陪伴状态  
- 未定版本就并行开工五端  
- 把「参考 han-server」做成改活许可/Notify 主路径当 wannian 内核  
- 在 Client 内再实现一套完整 ReAct「主脑」
