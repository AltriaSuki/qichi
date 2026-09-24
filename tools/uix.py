#!/usr/bin/env python3
"""操作模拟器（adb + uiautomator）。

命令行：
  python3 tools/uix.py texts            列出屏幕上的文字和无障碍描述
  python3 tools/uix.py tap <文字> [--last]  点一下显示这段文字的元素（有多个时默认第一个）
  python3 tools/uix.py type <英文>       往当前输入框打字（adb 输入不了中文）
  python3 tools/uix.py shot <文件.png>    截图
  python3 tools/uix.py bounds <文字>     看某个元素的位置
脚本里：sys.path 加上 tools/ 后 from uix import *
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB") or os.path.expanduser("~/Android/Sdk/platform-tools/adb")


def dump():
    """当前屏幕的界面树。"""
    for _ in range(3):
        subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
        out = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
        if out.strip().startswith("<?xml"):
            return ET.fromstring(out)
        time.sleep(0.5)
    raise SystemExit("读不到界面（模拟器开着吗？）")


def nodes(text):
    return [n for n in dump().iter("node") if text in (n.get("text"), n.get("content-desc"))]


def texts():
    return [n.get("text") or n.get("content-desc") for n in dump().iter("node") if n.get("text") or n.get("content-desc")]


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def tap_xy(x, y):
    subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)])


def tap(text, last=False):
    """点一下显示 [text] 的元素。找不到返回 False。"""
    hits = nodes(text)
    if not hits:
        print("找不到：", text)
        return False
    x1, y1, x2, y2 = bounds(hits[-1 if last else 0])
    tap_xy((x1 + x2) // 2, (y1 + y2) // 2)
    return True


def swipe(x1, y1, x2, y2, ms=400):
    subprocess.run([ADB, "shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms)])


def back():
    subprocess.run([ADB, "shell", "input", "keyevent", "KEYCODE_BACK"])


def type_(s):
    subprocess.run([ADB, "shell", "input", "text", s.replace(" ", "%s")])


def shot(path):
    with open(path, "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def open_link(link):
    """用深链打开 App 的某一页，如 qichi://room/<房间 id>/review"""
    subprocess.run([ADB, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", link, "app.qichi"], capture_output=True)


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["texts"]
    if cmd == "texts":
        print(" | ".join(texts()))
    elif cmd == "tap":
        tap(args[0], last="--last" in args)
    elif cmd == "type":
        type_(" ".join(args))
    elif cmd == "shot":
        shot(args[0])
    elif cmd == "bounds":
        for n in nodes(args[0]):
            print(n.get("bounds"), n.get("clickable"))
    else:
        print(__doc__)
