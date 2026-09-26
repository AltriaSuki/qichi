#!/usr/bin/env python3
"""本机测试用的假 AI：兼容 OpenAI 的 /v1/chat/completions，按提示词里的关键字回固定内容。

  python3 tools/fake_ai.py [端口，默认 9099]

问 AI 带着查询工具时（P11）：问题里有「…」就先用 search 查引号里的词，
拿到结果后引用第一条回答，用来演示「正在查」和点 [n] 跳转。
"""
import http.server
import json
import re
import sys

SUMMARY = "## 这一周\n（本机模拟回答）你们定下了要不要养猫：搬家后再养 [1]。也聊到了安静的餐厅 [2]。\n\n## 心情\n整体平稳，偶尔有点累。"
REVIEW = [
    {"title": "首付比例前后不一致", "body": "（本机模拟）前后两处写的首付比例不一样，请和对方确认。", "evidence": [{"ref": "p1-b2", "quote": "首付"}]},
]


def answer(user: str) -> str:
    if "原文：" in user and "已经记下的问题" in user:
        return json.dumps(REVIEW, ensure_ascii=False)
    if "回顾" in user:
        return SUMMARY
    if "对照" in user:
        return "（本机模拟回答）这段和「不必急着去哪里，先在这里坐一会儿」呼应：都是停下来的时刻。"
    if "出一道" in user or "问题" in user and "两个人" in user:
        return "如果下个月有一整个周末空出来，你最想怎么过？"
    return "（本机模拟回答）这句写的是她终于把心里的话落在纸上。写的都是小事，但正因为小，才显得真。"


def with_tools(body):
    """带工具的问 AI：第一轮查，第二轮引用查到的第一条。不需要查时返回 None。"""
    if not any(t["function"]["name"] == "search" for t in body.get("tools", [])):
        return None
    last = body["messages"][-1]
    if last["role"] == "tool":
        content = last["content"] or ""
        first = next((line for line in content.splitlines() if re.match(r"\[\d+] ", line)), None)
        if first is None:
            return {"content": "（本机模拟回答）查过了，没找到相关的。"}
        n, rest = re.match(r"\[(\d+)] (.*)", first).groups()
        return {"content": f"（本机模拟回答）找到了：{rest[:60]} [{n}]"}
    question = last["content"].split("的问题：")[-1]
    quoted = re.search(r"「([^」]+)」", question)
    if last["role"] != "user" or quoted is None:
        return None
    call = {"id": "call_fake", "type": "function", "function": {"name": "search", "arguments": json.dumps({"query": quoted.group(1)}, ensure_ascii=False)}}
    return {"content": None, "tool_calls": [call]}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        message = with_tools(body) or {"content": answer(body["messages"][-1]["content"])}
        out = json.dumps({"model": "mock", "choices": [{"message": message}],
                          "usage": {"prompt_tokens": 50, "completion_tokens": 30}}, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9099
    print(f"假 AI 在 http://127.0.0.1:{port}/v1")
    http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
