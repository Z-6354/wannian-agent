# 记忆注入超限：他者做法对照（0.2.3-D 讨论用）

`status`: 研究摘记 · 2026-09-23  
`question`: Top-N / 字符（或 token）预算打满后，其它 Agent 怎么避免「重要记忆进不了上下文」  
`for`: 对照本仓现行决议（score 排序 → Top-N=12 → CHAR_BUDGET=2000 静默停追加）  
`not`: 不授权改 D 实施；取舍须用户确认后再改决议

---

## 1. 问题本质

预算永远不够装下全部 ACTIVE。业界不是「消灭截断」，而是组合：

1. **少装**（硬条数 / 硬字符或 token）  
2. **先装谁**（排序 / 类别优先级 / 查询相关性）  
3. **装不下的去哪**（库里仍在；可按需 search / 会话工具再拉；或总结压缩历史）  
4. **身份类是否豁免**（预算外强制带）

---

## 2. 对照表

| 系统 | 预算形态 | 超限时 | 关键保护 | 证据 |
|------|----------|--------|----------|------|
| **本仓 D（现行）** | Top-N=12 + 字符 2000 | 按 score 取前 N，再按序拼；超字数 **整行跳过并 break** | 高 importance 靠 score 垫底；**无**身份豁免；**无**按用户话检索 | [k03-d](../plans/k03-d-recall-tombstone-http.md) |
| **Mem0 × OpenClaw recall** | `maxMemories`（默认 15）+ **tokenBudget**（默认 1500，~4 字/token） | **over-fetch** `top_k = maxMemories×2` → 类别序+importance 排序 → 预算装填；非身份条超预算 **continue 跳过该条、继续试下一条** | **identity / configuration 可强制纳入**（可超预算累加）；召回失败不挡主循环 | [mem0 `integrations/openclaw/recall.ts`](https://github.com/mem0ai/mem0/blob/0fbbb2f5/integrations/openclaw/recall.ts) |
| **Mem0 search 本体** | `top_k`（默认 20）+ similarity `threshold` | 向量/混合检索只返回 Top-K；内部常 **oversample** 再裁；超长 **embedding 查询** 尾部截断防 embed 爆 token | 「装不下」靠 **按查询相关** 少装，不是全表注入 | [Mem0 search docs](https://docs.mem0.ai/core-concepts/memory-operations/search)；PR #6981 embed 查询 32k char 护栏 |
| **CrewAI Memory** | `recall(..., limit=)`；任务推送常 **limit=5**，kickoff 可达 20 | 向量 **oversample** 后复合分排序再截断；注入仍是自然语言行，**未见**独立 char/token 二次预算 | 靠 **查询相关 + 衰减 + importance** 让预算内更准；深度 recall 可递归补洞 | [CrewAI memory docs](https://docs.crewai.com/en/concepts/memory)；`recall_oversample_factor` |
| **Open WebUI** | **双预算**：`MEMORIES_USER_CHAR_LIMIT` / `MEMORIES_CONTEXT_CHAR_LIMIT`（各默认 **2000**） | 注入字符硬顶；文档未承诺「半条截断」细节 | **user / context 分桶**，避免一类吃光另一类；另有 path 分层浏览，不全量平铺 | [Open WebUI Memory](https://docs.openwebui.com/features/chat-conversations/memory/) |
| **Letta / MemGPT 系** | 整窗 token 压力（如警示 ~75%，压到 ~30%） | **对话历史** 滑动窗总结 / 递归摘要；外置 archival；摘要超长再 **clip_chars** | 长期事实进 **core memory blocks**（常驻），不与「每次召回的一堆事实」抢同一刀切预算 | [Letta blog: Agent Memory](https://www.letta.com/blog/agent-memory/)；summarizer `clip_chars` |

---

## 3. 可抄手法（相对本仓）

| 手法 | 他者 | 对本仓代价 | 与 R1 关系 |
|------|------|------------|------------|
| A. 静默 Top-N + 字数停（现行） | WebUI 字符顶；多数系统都有硬顶 | 已实现路径 | **已选** |
| B. **按用户本轮话检索** 再 Top-N | Mem0 / CrewAI | 要向量或至少关键词；R1 **禁向量** | 更后；本批不可抄完整版 |
| C. **over-fetch 再预算** | OpenClaw / Mem0 / CrewAI | 无向量时 = listActive 后多取再裁，收益有限 | 轻量可抄（先取 24 再字数填） |
| D. **身份/规则强制带**（可破预算） | OpenClaw `identityAlwaysInclude` | 需 ContentKind/标签；身份高 importance 已部分覆盖 | 可讨论：对 `USER_FACT` 身份类或 importance≥阈值豁免 |
| E. **超预算 skip 单条继续试下一条** | OpenClaw（非身份） | 现行是 **break**，一条过长会堵死后面短高分条 | **小改、高价值** |
| F. **分桶预算**（user vs context） | Open WebUI | 本仓 ContentKind / Scope 可映射 | 可讨论 |
| G. **core 常驻 + 其余按需** | Letta | 需 curated 块 / 工具再查；接近旧 S8 curated 叙事 | 0.2.3 末段或更后 |
| H. **历史总结压窗** | Letta / WebUI compaction | 管的是 **对话** 不是记忆条；正交 | 属 0.2.4+ 上下文工程 |

---

## 4. 对「超过上限怎么办」的他者答案（一句话）

- **没有人**保证「全部记忆每轮都进 prompt」。  
- 主流：**库里保留 + 本轮只注入预算内最优子集**；身份类可强制；其余靠相关检索或下次再召。  
- 真正「避免丢关键事实」靠的是 **排序/豁免/分桶/按需拉**，不是取消上限。

---

## 5. 判定（用户 2026-09-23 · 待确认写入 D）

**最适包：A（保留 push 预算）+ E（超字数 continue）+ 按需 `search_memory`（无向量子串搜）。**

对齐 Open WebUI「注入有顶 + 工具可再搜」，避开 R1 禁向量下的 Mem0/CrewAI 语义检索，也暂不抄 OpenClaw 身份破预算与 Letta core 块。

正文实施增补：[k03-d-search-tool.md](../plans/k03-d-search-tool.md)。
