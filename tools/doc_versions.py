#!/usr/bin/env python3
"""给一篇文稿按顺序存几版（测试署名用）：两个人轮流存。

用法：python3 tools/doc_versions.py 标题 用户:密码:正文 [用户:密码:正文 …]
正文里的 \\n 会换成换行。房间用那个人的第一个房间。打印文稿 id。
"""
import json
import os
import sys
import time
import urllib.request
import uuid

API = os.environ.get("QICHI_API", "http://127.0.0.1:8080/api/v1/")


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
    return call("POST", "auth/login", body={"username": user, "password": password, "deviceName": "tools"})["accessToken"]


def main():
    title, steps = sys.argv[1], sys.argv[2:]
    doc = uuid7()
    room = None
    for i, step in enumerate(steps):
        user, password, text = step.split(":", 2)
        token = login(user, password)
        if room is None:
            room = call("GET", "me", token)["rooms"][0]["roomId"]
            call("POST", f"rooms/{room}/documents", token, {"id": doc, "title": title})
        call("POST", f"rooms/{room}/documents/{doc}/versions", token,
             {"id": uuid7(), "baseVersion": i, "body": text.replace("\\n", "\n")})
    print(doc)


if __name__ == "__main__":
    main()
