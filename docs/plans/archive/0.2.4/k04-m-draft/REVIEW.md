# 0.2.4-M · REVIEW（已交付）

`date`: 2026-09-25  
`status`: M1+M2 入仓；随 **0.2.4-F** 真人+窄测收口完成。

## 已入仓

### 逻辑 `chat/`
- `markdown/parse.js` · `incremental.js` · `render-blocks.js` · `markdown-text.js`
- `markdown/code-block.js` — fence + WeakMap highlight session
- `markdown/highlight.js` — js/ts/json/shell；32 行分组缓存；`md-tok-*`
- `render.js` — `div.chat-md` + `mountAssistantMarkdown`
- `app.js` — `render.js?v=20260925m2`

### 样式
- `layouts/chat.css` — MD 排版 + token 选择器
- `tokens.css` — `--md-tok-*` 默认（浅色）
- `themes/deepseek-dark.css` · `mono-dark.css` — 暗色 token

## 验收
- 用户真人：基本没问题（2026-09-25）
- F 窄测：见 [k04-f-draft/REVIEW.md](../k04-f-draft/REVIEW.md)

## 未做（后续单项，不挡 0.2.4）
- KaTeX / GFM 表格 / 引用式链接
