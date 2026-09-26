#!/usr/bin/env python3
"""灌大量数据测流畅度（P10-11）：两个人轮流发 N 条消息、记 M 条灵感。只用在本机测试服务端。

用法：python3 tools/seed_bulk.py 消息数 灵感数
例：  python3 tools/seed_bulk.py 3000 500
账号用本机测试的 aqi / xiaochi（密码 password-aqi / password-chi），房间用两人的第一个房间。
"""
import json
import os
import sys
import time
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

API = os.environ.get("QICHI_API", "http://127.0.0.1:8080/api/v1/")
LINES = ["周六早上出发怎么样？", "好呀，我把外套带上。", "晚上吃什么？", "楼下那家面馆吧", "今天有点累", "抱一下",
         "海边那家民宿还有房", "记得带相机 #旅行", "阳台的绿萝又长了", "明天早点起？"]


def uuid7() -> str:
    ms = int(time.time() * 1000)
    b = bytearray(ms.to_bytes(6, "big") + os.urandom(10))
    b[6] = (b[6] & 0x0F) | 0x70
    b[8] = (b[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(b)))


def call(method, path, token=None, body=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("content-type", "application/json")
    if token:
        req.add_header("authorization", "Bearer " + token)
    with urllib.request.urlopen(req) as r:
        text = r.read().decode()
        return json.loads(text) if text else None


def login(user, password):
    return call("POST", "auth/login", body={"username": user, "password": password, "deviceName": "seed"})["accessToken"]


def main():
    messages, ideas = int(sys.argv[1]), int(sys.argv[2])
    tokens = [login("aqi", "password-aqi"), login("xiaochi", "password-chi")]
    room = call("GET", "me", tokens[0])["rooms"][0]["roomId"]

    def send(i):
        call("POST", f"rooms/{room}/messages", tokens[i % 2], {"id": uuid7(), "kind": "text", "body": f"{LINES[i % len(LINES)]}（{i + 1}）"})

    def idea(i):
        call("POST", f"rooms/{room}/ideas", tokens[i % 2], {"id": uuid7(), "body": f"灵感 {i + 1}：{LINES[(i * 3) % len(LINES)]}"})

    # 消息要按顺序（seq 递增），少开几个线程
    with ThreadPoolExecutor(4) as pool:
        list(pool.map(send, range(messages)))
        list(pool.map(idea, range(ideas)))
    print(f"发了 {messages} 条消息、{ideas} 条灵感到房间 {room}")


if __name__ == "__main__":
    main()
