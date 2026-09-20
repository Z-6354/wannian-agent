# 14 · 内核自主更新与防自毁架构讨论稿

`date`: 2026-09-17  
`status`: **brainstorm-draft** — 聚焦自主修改内核时的可用性、恢复与数据安全，不讨论权限审批，不授权当前系统执行自更新。

## 1. 用户目标

万年能够根据用户要求修改自己的实现，包括核心部件；插件体系可以替换大量组件，但候选代码可能无法编译、无法启动、启动后行为错误，甚至破坏配置和数据。系统必须保证失败候选不能带走当前可工作的万年，并能自动回到最后已知可用版本。

目标不是“降低出错概率”，而是：

> 允许候选版本任意失败，但失败必须被隔离、检测并回退；当前稳定版本、用户数据和恢复入口始终可用。

## 2. 对 DeepSeek Harness 的核验

DeepSeek Harness 的核心经验值得借鉴：

- 每个部分都是插件，包括模型适配器、工具注册表、Session 日志和 Agent Loop；运行时由 profile、bundle 和 patch 组合。
- Cordis 插件注册是可逆 effect，插件卸载时撤销；服务通过稳定 key 和依赖注入连接，因此实现可替换。
- 动态 Cordis 工具使用稳定 plugin id、不可变 package id、run/update/stop，并保留版本指针以支持 restart/rollback。
- SessionEvent 是 append-only 持久真源，运行时 Agent 可以基于持久 Session 恢复。

来源：

- [DeepSeek Harness architecture](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/03-architecture.md)
- [Core subsystem](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/core.md)
- [Cordis tutorial](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/cordis-tutorial/index.md)
- [Dynamic Cordis tools](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/tool-catalog.md)

但官方仓库的实际问题也说明“可逆插件”并不足以保证系统不自毁：社区已报告 profile 配置非原子写入导致并发启动读到空文件，以及失败加载/回滚可能把空插件树写回配置；另有不兼容可选插件阻止应用外壳启动的恢复性讨论。

相关证据：

- [非原子 profile 写入问题](https://github.com/deepseek-ai/deepseek-harness/discussions/441)
- [失败回滚与配置 write-back 问题](https://github.com/deepseek-ai/deepseek-harness/discussions/3509)
- [不兼容插件导致启动失败的恢复提案](https://github.com/deepseek-ai/deepseek-harness/discussions/4175)

结论：万年可以借鉴其插件契约和不可变 package，但不能把“插件可卸载”等同于“内核更新一定可恢复”。

## 3. 必须存在内核之外的最小监督器

如果当前运行的内核既负责修改自己、启动自己、判断自己是否正常，又负责回滚自己，那么它一旦无法启动，就没有代码可以执行回滚。

因此必须有一个不随普通自更新一起替换的极小外部组件：

```text
wn-guardian（稳定监督器）
├─ 版本目录和 current/previous 指针
├─ 启动候选内核
├─ 健康检查与启动超时
├─ 原子切换
├─ 崩溃循环检测
├─ 自动回滚
└─ 最小恢复入口

wn-kernel（可自主更新）
├─ Agent Loop
├─ 模型与工具
├─ Memory / Relationship
├─ Turn / Streaming
├─ Plugin Runtime
└─ 自我迭代 Agent
```

`wn-guardian` 不是审批器，而是类似 bootloader/watchdog 的生命保障层。万年可以重写整个 kernel，但不能在同一次普通内核更新中重写 guardian。若连 guardian 也允许无保护自改，就无法对“永远能恢复”作出结构性保证。

## 4. 不允许原地修改

每次自我迭代必须产生一个新的不可变版本目录：

```text
versions/
  <content-hash-or-version>/
    kernel artifact
    plugin set
    manifest
    schema compatibility
    test evidence

slots/
  current  -> version A
  previous -> version Z
  candidate -> version B
```

禁止候选直接覆盖正在运行的 jar、插件文件、配置或当前指针。构建失败只会留下一个失败候选，不会改变 A。

版本发布使用：临时目录写完 → fsync/校验 hash → 原子 rename → 原子更新 candidate 指针。禁止 truncate 后原地重写关键配置。

## 5. 双运行时与影子启动

不能在当前进程内先卸载核心插件、再尝试加载新插件；一旦新实现破坏 Agent Loop、HTTP、配置或生命周期，当前进程也可能失去恢复能力。

推荐流程：

```text
稳定 A 继续服务用户
→ A 生成并构建候选 B
→ guardian 以新进程、独立端口启动 B
→ B 读取只读生产状态或隔离的数据副本
→ 执行启动、协议、流式、恢复和语义 canary
→ B 达标后进入短暂 shadow/canary
→ guardian 原子切换入口到 B
→ A 保持待命一段时间
→ B 持续健康后才将 B 标记为 current
→ B 异常则入口切回 A，并终止 B
```

用户不应在 B 尚未证明可用时失去 A。

## 6. 两阶段激活

### Phase 1 · Prepared

- 代码生成完成；
- 构建和依赖解析成功；
- manifest、协议和 schema 兼容性可解析；
- artifact 内容寻址且不可变。

### Phase 2 · Serving

- 新进程成功启动；
- readiness 检查通过；
- 能恢复测试 Session；
- 能完成一次真实的流式 Turn；
- 能持久化 Message 并重启恢复；
- 关键插件拓扑完整；
- 在观察窗口内没有崩溃或持续错误。

只有 Phase 2 通过，guardian 才更新 `current`。进程“端口打开”不等于 Agent 可用。

## 7. 健康判定必须覆盖语义

至少分三层：

1. **Liveness**：进程存在、事件循环/线程未死锁。
2. **Readiness**：HTTP/SSE、数据库、模型路由、插件拓扑可以接单。
3. **Semantic canary**：执行固定测试场景，验证 Turn 状态机、流式终态、消息持久化、重启恢复和关键领域不变量。

候选版本不能自己单方面报告“我正常”。guardian 通过稳定协议发起探针并核对输出、退出码、超时和持久化副作用。

## 8. 数据库是最大风险

程序回滚容易，数据库一旦被新版本做破坏性迁移，旧版本可能再也无法启动。

规则：

- 候选验证只能使用快照/副本或只读生产库。
- 未正式切换前禁止生产 schema mutation。
- 自更新迁移默认只允许 expand：新增表、列、索引，不删除、不重命名、不改变旧字段语义。
- 新旧版本在回退窗口内必须都能读取当前 schema。
- contract/delete 类迁移只能在旧版本回退窗口结束后的独立阶段执行。
- 数据格式和事件协议带版本；未知版本必须失败得可诊断，不能静默误读。
- 切换前创建可验证备份，并实际演练恢复，而不是只生成备份文件。

推荐把核心事实保存在追加式事件或兼容表中，把可重建 projection 与缓存视为可丢弃派生状态。

## 9. 插件更新与内核更新分轨

### 插件级更新

满足以下条件时可以在候选运行时中热装载：

- 依赖声明完整；
- effect 可完整 unwind；
- 状态不泄漏到全局单例；
- 插件失败不会带走 Agent Loop、Session、HTTP 和 guardian 通道。

### 内核级更新

以下部件变化必须走新进程 A/B 切换，不能只依赖进程内热重载：

- Agent Loop；
- Plugin Loader/Cordis 等价层；
- Session/Turn 持久化；
- 配置解析；
- HTTP/SSE 入口；
- 数据库访问和迁移；
- 自我更新协调器本身。

“一切皆插件”是模块边界，不代表所有插件都适合在当前生产进程内替换。

## 10. 更新状态机

```text
DRAFT
→ BUILDING
→ BUILD_FAILED
  或 BUILT
→ BOOT_TESTING
→ BOOT_FAILED
  或 READY
→ CANARY
→ ROLLED_BACK
  或 PROMOTED
→ STABLE
```

每个状态持久化在 guardian 自己的小型状态库中，而不是候选 kernel 的数据库中。机器重启后 guardian 根据状态继续：未完成切换默认回到 last-known-good，不猜测候选已成功。

## 11. 防崩溃循环

- 每个版本记录连续启动失败次数和运行时长。
- 候选在稳定窗口内多次退出，自动隔离为 bad version。
- guardian 不再自动启动被标记 bad 的相同 artifact。
- current 启动失败时回退 previous；previous 也失败时进入内置 recovery mode。
- recovery mode 至少能查看版本、日志、切换 last-known-good、导出数据和禁用候选；它不依赖 wn-kernel 插件树。

## 12. 根据用户要求迭代代码

用户需求进入一个持久化 SelfUpdateRequest：

```text
用户目标
→ 当前稳定内核生成规格和验收场景
→ 在独立工作区修改源码
→ 构建候选 artifact
→ 执行回归与新需求验收
→ guardian 影子启动和语义 canary
→ A/B 切换
→ 观察真实运行
→ 稳定或自动回退
```

即便生成代码的 Agent、编译器、测试或新内核全部崩溃，稳定 A 和 guardian 仍在。因此“自主更新”允许失败，但失败不会等于系统死亡。

## 13. 无法消除的边界

这套结构能防住绝大多数软件级自毁，但不能声称绝对不会坏：

- Agent 同时破坏所有磁盘版本或用户数据；
- 硬件/文件系统损坏；
- guardian 自身缺陷；
- 新旧版本共用外部服务并产生不可逆副作用；
- 错误候选通过了不充分的 canary，随后产生语义损坏。

因此可靠性目标应写成明确故障模型和恢复时间，而不是“保证永不崩”。结构上最重要的是让生成变更的系统与决定存活版本的系统不共命运。

## 14. 推荐定案

> 万年允许自主修改整个 wn-kernel，但永不原地覆盖当前版本。一个独立、极小、稳定的 wn-guardian 管理不可变版本、影子启动、语义健康检查、A/B 原子切换、崩溃循环隔离和自动回退。插件变化可组合，但核心链路变化必须通过新进程验证。数据库迁移采用 expand/contract 和回退窗口，候选不得直接对生产数据做破坏性修改。

这是可靠自我更新的最低结构，不依赖人工审批是否存在。
