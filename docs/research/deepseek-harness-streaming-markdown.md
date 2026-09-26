# DeepSeek Harness：流式 Markdown / 代码预览

`date`: 2026-09-25  
`scope`: 对照开源 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 本地副本 `D:/0HAN/Work/deepseek-harness`  
`why`: 万年 `/chat/` 助手正文目前用 `textContent` 纯文本，不渲染 Markdown；需弄清 DSH 在 **token 流式增长** 下如何做 MD 与代码块预览，再决定本产品接入方式。

---

## 1. 结论（可直接指导设计）

DeepSeek Harness **不是**「每 chunk 整篇 `marked.parse(fullText)`」。他们把问题拆成三层：

| 层 | 做什么 | 关键文件 |
|----|--------|---------|
| **块级增量解析** | 只冻结稳定前缀，每帧只重解析尾部 ≤2 个顶层块（+ 未闭合 fence 的特殊前沿） | `packages/client/ui-primitives/src/markdown/incremental.ts` |
| **流式渲染缓存** | 已冻结块缓存成 React 元素；尾部每帧重渲；`key` = 源码 offset，避免 remount | `…/markdown/MarkdownText.tsx`（`StreamingRenderer`） |
| **代码块增量高亮** | 未闭合/增长中的 fence 走 `CodeBlock streaming`；Shiki 只 tokenize **新增**行；实例用稳定 key | `…/markdown/CodeBlock.tsx` + `highlight.ts`（`StreamingHighlightSession`） |

流式阶段故意 **不做**：KaTeX（公式保持字面量，settled 再渲）、fileMentions / 本地链接触发（词汇未定型，避免冻进缓存）。

聊天接线：`AssistantMarkdown` 对 text block 传 `streaming={streaming}`，labels 用 `useMemo` 稳定身份，避免每 token 丢缓存。

---

## 2. 端到端数据流

```
LLM StreamChunk (text-delta)
        ↓
会话投影累积成 AssistantBlock.text（完整源码字符串，append-only）
        ↓
AssistantMarkdown  streaming=true
        ↓
MarkdownText → StreamingRenderer.render(fullText)
        ↓
IncrementalMarkdownParser.update(text)
   → frozen[]（单调增长）+ tail[]（≤2 不稳定块）
        ↓
render.tsx：fence → <CodeBlock streaming={true} key=sourceOffset />
        ↓
流结束 → streaming=false → parseGfmWithMath 全量 settled 渲（TeX/脚注自愈）
```

要点：**UI 仍持有完整 Markdown 源字符串**；增量只发生在 **解析/渲染工作量** 和 **代码高亮会话**，不是「只传 delta 给 Markdown 引擎」。

---

## 3. 块级增量：`IncrementalMarkdownParser`

来源注释摘要（`incremental.ts`）：

- 全量重解析相对最终长度是 **二次方**；CommonMark 块解析按行，追加文本通常只扰动 **最后一个顶层块**。
- 因此 **冻结除尾部 `UNSTABLE_TAIL_BLOCKS = 2` 以外的块**，每 chunk 只重解析切点后的源码尾。
- 切点用上一冻结块的 **end offset**（保留块间空行，切片与原文一致）。
- **未闭合顶层 fence** 不能整块冻结；另有「已完成行 / 当前半行」第二前沿，避免未封闭代码块被当成定稿。
- 已知偏差：引用式链接/脚注定义若落在冻结边界另一侧，流式期会字面显示，settled 全量解析后自愈。

`PositionedBlock.key` = 全文 start offset → React 跨冻结边界 **reconcile 不 remount**。

---

## 4. 代码预览 / 高亮：`CodeBlock` + Shiki

`CodeBlock` 在 `streaming===true` 时：

1. 维护 **稳定实例**（依赖 Markdown 层的 source-offset key）。
2. `StreamingHighlightSession.updateFrame(code, lang)`：只对 **追加** 部分重新 tokenize；已完成行按组（32 行一组）缓存 DOM。
3. 语法表：启动时仅加载 TS / shell / JSON；其它语言 **懒加载**；未知语言纯文本等宽，不报错。
4. 视口外可推迟高亮（`useViewportHighlighting`）。
5. Settled 后可切到 Shiki HTML 臂，与流式臂样式对齐（测试钉死两臂 parity）。

流式期 **未闭合 fence 也会出代码块 UI**（语言条 + 等宽正文 + 增量高亮），这就是「边生成边预览」的观感来源——不是另开 iframe，而是同一 fence 节点持续增长。

TeX：`render.tsx` 注明 streaming 时 ` ```math ` 与行内公式保持字面量，settled 再 KaTeX，避免半截公式闪错误。

---

## 5. 安全与未信任内容

- 助手 Markdown 视为 **untrusted**：直连 mdast 自研管线，**关闭 raw HTML / 不安全协议**。
- 外链/图片仅安全协议；本地路径图片 settled 后由 resolver 改写同源 API。
- 社区插件 `dsh-better-markdown`（markstream-react）走另一路径：流式与 settled **同一 renderer**，避免完成瞬间整树替换；本仓对照以 **官方 ui-primitives** 为准。

---

## 6. 与万年现状对照

| | DeepSeek Harness | 万年 `/chat/` 现状 |
|--|------------------|-------------------|
| 助手正文 | `MarkdownText` + mdast | `render.js` → `body.textContent = item.text` |
| 流式 | 增量解析 + 冻结块 + 代码增量高亮 | 整段纯文本替换，无 MD |
| 栈 | React + Shiki | 原生 JS / DOM，无 MD 依赖 |
| XSS | 禁 raw HTML | `textContent` 天然安全 |

---

## 7. 若万年要接：推荐路线（研究建议，非施工授权）

**不要**照搬整套 React/mdast；vanilla 可分两档：

### A. 最小可用（优先）

1. 助手节点改为容器；流式/完成都走同一 Markdown 渲染器。
2. 选用 **面向流式** 的轻量库（如 `marked` + 保守 sanitize，或社区 `stream-markdown` / markstream 的 web 组件思路），**每帧仍喂完整累积字符串**。
3. 性能：可选「只重渲最后一个未闭合 fence / 最后一段」的简化冻结（不必一次上 Shiki）。
4. 代码块：`<pre><code>` + 语言 class；流式期可先 **不高亮**，settled 再 `highlight.js` / Shiki（避免未闭合 fence 抖动）。
5. **禁止** `innerHTML` 直插未消毒 HTML；链接 `rel=noopener`、仅 `http(s)`。

### B. 对齐 DSH 观感（后续）

1. 块级冻结（尾 2 块不稳定）+ 稳定 key。
2. fence 增长时增量高亮会话。
3. 流式关闭数学；完成后一次性 KaTeX（若需要）。

**明确不做（本阶段）：** 为 Markdown 引入 React 运行时；流式期启用未消毒 HTML；每 token 全量重高亮整篇长回复。

---

## 8. 证据路径

| 主题 | 路径 |
|------|------|
| 流式 Markdown 入口 | `…/ui-primitives/src/markdown/MarkdownText.tsx` |
| 增量解析 | `…/ui-primitives/src/markdown/incremental.ts` |
| 渲染 / fence | `…/ui-primitives/src/markdown/render.tsx` |
| 代码块流式高亮 | `…/ui-primitives/src/markdown/CodeBlock.tsx`, `highlight.ts` |
| 聊天接线 | `…/ui-chat/src/client/chat/AssistantMarkdown.tsx` |
| LLM chunk 协议（非 MD） | [LLM Streaming 文档](https://deepseek-harness.github.io/deepseek-harness/en/reference/subsystems/llm-streaming) |
| 社区增强插件 | [zerob13/dsh-better-markdown](https://github.com/zerob13/dsh-better-markdown) |

---

## 9. 与本产品下一步

- 本文档 = **检索结论**。
- **施工单（已交付）**：[k04-m-streaming-markdown-implementation.md](../plans/archive/0.2.4/k04-m-streaming-markdown-implementation.md)；M 收口 [REVIEW](../plans/archive/0.2.4/k04-m-draft/REVIEW.md)；版本 F [REVIEW](../plans/archive/0.2.4/k04-f-draft/REVIEW.md)。
