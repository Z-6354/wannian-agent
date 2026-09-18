# 04 · 重建清单与分工

## 当前状态

- `products/wannian-agent/`：**仅计划文档**（脚手架已清空）。  
- 旧「本机 Windows 为 Session Owner + api/core/app 模块」方案 **作废**，以本目录定稿为准。

## 建议仓内落位（动手时）

```text
products/wannian-agent/          # 或改名为 products/wannian/ —— 动手前再确认
  README.md + 01..04 计划       # 计划可保留或迁 docs/design
  wn-server/                    # 核心（含内嵌对话 static）
  wn-manage/                    # 可选
  wn-web/                       # 后期
  wn-app/                       # 后期
  wn-agent/                     # v0.2
```

命名以 **wn-*** 产品名为准；Maven/包名重建时再定（如 `com.wannian.server`）。

## 重建步骤（授权「按定稿重建」后）

1. 确认目录名（保留 `wannian-agent` 或改为 `wannian`）。  
2. 新建 **wn-server** 工程：契约 + 内核扩展点 + Boot + 内嵌对话页。  
3. 核心实现留 `OWNER: YOU` 注释桩（Companion / Memory / Llm / AgentLoop），框架可跑通 stub。  
4. 更新 `docs/modules/INDEX.md`、`docs/overview.md`、`docs/architecture/products.md` 与本计划一致。  
5. 窄构建验证：package + 打开内嵌页打一轮 Turn。  
6. **不**在 MVP 创建 wn-agent / wn-app / 独立 wn-web。

## 分工

| 谁 | 负责 |
|----|------|
| 框架 / 契约 / 装配 / 内嵌页壳 / 各端业务接线 | 可代建（授权后） |
| Agent 循环、陪伴成长、记忆策略、人设 prompt | **维护者自写** |
| wn-manage / Bot / wn-agent 等后续 | 可代写 |

## 动手口令

未收到明确 **「按定稿重建」**（或等价授权）前：只维护本计划文档，不建工程代码。
