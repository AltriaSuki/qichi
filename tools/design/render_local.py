#!/usr/bin/env python3
"""把设计稿（.dc.html）截成图，用本机装的 Chromium（不用装 playwright）。

  python3 tools/design/render_local.py 截图目录 New-Spec New-Today …   （文件名前缀；不写就全部）

和 render.js 一样：去掉画布运行时外壳，按画板声明的尺寸截全图。字体从 Google Fonts 加载，要能联网。
环境变量 CHROMIUM 可以指定浏览器（默认依次找 chromium、google-chrome-stable）。
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
SCREENS = os.path.join(ROOT, "design", "screens")


def plain(src: str) -> str:
    helmet = (re.search(r"<helmet>([\s\S]*?)</helmet>", src) or [None, ""])[1]
    body = re.sub(r"<helmet>[\s\S]*?</helmet>", "", src)
    body = body[body.index("<x-dc>") + 6: body.index("</x-dc>")]
    return f'<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">{helmet}</head><body style="margin:0">{body}</body></html>'


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    out = sys.argv[1]
    prefixes = sys.argv[2:]
    os.makedirs(out, exist_ok=True)
    browser = os.environ.get("CHROMIUM") or shutil.which("chromium") or shutil.which("google-chrome-stable")
    if not browser:
        raise SystemExit("找不到 Chromium，设置 CHROMIUM=浏览器路径")
    for f in sorted(os.listdir(SCREENS)):
        if not f.endswith(".dc.html") or (prefixes and not any(f.startswith(p) for p in prefixes)):
            continue
        src = open(os.path.join(SCREENS, f), encoding="utf-8").read()
        m = re.search(r'"\$preview":\{"width":(\d+),"height":(\d+)\}', src)
        w, h = (int(m[1]), int(m[2])) if m else (390, 844)
        with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False, encoding="utf-8") as tmp:
            tmp.write(plain(src))
        png = os.path.join(out, f.replace(".dc.html", ".png"))
        subprocess.run([browser, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--window-size={w},{h}",
                        "--virtual-time-budget=8000", f"--screenshot={png}", "file://" + tmp.name],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        os.unlink(tmp.name)
        print(("ok  " if os.path.exists(png) else "失败 ") + png)


if __name__ == "__main__":
    main()
