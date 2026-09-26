#!/usr/bin/env python3
"""以某人身份在本机服务端问 AI 一组问题，列出每题的回答、引用、用量、耗时和它查了什么（P11）。

用法：python3 tools/ai_ask.py <用户名> <密码> "问题一" ["问题二" …]
查了什么从本机服务端日志里取（tools/dev_up.sh 写的 server.log，环境变量 QICHI_SERVER_LOG 可改）。
会真的调用 AI、花用量；只用在本机测试服务端。
"""
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
from dev_api import req, uuid7  # noqa: E402

LOG = os.environ.get("QICHI_SERVER_LOG", os.path.join(os.environ.get("QICHI_LOG_DIR", "/tmp/qichi-dev"), "server.log"))


def log_size():
    try:
        return os.path.getsize(LOG)
    except OSError:
        return 0


def lookups_since(offset):
    try:
        with open(LOG, "rb") as f:
            f.seek(offset)
            text = f.read().decode("utf-8", "replace")
    except OSError:
        return []
    return [line.split("查了：", 1)[1].strip() for line in text.splitlines() if "问 AI 第" in line and "查了：" in line]


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    user, password, questions = sys.argv[1], sys.argv[2], sys.argv[3:]
    token = req("POST", "auth/login", {"username": user, "password": password, "deviceName": "ai_ask"})["accessToken"]
    room = req("GET", "me", token=token)["rooms"][0]["roomId"]
    total_in = total_out = 0
    for q in questions:
        job = uuid7()
        offset = log_size()
        start = time.time()
        req("POST", f"rooms/{room}/ai/chat", {"jobId": job, "prompt": q}, token=token)
        while True:
            time.sleep(1)
            info = req("GET", f"rooms/{room}/ai/jobs/{job}", token=token)
            if info["status"] in ("done", "failed"):
                break
        took = time.time() - start
        print("=" * 60)
        print("问：", q)
        rounds = lookups_since(offset)
        print(f"查了 {len(rounds)} 轮：" + ("；".join(rounds) if rounds else "没查"))
        if info["status"] == "failed":
            print("失败：", info.get("error"))
            continue
        msgs = req("GET", f"rooms/{room}/messages", token=token)["messages"]
        answer = next(m for m in msgs if m["id"] == job)
        print("答：", answer["body"])
        for s in answer.get("aiSources", []):
            print(f"   [{s['number']}] {s['type']} · {s['label']}")
        print(f"用量 输入 {info['inputTokens']} / 输出 {info['outputTokens']} · {took:.1f} 秒")
        total_in += info["inputTokens"]
        total_out += info["outputTokens"]
    print("=" * 60)
    print(f"合计 输入 {total_in} / 输出 {total_out}")


if __name__ == "__main__":
    main()
