#!/usr/bin/env python3
"""直接调本机服务端准备测试数据（中文内容用它，adb 打不了中文）。

  python3 tools/dev_api.py send <用户名> <密码> <消息>        以某人身份在他的第一个房间发一条消息
  python3 tools/dev_api.py review <用户名> <密码> <文件> [标题]  上传一份审稿文件，等预览生成好后列出文字块
环境变量 QICHI_API 改服务端地址（默认 http://127.0.0.1:8080/api/v1/）。
"""
import json
import os
import sys
import time
import urllib.request
import uuid

API = os.environ.get("QICHI_API", "http://127.0.0.1:8080/api/v1/")


def req(method, path, body=None, token=None, raw=False):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    data = urllib.request.urlopen(r).read()
    return data if raw else (json.loads(data) if data else None)


def uuid7():
    ms = int(time.time() * 1000)
    r = uuid.uuid4().int
    return str(uuid.UUID(int=(ms << 80) | (0x7 << 76) | (((r >> 64) & 0xFFF) << 64) | (0b10 << 62) | (r & ((1 << 62) - 1))))


def login(user, password):
    return req("POST", "auth/login", {"username": user, "password": password, "deviceName": "dev_api"})["accessToken"]


def first_room(token):
    return req("GET", "rooms", token=token)[0]["id"]


def upload(token, room, path, kind="review"):
    boundary = "----qichi" + uuid.uuid4().hex
    name = os.path.basename(path)
    with open(path, "rb") as f:
        data = f.read()
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="kind"\r\n\r\n{kind}\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
            f"Content-Type: application/octet-stream\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    r = urllib.request.Request(API + f"rooms/{room}/files", data=body, method="POST",
                               headers={"Content-Type": "multipart/form-data; boundary=" + boundary, "Authorization": "Bearer " + token})
    return json.load(urllib.request.urlopen(r))


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["help"]
    if cmd == "send":
        user, password, text = args[0], args[1], " ".join(args[2:])
        t = login(user, password)
        room = first_room(t)
        print(req("POST", f"rooms/{room}/messages", {"id": uuid7(), "kind": "text", "body": text}, t)["id"])
    elif cmd == "review":
        user, password, path = args[0], args[1], args[2]
        title = args[3] if len(args) > 3 else os.path.splitext(os.path.basename(path))[0]
        t = login(user, password)
        room = first_room(t)
        f = upload(t, room, path)
        doc = req("POST", f"rooms/{room}/reviews", {"id": uuid7(), "title": title, "firstVersion": {"id": uuid7(), "fileId": f["id"]}}, t)
        print("审稿文件", doc["id"])
        for _ in range(90):
            pages = req("GET", f"rooms/{room}/reviews/{doc['id']}/versions/1/pages", token=t)
            if pages:
                break
            time.sleep(1)
        for p in pages:
            print("第", p["page"], "页")
            for b in p["blocks"]:
                print("  ", b["id"], b["kind"], b["text"][:60])
    else:
        print(__doc__)
