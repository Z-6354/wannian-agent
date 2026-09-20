# wannian-ui

`wannian-ui` 是与 `wn-server` 同级的独立样式包。它只提供 `/ui/` 下所有 Client 共用的 tokens、themes、components 与 layouts。管理页、对话页和浏览器端 API adapter 在 `wn-server/app` 内，不在本模块。

消费端按固定顺序引入：

```html
<link rel="stylesheet" href="/ui/tokens.css">
<link rel="stylesheet" href="/ui/themes/paper.css">
<link rel="stylesheet" href="/ui/components.css">
<link rel="stylesheet" href="/ui/layouts/console.css">
```

- `tokens.css`：与主题无关的间距、圆角、控件高度和动效；并提供可被主题覆盖的字体栈与标题/标签表达默认值。
- `themes/paper.css`：**默认第一套主题**——暖纸色桌面风（参考平安志：雾色底、宣纸面、墨色主按钮、朱红强调；**雅黑正文 + 楷体标题/品牌 + Consolas 等宽**）。
- `themes/hermes.css`：可选深青黑主题（窄体英文气质）。
- `themes/mono-light.css`、`themes/mono-dark.css`：可选黑白主题。
- `components.css`：无业务语义的按钮、字段、提示条、空状态与无障碍基础样式。
- `layouts/console.css`：控制台壳、导航、卡片、表格、对话框。
- `layouts/chat.css`：对话 Client 可复用的主页面布局。

换主题时只替换中间那一行的 theme 文件，不要改组件类名。

## 模块接口

页面只需要选择一个主题、一个布局，并使用模块提供的语义类名。颜色、控件状态、响应式和视觉变体都在本模块内维护；业务 HTML、页面 JavaScript 和 API adapter 不属于本模块，由 `wn-server/app` 维护。

布局可以不同：管理端使用 `console.css`，对话页使用 `chat.css`。主题可以相同，也可以由未来 Client 自主选择。共享视觉语言不等于共享页面壳。

主题文件除颜色外，还可覆盖：

- `--font-sans` / `--font-display` / `--font-brand` / `--font-mono`
- `--heading-size` / `--heading-tracking` / `--heading-line-height`
- `--label-transform` / `--label-tracking`
- `--button-transform` / `--button-tracking`
- `--brand-transform` / `--brand-tracking` / `--brand-sub-writing-mode`
- `--sidebar-*`（含 `--sidebar-pattern`、`--sidebar-accent`）
- `--theme-glow` / `--theme-noise-opacity`

`paper` 默认字体角色：

| Token | 用途 | 栈 |
| --- | --- | --- |
| `--font-sans` | 正文、按钮、标签、导航 | 微软雅黑 / Noto Sans SC |
| `--font-display` | 页面标题、卡片标题 | 楷体 / Noto Serif SC |
| `--font-brand` | 侧栏品牌名 | 楷体 |
| `--font-mono` | 状态行、代码感文案 | Consolas / Cascadia Mono |

## 参考与边界

默认 `paper` 主题参考平安志的暖纸色、楷体标题、朱红印章品牌与侧栏网格；卡片改用独立纸面阴影而非发丝线拼贴。仍保留多套 theme 文件，便于后续换肤。

不要在本模块加入业务文案、API 或页面状态管理。不要在业务目录重写 button、field、banner、card、table、dialog、status 等已有原语。新增跨页面视觉值时先补 token；新增页面形态时在 `layouts/` 增加独立布局，不把所有样式加载到同一页面。
