# 08 · 管理页与模型适配器（历史记录）

> **结构说明（2026-09-20）**：管理页曾放在 `wannian-ui` 或同级 `wn-web`。现行边界是页面与浏览器端 API adapter 在 `wn-server/app`，`wannian-ui` 只保留 `/ui/` 共享视觉。本文其余内容仅作为历史接口与验收记录。

`status`: **历史 H4 已交付** — 管理页与适配器批次记录。下一步是 **v0.2** 的 [K01](../01-checklist.md)，见 [施工单](../../plans/k01-agent-loop.md) / [路线图](../../plans/roadmap.md)。  
`plan-revised`: **2026-09-20** — W 与 B1–B3 已实施；`/chat/` 直接回答已计入 v0.1。本文其余为历史接口与验收记录。

## 1. 排期决定（交付时）

| 批次（旧称） | 现行 | 说明 |
|---|---|---|
| K03-W · Web 管理页 | 历史 H4 | 已交付 |
| K03-A · 错误码与日志 | **K01-A** | 可与 Loop 交错 |
| K03-B · 供应商/模型适配器 | 历史 H4 | 已交付 |
| K03-C · Agent Loop | **K01-B** | v0.2 |
| K03-D · TurnEngine | **K01-C** | 在 B 之后 |
| K03-E · live 与 execute | **K01-D** | 在 C 之后 |

**不做的理解：** 不要把「先做管理页」读成提前做完整现行 **K04**（旧 K06：SSE / 对话内嵌页）。K03-W 只覆盖**模型供应商与启用配置**的管理面；对话页与 SSE 仍按现行 K04。

与 [产品概览](../../product/01-overview.md) 的关系：MVP 曾写「wn-manage 可缺席」。本计划把**最小模型配置管理页**提前进当时的 K03，作为接入真实模型的前置；形态仍是 wn-server 内嵌静态页 + 只调 API，不是独立 wn-manage 工程。关掉管理页后，对话能力仍应可在后续阶段独立演进。

## 2. K03-W · Web 管理页（已交付记录）

### 2.0 参考来源（布局 / 逻辑 / UI）

对照本机与公开项目后，K03-W **模仿其管理壳与模型配置逻辑**，不照搬整站或第二进程。

| 项目 | 路径或出处 | 抄什么 | 不抄什么 |
|---|---|---|---|
| **legacy HANAGENT web-admin** | `legacy/web-admin`：`App.vue` 侧栏；`ModelsHub.vue` 页头+页内 Tab；`VendorPanel` / `ModelImportModal` | **侧栏 + 主区**；「模型与供应商」一页内分 **供应商 / 模型**；供应商卡片 → 表单 → **检索目录 → 多选启用** | 独立 Vite 侧车进程；模型池角色/拖拽排序；自动路由；Boss/Skill/搜索全导航 |
| **OpenClaw Control UI** | `openclaw/ui` Settings；[Model providers](https://docs.openclaw.ai/concepts/model-providers.md) | **Settings 分组导航**；供应商 **卡片**（协议状态、密钥来源只显示 env 名、不回显密钥）；页头进 **Setup 子流程**；探测结果分类（auth/限流/超时） | Lit+Gateway RPC 全家桶；用量/计费；OAuth 市场；完整 Agents/MCP 设置树 |
| **Hermes Desktop** | `hermes-agent/apps/desktop` Settings：`SECTIONS` + `PROVIDER_GROUPS` | **注册表驱动导航**（加一项即多一页）；Providers 分 Accounts / Keys / Custom endpoints；模型选择与供应商配置分离 | Electron/计费/插件贡献导航（本批只预留注册表形状） |
| **Open WebUI**（GitHub） | Admin Settings：Connections vs Models 分路由 | **连接（供应商）与模型目录分栏**；Admin 与个人设置边界清晰（万年 v0.1 单用户，但路由仍分开） | 多租户、Pipeline、全量 Admin 标签 |
| **LibreChat**（GitHub） | Admin / endpoint schema | **schema/注册表驱动表单**（新协议字段少改壳）；自定义 endpoint = baseURL + models.fetch | 独立 Admin 微服务；复杂 modelSpecs 体系 |

综合原则：**壳学 OpenClaw/Hermes（可扩展导航），主流程学 web-admin（供应商→检索→启用），领域分界学 Open WebUI（Connections ≠ Models），表单扩展学 LibreChat（协议字段表驱动）。**

### 2.1 目标与主路径

用户在浏览器完成（W 阶段接桩 API；B 换真实适配器，UI 不变）：

```text
打开 /manage/
→ 侧栏进入「模型与供应商」
→ Tab「供应商」：新增/编辑 vendor（protocol + baseUrl + apiKeyEnv）
→ 在该 vendor 上点「检索模型」→ 只调 listModels(vendorId)
→ Tab「模型」或同页抽屉：展示目录，勾选要启用的模型
→ 「启用」→ enableModel(vendorId, modelId)
→ 页头只读展示当前启用：vendorId / modelId
```

按钮不内置目录逻辑；目录只来自后端方法/API。

### 2.2 信息架构与可扩展壳

嵌入 `wn-server` 静态资源，同源。布局固定为：

```text
┌────────────┬──────────────────────────────────────────────┐
│ 品牌       │ 页头：标题 · 当前启用摘要 · 次要动作           │
│            ├──────────────────────────────────────────────┤
│ 导航注册表 │ 内容区（按 nav id 挂载面板）                    │
│ · 模型*    │                                              │
│ · 系统○    │                                              │
│ · …预留    │                                              │
└────────────┴──────────────────────────────────────────────┘
* K03-W 唯一实装    ○ 占位或只读桩，不拦主路径
```

**导航必须是数据，不是散落 RouterLink：**

```text
MANAGE_NAV = [
  { id: "models",   path: "/manage/",          label: "模型与供应商", group: "agents" },
  { id: "system",   path: "/manage/system/",   label: "系统",         group: "system",  status: "stub" },
  // 日后：auth / backup / tools / tasks … 只追加条目 + 面板模块
]

MANAGE_GROUPS = [
  { id: "agents", label: "智能体" },   // 对齐 OpenClaw「Agents」组里的 model-providers
  { id: "system", label: "系统" },
]
```

扩展规则（实施时写进 `manage/nav.js` 或等价）：

1. 新管理能力 = 新 `nav` 条目 + 一个面板模块 + 可选 API 前缀；**不改壳布局**  
2. 「模型」页内子流程（如日后 Setup 向导）用 **子页归属**：侧栏仍高亮 `models`（学 OpenClaw `model-setup` → `model-providers`）  
3. 不做 Hermes 式插件 contrib，直到真有插件；注册表形状先留好  
4. 对话页（现行 K04）是另一入口；管理壳侧栏可链到「打开对话」，但不把 SSE 做进管理页  

### 2.3 「模型与供应商」页内逻辑（学 web-admin，裁剪）

页内 Tab（W 只启用前两个；第三个占位）：

| Tab | 职责 | 对应后端 |
|---|---|---|
| **供应商** | 卡片网格：协议徽章、baseUrl、密钥来源（仅 env 名）、状态；添加/编辑抽屉或模态 | `upsertVendor` / list vendors |
| **模型** | 当前启用摘要 + 从某供应商检索出的目录表（多选/单选启用） | `listModels` / `enableModel` / `getEnabled` |
| **路由** | `status: stub`，文案「K07+」 | 无 |

供应商卡片主操作（对齐 OpenClaw provider card，首批子集）：

```text
编辑 · 检索模型 · （可选）测试连接（W 可桩；真探测在 B）
```

检索结果区：

```text
表格或可选列表：modelId | displayName | 勾选
主按钮：启用所选（v0.1 默认单一启用；UI 仍用「启用选择」结构，便于日后多角色）
空态：尚未检索 / 供应商未配置
错误态：稳定 code + 安全文案（不贴 HTTP 体）
```

**供应商 ≠ 模型** 在 UI 上强制分开：不得出现「只填一个 model 字符串、无 vendor」的保存入口。

### 2.4 UI 视觉与交互约定

目标观感：接近 **控制台 / Settings**，不是营销落地页。

| 要素 | 约定 |
|---|---|
| 结构 | 左侧窄导航（约 220–260px）+ 主内容；内容最大宽度可读，避免全宽散表格 |
| 层级 | 页头一行标题 + 一行当前启用；其下 Tab；其下卡片/表 |
| 卡片 | 供应商用卡片；模型目录用表或密集列表（目录可能很长） |
| 表单 | 抽屉或模态；字段：id、显示名、protocol 下拉、baseUrl、apiKeyEnv（占位符提示，不回显密钥） |
| 协议下拉 | 由前端 `PROTOCOL_OPTIONS` 注册表驱动（首项 `openai-compatible`）；新协议只加选项 + 后端适配器 |
| 反馈 | 顶栏成功/错误条；危险操作（删除供应商、覆盖启用）二次确认 |
| 主题 | 浅色工作台默认；CSS 变量（`--bg` `--panel` `--border` `--accent` `--danger`）；**不用**紫渐变营销风、大圆角 pill 堆、卡片套卡片 |
| 技术栈 | **内嵌静态**：`app/src/main/resources/static/manage/`（HTML + CSS + 少量 JS 模块）。允许后续升级为同目录 Vite 构建产物，但 **禁止** 再起 legacy 那种独立 web-admin 进程 |
| 无障碍 | 按钮有可见标签；错误与 `aria-live`；键盘可完成主路径 |

### 2.5 API 与页面接线

路径、口令和表结构已经在 §2.8–§2.11 定死。页面只调本机 `/api/manage/model/...`。W 的 `models:list` 返回固定桩目录；K03-B 换成真实 `VendorAdapter.listModels`，路径和 JSON 形状不变。

密钥只存环境变量名。响应和日志不得出现变量的值。

### 2.6 范围

**做**

- 上述壳 +「模型与供应商」实装面板（桩 API）  
- 导航注册表与 CSS 变量主题  
- 最小鉴权壳（可与现行 K04 共用策略）  
- Fake 默认，无公网  

**不做**

- 真实厂商 HTTP / 官方 SDK（K03-B）  
- 对话 SSE / 完整现行 K04 内嵌聊  
- 模型池多角色、拖拽排序、自动路由  
- 独立 wn-manage 工程、OpenClaw 用量面板、LibreChat 独立 Admin 服务  

### 2.7 验收（W）

```text
[ ] /manage/ 侧栏来自 MANAGE_NAV；仅「模型与供应商」为完整主路径
[ ] 完成：建供应商 → 检索 → 选模型 → 启用 → 页头显示 vendorId+modelId（桩数据）
[ ] UI 上供应商与模型分开展示；无「无 vendor 裸 model」保存口
[ ] 未认证不能写入启用配置；响应无 apiKey 明文
[ ] 新增一个 stub 导航项无需改壳布局（注册表可扩展证明）
[ ] 页面不直连厂商；默认测试无公网
```

### 2.8 已定死的选择

下列四项不再二选一。实施时按此执行，发现冲突先停，不要另起一套。

| 项 | 决定 | 理由 |
|---|---|---|
| 持久化 | **SQLite `V005__model_vendor.sql`** | 配置要跟现有库一起备份、重启仍在。不改 V001–V004。不另存一份 YAML/JSON，避免两处真相 |
| API 路径 | **`/api/manage/model/...`** | 与现有 `/api/conversations` 同属对外 API。`/internal/*` 继续只给探针（现有 `/internal/live`），管理配置不塞进去 |
| 页面入口 | **仅 `/manage/`** | 一个 `index.html`。页内用 hash（`#models`、`#system`），加新页只改导航数据，不为每一页加 Spring 转发 |
| 鉴权 | **本机回环不校验**；非回环 `Authorization: Bearer`，口令在数据目录 `manage.properties`（环境变量 `WANNIAN_MANAGE_TOKEN` 可覆盖） | 本地打开管理页不用输口令。外网或局域网访问才要口令。不信任 `X-Forwarded-For` |
| 默认 Tab | 没有任何供应商时打开 **供应商**；已有供应商时打开 **模型** | 空库先导入；配过之后先看到启用状态 |

密钥本身不入库、不进响应、不进日志。库存的是环境变量**名字**（`apiKeyEnv`）。W 的检索桩**不读取**该环境变量的值；真正拿密钥去访问厂商是 K03-B。

未设置环境变量时，启动会在数据目录写入 `manage.properties`（默认 `token=dev-manage`）。本机 `127.0.0.1` / `::1` 访问 `/api/manage/**` 不校验口令。其余来源口令错误返回 `401`，口令为空返回 `503` `MANAGE_UNCONFIGURED`。

### 2.9 允许与禁止修改的文件

**允许新增或修改**

```text
wn-server/app/src/main/resources/db/migration/V005__model_vendor.sql
wn-server/app/src/main/resources/application.yml          # 只加 wannian.manage.token
wn-server/app/src/main/resources/static/manage/           # 整目录新建
wn-server/app/src/main/java/com/wannian/server/app/manage/ # 整包新建
wn-server/app/src/test/java/com/wannian/server/app/manage/
```

**禁止**

- 改 `kernel/`、`api/`、V001–V004、`TurnController`、会话接收语义
- 新建独立前端工程、Vite 侧车、`legacy/web-admin` 里的页面
- 在本批发真实 HTTP 到厂商，或把 `java.net.http` / SDK 引进去
- 在 Controller 里手写新的错误字符串；新 code 只允许出现在下面的 `ManageReason` 一处

### 2.10 数据

`V005__model_vendor.sql` 只建两张表。注释用中文写清「这是配置事实，不是缓存」。

```text
model_vendor
  id            TEXT PRIMARY KEY     -- 用户填写，创建后不可改
  display_name  TEXT NOT NULL
  protocol      TEXT NOT NULL        -- 本批只接受 openai-compatible
  base_url      TEXT NOT NULL        -- 存去掉末尾斜杠后的值
  api_key_env   TEXT NOT NULL        -- 只存变量名
  revision      INTEGER NOT NULL     -- 从 0 起，更新必须 CAS
  created_at    TEXT NOT NULL        -- ISO-8601
  updated_at    TEXT NOT NULL

model_enabled
  singleton     INTEGER PRIMARY KEY CHECK (singleton = 1)
  vendor_id     TEXT NOT NULL REFERENCES model_vendor(id)
  model_id      TEXT NOT NULL
  updated_at    TEXT NOT NULL
```

不变量：

- 已加入列表写入 `model_listed`。当前使用是同一表上的 `enabled` 字段（最多一行）。
- 供应商检索结果只留在进程内存，不写入磁盘。启用只能选已加入列表里的模型。
- `model_id` 必须属于该供应商协议的目录。W 的目录是代码里的固定桩，不落库。
- 删除供应商时，若启用行指向它，**同一事务**删掉启用行。不要留悬空外键，也不要先删启用再失败留一半。
- `id` 规则：`^[a-z][a-z0-9-]{1,31}$`。`displayName` 1–80 个字符，去首尾空白，中间空白保留。
- `baseUrl` 只允许 `http` 或 `https`，禁止用户名密码、查询串、`file:`。
- `apiKeyEnv` 规则：`^[A-Z][A-Z0-9_]{0,63}$`。

桩目录（仅 `openai-compatible`，写在一个 Java 常量里，页面不得再写一份）：

```text
stub-chat       桩对话模型
stub-reasoner   桩推理模型
```

其他 `protocol` 一律拒绝，`code=PROTOCOL_UNSUPPORTED`。检索未知 `vendorId`：`VENDOR_NOT_FOUND`。启用的 `modelId` 不在桩目录：`MODEL_NOT_IN_CATALOG`。

### 2.11 HTTP

JSON 字段用驼峰，与现有 `ReceiveTurnRequest` 一致。响应里的拒绝沿用现有习惯：`code` + `detail`。`detail` 是安全短句，不含 SQL、堆栈、环境变量的值。

所有 `/api/manage/**` 先查口令，再做业务。比对用常量时间比较（`MessageDigest.isEqual` 对 UTF-8 字节），不要用 `String.equals`。错误口令 `401`，`code=UNAUTHENTICATED`。正确口令才进业务。

| 方法与路径 | 作用 | 成功 | 失败 |
|---|---|---|---|
| `GET /api/manage/model/vendors` | 列出供应商。每条含 `id, displayName, protocol, baseUrl, apiKeyEnv, revision` | `200` 数组，可空 | 口令问题见上 |
| `PUT /api/manage/model/vendors/{id}` | 创建或更新。体：`displayName, protocol, baseUrl, apiKeyEnv`。更新另要 `expectedRevision` | 创建 `201`，更新 `200`，体为保存后的记录（revision 已 +1 或新建为 0） | `409 REVISION_CONFLICT`；校验失败 `400 ILLEGAL_ARGUMENT`；协议不对 `400 PROTOCOL_UNSUPPORTED` |
| `DELETE /api/manage/model/vendors/{id}` | 删除。若该供应商正被启用，同事务清掉启用行 | `204` 无体 | `404 VENDOR_NOT_FOUND` |
| `POST /api/manage/model/vendors/{id}/models:list` | 检索目录。无请求体 | `200` `{ "vendorId", "entries": [ { "id", "displayName" } ] }` | `404` / `400 PROTOCOL_UNSUPPORTED` |
| `GET /api/manage/model/enabled` | 当前启用 | 无启用：`200` `{ "enabled": null }`。有：`{ "enabled": { "vendorId", "modelId" } }` | — |
| `PUT /api/manage/model/enabled` | 体：`vendorId, modelId`。替换唯一启用行 | `200` 同样形状 | 供应商不存在 `404`；模型不在目录 `400 MODEL_NOT_IN_CATALOG` |

`PUT` 创建时路径里的 `id` 就是主键，请求体不要再带一个可冲突的 id。更新时 `expectedRevision` 不匹配则整行不变。

`ManageReason` 本批只允许这些常量，字符串与上表 `code` 相同：

```text
MANAGE_UNCONFIGURED
UNAUTHENTICATED
ILLEGAL_ARGUMENT
REVISION_CONFLICT
PROTOCOL_UNSUPPORTED
VENDOR_NOT_FOUND
MODEL_NOT_IN_CATALOG
```

`ILLEGAL_ARGUMENT`、`REVISION_CONFLICT` 与回合接口已有用词相同，但本批**不要**去改回合的 `HttpMapping`。管理接口自己映射状态码。K03-A 再把这份常量和回合里的字符串收成一处；收编之前禁止第三处再写新 code。

### 2.12 页面文件与行为

目录：`wn-server/app/src/main/resources/static/manage/`。

| 文件 | 职责 |
|---|---|
| `index.html` | 骨架：侧栏挂载点、页头、Tab 挂载点、内容挂载点、一条 `aria-live` 提示。不含供应商业务判断 |
| `manage.css` | 只使用变量。见下表。侧栏宽 240px。内容区最大宽度 960px |
| `nav.js` | 导出 `MANAGE_GROUPS`、`MANAGE_NAV`。系统项 `status: "stub"` |
| `protocols.js` | 导出 `PROTOCOL_OPTIONS`，目前一项：`{ id: "openai-compatible", label: "OpenAI 兼容" }` |
| `api.js` | `fetch` 封装。从 `sessionStorage` 读口令，放进 `Authorization`。解析 JSON。不把口令写进 URL |
| `models-panel.js` | 供应商卡片、抽屉表单、检索、启用。只调 `api.js` |
| `system-panel.js` | 一段说明：「系统探针在现行 K06（旧 K08）。本页只证明导航可以加一项。」 |
| `app.js` | 读 hash，高亮导航，挂载对应面板。无供应商时默认 `#vendors` 逻辑在面板内，不在骨架里写死文案 |

视觉变量（写进 `manage.css` 的 `:root`，不要在组件里写死色值）：

```text
--bg: #f4f1ea
--panel: #fffcf7
--ink: #1c1915
--muted: #6b645c
--border: #e4ddd2
--accent: #0f6e56
--danger: #9f2d2d
--sidebar: #1c1915
--sidebar-ink: #f4f1ea
```

字体用系统无衬线（`Segoe UI`, `PingFang SC`, sans-serif）。侧栏深色、主区暖纸色。按钮直角或 4px 圆角。不要渐变背景、不要发光、不要图标字体库。

交互顺序（必须按这个做，方便以后只换 `api.js` 后面的实现）：

1. 打开 `/manage/`。若 `sessionStorage` 没有口令，页头显示输入框「管理口令」和「保存到本次会话」。保存后才请求 API。刷新同一标签页仍在；关掉标签页就没了。不要用 `localStorage`。
2. 口令保存后请求 `GET vendors` 与 `GET enabled`。失败条显示 `code` 与 `detail`。
3. 供应商 Tab：空态文案「还没有供应商」。按钮「添加供应商」打开抽屉。字段顺序：id、显示名、协议、Base URL、密钥变量名。新建可填 id；编辑时 id 只读。保存调用 `PUT`。卡片显示协议、Base URL、变量名，不显示密钥值。操作：编辑、检索模型、删除。删除前 `confirm`，文案说明若正在启用会一并取消。
4. 「检索模型」调用 `models:list`，然后把 hash 改为模型 Tab，并带上 `vendorId`（例如 `#models?vendor=openai-main`，用 `URLSearchParams` 解析，不要自己切字符串出错）。
5. 模型 Tab：页头第二行只读「当前启用：{vendorId} / {modelId}」，没有则「尚未启用」。目录用表格，单选（`radio`），不是多选。主按钮「启用所选」。v0.1 只有一个启用位；表格结构留 `modelId` 列，方便以后加角色，但本批不要加角色列。
6. 启用成功后重新 `GET enabled`，不要只改本地字。
7. 系统导航可点，只渲染 `system-panel.js`。

页面里禁止出现写死的模型 id 列表。目录行只渲染接口返回的 `entries`。

### 2.13 分步实施

一次只做一步。上一步的测试输出贴出后再做下一步。

**W1 · 库与接口，无页面**

1. 加 V005 与 `application.yml` 的 `wannian.manage.token: ${WANNIAN_MANAGE_TOKEN:}`。
2. 写 `ManageReason`、请求/响应 record、`ModelVendorStore`（JDBC，放 `app.manage`，不进 kernel）、`ModelManageController`。
3. 过滤器只拦截 `/api/manage/**`。`/internal/live` 与 `/api/conversations/**` 行为不变。
4. 测试用临时数据目录（照 `ConversationHttpTest` 的 `@TempDir` + `wannian.data-dir`）。用 `TestRestTemplate`。

W1 必须有的断言：

```text
未设置口令 → PUT vendor 503 MANAGE_UNCONFIGURED，vendor 表行数为 0
错误口令 → 401，行数为 0
正确口令创建 → 201，再 GET 能看到，响应无任何看起来像密钥的字段
同 id 用错误 expectedRevision 更新 → 409，display_name 仍是旧值
protocol=anthropic → 400，无插入
baseUrl=file:///tmp → 400
检索不存在的 id → 404
对已保存的 openai-compatible 供应商检索 → 恰好两条桩，id 为 stub-chat 与 stub-reasoner
启用 stub-chat → GET enabled 一致；再启用 stub-reasoner → 仍只有一行
启用 not-a-model → 400，启用行不变
删除正在启用的供应商 → 204，vendor 与 enabled 都没有该 id
/internal/live 在错误管理口令下仍是 200（过滤器没有拦错路径）
```

**W2 · 页面骨架**

1. 放入 2.12 的静态文件。`models-panel.js` 可以先只渲染标题，但 `nav.js` 必须已含模型与系统两项。
2. Spring Boot 默认会把 `classpath:/static/manage/index.html` 映到 `/manage/index.html`。再加一个只做转发的 `ManagePageController`：`GET /manage` 与 `GET /manage/` 返回该 html。不要用这个 Controller 写业务。
3. 测试：`GET /manage/` 返回 200 且正文含 `id="manage-nav"`；`GET /manage/nav.js` 200。不要在测试里启动浏览器。

**W3 · 把面板接到 W1**

1. 按 2.12 的交互接上。
2. 手工验收（实施者本机，口令用一次性测试值，不要写进仓库）：
   - 无口令时看不到供应商数据
   - 填入测试口令后能走完添加 → 检索 → 单选启用 → 页头出现两个 id
   - 刷新后启用仍在（证明走了数据库，不是只存在内存）
   - 点「系统」侧栏高亮变了，主区换了说明，侧栏 DOM 结构没换
3. 把上述手工步骤的结果写进交付说明。自动化测试仍以 W1 的 HTTP 断言为准；不要为了页面去点 Selenium，除非本机已有现成浏览器工具。

### 2.14 禁止的错误修法

- 用前端数组冒充检索结果，接口失败仍显示两条假模型
- 把 API key 放进表单「密钥」输入框并 POST 到服务器
- 为了让页面好测而关掉口令检查
- 启用多个模型，或把启用写成 vendor 表上的一个布尔字段（那样换模型会丢历史供应商）
- 改会话接口来「顺便」返回当前模型
- 复制 legacy `web-admin` 的 Vue 工程进 `wn-server`

### 2.15 验收（W）

```text
[ ] V005 在空库与已有 V004 的库上都能迁过；不改旧 migration 校验和
[ ] W1 断言全部有测试输出，且失败路径没有半行写入
[ ] /manage/ 侧栏数据来自 nav.js；系统项可点且不改骨架结构
[ ] 浏览器主路径：建供应商 → 检索 → 单选启用 → 页头显示 vendorId 与 modelId → 刷新仍在
[ ] 页面上没有「不选供应商、只填模型名」的保存按钮
[ ] 未配置口令或口令错误不能写配置；响应与日志都没有密钥值
[ ] 默认 mvn test 不访问公网；/internal/live 与接收回合旧测试仍过
```

### 2.16 交付时要交回的材料

```text
改过的文件列表
是否碰到禁止目录
执行的 Maven 命令与退出码
W1 每条断言对应的测试名
浏览器主路径做到哪一步；没做的原因
仍未做的 K03-B 事项（应全部未做）
```

## 3. K03-B · 供应商与模型适配器（已交付）

本批不是「单独做一个空 ModelPort」，而是**供应商适配器 + 模型调用适配器**，并与管理页已有接口对接。

### 3.0 子批次（实施记录）

| 子批 | 状态 | 内容 |
|---|---|---|
| B1 | 已交付 | `VendorAdapter` + Fake/OpenAI `listModels`；`wannian.model.mode=fake\|live`；`EnvAccess` |
| B2 | 已交付 | `ModelCatalogCache`（TTL）；list 写入、enable 优先读缓存；vendor 变更失效 |
| B3 | 已交付 | kernel `ModelPort`/`ModelRequest`/`ModelOutcome`；`FakeModelAdapter` / `OpenAiCompatibleModelAdapter` / `TimeoutModelPort`；`EnabledModelPortResolver`；`POST /probe`（仅校验已启用模型，不进 Turn） |

### 3.1 参考（必须遵守的分层）

| 来源 | 可抄 | 不抄 |
|---|---|---|
| **legacy han-agent** | `VendorSettings`（凭证/baseUrl/协议）≠ `PoolEntry`（vendorId + modelId）；`VendorModelCatalog.discover` 挂在供应商侧；启用写在配置，不靠裸 model 字符串 | 完整 pool/CLI UI；`LlmClient` 不带 list 导致平行 catalog |
| **Hermes** | `ProviderProfile.fetch_models()`：默认 `GET …/models`，失败用 curated fallback | 插件市场、复杂 pricing |
| **OpenClaw** | catalog 行含 `provider` + `id` | 整套 UI / compat 图 |
| **wannian 28/34** | kernel 只见 `ModelPort.decide`；协议分支在 app；装饰器在调用链外 | 把 `listModels` 塞进 Loop |

命名（避免 provider/vendor 混用）：

- **protocol**：线格式（如 `openai-compatible`）
- **vendor**：一份已导入的凭证端点
- **model**：目录中的一个 id
- **enabled selection**：`vendorId` + `modelId`，装配出可调用的 `ModelPort`

### 3.2 分层

```text
管理面（K03-W 已有页面）
  → upsertVendor / listModels / enableModel
       → VendorAdapter.listModels(vendor)     // 供应商适配器
       → 写入 EnabledModelSelection
运行面
  → resolveModelPort()
       → ModelCallAdapter（绑定 vendor + modelId）implements ModelPort
```

| 适配器 | 拥有 | 不拥有 |
|---|---|---|
| **VendorAdapter** | baseUrl、鉴权、`listModels`、协议探测、HTTP→稳定 code | 不实现 `decide` |
| **ModelCallAdapter** | 绑定的 `modelId`、chat 请求形状、封闭决策/失败 | 不拉全站目录 |

一句话：**供应商管「连谁、目录有什么」；模型适配器管「用哪个 id 怎么聊」。** 不是一模型一类，而是一协议一调用适配器 + 构造绑定 modelId。

### 3.3 方法（无新 UI 按钮）

```text
upsertVendor(vendorDraft)
listModels(vendorId) → List<ModelCatalogEntry>   // 替换 W 的桩
enableModel(vendorId, modelId)
resolveModelPort() → ModelPort
```

管理页已有的「检索模型」「启用」只改 `ModelVendorStore` 背后的实现，不改 §2.11 的路径和 JSON。`listModels` 在 B 里改为调用 `VendorAdapter`，不再返回写死的 `stub-chat` / `stub-reasoner`。页面不得因此改骨架。

### 3.4 B 的交付与禁止

**做：** Fake + OpenAI 兼容的 VendorAdapter / ModelCallAdapter；JDK HttpClient；装饰器骨架；密钥仅环境变量引用；默认 `wannian.model.mode=fake`。

**不做：** 官方 SDK；Loop/TurnEngine；把 listModels 放进 kernel `ModelPort`；配置文件明文 key。

### 3.5 B 验收

```text
[x] FakeVendor.listModels 稳定；无网络
[x] 兼容 VendorAdapter 对假 HTTP 服务器解析 /models
[x] enable 后 resolve 的 ModelPort.decide 可用；换 modelId 不换 VendorAdapter 类
[x] 管理页主路径改为真实适配器后仍通过
[x] kernel 无厂商 SDK；协议单测打本地桩。公网验收为 PublicVendorLiveTest（需 DEEPSEEK_API_KEY）：listModels + 一次 decide
[x] 日志不含 apiKey
[x] B2：list 后再 enable 不二次打 /models（TTL 内）
[x] B3：fake 下 POST /probe 返回 final；未启用 → MODEL_NOT_ENABLED
```

窄测证据（2026-09-19）：`ModelCatalogCacheTest`、`ModelCatalogCacheHttpTest`、`OpenAiCompatibleModelAdapterTest`、`ModelProbeHttpTest` 及既有 manage/listModels 相关测试共 18 条通过。

## 4. 与后续批次的接口

- 现行 **K01-B**（旧 K03-C）的 `DefaultAgentLoop` 只依赖 `ModelPort`，不依赖 VendorAdapter 或管理页  
- 现行 **K01-C** 认领成功后才调用 Loop；COMMITTING 恢复不重跑模型（29 / 32 已放行契约）  
- 现行 **K04**（旧 K06）对话内嵌页消费 Turn/SSE，不承担供应商配置；配置只在管理页  

## 5. 当前禁止

- 不把 manage `probe` 当成 Turn 业务路径，或在 K01-B 之前把 Loop 接到会话 HTTP  
- 不把本计划理解成授权 commit / push  
- 不提前做 Guardian、远程节点、独立 wn-manage 工程  
