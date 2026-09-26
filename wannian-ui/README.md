# wannian-ui

`wannian-ui` 是与 `wn-server` 同级的独立样式包。它只提供 `/ui/` 下所有 Client 共用的 tokens、themes、components 与 layouts。管理页、对话页和浏览器端 API adapter 在 `wn-server/app` 内，不在本模块。

本地调整样式时，从仓库根目录运行 `./scripts/start-wn-server.ps1`。该脚本让 `/ui/` 直接读取本模块的源文件，并关闭静态资源缓存；保存 CSS 后刷新页面即可看到变化，不必重建 jar 或重启服务。Java 代码或页面脚本发生变化时仍需用 `-Rebuild` 重新打包。直接运行 jar 时继续使用包内样式。

消费端按固定顺序引入：

```html
<link rel="stylesheet" href="/ui/tokens.css">
<link rel="stylesheet" href="/ui/themes/paper.css">
<link rel="stylesheet" href="/ui/components.css">
<link rel="stylesheet" href="/ui/layouts/console.css">
```

- `tokens.css`：与主题无关的间距、圆角、控件高度、动效与 elevation；并提供可被主题覆盖的字体栈与标题/标签表达默认值。
- `themes/paper.css`：**默认入口**（页面 HTML 仍链此文件），内容为 DeepSeek Harness 网页端浅色调色板。
- `themes/deepseek.css`：与 `paper.css` 同值的命名副本，便于文档与一键替换对照。
- `themes/deepseek-dark.css`：可选深色主题（同套 DSH 语义色）。
- `themes/hermes.css`：可选深青黑主题（窄体英文气质）。
- `themes/mono-light.css`、`themes/mono-dark.css`：可选黑白主题。
- `components.css`：按钮 / 字段 / checkbox / banner，以及前瞻原语（pill、tag、switch、menu、toast、code-block 等）。
- `layouts/console.css`：控制台壳、卡片、表格、对话框、settings-row。
- `layouts/chat.css`：对话气泡、输入胶囊，以及前瞻的 hero / toolbar / turn-status。

换主题时只替换中间那一行的 theme 文件，不要改组件类名。例如深色：

```html
<link rel="stylesheet" href="/ui/themes/deepseek-dark.css">
```

## 模块接口

页面只需要选择一个主题、一个布局，并使用模块提供的语义类名。颜色、控件状态、响应式和视觉变体都在本模块内维护；业务 HTML、页面 JavaScript 和 API adapter 不属于本模块，由 `wn-server/app` 维护。

布局可以不同：管理端使用 `console.css`，对话页使用 `chat.css`。主题可以相同，也可以由未来 Client 自主选择。共享视觉语言不等于共享页面壳。

### 已在用的类

`button` / `.btn` / `.secondary` / `.ghost` / `.danger`、`.field` / `.field.checkbox`、`.banner`、`.empty-state`、`.muted`、`.stack`、`.card*`、`.table-*`、`.dialog*`、`.nav-*`、`.chat-*`、`.composer`。

### 前瞻原语（可直接挂 class，无需再改样式包）

| 类名 | 用途 | 参考 DSH |
| --- | --- | --- |
| `.pill` / `[aria-pressed=true]` | 可点选胶囊筛选 | Pill |
| `.tag[data-tone]` | 只读胶囊徽章（outline/solid/quiet/success/info/warning/danger） | Tag |
| `.switch` + `.switch-thumb` | 36×20 开关（`aria-checked`） | Switch |
| `.icon-btn` | 圆形图标钮 | icon button |
| `.state-dot[data-state]` | done/warning/error/ongoing/idle | StateDot |
| `.disclosure` / `.disclosure-row` / `.disclosure-body` | 紧凑展开行 | DisclosureRow |
| `.tabs` / `.tab` | 视图切换 | view tabs |
| `.menu` / `.menu-item` / `.menu-label` / `.menu-sep` | 下拉菜单面 | Menu |
| `.tooltip` | 悬浮提示 | Tooltip |
| `.toast` | 顶栏瞬时提示 | Toast |
| `.kbd` | 快捷键徽记 | — |
| `.notice` / `.callout[data-tone]` | 轻提示 / 说明块 | InputBar notice |
| `.skeleton` / `.spinner` / `.pending-dot` / `.progress` / `.meter` | 加载与进度 | — |
| `.code-block` / `.terminal-block` / `.diff-block` / `.read-block` | Agent 输出卡片壳 | Code/Terminal/Diff/Read |
| `.form-grid` / `.row-between` | 表单排版 | — |
| `.settings-row` | 设置行（标题+控件） | settings |
| `.chat-hero` / `.composer-toolbar` / `.chat-turn-status` / `.chat-actions` | 对话空态与过程行 | HeroShell / InputBar |

主题文件除颜色外，还可覆盖：

- `--font-sans` / `--font-display` / `--font-brand` / `--font-mono`
- `--heading-size` / `--heading-tracking` / `--heading-line-height`
- `--label-transform` / `--label-tracking`
- `--button-transform` / `--button-tracking`
- `--brand-transform` / `--brand-tracking` / `--brand-sub-writing-mode` / `--brand-mark-rotate`
- `--sidebar-*`（含 `--sidebar-pattern`、`--sidebar-accent`）
- `--bubble-user` / `--bubble-assistant` / `--link` / `--scrollbar-thumb*`
- `--send` / `--send-hover` / `--send-foreground` / `--interactive-hover` / `--interactive-active`
- `--tooltip-bg` / `--tooltip-ink` / `--toast-bg` / `--toast-ink` / `--menu-bg`
- `--code-bg` / `--code-banner-bg` / `--skeleton`
- `--elevation-stroke-color` / `--elevation-panel` / `--elevation-soft` / `--elevation-prominent`
- `--theme-glow` / `--theme-noise-opacity`

默认（DeepSeek Harness）字体角色：

| Token | 用途 | 栈 |
| --- | --- | --- |
| `--font-sans` | 正文、按钮、标签、导航、标题 | 系统 UI / PingFang / 微软雅黑 |
| `--font-display` | 页面标题、卡片标题 | 同 sans |
| `--font-brand` | 侧栏品牌名 | 同 sans |
| `--font-mono` | 状态行、代码感文案 | SF Mono / JetBrains Mono / Consolas |

## 参考与边界

默认视觉对齐本地 `0HAN/Work/deepseek-harness` 的 `packages/client/ui-theme` 与 `ui-primitives` 目录（浅色 alias、发丝 elevation、浅蓝用户气泡、浅色侧栏）。不复制 DSH 业务组件或商标素材；只映射公开语义 token 与可复用类名。

不要在本模块加入业务文案、API 或页面状态管理。不要在业务目录重写 button、field、banner、card、table、dialog、status 等已有原语。新增跨页面视觉值时先补 token；新增页面形态时在 `layouts/` 增加独立布局，不把所有样式加载到同一页面。
