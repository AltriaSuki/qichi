#!/usr/bin/env python3
"""把 gen_screens.py 里的 IC 图标表转成 Kotlin，写进 QichiIcons.kt 的「生成区」。

用法：python3 tools/design/gen_icons.py
圆、圆角矩形都换成 path；单个元素自带的 stroke-width 单独成一条 path。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/design'))
from gen_screens import IC  # noqa: E402

TARGET = ROOT / 'android/app/src/main/java/app/qichi/core/designsystem/icon/QichiIcons.kt'
BEGIN = '    // ── 生成区开始（tools/design/gen_icons.py，不要手改） ──'
END = '    // ── 生成区结束 ──'

# IC 名字 → Kotlin 名字（没列的按首字母大写）
NAMES = {
    'chev': 'ChevronRight', 'chevl': 'ChevronLeft', 'cal': 'Calendar', 'today': 'CalendarToday',
    'list': 'BulletList', 'photo': 'Image', 'qna': 'Qna', 'toc': 'Toc',
}


def num(v):
    v = round(float(v), 3)
    return str(int(v)) if v == int(v) else str(v)


def attrs(s):
    return dict(re.findall(r'([\w-]+)="([^"]*)"', s))


def to_path(tag, a):
    if tag == 'path':
        return a['d']
    if tag == 'circle':
        cx, cy, r = (float(a[k]) for k in ('cx', 'cy', 'r'))
        return f'M{num(cx - r)} {num(cy)}a{num(r)} {num(r)} 0 1 0 {num(2 * r)} 0a{num(r)} {num(r)} 0 1 0 {num(-2 * r)} 0'
    if tag == 'rect':
        x, y, w, h = (float(a[k]) for k in ('x', 'y', 'width', 'height'))
        r = float(a.get('rx', 0))
        if r == 0:
            return f'M{num(x)} {num(y)}h{num(w)}v{num(h)}h{num(-w)}z'
        return (f'M{num(x + r)} {num(y)}h{num(w - 2 * r)}a{num(r)} {num(r)} 0 0 1 {num(r)} {num(r)}'
                f'v{num(h - 2 * r)}a{num(r)} {num(r)} 0 0 1 {num(-r)} {num(r)}h{num(-(w - 2 * r))}'
                f'a{num(r)} {num(r)} 0 0 1 {num(-r)} {num(-r)}v{num(-(h - 2 * r))}a{num(r)} {num(r)} 0 0 1 {num(r)} {num(-r)}z')
    raise ValueError(tag)


def kotlin(name, svg):
    parts = []  # [(width or None, d)]
    for tag, rest in re.findall(r'<(\w+)([^>]*)/>', svg):
        a = attrs(rest)
        w = a.get('stroke-width')
        d = to_path(tag, a)
        if parts and parts[-1][0] == w:
            parts[-1] = (w, parts[-1][1] + d)
        else:
            parts.append((w, d))
    kname = NAMES.get(name, name[:1].upper() + name[1:])
    args = ', '.join(f'"{d}"' if w is None else f'"{d}" to {num(w)}f' for w, d in parts)
    return f'    val {kname}: ImageVector by lazy {{ icon("{name}", {args}) }}'


def main():
    src = TARGET.read_text()
    start, end = src.index(BEGIN), src.index(END)
    body = '\n'.join(kotlin(n, s) for n, s in IC.items())
    TARGET.write_text(src[:start] + BEGIN + '\n' + body + '\n' + src[end:])
    print(f'{len(IC)} 个图标写入 {TARGET.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
