#!/usr/bin/env python3
"""拿固定的中文材料，对真 AI 跑一遍栖迟的每个提示词：检查格式，并把回答存下来给人看效果。

提示词只在假 AI 上测过流程；换服务商、换模型、改提示词之后，用它看真模型的表现。

  AI_PROVIDER=openai-compatible AI_BASE_URL=https://…/v1 AI_API_KEY=… AI_MODEL=… python3 tools/ai_eval.py [提示词名…]

AI_PROVIDER 也可以是 anthropic（AI_BASE_URL 如 https://api.anthropic.com）。不写提示词名就全部跑。
回答存在 ${QICHI_EVAL_OUT:-/tmp/qichi-ai-eval}/时间.md（密钥不会写进去）。有检查没过时退出码为 1。
"""
import datetime
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PROMPTS = os.path.join(ROOT, "server/src/main/resources/prompts")

# ── 固定材料 ──

REVIEW_TEXT = """[p1-b1] 厨房改造报价方案
[p1-b2] 本方案包括橱柜、水槽和台面的更换。付款方式：签约时首付百分之三十，余款在验收合格后一次付清。
[p1-b3] 项目 数量 单价
[p1-b4] 橱柜 3 米 1200
[p1-b5] 水槽 1 个 800
[p2-b1] 补充说明：签约后首付款为合同总价的百分之四十。
[p2-b2] 整个工期预计六周，如遇材料延迟，双方协商顺延。"""

SUMMARY_SOURCES = """[1] 9月2日 决定：先不养猫，等搬家后再说
[2] 9月3日 聊天 小迟：那家安静的日料店下周末还有位子
[3] 9月5日 心情 阿栖：有点累（6），连开了三个会
[4] 9月6日 灵感 小迟：周末早起去海边看日出
[5] 9月7日 计划完成：整理好阳台"""

CASES = {
    "review_findings": dict(title="报价方案", version="2", max="8", truncated="", known="（无）", text=REVIEW_TEXT),
    "summary": dict(range="2026年9月1日—2026年9月7日", kind="一周", people="阿栖、小迟", length="500", sources=SUMMARY_SOURCES),
    "chat_answer": dict(asker="阿栖", prompt="周六适合去哪片海？我们想人少一点。",
                        history="小迟：周六早上出发怎么样？\n阿栖：好呀，想去人少的海边\n小迟：车程别超过两小时"),
    "question_suggest": dict(history="小迟：周六早上出发怎么样？\n阿栖：好呀\n小迟：最近总是加班，有点想休息"),
    "read_explain": dict(title="海边的旅店", author="（林晚）", text="她不必急着去哪里，先在这里坐一会儿。",
                         before="雨是从傍晚开始下的。", after="她于是真的坐了很久。"),
    "read_compare": dict(title="海边的旅店", author="（林晚）", text="她不必急着去哪里，先在这里坐一会儿。",
                         notes="阿栖：我们总是太急 —— 想起去年搬家那周\n小迟：雨天就适合发呆"),
}

# ── 检查 ──


def check(name, answer, vars_):
    """返回没通过的检查（空列表 = 通过）。"""
    problems = []
    if not answer.strip():
        return ["回答是空的"]
    if name == "review_findings":
        start, end = answer.find("["), answer.rfind("]")
        try:
            items = json.loads(answer[start:end + 1])
        except Exception:
            return ["不是能解析的 JSON 数组（服务端会当作一条都没有）"]
        text = re.sub(r"\s+", "", vars_["text"])
        for i, f in enumerate(items):
            if not f.get("title"):
                problems.append(f"第 {i + 1} 条没有标题")
            for e in f.get("evidence", []):
                q = re.sub(r"\s+", "", e.get("quote", ""))
                if not q or q not in text:
                    problems.append(f"第 {i + 1} 条的证据不是原文：{e.get('quote')!r}（服务端会丢掉）")
        if not any("30" in json.dumps(f, ensure_ascii=False) or "三十" in json.dumps(f, ensure_ascii=False) for f in items):
            problems.append("没发现首付「三十」和「四十」前后矛盾（材料里故意放的）")
    elif name == "summary":
        cited = set(re.findall(r"\[(\d+)\]", answer))
        if not cited:
            problems.append("没有用 [n] 标出依据")
        if cited - {"1", "2", "3", "4", "5"}:
            problems.append(f"引用了不存在的编号：{sorted(cited - {'1', '2', '3', '4', '5'})}")
        if len(answer) > 800:
            problems.append(f"太长了：{len(answer)} 字")
    elif name in ("read_explain", "read_compare"):
        if len(answer) > 400:
            problems.append(f"太长了：{len(answer)} 字（要求一般不超过 200 字）")
    elif name == "question_suggest":
        if len(answer) > 80 or "\n" in answer.strip():
            problems.append("应该只是一道简短的问题")
    return problems

# ── 调用 ──


def render(name, vars_):
    text = open(os.path.join(PROMPTS, name + ".md"), encoding="utf-8").read()
    for k, v in vars_.items():
        text = text.replace("{{" + k + "}}", v)
    left = re.findall(r"\{\{(\w+)\}\}", text)
    if left:
        raise SystemExit(f"{name} 的材料缺变量：{left}")
    parts = re.split(r"(?m)^---\s*$", text, maxsplit=1)
    return (parts[0].strip(), parts[1].strip()) if len(parts) == 2 else ("", text.strip())


def call(system, user):
    provider = os.environ.get("AI_PROVIDER", "openai-compatible")
    base = os.environ["AI_BASE_URL"].rstrip("/")
    key = os.environ["AI_API_KEY"]
    model = os.environ["AI_MODEL"]
    if provider == "anthropic":
        url = base + "/v1/messages"
        body = {"model": model, "max_tokens": 3000, "system": system, "messages": [{"role": "user", "content": user}]}
        headers = {"x-api-key": key, "anthropic-version": "2023-06-01", "Content-Type": "application/json"}
    else:
        url = base + "/chat/completions"
        messages = ([{"role": "system", "content": system}] if system else []) + [{"role": "user", "content": user}]
        body = {"model": model, "max_tokens": 3000, "messages": messages}
        headers = {"Authorization": "Bearer " + key, "Content-Type": "application/json"}
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method="POST")
    started = datetime.datetime.now()
    data = json.load(urllib.request.urlopen(req, timeout=180))
    seconds = (datetime.datetime.now() - started).total_seconds()
    text = "".join(c.get("text", "") for c in data["content"]) if provider == "anthropic" else data["choices"][0]["message"]["content"]
    return text, seconds


def main():
    for v in ("AI_BASE_URL", "AI_API_KEY", "AI_MODEL"):
        if not os.environ.get(v):
            raise SystemExit(f"请设置 {v}（见文件开头说明）")
    names = sys.argv[1:] or list(CASES)
    out_dir = os.environ.get("QICHI_EVAL_OUT", "/tmp/qichi-ai-eval")
    os.makedirs(out_dir, exist_ok=True)
    report = [f"# 栖迟提示词验证 · {os.environ['AI_MODEL']} · {datetime.datetime.now():%Y-%m-%d %H:%M}\n"]
    failed = 0
    for name in names:
        system, user = render(name, CASES[name])
        try:
            answer, seconds = call(system, user)
        except Exception as e:
            answer, seconds = "", 0
            problems = [f"调用失败：{e}"]
        else:
            problems = check(name, answer, CASES[name])
        failed += bool(problems)
        mark = "通过" if not problems else "没通过"
        print(f"{name}: {mark}（{seconds:.1f} 秒）" + "".join(f"\n  - {p}" for p in problems))
        report.append(f"## {name} · {mark} · {seconds:.1f} 秒\n")
        report += [f"- {p}" for p in problems]
        report.append(f"\n```\n{answer.strip()}\n```\n")
    path = os.path.join(out_dir, f"{datetime.datetime.now():%Y%m%d-%H%M%S}.md")
    open(path, "w", encoding="utf-8").write("\n".join(report))
    print(f"回答存在 {path}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
