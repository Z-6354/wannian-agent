# 0.2.2-R · 主机环境标签扩展（OS / PS family 同构求交）

`status`: **已实施 · R0–R2 代码与窄测通过**（2026-09-22；**可选模式缓存未做 · 不算欠账**）
`version`: **0.2.2 重构补强**（别名 K02-R；不新开小版本号，不挡 0.2.3）  
`authority`: [k02-tools §12.2](./k02-tools.md) · [roadmap](./roadmap.md) · [04-kernel §7](../guide/04-kernel-reference.md)  
`prerequisite`: 0.2.2 阶段 1–4 代码闭环；保留 `ToolVisibilityResolver` 求交语义  

**设计转向（用户 2026-09-22）：** 放弃「同一 tool id 启动换 Adapter」。多版本与 Win/Linux **同一结构**：不同 tool id + `requiredCapabilities`，启动探测点亮主机标签，求交后模型只看见一条。

---

## 0. 两个问题的结论

### 0.1 要不要每回合「生成」可见工具？

| 事 | 频率 | 说明 |
|----|------|------|
| **探测** OS / PS 版本 | **仅进程启动一次** | 冻结进 `HostCapabilitySet`（或等价快照）；升级 PS 须**重启** |
| **求交生成** descriptors | 每回合可做，但很轻 | 只是模式名单 × Catalog × `containsAll`；工具数个位数时可忽略 |
| 模式切换 | 每回合可能变 | `chat` / `work` / `research` 名单不同 → 求交输入变 |

**建议：**

1. **禁止**每回合重新探测 pwsh / OS。  
2. **允许**每回合做求交（现状）；或在 Catalog/Bindings 重建时预计算并缓存 `Map<(RoleId,FacetId), ToolVisibility>`，`assemble` 只读缓存。  
3. `PUT tools` 热更新 → 失效缓存并重算求交（仍**不**重探）。

不值得为「省求交」上复杂框架；值得保证的是 **探测只启动一次**。

### 0.2 同 id 绑实现 vs 多 id + 能力标签？

**已定：多 id + 能力标签**（与 `os.windows` / `os.linux` 同构）。

```text
启动探测
  → HostCapabilitySet = { os.windows, net.http, shell.ps.family7 }  // 例
Catalog 可同时登记
  powershell_resolve_5（要求 os.windows + shell.ps.family5）
  powershell_resolve_7（要求 os.windows + shell.ps.family7）
模式名单可同时包含这两个 id
  → 求交后模型 descriptors 里只剩一条
```

| 方案 | 结论 |
|------|------|
| 同 id、启动换实现（旧稿） | **撤销**；多一套 Binder，和现有求交重复 |
| **多 id + HostCapability** | **采用**；零新抽象，模型仍只感知一个 |

模型在不同机器上看到的**名字**可能是 `_5` 或 `_7`（单机进程内不变）。可接受：与「Linux 上看不见 PS」一样，是环境决定可见集合。

---

## 1. 目标架构（修订）

```text
启动
  LocalHostCapabilityDetector（扩展）
    → OS 标签 + PS family 标签（至多一个 family）+ net.http
    → 冻结 HostCapabilitySet 注入 ContextAssembler

Catalog / 池
  每个变体一个 tool id + 自己的 Adapter + requiredCapabilities
  无「Implementation Binder」缝

每回合（或缓存命中）
  ToolVisibilityResolver：Catalog ∩ 模式 ∩ HostCapability
  → 模型只拿 descriptors
```

### 1.1 真相缝（精简）

| 缝 | 职责 |
|----|------|
| **探测**（app，启动一次） | 点亮 `os.*` / `shell.ps.family5` \| `shell.ps.family7` |
| **池 / Catalog** | 显式登记各 id；PS 两变体两个 id |
| **模式绑定** | 名单可列多个变体 id；求交负责只留一个 |
| **可见集求交** | 已有，不改公式 |

**禁止：** execute 内再选 5/7；热更新时重探；Loop 内 `if Windows`。

---

## 2. 命名与归桶（已定）

### 2.1 工具 id（两变体，不再共用一个对外 id）

| tool id | requiredCapabilities | Adapter |
|---------|----------------------|---------|
| `powershell_resolve_5` | `os.windows` + `shell.ps.family5` | 面向 5.x 引擎的解析/报告实现 |
| `powershell_resolve_7` | `os.windows` + `shell.ps.family7` | 面向 7.x |

- 原单一 id `powershell_resolve`：**迁移删除或仅作管理页兼容别名（默认删除）**；模式 / enabled 改为两个新 id。  
- **不做**「同 id 换实现」；**不做**改名为单独的 `pwsh`（除非另授权）。

### 2.2 版本归桶 → 主机标签（不是小版本工具 id）

| 读到的主版本 | 点亮标签 |
|--------------|----------|
| 5、5.1、5.2、… | `shell.ps.family5` |
| 7、7.1、7.2、… | `shell.ps.family7` |
| 识别不出（有壳但版本串怪） | **默认 `shell.ps.family5`** |
| 其它主版本（如 6） | 本批默认 `shell.ps.family5` |
| 7 与 5 皆可用 | **只点亮 `shell.ps.family7`**（优先 7） |
| 完全无可用引擎 | **不点亮**任何 `shell.ps.family*` → 两变体都不可见 |

工具 id **不**写成 `powershell_resolve_5.1`；小版本只影响归桶标签。

### 2.3 跨平台工具

`current_time` / `calculate`：无 OS 要求。  
`http_read`：`net.http`。  
未来 Linux 专用：标 `os.linux`；Windows 主机无该标签 → 模型不可见（现状结构）。

### 2.4 模式 / enabled

烟火 `work`（及需要处）名单**同时包含** `_5` 与 `_7`；靠求交只露一个。  
`enabled` 默认两者都开；用户可关掉其中一个（则即使主机匹配也可能不可见）。

---

## 3. 可见集缓存（可选 · **未做**）

每回合求交成本很低（工具数个位数），**当前不实施**。仅当可见集生成变贵或延迟敏感时再开。

```text
启动或 tools 热重建后：
  for 每个已绑定 (role, facet):
    cache[(role,facet)] = resolver.resolve(role, facet, frozenHost)

assemble:
  读 cache；若未命中则 resolve 一次并写入
```

**若实施：** 探测计数仍为 1 / 进程；热改 enabled 后缓存刷新且探测计数不增加。**不算 0.2.2 欠账。**

---

## 4. 分阶段实施

### R0 · 能力常量与探测扩展

1. `HostCapabilities` 增加 `SHELL_PS_FAMILY5` / `SHELL_PS_FAMILY7`（字符串定死）。  
2. 扩展 `LocalHostCapabilityDetector`（或旁路 Probe）：Windows 上归桶点亮至多一个 family；逻辑抽自现 `PowerShellResolveToolAdapter` 探测代码。  
3. 单测：归桶表 §2.2；探测只调一次。

### R1 · 拆成两个 tool id

1. 池内登记 `powershell_resolve_5` / `_7`，删除（或停止默认启用）旧 `powershell_resolve`。  
2. 更新 `YanhuoToolBindings`、默认 `enabled`、管理页文案。  
3. Adapter：两套或一带 `EngineFamily` 构造参数；**execute 不再选引擎**。  
4. 迁移：旧 `wannian.json` 若含 `powershell_resolve` → 启动时改写为两个新 id 或文档说明需手改。

### R2 · 求交回归 + 可选缓存

1. 假主机：family5 → 只见 `_5`；family7 → 只见 `_7`；Linux → 皆不见。  
2. （可选）模式级可见集缓存。  
3. 文档：更新 k02 §3.4 / §12.2；清单若需补一句。

---

## 5. 异常边界

| 场景 | 预期 |
|------|------|
| 无 PS 引擎 | 无 family 标签；两 id 均不可见 |
| 有壳版本不可解析 | family5 |
| 热改 tools | 不重探；重算/刷新可见集 |
| 本机 5→7 | **重启**后标签变 family7 |

---

## 6. 禁止的错误修法

- 再引入「同 id Implementation Binder」与求交双轨。  
- 每回合探测 pwsh。  
- 为 5.1/5.2 各注册一个工具 id。  
- 模式只写一个变体 id 却期望「自动换成另一个版本号名字」。  
- 开放扫包、任意 PS 执行。

---

## 7. 验收断言

```text
[x] OS / PS family 均经 HostCapability 求交；模型单机只见一个 PS resolve id
[x] 探测仅启动一次（LocalHostCapabilityDetector → PowerShellFamilyProbe）；热更新 tools 不重探
[x] 5.x→family5→可见 _5；7.x→_7；皆有→_7；无引擎→皆不可见；怪版本→默认 _5（归桶单测 + 可见集矩阵）
[x] Linux 不可见任一 powershell_resolve_*
[x] 现有工具窄测回归；模式名单含两变体
[ ] （若做缓存）facet 切换结果正确；重建后缓存失效 — 本批未做，求交仍每回合轻量执行
```

**迁移：** `wannian.json` 中旧 id `powershell_resolve` 在 `ToolSettings.readTools` 时展开为 `_5` + `_7`。

---

## 8. 允许 / 禁止改动文件

**允许：** `HostCapabilities`、`LocalHostCapabilityDetector`、`BuiltinToolPool` / Registrar、PS Adapter 拆分、`YanhuoToolBindings`、`ToolSettings` 默认值与迁移、相关测试、k02 文档。  
**禁止未扩权：** Loop 决策、Turn 提交、Memory/Outbox、kernel 引入 Jackson。

---

## 9. 排期与授权

未获「按本计划实施」前只改文档。本修订**简化**原 id/实现分离方案，优先于 0.2.3 工具增强落地更合适，仍不挡 Memory 文档开工。

## 10. 执行者交付

1. 探测单测 + 可见集假主机矩阵。  
2. 启动日志或测试输出：本机 `HostCapabilitySet` + 可见 PS id。  
3. 写明旧 `powershell_resolve` 迁移方式。
