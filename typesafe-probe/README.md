# TypeSafe 初步探测

对照 [TypeSafe 控制台](https://console.typesafe.ai/home) 和 [Quick start](https://docs.typesafe.ai/introduction/quickstart.md)。密钥只读环境变量 `TYPESAFE_API_KEY`。

端点：`POST https://api.typesafe.ai/v1/systemone`，模型字段写 `jev-latest`。

## 运行

```powershell
powershell -NoProfile -File D:\0HAN\HANAGENT\products\wannian-agent\typesafe-probe\probe.ps1
powershell -NoProfile -File D:\0HAN\HANAGENT\products\wannian-agent\typesafe-probe\probe.ps1 -Case refund
```

`request.json` 是最早那条单请求，和 `cases/01-urgent-ticket.json` 相同。日常看 `cases/`。

## 用例在看什么

| 文件 | 对照点 |
| --- | --- |
| `01-urgent-ticket.json` | 同一段文本一次问 Choice、Score、Noul。返回选项和数字，不是一段回复。Stripe 接入失败会在账单和技术之间摇摆，置信度不高。 |
| `02-calm-invoice.json` | 问题与 01 完全相同，只换平静文本。紧急和挫败靠近 0，用来对照 01。 |
| `03-mixed-intent.json` | 崩溃、报价、账单挤在一条消息里。Choice 仍必须选出一个组；旁边的 Noul 分别问三个话题在不在。选中一组不等于另外两件事不存在。 |
| `04-refund-atoms.json` | 不问“该不该退款”。拆成是否要求退款、是否同日重复扣款、政策把单子送到哪。组合规则留在代码里。 |
| `05-chinese-ticket.json` | 中文状态和中文题面。输出形状与英文用例相同。 |
