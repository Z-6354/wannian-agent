# 样例导入实跑记录 2026-09-25

## Step 0 前置
- file: D:\Downloads\和妹妹一起的日子-檐下拭剑听雨.txt
- bytes: 4418343
- sha256: 13E28CCA03EE720FA97CA8336148351950E1B2FA34956F6DEA774FCA98A8F69C
- expected_sha256: 13E28CCA03EE720FA97CA8336148351950E1B2FA34956F6DEA774FCA98A8F69C
- hash_match: True
- personas_count: 1
- personas: {"id":{"value":"yanhuo"},"displayName":"杜小洛","status":"ACTIVE","revision":0,"profile":{"schemaVersion":1,"displayName":"新角色","soul":"杜小洛，保持原有稳定人格。","voice":"读取 data-dir/prompts/VOICE.md。","identity":"杜小洛","sources":[],"evidence":[],"defaultOverlayTraits":null},"sourceId":null,"updatedAt":"1970-01-01T00:00:00Z"}
- overlay_before: {"active":false,"revision":0,"sourcePersonaId":null,"soul":null,"voice":null,"evidenceSummary":null,"createdAt":null}
- manage_models_fail: 响应状态代码未指示成功: 404 ()。

## Step 1 上传
- requestKey: live-duxiaolu-20260925-215932
- http: 202
- elapsed_ms: 605
- body: {"importId":"7d973d67-ed45-4636-88ff-a350e2e67d6b","status":"PENDING","target":"DEFAULT_YANHUO"}

## Step 2 轮询
- elapsed: 00:00:02.1279851
- polls:
  - [00:00] #1 status=FAILED progress=100 err=INVALID_MODEL_OUTPUT scan=384 matched=163 modelCh=0 windows=0 inputChars=0 unmodeled=0 candidate=
- final_status: FAILED
- final_body:
```json
{
  "importId": "7d973d67-ed45-4636-88ff-a350e2e67d6b",
  "status": "FAILED",
  "progress": 100,
  "candidatePersonaId": null,
  "errorCode": "INVALID_MODEL_OUTPUT",
  "errorSummary": "模型重试后仍未返回 JSON 对象（长度=322，左花括号=false，右花括号=false，Markdown围栏=false）",
  "target": "DEFAULT_YANHUO",
  "coverage": {
    "scannedChapters": 384,
    "matchedChapters": 163,
    "modelChapters": 0,
    "modelWindows": 0,
    "inputChars": 0,
    "unmodeledChapters": 0,
    "algorithmVersion": "chapter-stratified-v1",
    "windows": []
  }
}
```

## Step 2 轮询（样例全书）

- importId: `7d973d67-ed45-4636-88ff-a350e2e67d6b`
- 终态: **FAILED**
- errorCode: `INVALID_MODEL_OUTPUT`
- errorSummary: 模型重试后仍未返回 JSON 对象（长度=322，左花括号=false，右花括号=false，Markdown围栏=false）
- coverage:
  - scannedChapters: **384**（本地扫描成功）
  - matchedChapters: **163**（命中「杜小洛」）
  - modelChapters/windows/inputChars: **0**（未产出可用模型批）
- 耗时: created 13:59:33Z → updated 13:59:37Z（约 4s，含扫描+两次模型调用）
- enabled model: `wannian-openai` / `gemini-3.8-flash-high`
- jar scanner: `chapter-stratified-v1`（源码已是 v2，当前进程 jar 仍是 v1）

## Step 3/4

因 Step 2 无 `candidatePersonaId`，**未执行** preview / apply-to-default。

## 对照诊断

### A. 模型探针（简单 JSON）

- `POST /api/manage/model/probe`：成功返回合法 JSON，约 429 字。说明密钥与启用模型可用。

### B. 迷你 TXT（4 章，DEFAULT_YANHUO）

- importId: `cbd93992-5689-4d65-8aba-28f9d5288338`
- 扫描: 4/4 章命中
- 终态: **FAILED** `OUTPUT_BUDGET_EXCEEDED`（单次输出硬顶 1500 字/token）
- 说明: 即便模型返回 JSON，当前导入预算也会在首批掐掉。

### C. 用全书真实窗口探针（约 9k 字片段）

- 能返回带 `{...}` 的 JSON（约 1553 字，带 ```json 围栏）
- 但字段漂移: `quote/start/end` 而非 `excerpt/startOffset/endOffset`；`defaultOverlayTraits` 非约定枚举对象
- 即便解析放宽，也会先撞上 1500 输出预算

## 失败定位结论

| 步骤 | 结果 | 说明 |
|------|------|------|
| 0 前置 | OK | 4,418,343 bytes，SHA256 与计划一致 |
| 1 上传 | OK | HTTP 202，PENDING→异步 |
| 2 抽取 | **FAIL** | 本地扫描 OK；模型批失败 |
| 3 预览 | 跳过 | 无候选草稿 |
| 4 应用 overlay | 跳过 | 依赖 3 |

根因（按优先级）：

1. **导入单次输出预算 ≤1500** 过紧，合法画像 JSON 常超限 → `OUTPUT_BUDGET_EXCEEDED`
2. **模型未严格遵守字段名/枚举 schema**（gemini 自由发挥）→ 即便过预算也会 `MODEL_SCHEMA_INVALID` / 证据核验失败
3. 全书首跑另见 **322 字无花括号** 非 JSON（可能是供应商短拒绝/异常正文）；需在重试时把原始模型正文写入任务摘要才能定论

## 建议下一步（未改代码）

1. 提高 `PersonaImportService` 单次输出预算（例如 4k–6k，总预算仍受 24k 约束）
2. 抽取 prompt 附带最小 JSON 样例；必要时 Jackson 别名兼容 `quote`/`start`
3. `-Rebuild` 后再跑同一 `requestKey` 或新 key 重传样例
4. 失败时把模型原文前 200 字写入 `error_summary`，便于下次一眼定位
