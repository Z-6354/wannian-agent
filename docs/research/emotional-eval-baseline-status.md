# 烟火情感评测 v1：基线状态记录

记录日期：2026-09-25（Asia/Shanghai）  
评测样本版本：`emotional-eval-v1.json` / v1.0 / seed `20260925`  
工作树 HEAD：`e6955b7500eeaa14c09d68bacf844c1428f7577b`（工作树存在大量未提交工作）

## 结论

评测协议和原创合成样本已建立；本次没有取得任何真实模型输出，因此真实模型质量基线仍缺失。没有执行候选与基线 A/B，也没有评分结果或改进结论。

根因是原有效提示词快照缺失：`SOUL.md`、`VOICE.md`、`IDENTITY.md` 在当前工作树均为未跟踪文件，按协作指示不把它们当作改动前版本。只检查了 `data/` 根目录项名称，发现 `manage.properties` 与 `wannian.db`；没有读取这两个文件或任何私人会话/数据库内容，也未确认其中是否有可用的脱敏提示词快照。当前没有可证明为部署前有效的快照来源及其哈希，所以不能运行有意义的现状基线。若后续取得获准的旧快照，应先核对来源、有效时间与文件 SHA-256，再用同一模型、端点、采样参数运行。

## 能力与运行阻碍

- 现有真实模型通路：`DefaultAgentLoopLiveTest` 使用真实 `OpenAiCompatibleModelAdapter` 连接 `https://api.deepseek.com/v1`，模型 ID 为 `deepseek-flash`；该用例只测非空回复与一次调用，不测自然度。另有 `PublicVendorLiveTest` 做目录与单轮出站。
- 环境检查只记录存在性：`DEEPSEEK_API_KEY`、`ZHIPU_API_KEY` 已设置；`WANNIAN_MODEL_API_KEY`、`WANNIAN_AI_API_KEY` 未设置。没有读取或输出任何凭据值。凭据存在本身不能说明原模型绑定、权限、额度或授权的评测端点。
- Java 21 可用，但当前环境未发现 `mvn`、Maven Wrapper；`wn-server` 有 `pom.xml`。故现有 Maven live 测试不能启动。没有绕过完整 turn 用另一服务调用来伪装项目真实链路。
- 原始提示词快照哈希、模型请求配置哈希、输出/延迟/token/费用：均未取得。脚本在每次真实 API 请求时记录 system 文本 SHA-256、模型 ID、请求采样参数、延迟及 API 返回的 token 数；密钥不写入结果。

## 可复现评测资产

- [固定样本与类别](./emotional-eval-v1.json)：28 条普通情境 + 2 条紧急情境组成 30 条固定集；8 条长上下文包含在固定集内；另有 8 条紧急变体单列。全部是原创合成输入，无私人数据。
- [评分量表和数据许可核对](./emotional-eval-rubric-v1.md)：双人盲评、五项 0–2 量表、缺陷标签及外部数据集访问/许可边界。
- [采样与评分汇总脚本](../../scripts/emotional_eval.py)：只提供 OpenAI 兼容 chat/completions 直连对照，必须通过 `--system-file` 显式指定提示词快照。此直连结果不等于应用完整 turn 基线。

当前公开数据集抽样为 0：EmpatheticDialogues 数据许可尚未核实到数据级；ESConv 仓库声明仅供学术研究；LongMemEval 虽为 MIT 仓库，仍须核对数据文件/派生样本的许可与来源；LoCoMo 数据许可适用范围待确认。本版本不复制语料、不采集热线对话。协议记录了核验入口与替代策略。

## 后续命令

取得获准、可确认有效的 system 快照后，先在仓库外的评测目录执行，避免把模型输出或隐私内容提交到仓库：

```powershell
$env:DEEPSEEK_API_KEY = '<由本机安全方式提供>'
python scripts/emotional_eval.py sample `
  --system-file 'D:\approved-snapshot\system.txt' `
  --output 'D:\private-eval\baseline.jsonl' `
  --repetitions 3
```

单样本冒烟运行可通过重复添加 `--case S01` 和 `--case S12` 限定；这只验证连通性，不足以称为基线。候选比较必须交错采样，保存两评审盲评 CSV 后汇总：

```powershell
python scripts/emotional_eval.py summarize 'D:\private-eval\ratings.csv'
```

脚本默认配置来自当前仓库测试用例中的 DeepSeek 地址/模型与应用默认采样值；真正执行前须由评测负责人核对历史供应商实际发送的配置。为当前快照和候选分别保存副本及哈希。不要把含真实输出的 JSONL 或评分 CSV 放进 Git。
