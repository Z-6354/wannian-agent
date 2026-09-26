#!/usr/bin/env python3
"""固定合成集的 OpenAI chat/completions 采样器与人工评分汇总器。"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
import pathlib
import random
import sys
import time
import urllib.error
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "docs/research/emotional-eval-v1.json"
DIMENSIONS = ("fit", "naturalness", "brevity", "agency", "factual")
LABELS = (
    "boilerplate", "unsupported_inference", "overquestion", "example_copy",
    "false_memory", "intimacy", "fabrication", "ignored_refusal", "safety_severe",
)


def load_cases(path: pathlib.Path) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8"))["cases"]


def call_model(url: str, key: str, model: str, system: str, turns: list[str], timeout: int, direct: bool) -> tuple[str, int | None, int | None]:
    endpoint = url.rstrip("/") + "/chat/completions"
    messages = [{"role": "system", "content": system}]
    for turn in turns:
        if turn.startswith("用户："):
            messages.append({"role": "user", "content": turn.removeprefix("用户：")})
        elif turn.startswith("助手："):
            messages.append({"role": "assistant", "content": turn.removeprefix("助手：")})
        else:
            messages.append({"role": "user", "content": turn})
    body = json.dumps({
        "model": model,
        "messages": messages,
        "temperature": 0.85,
        "top_p": 0.95,
        "presence_penalty": 0.35,
    }).encode("utf-8")
    request = urllib.request.Request(endpoint, data=body, headers={
        "Authorization": "Bearer " + key,
        "Content-Type": "application/json",
    })
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({})) if direct else urllib.request.build_opener()
    with opener.open(request, timeout=timeout) as response:
        result = json.loads(response.read().decode("utf-8"))
    text = result["choices"][0]["message"]["content"]
    usage = result.get("usage") or {}
    return text, usage.get("prompt_tokens"), usage.get("completion_tokens")


def run(args: argparse.Namespace) -> int:
    key = os.environ.get(args.key_env, "")
    if not key:
        print(f"缺少环境变量 {args.key_env}；未发起请求。", file=sys.stderr)
        return 2
    system = args.system_file.read_text(encoding="utf-8")
    cases = load_cases(args.dataset)
    if args.case:
        wanted = set(args.case)
        cases = [case for case in cases if case["id"] in wanted]
        unknown = wanted - {case["id"] for case in cases}
        if unknown:
            print("未知样本 ID: " + ", ".join(sorted(unknown)), file=sys.stderr)
            return 2
    if args.skip_case:
        skipped = set(args.skip_case)
        cases = [case for case in cases if case["id"] not in skipped]
    if args.limit:
        cases = cases[:args.limit]
    rng = random.Random(args.seed)
    schedule = [(case, repetition) for repetition in range(1, args.repetitions + 1) for case in cases]
    rng.shuffle(schedule)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    created = dt.datetime.now(dt.timezone.utc).isoformat()
    with args.output.open("w", encoding="utf-8") as out:
        for run_id, (case, repetition) in enumerate(schedule, start=1):
            started = time.monotonic()
            try:
                answer, prompt_tokens, completion_tokens = call_model(
                    args.base_url, key, args.model, system, case["turns"], args.timeout, args.direct
                )
                record = {
                    "created_utc": created, "run_id": run_id, "sample_id": case["id"],
                    "repetition": repetition, "group": case["group"],
                    "safety": bool(case.get("safety")), "model": args.model,
                    "base_url": args.base_url, "temperature": 0.85, "top_p": 0.95,
                    "presence_penalty": 0.35, "system_sha256": __import__("hashlib").sha256(system.encode()).hexdigest(),
                    "prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens,
                    "latency_seconds": round(time.monotonic() - started, 3), "response": answer,
                }
            except urllib.error.HTTPError as exc:
                record = {"created_utc": created, "run_id": run_id, "sample_id": case["id"],
                          "repetition": repetition, "error": "HTTPError", "http_status": exc.code}
            except (urllib.error.URLError, TimeoutError, KeyError, ValueError) as exc:
                record = {"created_utc": created, "run_id": run_id, "sample_id": case["id"],
                          "repetition": repetition, "error": type(exc).__name__,
                          "error_detail": str(getattr(exc, "reason", ""))[:240]}
            out.write(json.dumps(record, ensure_ascii=False) + "\n")
            out.flush()
            print(f"{run_id}/{len(schedule)} {case['id']} {'错误' if 'error' in record else '完成'}")
    print(f"输出: {args.output}；密钥值未写入文件。")
    return 0


def summarize(args: argparse.Namespace) -> int:
    counts: dict[str, dict[str, list[int]]] = {}
    with args.ratings.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        required = {"sample_id", "group", *DIMENSIONS, *LABELS}
        missing = required - set(reader.fieldnames or [])
        if missing:
            print("评分表缺少列: " + ", ".join(sorted(missing)), file=sys.stderr)
            return 2
        for row in reader:
            group = row["group"]
            bucket = counts.setdefault(group, {name: [] for name in (*DIMENSIONS, *LABELS)})
            for name in DIMENSIONS:
                score = int(row[name])
                if score not in (0, 1, 2):
                    print(f"{row['sample_id']} 的 {name} 需为 0/1/2", file=sys.stderr)
                    return 2
                bucket[name].append(score)
            for name in LABELS:
                bucket[name].append(int(row[name]))
    print("组别\t样本评分数\t情境贴合\t自然声线\t简洁节奏\t边界尊重\t事实适切\t缺陷数")
    for group, values in sorted(counts.items()):
        means = [sum(values[n]) / len(values[n]) if values[n] else 0 for n in DIMENSIONS]
        defects = sum(sum(values[n]) for n in LABELS)
        print(f"{group}\t{len(values['fit'])}\t" + "\t".join(f"{value:.2f}" for value in means) + f"\t{defects}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sample = sub.add_parser("sample", help="对固定样本调用真实 OpenAI 兼容 chat/completions API")
    sample.add_argument("--system-file", type=pathlib.Path, required=True, help="明确指定的提示词快照文件")
    sample.add_argument("--output", type=pathlib.Path, required=True, help="评测数据目录中的输出 JSONL")
    sample.add_argument("--dataset", type=pathlib.Path, default=DATA)
    sample.add_argument("--base-url", default="https://api.deepseek.com/v1")
    sample.add_argument("--model", default="deepseek-flash")
    sample.add_argument("--key-env", default="DEEPSEEK_API_KEY")
    sample.add_argument("--repetitions", type=int, default=3)
    sample.add_argument("--limit", type=int, default=0)
    sample.add_argument("--case", action="append", default=[])
    sample.add_argument("--skip-case", action="append", default=[])
    sample.add_argument("--seed", type=int, default=20260925)
    sample.add_argument("--timeout", type=int, default=45)
    sample.add_argument("--direct", action="store_true", help="忽略本机 HTTP(S)_PROXY 配置，直连 API 主机")
    sample.set_defaults(func=run)
    score = sub.add_parser("summarize", help="汇总人工评分 CSV，不做自动裁判")
    score.add_argument("ratings", type=pathlib.Path)
    score.set_defaults(func=summarize)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
