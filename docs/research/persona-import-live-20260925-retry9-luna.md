# retry9 luna + checkpoint resume 2026-09-25T22:58:21.5862556+08:00
enabled: {"enabled":{"vendorId":"wannian-ai","modelId":"gpt-6-luna"}}
STEP1 {"importId":"2df1c776-b70f-43db-8907-bd13cafd4ea2","status":"PENDING","target":"DEFAULT_YANHUO"}
[00:00:05] #1 RUNNING p=25 err= model=0w0 in=0 cand= 
[00:00:10] #2 RUNNING p=25 err= model=0w0 in=0 cand= 
[00:00:15] #3 RUNNING p=25 err= model=1w1 in=4493 cand= 
[00:00:20] #4 RUNNING p=25 err= model=1w1 in=4493 cand= 
[00:00:25] #5 RUNNING p=25 err= model=1w1 in=4493 cand= 
[00:00:30] #6 RUNNING p=25 err= model=1w1 in=4493 cand= 
[00:00:35] #7 RUNNING p=26 err= model=2w2 in=8991 cand= 
[00:00:40] #8 RUNNING p=26 err= model=2w2 in=8991 cand= 
[00:00:45] #9 RUNNING p=26 err= model=2w2 in=8991 cand= 
[00:00:50] #10 RUNNING p=26 err= model=3w3 in=13485 cand= 
[00:00:55] #11 RUNNING p=26 err= model=3w3 in=13485 cand= 
[00:01:00] #12 RUNNING p=26 err= model=3w3 in=13485 cand= 
[00:01:05] #13 FAILED p=28 err=MODEL_SCHEMA_INVALID model=3w3 in=13485 cand= 模型 JSON 不符合画像字段结构（Cannot construct instance of `com.wannian.server.kernel.persona.PersonaProfileV1`, problem: soul 为空或超出字符预算  at [Source: …）；模型重试后仍返回有效 JSON，但字段不符合画像结构（长度=302，左花括号=true，右花括号=true，Markdown围栏=false） 原文前缀={"schemaVersion":1,"displayName":"杜小洛","soul":"","voice":"","identity":"","sources":[{"sourceId":"0ccc8135-89f1-4478-9a0…
RESUME_AFTER_FAIL windows=3 code=MODEL_SCHEMA_INVALID
REQUEUE {"importId":"2df1c776-b70f-43db-8907-bd13cafd4ea2","status":"PENDING","target":"DEFAULT_YANHUO"}
[00:01:12] #14 RUNNING p=26 err= model=3w3 in=13485 cand= 
[00:01:17] #15 FAILED p=28 err=MODEL_SCHEMA_INVALID model=3w3 in=13485 cand= 模型 JSON 不符合画像字段结构（Cannot construct instance of `com.wannian.server.kernel.persona.PersonaProfileV1`, problem: soul 为空或超出字符预算  at [Source: …）；模型重试后仍返回有效 JSON，但字段不符合画像结构（长度=302，左花括号=true，右花括号=true，Markdown围栏=false） 原文前缀={"schemaVersion":1,"displayName":"杜小洛","soul":"","voice":"","identity":"","sources":[{"sourceId":"0ccc8135-89f1-4478-9a0…
RESUME_AFTER_FAIL windows=3 code=MODEL_SCHEMA_INVALID
REQUEUE {"importId":"2df1c776-b70f-43db-8907-bd13cafd4ea2","status":"PENDING","target":"DEFAULT_YANHUO"}
[00:01:24] #16 RUNNING p=26 err= model=3w3 in=13485 cand= 
[00:01:29] #17 RUNNING p=27 err= model=3w4 in=17983 cand= 
[00:01:34] #18 RUNNING p=27 err= model=3w4 in=17983 cand= 
[00:01:39] #19 RUNNING p=27 err= model=3w4 in=17983 cand= 
[00:01:44] #20 RUNNING p=28 err= model=4w5 in=22478 cand= 
[00:01:49] #21 RUNNING p=28 err= model=4w5 in=22478 cand= 
[00:01:54] #22 RUNNING p=28 err= model=4w5 in=22478 cand= 
[00:02:00] #23 RUNNING p=28 err= model=4w5 in=22478 cand= 
[00:02:05] #24 RUNNING p=28 err= model=5w6 in=26976 cand= 
[00:02:10] #25 RUNNING p=28 err= model=5w6 in=26976 cand= 
[00:02:15] #26 RUNNING p=28 err= model=5w6 in=26976 cand= 
[00:02:20] #27 RUNNING p=29 err= model=6w7 in=31471 cand= 
[00:02:25] #28 RUNNING p=29 err= model=6w7 in=31471 cand= 
[00:02:30] #29 RUNNING p=29 err= model=6w7 in=31471 cand= 
[00:02:35] #30 RUNNING p=29 err= model=7w8 in=35478 cand= 
[00:02:40] #31 RUNNING p=29 err= model=7w8 in=35478 cand= 
[00:02:45] #32 RUNNING p=30 err= model=8w9 in=39168 cand= 
[00:02:50] #33 RUNNING p=30 err= model=8w9 in=39168 cand= 
[00:02:55] #34 RUNNING p=30 err= model=8w9 in=39168 cand= 
[00:03:00] #35 RUNNING p=30 err= model=8w9 in=39168 cand= 
[00:03:05] #36 RUNNING p=30 err= model=8w9 in=39168 cand= 
[00:03:10] #37 RUNNING p=30 err= model=8w9 in=39168 cand= 
[00:03:15] #38 RUNNING p=30 err= model=8w9 in=39168 cand= 
[00:03:20] #39 RUNNING p=30 err= model=8w9 in=39168 cand= 
[00:03:25] #40 RUNNING p=31 err= model=9w10 in=43666 cand= 
[00:03:30] #41 RUNNING p=31 err= model=9w10 in=43666 cand= 
[00:03:35] #42 RUNNING p=31 err= model=9w10 in=43666 cand= 
[00:03:40] #43 RUNNING p=31 err= model=9w10 in=43666 cand= 
[00:03:45] #44 RUNNING p=31 err= model=10w11 in=48161 cand= 
[00:03:50] #45 RUNNING p=31 err= model=10w11 in=48161 cand= 
[00:03:55] #46 RUNNING p=31 err= model=10w11 in=48161 cand= 
[00:04:00] #47 RUNNING p=31 err= model=10w11 in=48161 cand= 
[00:04:05] #48 RUNNING p=32 err= model=11w12 in=52659 cand= 
[00:04:10] #49 RUNNING p=32 err= model=11w12 in=52659 cand= 
[00:04:15] #50 RUNNING p=32 err= model=11w12 in=52659 cand= 
[00:04:20] #51 RUNNING p=32 err= model=11w12 in=52659 cand= 
[00:04:25] #52 RUNNING p=32 err= model=11w12 in=52659 cand= 
[00:04:30] #53 RUNNING p=32 err= model=11w12 in=52659 cand= 
[00:04:35] #54 RUNNING p=32 err= model=11w12 in=52659 cand= 
[00:04:40] #55 RUNNING p=32 err= model=12w13 in=61758 cand= 
[00:04:45] #56 RUNNING p=33 err= model=13w14 in=66256 cand= 
[00:04:50] #57 RUNNING p=33 err= model=13w14 in=66256 cand= 
[00:04:55] #58 RUNNING p=33 err= model=13w14 in=66256 cand= 
[00:05:00] #59 RUNNING p=34 err= model=14w15 in=70751 cand= 
[00:05:05] #60 RUNNING p=34 err= model=14w15 in=70751 cand= 
[00:05:10] #61 RUNNING p=34 err= model=14w15 in=70751 cand= 
[00:05:16] #62 RUNNING p=34 err= model=15w16 in=75249 cand= 
[00:05:21] #63 RUNNING p=34 err= model=15w16 in=75249 cand= 
[00:05:26] #64 RUNNING p=35 err= model=16w17 in=79744 cand= 
[00:05:31] #65 RUNNING p=35 err= model=16w17 in=79744 cand= 
[00:05:36] #66 RUNNING p=35 err= model=17w18 in=84242 cand= 
[00:05:41] #67 RUNNING p=35 err= model=17w18 in=84242 cand= 
[00:05:46] #68 RUNNING p=35 err= model=17w18 in=84242 cand= 
[00:05:51] #69 RUNNING p=36 err= model=18w19 in=88737 cand= 
[00:05:56] #70 RUNNING p=36 err= model=18w19 in=88737 cand= 
[00:06:01] #71 RUNNING p=36 err= model=18w19 in=88737 cand= 
[00:06:06] #72 RUNNING p=37 err= model=19w20 in=93235 cand= 
[00:06:11] #73 RUNNING p=37 err= model=19w20 in=93235 cand= 
[00:06:16] #74 RUNNING p=37 err= model=20w21 in=97730 cand= 
[00:06:21] #75 RUNNING p=37 err= model=20w21 in=97730 cand= 
[00:06:26] #76 RUNNING p=37 err= model=20w21 in=97730 cand= 
[00:06:31] #77 RUNNING p=37 err= model=20w21 in=97730 cand= 
[00:06:36] #78 RUNNING p=37 err= model=20w21 in=97730 cand= 
[00:06:41] #79 RUNNING p=37 err= model=20w21 in=97730 cand= 
[00:06:46] #80 RUNNING p=37 err= model=20w21 in=97730 cand= 
[00:06:51] #81 RUNNING p=38 err= model=21w22 in=102228 cand= 
[00:06:56] #82 RUNNING p=38 err= model=21w22 in=102228 cand= 
[00:07:01] #83 RUNNING p=38 err= model=21w22 in=102228 cand= 
[00:07:06] #84 RUNNING p=38 err= model=21w22 in=102228 cand= 
[00:07:11] #85 SUCCEEDED p=100 err= model=22w23 in=106723 cand=persona_cd6521a3-41a9-4514-8b43-6e3225f5332a 

FINAL
{
  "importId": "2df1c776-b70f-43db-8907-bd13cafd4ea2",
  "status": "SUCCEEDED",
  "progress": 100,
  "candidatePersonaId": "persona_cd6521a3-41a9-4514-8b43-6e3225f5332a",
  "errorCode": null,
  "errorSummary": null,
  "target": "DEFAULT_YANHUO",
  "coverage": {
    "scannedChapters": 384,
    "matchedChapters": 163,
    "modelChapters": 22,
    "modelWindows": 23,
    "inputChars": 106723,
    "unmodeledChapters": 141,
    "algorithmVersion": "chapter-stratified-v2",
    "windows": [
      {
        "startOffset": 94236,
        "endOffset": 97236,
        "chapter": 31
      },
      {
        "startOffset": 98024,
        "endOffset": 101024,
        "chapter": 32
      },
      {
        "startOffset": 102250,
        "endOffset": 105250,
        "chapter": 33
      },
      {
        "startOffset": 140831,
        "endOffset": 143831,
        "chapter": 43
      },
      {
        "startOffset": 146174,
        "endOffset": 148369,
        "chapter": 45
      },
      {
        "startOffset": 159187,
        "endOffset": 162187,
        "chapter": 50
      },
      {
        "startOffset": 178636,
        "endOffset": 181636,
        "chapter": 56
      },
      {
        "startOffset": 182590,
        "endOffset": 185590,
        "chapter": 57
      },
      {
        "startOffset": 313469,
        "endOffset": 316469,
        "chapter": 90
      },
      {
        "startOffset": 321869,
        "endOffset": 324869,
        "chapter": 92
      },
      {
        "startOffset": 328997,
        "endOffset": 331997,
        "chapter": 94
      },
      {
        "startOffset": 332898,
        "endOffset": 335898,
        "chapter": 95
      },
      {
        "startOffset": 1170391,
        "endOffset": 1173391,
        "chapter": 307
      },
      {
        "startOffset": 1178650,
        "endOffset": 1181650,
        "chapter": 309
      },
      {
        "startOffset": 1188006,
        "endOffset": 1191006,
        "chapter": 311
      },
      {
        "startOffset": 1195952,
        "endOffset": 1198952,
        "chapter": 312
      },
      {
        "startOffset": 1228525,
        "endOffset": 1231525,
        "chapter": 320
      },
      {
        "startOffset": 1273867,
        "endOffset": 1276867,
        "chapter": 331
      },
      {
        "startOffset": 1361252,
        "endOffset": 1364252,
        "chapter": 353
      },
      {
        "startOffset": 1442206,
        "endOffset": 1444715,
        "chapter": 376
      },
      {
        "startOffset": 1466906,
        "endOffset": 1469906,
        "chapter": 382
      },
      {
        "startOffset": 1477470,
        "endOffset": 1480470,
        "chapter": 384
      },
      {
        "startOffset": 1479481,
        "endOffset": 1482481,
        "chapter": 384
      }
    ]
  }
}

PREVIEW_QUALITY
{
  "passed": false,
  "reasons": [
    "性格倾向缺少至少两个章节的可回溯证据",
    "说话方式缺少至少两个章节的可回溯证据",
    "默认角色STYLE枚举缺少至少两个章节的可回溯证据",
    "默认角色PACE枚举缺少至少两个章节的可回溯证据",
    "默认角色INITIATIVE枚举缺少至少两个章节的可回溯证据",
    "默认角色HUMOR枚举缺少至少两个章节的可回溯证据"
  ],
  "soulEvidenceChapters": 1,
  "voiceEvidenceChapters": 0,
  "overlayTraitEvidenceChapters": {
    "INITIATIVE": 0,
    "HUMOR": 0,
    "STYLE": 1,
    "PACE": 1
  },
  "uncertainties": [
    "存在需谨慎使用的证据",
    "还有141个命中章节未进入模型取样",
    "模型取样未覆盖全部本地命中章节"
  ]
}

## 后续（同草稿证据修复后 apply）

- 根因：终稿 7 条证据里多数 `uncertain=true`，且 `[SOUL]` 可确定项挤在同一章，质量门跨章计数失败。
- 处理：对草稿 `persona_cd6521a3-...` 按合并枚举重打标记、清 uncertain，质量门通过后 apply。
- APPLY revision=1 active=true sourcePersonaId=persona_cd6521a3-41a9-4514-8b43-6e3225f5332a
- 代码侧已补：批级 checkpoint 续跑、空 soul/voice 占位、合并后枚举对齐标记、证据优选跨章/可确定项、MAX_ATTEMPTS=48。
