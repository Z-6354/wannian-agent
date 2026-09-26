# 供应商 Preset 重构（内置选厂 + 文件密钥）

`status`: **已归档 · 代码已入仓 · 2026-09-25** — 不作活开工入口；对话栏选型仍属后续（正文 §6）。  
`scope`: 管理台供应商连接；**不**改对话输入栏选型（仅记入后续）  
`related`: 现有 `model_vendor` / `model_listed` / `openai-compatible`+`openai-responses`

---

## 1. 已锁定决议

| # | 决议 |
|---|------|
| 1 | **只内置 Preset** 供用户选择；架构预留后续「外部自定义」扩展点，本批不暴露 UI/API 入口 |
| 2 | 密钥写入 `{data-dir}/secrets/{vendorId}.key` **明文文件**；不加密（本机、性能优先）；读路径优先文件，回退环境变量以兼容旧 live |
| 3 | **模型启用保持现状**（检索 → 加入列表 → 启用）；禁止手填 modelId（现状已无） |
| 4 | **后续**：对话输入栏选模型 — **只写计划，本批不动**（见 §6） |

---

## 2. 目标 UX

1. 供应商页：展示内置 Preset；未连接可「连接」并粘贴 API Key；已连接可「更新密钥 / 检索模型 / 断开」
2. 用户**不再**填写 id / protocol / baseUrl / 环境变量名
3. 模型页逻辑不变

---

## 3. 架构（工厂 / 适配器）

```
VendorPresetSource          // 接口；本批 BuiltinVendorPresetSource
CompositeVendorPresetSource // 预留：Builtin + 未来 Custom
VendorPreset                // id, displayName, protocol, baseUrl, defaultApiKeyEnvHint
VendorSecretStore           // secrets/{id}.key 读写删
VendorCredentialAccess      // get(vendorId, apiKeyEnv) → file ?? getenv
ModelPortFactory            // VendorRecord+modelId → ModelPort（按 protocol 选端点）
VendorAdapterResolver       // 已有；Catalog 适配器
```

运行时：`EnabledModelPortResolver` 改走 `ModelPortFactory` + `VendorCredentialAccess`。

---

## 4. 内置 Preset（本批）

| id | 显示名 | protocol | baseUrl |
|----|--------|----------|---------|
| `wannian-ai` | 万年AI · Responses | `openai-responses` | `https://api.wannian.fun/v1` |
| `wannian-openai` | 万年AI · Chat | `openai-compatible` | `https://api.wannian.fun/v1` |
| `deepseek` | DeepSeek | `openai-compatible` | `https://api.deepseek.com/v1` |
| `zhipu` | 智谱 | `openai-compatible` | `https://open.bigmodel.cn/api/paas/v4` |

> 两个万年 Preset **共享** `secrets/wannian-ai.key` 与 `WANNIAN_AI_API_KEY`；连接任一侧会一并登记另一侧，并按目录 `owned_by`（codex vs openai/deepseek）过滤 / 迁移已加入模型。

未知 id 不允许「连接」；库中遗留非 preset 行仍可删除，不可再通过 UI 新建。

---

## 5. API 变更

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/manage/model/presets` | 内置列表 + `connected` / `hasSecret` |
| PUT | `/api/manage/model/vendors/{id}/connect` | body `{ apiKey }`；按 preset upsert 行 + 写密钥文件 |
| DELETE | `/api/manage/model/vendors/{id}` | 删库行 + 删密钥文件（既有） |
| PUT | `/api/manage/model/vendors/{id}` | **保留**供测试/兼容；管理 UI **不再调用** |

`VendorBody` 增补：`hasSecret`（boolean）、`builtin`（boolean）。

---

## 6. 后续（本批不做）

- 对话 composer **输入栏旁模型下拉**：读 enabled/listed，切换即 `PUT /enabled`；需与排队中回合互斥文案
- `CustomVendorPresetSource`：用户填 baseUrl+protocol；接入 `CompositeVendorPresetSource`
- 密钥可选轻量混淆（非安全边界）

---

## 7. 允许修改 / 新建文件

**新建**

- `…/manage/VendorPreset.java`
- `…/manage/VendorPresetSource.java`
- `…/manage/BuiltinVendorPresetSource.java`
- `…/manage/VendorSecretStore.java`
- `…/manage/VendorCredentialAccess.java`
- `…/model/ModelPortFactory.java`

**修改**

- `ModelManageController` / `ManageBodies` / `ModelVendorStore`（connect）
- `EnabledModelPortResolver` / `OpenAiCompatibleModelAdapter`（凭据）
- `ManageAdapterConfig`
- `manage/vendors-page.js` / `api.js` / `protocols.js`（可弱化）
- `docs/plans/README.md` 索引行

**禁止**

- 改 Turn / SSE / chat 消息路径
- 本批改 composer 模型选择 UI
- 把密钥写进 SQLite 或 git

---

## 8. 验收

1. GET presets 含 wannian-ai / wannian-openai / deepseek / zhipu  
2. connect 万年AI 写共享密钥后两侧 `hasSecret=true`；Responses 目录仅 Codex，Chat 目录含 Gemini  
3. UI 无 protocol/baseUrl/环境变量名字段（卡片可只读展示协议）  
4. 启用模型路径与改前一致（listed → enabled）  
5. 旧 `DEEPSEEK_API_KEY` / `WANNIAN_AI_API_KEY` 环境变量在无文件时仍可用（回退）

---

## 9. 依赖序

Preset + SecretStore → CredentialAccess → connect API → ModelPortFactory 接线 → UI → 本地 smoke
