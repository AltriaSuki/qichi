#!/usr/bin/env python3
"""整理 Kotlin 文件的 import：排序、去重、删掉没用到的。

用法：python3 tools/kt_imports.py 文件.kt [更多文件…]
按名字在正文里找有没有用到；属性委托要用的 getValue / setValue / provideDelegate 总是保留。
"""
import re
import sys

KEEP = {'*', 'getValue', 'setValue', 'provideDelegate'}

for path in sys.argv[1:]:
    lines = open(path).read().split('\n')
    idx = [i for i, l in enumerate(lines) if l.startswith('import ')]
    if not idx:
        continue
    a, b = idx[0], idx[-1]
    imports = [l for l in lines[a:b + 1] if l.startswith('import ')]
    rest = '\n'.join(lines[:a] + lines[b + 1:])
    keep = []
    for line in sorted(set(imports)):
        name = line.split(' as ')[-1] if ' as ' in line else line.split()[-1].split('.')[-1]
        if name in KEEP or re.search(r'\b' + re.escape(name) + r'\b', rest):
            keep.append(line)
    lines[a:b + 1] = keep
    open(path, 'w').write('\n'.join(lines))
