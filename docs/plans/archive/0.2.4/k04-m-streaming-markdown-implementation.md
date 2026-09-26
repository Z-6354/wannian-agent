# 0.2.4-M · 流式 Markdown / 代码预览（对齐 DeepSeek Harness 逻辑）

`status`: **已交付 · 2026-09-25**（M1+M2 入仓；F 真人+窄测通过）  
`upstream`: [研究对照](../../../research/deepseek-harness-streaming-markdown.md) · [D 施工单](./k04-d-chat-ui-implementation.md) · [工作流](../../version-stage-workflow.md)  
`alias`: K04-M  
`depends`: **0.2.4-D/E** 已入仓（transcript 增量 DOM、`reply.delta` 累积完整 `item.text`）  
`blocks`: （已解除）原挡 **0.2.4-F**；F 已交付  
`draft`: [`k04-m-draft/MANIFEST.md`](./k04-m-draft/MANIFEST.md) · [`REVIEW.md`](./k04-m-draft/REVIEW.md)  
`review`: M1+M2 与 F 收口完成；已知未扩见 [F REVIEW](./k04-f-draft/REVIEW.md)

---

## 0. 执行者先读

本阶段把助手气泡从 `textContent` 纯文本，换成 **与 DeepSeek Harness 同构的三层流式 Markdown**：

1. **块级增量解析** — 冻结稳定前缀，每帧只重解析尾部不稳定块（含未闭合 fence 前沿）  
2. **流式渲染缓存** — 已冻结块保留 DOM；尾部重渲；稳定 `data-md-key`（源码 offset）避免整块销毁重建  
3. **代码块预览** — 未闭合/增长中的 fence 即出 `<pre>` 预览；高亮增量（阶段内分档，见 §3）

栈约束：**原生 ES module + DOM**，**禁止引入 React**。解析可自研轻量块切分或嵌入极小 lexer；**禁止**整仓拷贝 DSH TypeScript。

**禁止回退到研究文档 §7 路线 A**（`marked` + 每帧整篇 sanitize/`innerHTML` 作为唯一路径）。本单锁定研究结论中的 **三层增量逻辑**（vanilla 化），不是「最小 marked 整篇替换」。

### 0.1 硬约束：前端逻辑与样式解耦

| 层 | 落点 | 职责 | 禁止 |
|----|------|------|------|
| **逻辑 / DOM** | `wn-server/.../META-INF/resources/chat/`（及 `chat/markdown/`） | 解析、冻结、增量 patch、安全链接策略、语义 class / `data-*` | `element.style.*`（除高亮 token 的必需例外见 §6.3）、`<style>` 注入、内联 `style=""`、把颜色/间距/字体写进 JS 常量当「主题」 |
| **样式** | `wannian-ui/.../ui/layouts/chat.css`（必要时 `tokens.css` / theme） | 排版、代码块 chrome、流式态、主题色、暗色 | 在 CSS 里假定 JS 结构之外的「魔法字符串」；禁止 JS 再读 CSS 变量做业务分支 |
| **主题 token** | `wannian-ui/.../ui/tokens.css` + 现有 theme 文件 | `--md-*` / `--shiki-*`（若做高亮）供 CSS 消费 | 在 chat JS 里 hardcode `#rrggbb` |

**契约边界（执行者必须遵守）：**

1. JS 只产出 **稳定、文档化的 DOM 契约**（见 §5）：标签名、语义 class、`data-streaming` / `data-md-key` / `data-lang` 等。  
2. **观感变更只改 wannian-ui**；逻辑变更只改 chat JS。同一 PR/阶段内允许两侧同改，但**禁止**为修样式去改解析算法，也禁止为修解析在 JS 里贴补丁样式。  
3. 高亮若输出 span 颜色：允许 `span.style.color` **仅**来自 highlighter 主题表，且主题表颜色须映射到 **CSS 变量名**（`var(--shiki-token-*)`）或在 settled 臂用 class；禁止在 JS 写死品牌色。  
4. `index.html` 只链 stylesheet；**不**内嵌 Markdown 相关 `<style>`。  
5. 草稿目录拆分：`…-draft/wn-server/.../chat/` 与 `…-draft/wannian-ui/.../` **分目录**；MANIFEST 分「逻辑文件 / 样式文件」两轨审阅。  
6. **类名只许用 §5 表**；缺类名 → 先改本施工单 §5，再改代码。禁止执行者临时发明 `md-foo`。

用户消息 **保持** `textContent`（本阶段不 MD 化用户气泡）。

### 0.2 接线硬约束（审阅必须项 · 执行者易错）

1. **废除 assistant 的 `textContent` 相等短路**  
   现码 `render.js` 约 201–202 行：`if (body.textContent !== (item.text || "")) body.textContent = …`。MD 化后 `body.textContent` 是渲染后纯文本，**不等于**源 Markdown → 每帧误判。  
   **必须**：assistant 路径**删除**该比较；每帧调用 `mountAssistantMarkdown`；`text`/`streaming` 未变时的短路 **只**在 markdown 模块内用 `lastText` + `lastStreaming`。

2. **正文容器必须是 `div`，禁止用 `p` 包块级**  
   现码 `document.createElement("p")` + `className = "chat-text"`。容器内会有 `h2` / `ul` / `pre`，放进 `<p>` 会被浏览器拆树，冻结 key 全废。  
   **必须**：新建 `document.createElement("div")`，`className = "chat-text chat-md"`；若已存在 `p.chat-text`，`replaceWith(div)` 一次并迁移 `data-unfinished`。用户气泡仍可用 `p.chat-text`。

3. **`StreamingRenderer` 实例跨帧挂在元素上**  
   **必须**：模块级 `WeakMap<Element, StreamingRenderer>`（或等价）；`mountAssistantMarkdown(el, …)` 按 `el` 取/建实例。每帧 `new StreamingRenderer()` **禁止**（冻结缓存失效）。容器随 `li` `remove()` 被 GC 时 WeakMap 条目自然失效。

4. **cache bust 链（现状是 per-import `?v=`）**  
   现网：`app.js` import `/chat/render.js?v=…`；**不是**只改 `index.html`。  
   **必须允许并执行**：`render.js` import `/chat/markdown/markdown-text.js?v=…`；markdown 内相对/绝对 import 各文件带 `?v=`；改 `render.js` 后 **bump `app.js` 里 render 的 `?v=`**。MANIFEST 列齐版本戳。

5. **`data-streaming` 每帧同步**  
   `streaming===true` → 容器设 `data-streaming=""`；`false` → `removeAttribute("data-streaming")`。未闭合 fence 根另设 `data-streaming`；闭合后去掉该 fence 上的属性。

---

## 1. 已核实的代码事实

1. **`chat/render.js`**：`patchItem` 对 assistant 用 `.chat-text` + `body.textContent = item.text`（约 194–203 行）；流式与 settled **同一路径**，无 Markdown；正文用 **`createElement("p")`**。文件头注释写「全部 textContent」——接线后须改为「助手 MD 走 mount；用户/工具仍 textContent」。  
2. **状态层**：`item.text` 已是 **完整累积字符串**（SSE delta 在 state/stream 追加）——与 DSH「每帧喂全文、增量只在解析/渲染」一致，**不必**改 SSE 协议。  
3. **`chat.css`**：全局 `.chat-text { white-space: pre-wrap }`；另有 `.chat-message[data-role="assistant"] .chat-text p` / `p + p` 占位。代码块 / 标题 / 列表 / fence chrome **未**定义完整 MD 排版。  
4. **加载**：`index.html` 链 `/ui/layouts/chat.css`；入口 `type=module` → shell → `app.js` 再 import 各 chat 模块（**cache bust 在各 import 的 `?v=`**）。静态 JS 可由 `start-wn-server.ps1` 源目录挂载。  
5. **安全现状**：`textContent` 天然无 XSS；引入 MD 后须显式禁 raw HTML；**禁止**用 `innerHTML` 拼源码或高亮 HTML。  
6. **研究结论**：见 [deepseek-harness-streaming-markdown.md](../../../research/deepseek-harness-streaming-markdown.md)；本单对齐其 **逻辑**，不照搬 React/mdast/Shiki 全家桶；**不**采用该文 §7 路线 A。  
7. **transcript 键**：`domKey` = `turn:` + turnId；节点 `remove` 时旧 body 销毁 → WeakMap renderer GC。temporary→committed 同 key 原地 patch；非 append 文本由 `generation++` 清缓存。

---

## 2. 不变量（行为 + 解耦）

### 2.1 流式 Markdown（对齐 DSH）

1. **输入**：每帧 `fullText = item.text`（append-only）；非 append（临时块被 committed 替换等）→ **generation++**，丢弃冻结缓存，全量重走。  
2. **冻结**：顶层块除尾部 `UNSTABLE_TAIL_BLOCKS = 2` 外冻结；切点 = 上一冻结块 **end offset**（保留块间空行）。  
3. **未闭合 fence**：不得整块冻结；已完成行可进入稳定预览，当前半行属尾前沿。流式期 **必须** 显示代码块壳（语言条可空），内容随 chunk 增长。  
4. **稳定 key**：每个顶层块 DOM 根带 `data-md-key="{sourceStartOffset}"`；冻结跨越时 **移动/保留节点**，禁止因 key 变 remount 导致滚动跳动。  
5. **流式 vs settled**：`streaming=true`（`item.temporary` 或 `status==="running"`）走增量管线；结束后 **同 renderer 的 settled 全量解析**一次（自愈引用式链接等偏差），**禁止** settled 换另一套库导致闪白/整树替换观感。  
6. **流式禁数学求值**：行内 `$…$` / ` ```math ` 保持字面量或等宽；本阶段 **不做 KaTeX**（可后续单独立项）。  
7. **安全**：助手 MD = untrusted；**不解析 raw HTML**；链接仅 `http:`/`https:`（可选 `mailto:`）；`a[target=_blank]` 须 `rel="noopener noreferrer"`；`href`/`src` 只经白名单设置属性，**禁止**把未消毒字符串写入 `innerHTML`。图片本阶段：仅绝对 `http(s)` URL → `<img class="md-image">`，`alt` 用 textContent 等价安全赋值；本地路径 / 相对路径 **不**渲染为可加载图（保留为段落文本或省略）。  
8. **用户气泡**：仍纯文本。  
9. **工具卡 / 过程面板**：仍 `textContent` / 现有 JSON 展示；**不**对工具输出跑 MD。  
10. **空正文**：`streaming` 且 `text===""` → 空 `.chat-md` + `data-streaming`，不抛错、不留陈旧子树。  
11. **段落内单换行（soft break）**：按 CommonMark **合并为空格**（不插入 `<br>`）。硬换行（行尾两空格或 `\`）本阶段可不实现，当作普通空格/文本。

### 2.2 解耦不变量

1. Markdown 模块 **零** CSS 文本、零 `CSSStyleSheet.insertRule`、零动态 `link` 注入。  
2. 视觉 class 命名空间：`md-*`（正文块）与容器修饰 `.chat-md`——**类名表锁定在本施工单 §5**；CSS 不得依赖未列出的临时 class。  
3. `render.js` 只调用 `mountAssistantMarkdown(el, text, { streaming })`；**不**在 `render.js` 内写标题/列表样式分支；**不**用 `body.textContent` 与 `item.text` 比较。  
4. 主题切换（paper / deepseek / mono…）只改 CSS 变量；Markdown JS **不读** `data-theme` 做分支（高亮主题若需要，用 CSS 变量自动跟随）。

### 2.3 本阶段明确不做（吞进段落或字面量，禁止半吊子实现）

| 特性 | 行为 |
|------|------|
| GFM 表格 | 当普通段落行文本，**不**建 table DOM |
| 任务列表 `- [ ]` | 当普通无序列表项文本（可保留 `[ ]` 字面） |
| 引用式链接 / 脚注 | 流式期可字面；settled 可不解析；**不**单独立项本阶段 |
| 嵌套列表 depth&gt;2 | 更深内容吞进当前项文本或段落；M1 只保证 depth≤2 结构正确 |
| 自动链接 `&lt;https://…&gt;` / 裸 URL | 可不识别；仅显式 `[text](url)` |
| KaTeX / 数学求值 | 字面量 |
| 用户气泡 / 工具输出 MD | 不做 |

---

## 3. 阶段拆分

| 阶段 | 内容 | 测试 |
|------|------|------|
| **M1** | 块级增量解析 + DOM 冻结/尾重渲 + fence 预览（**不高亮**）+ 基础块（§6.2）+ **样式轨**完整 MD/代码块排版 | 普通阶段无测文件 |
| **M2** | 代码块 **增量高亮**（**自研极小 tokenizer**，见 §6.3）+ settled 与流式臂视觉 parity；token 色走 CSS 变量 | 同上 |
| **F（版本末）** | 窄测 + 真人：长回复流式、未闭合 fence、切换会话不串、主题下可读 | **仅 F** |

M1 可独立入仓验收行为；M2 不挡 F 的「能看 MD」门，但 F 清单应区分「有/无高亮」。

**本施工单覆盖 M1+M2 设计**；执行时按工作流先开 `k04-m-draft/`，M1 全部审过入仓后再开 M2 文件集（或同草稿分批 MANIFEST）。

**M2 选型（已锁定，除非用户改口）**：**自研/仓内极小 tokenizer**，语言集仅 `js` | `ts` | `javascript` | `typescript` | `json` | `shell` | `bash` | `sh`；其它语言 → 等宽纯文本，不报错。  
**禁止**：CDN 拉取 Shiki/Highlight.js 全家桶；未经 MANIFEST 的 npm 运行时依赖。若日后改 vendor 单文件，须先改本单 + 许可证 + 体积上限写入 MANIFEST（建议未压缩 &lt;150KB）。

---

## 4. 允许 / 禁止修改

### 4.1 允许（逻辑轨）

| 序 | 路径 | 目的 |
|----|------|------|
| M1-1 | `chat/markdown/incremental.js`（新） | `IncrementalMarkdownParser`：frozen/tail/generation |
| M1-2 | `chat/markdown/parse.js`（新） | 顶层块切分 + 行内子集；禁 HTML |
| M1-3 | `chat/markdown/render-blocks.js`（新） | 轻结构 → DOM；只设 §5 class |
| M1-4 | `chat/markdown/code-block.js`（新） | fence DOM：header/lang/pre/code；复制按钮；`streaming` 时增量改 code 文本 |
| M1-5 | `chat/markdown/markdown-text.js`（新） | `WeakMap` + `StreamingRenderer` + settled；对外 `mountAssistantMarkdown` |
| M1-6 | `chat/render.js` | assistant：`div.chat-text.chat-md` + 删 textContent 短路 + 调 M1-5；更新文件头安全注释；user 不变 |
| M1-7 | `chat/app.js` | **仅** bump `render.js`（及若直接 import markdown 时）的 `?v=` |
| M1-8 | markdown 各文件互相 import 的 `?v=` | 与 M1-7 一并列入 MANIFEST |
| M2-1 | `chat/markdown/highlight.js`（新） | 增量 tokenize 会话；输出 `md-tok-*` class 或 `var(--shiki-*)` |
| M2-2 | `chat/markdown/code-block.js` | 接 streaming highlight session |

### 4.2 允许（样式轨 · 与逻辑分目录）

| 序 | 路径 | 目的 |
|----|------|------|
| S1 | `wannian-ui/.../layouts/chat.css` | `.chat-md`、`.md-*`、`.md-code-*` 排版；覆盖 pre-wrap；流式 `data-streaming` |
| S2 | `wannian-ui/.../tokens.css`（按需） | `--md-code-bg`、`--shiki-*` / `--md-tok-*` 等 |
| S3 | 各 `themes/*.css`（按需、最小） | 主题覆盖 code/链接色；禁止复制整套 MD 规则 |

### 4.3 禁止

- 全部 `wn-server/**/*.java`、migration、manage 页  
- 引入 React / Vue / CDN 整包 marked+DOMPurify「每帧 innerHTML 整篇替换」作为唯一路径  
- 回退研究文档 §7 路线 A  
- 在 `chat/*.js` 写颜色、字号、间距（高亮 token 映射到 CSS 变量除外）  
- 对用户气泡 / 工具输出启用 MD  
- KaTeX / 脚注完整实现 / 本地文件图片 API / GFM 表格 DOM（本阶段）  
- 普通阶段新建 `*Test.java` / 前端测文件  
- assistant 路径用 `body.textContent === item.text`（或类似）做更新门  
- 用 `<p>` 作为 MD 容器  
- 每帧新建 `StreamingRenderer` 而不走 WeakMap  
- 类名不进 §5 的临时发明  

---

## 5. DOM 契约（逻辑 ↔ 样式唯一接口）

助手正文容器（**必须是 `div`**，替代纯文本 `p.chat-text`）：

```html
<div class="chat-text chat-md" data-streaming data-unfinished="failed">
  <!-- 顶层块；key = 源码 start offset -->
  <h2 class="md-heading" data-md-key="12" data-level="2">…</h2>
  <p class="md-paragraph" data-md-key="40">
    …<code class="md-inline-code">…</code>…
    <strong class="md-strong">…</strong>
    <em class="md-em">…</em>
    <a class="md-link" href="https://…" target="_blank" rel="noopener noreferrer">…</a>
  </p>
  <div class="md-code" data-md-key="80" data-lang="js" data-streaming>
    <div class="md-code-header">
      <span class="md-code-lang"></span>
      <button type="button" class="md-code-copy"
        data-label-copy="复制" data-label-copied="已复制">复制</button>
    </div>
    <pre class="md-code-pre"><code class="md-code-body">…</code></pre>
  </div>
  <ul class="md-list" data-md-key="200">
    <li class="md-list-item">…</li>
  </ul>
  <ol class="md-list md-list-ordered" data-md-key="300" start="1">
    <li class="md-list-item">…</li>
  </ol>
  <blockquote class="md-quote" data-md-key="400">…</blockquote>
  <hr class="md-rule" data-md-key="500" />
  <p class="md-paragraph" data-md-key="600">
    <img class="md-image" src="https://…" alt="…" />
  </p>
</div>
```

锁定约定：

| 属性 / class | 谁写 | 含义 |
|--------------|------|------|
| `.chat-md` | JS | 启用 MD 排版作用域 |
| 容器 `[data-streaming]` | JS 每帧 | 整消息仍在增长 |
| fence `[data-streaming]` | JS | 该 fence 未闭合或内容仍增 |
| `data-md-key` | JS | 源码 start offset；patch 主键 |
| `data-lang` | JS | fence info；可空 |
| `data-level` | JS | ATX 1–6 |
| `data-unfinished` | `render.js` | 沿用现有：`failed` / `cancelled`；与 MD 无关 |
| `.md-heading` | JS | 标签为 `h1`–`h6` 之一 |
| `.md-paragraph` | JS | |
| `.md-list` / `.md-list-ordered` | JS | `ul` 仅 `.md-list`；`ol` 为 `.md-list.md-list-ordered` |
| `.md-list-item` | JS | |
| `.md-quote` / `.md-rule` | JS | |
| `.md-code` + header/lang/pre/body | JS | |
| `.md-code-copy` | JS：委托或 `data-md-copy-bound` 防重复绑 | 样式只做按钮外观 |
| `.md-strong` `.md-em` `.md-inline-code` `.md-link` | JS | |
| `.md-image` | JS（仅白名单 URL） | |
| `.md-caret`（可选） | JS 仅语义节点，**无** inline style | 优先用 CSS `::after`，可不插入 |
| M2：`.md-tok-keyword` `.md-tok-string` `.md-tok-comment` `.md-tok-number` `.md-tok-punct` `.md-tok-property`（`.md-tok-plain` 可无 span） | JS highlight | CSS 用 `--md-tok-*` |

**删除**旧假设：assistant `.chat-text` 的 `white-space: pre-wrap` 对 `.chat-md` 改为由块级元素承担换行；用户气泡保留 pre-wrap。

文案（复制按钮）：默认中文「复制」/「已复制」；`data-label-*` 或 JS 常量；**样式不写文案**。

**复制按钮行为（写死）：**

- 用容器级事件委托，或按钮上设 `data-md-copy-bound` 后不再 `addEventListener`。  
- `navigator.clipboard.writeText` 成功 → 文案切「已复制」，约 1.5s 恢复「复制」。  
- 失败：静默或短时保持「复制」（不 `throw` 到 `render`）。

---

## 6. 分步实施（执行顺序）

### 6.1 总序

1. **S1 骨架 CSS**（占位 class + **必须**写入 §6.5 的 pre-wrap 覆盖）→  
2. **M1-2 parse** → **M1-1 incremental** → **M1-3/4 render+code** → **M1-5 markdown-text（含 WeakMap）** →  
3. **M1-6 接线 render.js**（div 容器、删 textContent 短路）→ **M1-7/8 cache bust** →  
4. **S1 补全排版**（对照真实 DOM）→  
5. **M2** highlight + S2/S3 token →  
6. 文档指针（README / checklist 行为项）

样式与逻辑可并行草稿，但 **接线审阅以 DOM 契约为准**：先合逻辑契约，再调视觉。

### 6.2 解析与增量（对齐 DSH，vanilla 化）

**`parse.js`**

- 输入源字符串 → 顶层块数组 `{ type, start, end, … }`。  
- **必须**支持：ATX 标题、段落、fenced code（`` ``` `` / `~~~`）、无序/有序列表（depth≤2）、blockquote、`---` 分割线、行内 `` `code` `` / `**` / `*` / `[text](url)`；可选 `![alt](url)`（仅 http(s)）。  
- **不**输出 HTML 节点；未知行、表格行、更深嵌套 → 吞进段落文本。  
- 块 `position.start.offset` / `end.offset` 必填（冻结依赖）。  
- soft break：段落内单 `\n` → 空格。

**`incremental.js`**

- `UNSTABLE_TAIL_BLOCKS = 2`。  
- `update(text) → { frozen, tail, generation }`。  
- 非 `text.startsWith(prev)` 或缩短 → `generation++`，清空冻结。  
- 未闭合 fence：参考研究文档「第二前沿」；不得把未闭合 fence 放进 `frozen`。

**`markdown-text.js` · StreamingRenderer**

- 模块级 `const renderers = new WeakMap()`；`mountAssistantMarkdown(el, text, opts)` 取/建。  
- 实例持有 parser + `frozenRoots: Map<key, Element>` + `lastText` + `lastStreaming` + `generation`。  
- 每帧：若 `text === lastText && streaming === lastStreaming` → return；否则更新。  
- 对新冻结块 `renderBlock` 一次并挂到容器；tail 按 key patch（禁止无 key 整容器 `replaceChildren` 清掉冻结节点）。  
- `streaming===false`：清流式缓存，settled 全量 parse+render 一次；去掉容器 `data-streaming`。  
- `generation` 变化：丢弃 `frozenRoots`，子树按新结果重建（仍尽量按 key 复用若 offset 未变）。

### 6.3 代码块

**M1：** `code-block.js` 只更新 `.md-code-body` 的 **textContent**（或按行 span **无颜色 class**）；header 显示 lang；复制见 §5。  
**M2：** `StreamingHighlightSession`：只 tokenize 追加；已完成行分组缓存（建议 32 行一组）；语言不在锁定集 → 纯文本；**不得阻塞首帧**（先 textContent，空闲再升级 token 节点）。颜色：**class `md-tok-*` + CSS 变量**，或 `style.color = 'var(--shiki-token-keyword)'`——**禁止** `'#ff0000'` 字面量。高亮也 **禁止** `innerHTML` 插入 tokenizer 输出字符串；用 `createElement` / `textContent` 建 token 节点。

### 6.4 `render.js` 接线（伪码 · 必须遵守）

```text
assistant body:
  - let body = node.querySelector(":scope > .chat-text")
  - if (!body || body.tagName !== "DIV" || !body.classList.contains("chat-md")):
      create div.chat-text.chat-md; if old p: migrate data-unfinished; replaceWith / after(role|status)
  - streaming = !!(item.temporary || item.status === "running")
  - mountAssistantMarkdown(body, item.text || "", { streaming })
  - 同步 data-unfinished（failed/cancelled）与现有逻辑一致
  - 禁止: if (body.textContent !== item.text) …
  - 禁止: body.textContent = item.text
```

改文件头注释为：助手正文经 `mountAssistantMarkdown`；用户气泡与工具输出仍仅 `textContent`；禁止 `innerHTML` 直插不可信内容。

### 6.5 样式轨要点（S1）

在 `chat.css` 增加独立分区注释 `/* assistant markdown (0.2.4-M) */`：

**必须覆盖（特异性压过全局 `.chat-text`）：**

```css
.chat-message[data-role="assistant"] .chat-text.chat-md {
  white-space: normal;
}
```

- 块间距用 `.md-paragraph` + `.md-*` 相邻选择器；**不再**依赖 `.chat-text p` / `p + p` 作为助手 MD 主路径（可保留旧规则以免历史误伤，但 `.md-paragraph` 须有明确 margin）。  
- `.md-code`：等宽、背景 `var(--md-code-bg, …)`、圆角用现有 `--radius-*`。  
- `.md-code[data-streaming] .md-code-body`：可选尾部闪烁用 CSS `::after`，**优先不**由 JS 插光标节点。  
- 链接色 `var(--link)`；行内 code 背景弱化。  
- 各 theme **只覆盖变量**，不复制整段 MD 选择器（除非某 theme 必须例外，写明原因）。

---

## 7. 草稿与审阅（工作流）

```
docs/plans/archive/0.2.4/k04-m-draft/
  MANIFEST.md          # 分 Logic / Style 两表；审序；?v= 戳列表
  wn-server/app/src/main/resources/META-INF/resources/chat/markdown/…
  wn-server/.../chat/render.js
  wn-server/.../chat/app.js          # 仅 ?v= bump
  wannian-ui/src/main/resources/META-INF/resources/ui/layouts/chat.css
  wannian-ui/.../tokens.css   # 若动
```

- **一次只推一个文件**审阅；逻辑文件通过后再推依赖它的样式补丁（或先推 CSS 占位再推逻辑——MANIFEST 写明）。  
- 禁止未审批量拷入生产路径。  
- 入仓后删除或清空 draft（与其它 K04 阶段一致）。  
- **计划未过开工门前**：禁止向生产 `chat/` / `wannian-ui` 写码。

---

## 8. 验收（本阶段行为 · 无测文件）

### M1

- [ ] 流式回复出现标题/列表/链接/行内 code，而非原始 `##` 纯文本墙。  
- [ ] 助手正文 DOM 为 `div.chat-text.chat-md`（非 `p`）；非法嵌套不出现。  
- [ ] 未闭合 `` ``` `` 即显示代码块壳，内容随 token 增长；闭合后结构不闪烁重建（key 稳定）。  
- [ ] 长回复流式时已滚过内容不明显整段重排（冻结块 DOM 保留；WeakMap 实例跨帧存活）。  
- [ ] 结束后 settled 与流式最终 DOM 语义一致（引用式/脚注本阶段可不支持）。  
- [ ] 恶意内容：`<script>`、`javascript:` 链接不以可执行/可导航形式出现；无 `innerHTML` 注入。  
- [ ] 用户气泡仍纯文本；工具卡不变。  
- [ ] **解耦**：`chat/markdown/**/*.js` 无颜色/字号字面量；观感可仅改 `chat.css` 验证。  
- [ ] 切换会话 / committed 替换临时块后无串台、无陈旧冻结缓存（generation）。  
- [ ] 复制按钮不重复绑定；成功有「已复制」反馈。  
- [ ] `app.js` / markdown import 的 `?v=` 已 bump，硬刷新后加载新逻辑。

### M2

- [ ] 锁定语言集（js/ts/json/shell）settled 有高亮；流式增长不每帧全量重亮整块。  
- [ ] 未知语言等宽纯文本，不报错。  
- [ ] 主题切换后代码色跟随 CSS 变量（抽查 paper / 一暗色 theme）。  
- [ ] 无 CDN / 无 Shiki 全家桶。

### 明确留给 F

- 自动化回归、真人长会话、弱网 SSE 下 MD 正确性。

---

## 9. 禁止的错误修法

1. 每 chunk `innerHTML = marked.parse(fullText)` 无冻结；或回退研究路线 A。  
2. 为「好看」在 JS 里设 `el.style.margin = …`。  
3. 把 Markdown CSS 写进 `chat/index.html` 或 JS 字符串。  
4. 引入 React 只为拷贝 DSH 组件。  
5. 流式启用 KaTeX 导致半截公式报错闪烁。  
6. 扩大范围改 Java / 工具投影 / 用户气泡 MD / GFM 表格。  
7. 样式与逻辑揉进同一「大 render」文件且 class 名随手发明不进 §5。  
8. 保留 `body.textContent !== item.text` 作为 assistant 更新门。  
9. 用 `<p>` 作 MD 容器导致浏览器拆树。  
10. 每帧 `new StreamingRenderer()` 丢掉冻结缓存。

---

## 10. 依赖与授权边界

- **不**改 SSE、Turn、Message schema。  
- **不** commit / push，除非用户另授。  
- M2 **默认自研 tokenizer**；若改 vendor，须 **vendor 进仓**（`chat/markdown/vendor/`）+ 许可证 + MANIFEST 体积/CSP，并先改本单。  
- 与 **0.2.4-F** 关系：M 入仓后 F 清单增加「助手 Markdown + 流式代码预览」真人项。

---

## 11. 执行者交付格式

每阶段收口报告须含：

1. 已入仓文件列表（逻辑 / 样式分列）+ `?v=` 变更说明  
2. DOM 契约若有增补 → **先改本施工单 §5** 再改代码  
3. 手工验收勾选 §8（截图或简短操作步骤）  
4. 未决（仅记录，本阶段不实现）：引用式链接、表格 DOM、KaTeX 是否单独立项  

---

## 12. 开工门

- [x] 用户确认：对齐 DSH 三层逻辑 + **逻辑/样式分轨**（本单 §0.1）  
- [x] 确认阶段序：先 M1 再 M2；插入在 D/E 之后、**F 之前**  
- [x] 确认 M2：**自研极小 tokenizer**（§3 锁定集）；不拉 CDN Shiki  
- [x] 确认本修订稿已合入审阅必须项（§0.2 / §5 / §6.4 / §6.5）  
- [x] 下一步：建 `docs/plans/archive/0.2.4/k04-m-draft/MANIFEST.md`，按 §6.1 开写 draft（**审过再拷生产**）

**测试策略**：普通阶段不写测文件；§8 供阶段代码审与 F 复用。
