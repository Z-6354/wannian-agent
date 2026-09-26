# 0.2.4-M · MANIFEST

`status`: **已交付（随 0.2.4-F）** — 2026-09-25  
`plan`: [k04-m-streaming-markdown-implementation.md](../k04-m-streaming-markdown-implementation.md)  
`cache-bust`: `?v=20260925m2`

## Style

| # | 生产路径 | 审过 |
|---|----------|------|
| S1 | `wannian-ui/.../layouts/chat.css`（MD 排版 + `md-tok-*`） | [x] |
| S2 | `wannian-ui/.../tokens.css`（`--md-tok-*`） | [x] |
| S3 | `themes/deepseek-dark.css` · `themes/mono-dark.css`（token 覆盖） | [x] |

## Logic

| # | 生产路径 | 审过 |
|---|----------|------|
| L1–L5 | `chat/markdown/{parse,incremental,code-block,render-blocks,markdown-text}.js` | [x] |
| L6–L7 | `chat/render.js` · `chat/app.js` | [x] |
| M2-1 | `chat/markdown/highlight.js` | [x] |
| M2-2 | `chat/markdown/code-block.js`（接 StreamingHighlightSession） | [x] |

草稿目录保留对照；生产为权威。
