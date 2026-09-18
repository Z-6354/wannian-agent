# 01 · 产品承诺与组件

## 产品承诺

- **个人使用**（单用户，不做多租户）
- **情感向可成长陪伴**（关系状态与长期记忆在核心，而非堆工具）
- **大脑在云端**：随时可聊、多端同一会话真相
- **本地是手脚**（v0.2）：文件 / shell / 截图等能力节点，不跑第二套主脑

## 组件一览

| 名称 | 角色 | 依赖 | 可缺席 |
|------|------|------|--------|
| **wn-server** | Session Owner：Turn、陪伴、记忆、LLM、鉴权、通道 SPI、**可复用内核**、**内嵌对话页** | 无 | **否（核心）** |
| **wn-manage** | 管理端（配置 / 会话 / 节点 / 人设） | 只调 wn-server API | 是 |
| **wn-web** | 独立网页对话（从内嵌页演进拆出） | wn-server API | 是（MVP 用内嵌页） |
| **wn-app** | 手机端 | wn-server API | 是 |
| **wn-agent** | Windows 电脑客户端（能力节点） | wn-server 派发 / 心跳 | 是（**v0.2**） |

## 不变量

1. 聊天记录与陪伴状态 **只在 wn-server** 一份；各端不私藏主会话库。  
2. 任一 Client（含 wn-manage）关闭，其它 Client 仍可用，只要 wn-server 在。  
3. Client **不实现第二套 Agent 主循环**；只消费 API / 执行被派发的工具。  
4. Bot / IM 等是 Channel，写入同一会话库，不是平行大脑。

## 与历史遗产

- **han-server**、旧 Boss/Notify 矩阵：仅作实现参考，**不背兼容包袱**；主交付是 wannian 这一套。  
- **legacy/han-agent**：不解冻、不复制巨石；可借鉴 Turn 管道 / 预算 / propose→confirm 等概念。  
- 不把 Turn / 陪伴写进 han-server 许可业务。
