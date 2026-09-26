# -*- coding: utf-8 -*-
"""生成设计稿「新方向」页（design/screens/New-*.dc.html）。

用法：python3 tools/design/gen_screens.py，然后把改动的文件同步到画布。

「新方向」第二版：字和层次参考 Day One、iA Writer、Bear；颜色沿用晨雾；加一套统一的装饰。

装饰只从这一套里取：胶带、拍立得、邮票与邮戳、印章、蜡封、绿萝线描、荧光笔、手写字、图标色块、贴纸。
"""
import math
import os
import re
from html.parser import HTMLParser

# 读写仓库里的 design/screens/（晨雾那几张是插画和占位图的来源）
PROJ = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'design', 'screens')


def rd(name):
    with open(os.path.join(PROJ, name), encoding='utf-8') as f:
        return f.read()


OUT = {}


def wr(name, text):
    OUT[name] = text
    with open(os.path.join(PROJ, name), 'w', encoding='utf-8') as f:
        f.write(text)


SANS = "'Noto Sans SC','PingFang SC','Source Han Sans SC','Microsoft YaHei',sans-serif"
MONO = "'IBM Plex Mono','Noto Sans SC',ui-monospace,monospace"
SERIF = "'Noto Serif SC','Songti SC','STSong',serif"
HAND = "'Long Cang','Kaiti SC','STKaiti',cursive"
FONTS = ('<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@300;400;500&family=Long+Cang'
         '&family=Noto+Sans+SC:wght@400;500;600;700&family=Noto+Serif+SC:wght@400;600&display=swap" rel="stylesheet">')


def svg_at(src, marker):
    i = src.index(marker)
    return src[src.rindex('<svg', 0, i):src.index('</svg>', i) + len('</svg>')]


def grain_of(src):
    return re.search(r'<feColorMatrix type="matrix" values="([^"]+)"', src).group(1)


SRC = {k: rd(f) for k, f in [('day', 'Main.dc.html'), ('dawn', 'Sky-Dawn.dc.html'), ('dusk', 'Sky-Dusk.dc.html'),
                             ('night', 'Sky-Night.dc.html')]}
HERO = {k: svg_at(v, 'aria-label="房间主视觉"') for k, v in SRC.items()}
DOC = svg_at(rd('Review.dc.html'), 'aria-label="第二页')


def hexrgb(h):
    return int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16)


def rgba(h, a):
    r, g, b = hexrgb(h)
    return f'rgba({r},{g},{b},{a})'


def pal(name, bg, surface, paper, ink, muted, faint, line, line2, accent, A, B, on, card, dark=False):
    return dict(name=name, bg=bg, surface=surface, paper=paper, ink=ink, muted=muted, faint=faint, line=line,
                line2=line2, accent=accent, A=A, B=B, on=on, card=card, dark=dark, grain=grain_of(SRC[name]),
                fab='0 10px 26px rgba(0,0,0,.45)' if dark else '0 10px 26px rgba(42,47,53,.2)',
                lift='0 1px 0 rgba(0,0,0,.3),0 12px 28px rgba(0,0,0,.3)' if dark else f'0 1px 0 {line},0 12px 28px rgba(43,50,58,.08)')


DAY = pal('day', '#ECEBE6', 'rgba(255,255,255,.6)', '#F7F6F2', '#2A2F35', '#5D6167', '#9DA0A3', '#DAD9D3', '#C4C4BE',
          '#9A5552', '#A8625F', '#4F6B7A', '#FFFFFF', '#FBFAF7')
DAWN = pal('dawn', '#EEE7E4', 'rgba(255,255,255,.6)', '#F7F2F0', '#2E2F38', '#655E66', '#A69BA0', '#DDD3D1', '#C8BCBC',
           '#9A5552', '#A8625F', '#4F6B7A', '#FFFFFF', '#FCF9F8')
DUSK = pal('dusk', '#ECE3D6', 'rgba(255,255,255,.55)', '#F6F0E6', '#2F2B28', '#665D55', '#A89C8E', '#DDD2C3', '#C9BBA8',
           '#94523A', '#A55E45', '#4F6B7A', '#FFFFFF', '#FBF7F0')
NIGHT = pal('night', '#1B2027', 'rgba(255,255,255,.05)', '#222830', '#E3E6E4', '#9CA5AD', '#5F6973', '#2B323B', '#3A434D',
            '#D8A09C', '#D39A96', '#93AEBD', '#1B2027', '#29303A', dark=True)


def T(P, key, a):
    """某个颜色的淡色。"""
    return rgba(P[key], a)


# ---------------------------------------------------------------- 图标
IC = {
    'back': '<path d="M15 5l-7 7 7 7"/>',
    'search': '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.3-4.3"/>',
    'more': '<path d="M5.5 12h.01M12 12h.01M18.5 12h.01" stroke-width="2.6"/>',
    'plus': '<path d="M12 5v14M5 12h14"/>',
    'check': '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    'chev': '<path d="M9 5l7 7-7 7"/>',
    'chevl': '<path d="M15 5l-7 7 7 7"/>',
    'cal': '<rect x="4" y="5.5" width="16" height="14.5" rx="2"/><path d="M4 10h16M8.5 3.5v4M15.5 3.5v4"/>',
    'today': '<rect x="4" y="5.5" width="16" height="14.5" rx="2"/><path d="M4 10h16M8.5 3.5v4M15.5 3.5v4"/><path d="M12 15h.01" stroke-width="2.8"/>',
    'eye': '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
    'focus': '<path d="M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4"/>',
    'people': '<circle cx="9" cy="8.5" r="3"/><path d="M3.5 19.5c.8-3 3-4.6 5.5-4.6s4.7 1.6 5.5 4.6"/><circle cx="16.5" cy="9.5" r="2.4"/><path d="M15.6 14.7c2.2-.3 4.2 1 4.9 3.6"/>',
    'mood': '<circle cx="12" cy="12" r="8.5"/><path d="M8.6 14.2c1.9 2 4.9 2 6.8 0"/><path d="M9 10h.01M15 10h.01" stroke-width="2.4"/>',
    'qna': '<path d="M4.5 5.5h15v10.5h-8.5l-4.5 3.5V16H4.5z"/><path d="M10.2 9a1.9 1.9 0 1 1 2.7 1.7c-.6.3-.9.7-.9 1.3"/><path d="M12 13.9h.01" stroke-width="2.4"/>',
    'flag': '<path d="M6 20.5V4.5"/><path d="M6 5h11l-2.5 4 2.5 4H6"/>',
    'todo': '<circle cx="12" cy="12" r="8.5"/><path d="M8.5 12.2l2.4 2.4 4.6-4.9"/>',
    'idea': '<path d="M9.5 17.5h5M10.3 20.5h3.4"/><path d="M12 3.5a5.5 5.5 0 0 0-3.3 9.9c.6.5.8 1 .8 1.6v.5h5V15c0-.6.2-1.1.8-1.6A5.5 5.5 0 0 0 12 3.5z"/>',
    'pen': '<path d="M4.5 19.5l1-4.4L15.6 5a2.1 2.1 0 0 1 3 3L8.5 18.5z"/><path d="M13.8 6.8l3 3"/>',
    'mail': '<rect x="3.5" y="6" width="17" height="12" rx="2"/><path d="M4.5 7.5l7.5 5.5 7.5-5.5"/>',
    'archive': '<rect x="3.5" y="4.5" width="17" height="4" rx="1"/><path d="M5 8.5v10.5h14V8.5M10 12.5h4"/>',
    'sign': '<path d="M12 3v18"/><path d="M12 5h6l2 2.5-2 2.5h-6"/><path d="M12 12.5H6L4 15l2 2.5h6"/>',
    'timeline': '<path d="M7 4v16"/><path d="M11 7h8M11 12h8M11 17h5"/><circle cx="7" cy="7" r="1.6"/><circle cx="7" cy="12" r="1.6"/><circle cx="7" cy="17" r="1.6"/>',
    'book': '<path d="M12 6.5c-2-1.5-4.5-2-8-2v13c3.5 0 6 .5 8 2 2-1.5 4.5-2 8-2v-13c-3.5 0-6 .5-8 2z"/><path d="M12 6.5v13"/>',
    'review': '<path d="M6 3.5h8l4 4v13H6z"/><path d="M14 3.5v4h4"/><path d="M9 13.5l2 2 4-4"/>',
    'summary': '<path d="M5 6h14M5 10h14M5 14h9M5 18h6"/>',
    'lock': '<rect x="5.5" y="10.5" width="13" height="9.5" rx="2"/><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"/>',
    'send': '<path d="M12 19V5M6 11l6-6 6 6"/>',
    'clock': '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/>',
    'toc': '<path d="M5 7h14M5 12h14M5 17h9"/>',
    'bookmark': '<path d="M7 4h10v16l-5-4-5 4z"/>',
    'undo': '<path d="M9 8l-4 4 4 4"/><path d="M5 12h9.5a4.5 4.5 0 0 1 0 9H12"/>',
    'redo': '<path d="M15 8l4 4-4 4"/><path d="M19 12H9.5a4.5 4.5 0 0 0 0 9H12"/>',
    'photo': '<rect x="3.5" y="5.5" width="17" height="13" rx="2"/><circle cx="9" cy="10.5" r="1.5"/><path d="M20.5 16l-5-5-8.5 8"/>',
    'quote': '<path d="M9.5 7H5.5v5h4v1.2c0 1.8-1 3-2.8 3.8"/><path d="M18.5 7h-4v5h4v1.2c0 1.8-1 3-2.8 3.8"/>',
    'list': '<path d="M9.5 7h10M9.5 12h10M9.5 17h10"/><path d="M5 7h.01M5 12h.01M5 17h.01" stroke-width="2.6"/>',
    'checkbox': '<rect x="4.5" y="4.5" width="15" height="15" rx="3"/><path d="M8.5 12.2l2.4 2.4 4.6-4.9"/>',
    'repeat': '<path d="M17 4l3 3-3 3M20 7H8a4 4 0 0 0-4 4M7 20l-3-3 3-3M4 17h12a4 4 0 0 0 4-4"/>',
    'pin': '<path d="M9 4h6l-1 5 3 3H7l3-3z"/><path d="M12 12v8"/>',
    'sun': '<circle cx="12" cy="12" r="4"/><path d="M12 2.8v2.2M12 19v2.2M2.8 12h2.2M19 12h2.2M5.5 5.5l1.5 1.5M17 17l1.5 1.5M5.5 18.5L7 17M17 7l1.5-1.5"/>',
    'chat': '<path d="M6.5 4.5h11a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H11l-4.5 3.5v-3.5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z"/>',
    'rings': '<circle cx="9" cy="12" r="5.2"/><circle cx="15" cy="12" r="5.2"/>',
    'user': '<circle cx="12" cy="8.5" r="3.6"/><path d="M5 20c1-4 3.8-6 7-6s6 2 7 6"/>',
    'tag': '<path d="M4 4.5h7.5l8.5 8.5-7 7-8.5-8.5z"/><circle cx="8.5" cy="9" r="1.3"/>',
    'spark': '<path d="M12 3c.7 5.2 3.8 8.3 9 9-5.2.7-8.3 3.8-9 9-.7-5.2-3.8-8.3-9-9 5.2-.7 8.3-3.8 9-9z"/>',
    'moon': '<path d="M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5z"/>',
    'rain': '<path d="M7 14.5h10a4 4 0 0 0 .6-7.95A5.5 5.5 0 0 0 7 6a4.3 4.3 0 0 0 0 8.5z"/><path d="M9 18l-1 2.5M13 18l-1 2.5M17 18l-1 2.5"/>',
    'wave': '<path d="M3 10c2-2 4-2 6 0s4 2 6 0 4-2 6 0M3 15c2-2 4-2 6 0s4 2 6 0 4-2 6 0"/>',
    'drop': '<path d="M12 4c3 4 6 7.2 6 10.5a6 6 0 0 1-12 0C6 11.2 9 8 12 4z"/>',
    'flame': '<path d="M12 3c1 3.5 5 5.5 5 10a5 5 0 0 1-10 0c0-2.5 1.5-4 2.5-5 .3 1.6 1 2.5 2 3 .5-3-.5-5.5.5-8z"/>',
    'scribble': '<path d="M4 14c2-4 3 3 5-1s3 3 5-1 3 3 6-2"/>',
    'heart': '<path d="M12 19.5s-7-4.3-7-9.5a3.8 3.8 0 0 1 7-2.1A3.8 3.8 0 0 1 19 10c0 5.2-7 9.5-7 9.5z"/>',
    'here': '<path d="M12 21s6-5.4 6-10.5A6 6 0 0 0 6 10.5C6 15.6 12 21 12 21z"/><circle cx="12" cy="10.5" r="2.2"/>',
    'hourglass': '<path d="M7 3.5h10M7 20.5h10M8 3.5c0 4.5 4 5.5 4 8.5s-4 4-4 8.5M16 3.5c0 4.5-4 5.5-4 8.5s4 4 4 8.5"/>',
    'stop': '<rect x="7" y="7" width="10" height="10" rx="2"/>',
    'refresh': '<path d="M19 12a7 7 0 1 1-2.1-5"/><path d="M19 4.5V9h-4.5"/>',
    'arrow': '<path d="M5 12h14M13 6l6 6-6 6"/>',
}


def icon(name, size=22, sw=1.5):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="{sw}" '
            f'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" style="flex:none;">{IC[name]}</svg>')


# ---------------------------------------------------------------- 占位照片（几幅不同的小插画）
_n = [0]


def nid():
    _n[0] += 1
    return _n[0]


def scene(kind, w, h, radius=0, alt='照片'):
    n = nid()
    if kind == 'sea':
        body = (f'<defs><linearGradient id="sk{n}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#CBD9E0"/>'
                f'<stop offset="1" stop-color="#F3EEE5"/></linearGradient></defs><rect width="300" height="300" fill="url(#sk{n})"/>'
                '<circle cx="206" cy="112" r="34" fill="#F8E8D1"/><circle cx="206" cy="112" r="52" fill="#F8E8D1" fill-opacity=".35"/>'
                '<rect y="168" width="300" height="70" fill="#9DB2BB"/><rect y="198" width="300" height="40" fill="#86A0AB"/>'
                '<path d="M0 180h300M24 206h60M142 213h92M40 224h44M200 196h70" stroke="#FFFFFF" stroke-opacity=".55" '
                'stroke-width="2.4" stroke-linecap="round"/><path d="M0 238c60-16 120-16 180-5s90 10 120 4V300H0z" fill="#EADFCB"/>'
                '<circle cx="146" cy="246" r="4.2" fill="#3B4248"/><rect x="142" y="250" width="8.4" height="17" rx="3.5" fill="#3B4248"/>'
                '<circle cx="160" cy="247" r="3.8" fill="#3B4248"/><rect x="156.3" y="251" width="7.4" height="16" rx="3.2" fill="#3B4248"/>'
                '<path d="M60 60l8 4-8 4M84 44l6 3-6 3" stroke="#6D7F88" stroke-width="1.6" fill="none" stroke-linecap="round"/>')
    elif kind == 'window':
        body = ('<rect width="300" height="300" fill="#ECE3D7"/><rect x="18" y="18" width="40" height="230" fill="#E1D2C1"/>'
                '<rect x="70" y="36" width="176" height="150" fill="#DCE6EA"/><path d="M70 150c40-20 80-6 120-16s46-8 56-6V186H70z" fill="#C3CFD3"/>'
                '<rect x="70" y="36" width="176" height="150" fill="none" stroke="#FFFFFF" stroke-width="9"/>'
                '<path d="M158 36v150M70 111h176" stroke="#FFFFFF" stroke-width="6"/><rect x="50" y="186" width="220" height="12" fill="#FFFFFF"/>'
                '<path d="M126 150h48l-6 36h-36z" fill="#B98A6E"/>'
                '<path d="M150 150c-10-22-34-26-44-18M150 150c8-24 30-30 42-20M150 150c0-26-8-40-2-54M132 186c-10 30-6 56 8 84" '
                'stroke="#6E8A6B" stroke-width="2" fill="none"/>'
                + ''.join(f'<path transform="translate({x} {y}) rotate({a})" d="M0 0C-5 2-12 0-12-8C-12-15-5-19 0-25C5-19 12-15 12-8C12 0 5 2 0 0Z" fill="#7F9C7B"/>'
                          for x, y, a in [(108, 134, -60), (190, 130, 60), (148, 98, 0), (126, 212, -30), (138, 244, 20),
                                          (132, 270, -10), (170, 128, 10), (118, 150, -100)]))
    elif kind == 'shelf':
        books = ''
        x = 36
        cols = ['#9A5552', '#4F6B7A', '#D6C3A3', '#7F8C79', '#C9A98C', '#5D6167', '#B8876F', '#A9B8BF']
        for i, (wd, ht) in enumerate([(20, 70), (26, 80), (18, 64), (24, 76), (22, 70), (16, 60), (26, 82)]):
            books += (f'<rect x="{x}" y="{118 - ht}" width="{wd}" height="{ht}" rx="2" fill="{cols[i]}"/>'
                      f'<rect x="{x + 3}" y="{118 - ht + 10}" width="{wd - 6}" height="3" fill="#FFFFFF" fill-opacity=".45"/>')
            x += wd + 3
        body = ('<rect width="300" height="300" fill="#E9E2D6"/>' + books
                + '<rect x="238" y="44" width="12" height="74" rx="2" fill="#7F8C79" transform="rotate(14 244 118)"/>'
                '<rect x="20" y="118" width="260" height="9" fill="#A98C6F"/><rect x="20" y="228" width="260" height="9" fill="#A98C6F"/>'
                '<rect x="46" y="176" width="46" height="52" rx="2" fill="#FFFFFF" stroke="#C9B79C" stroke-width="4"/>'
                '<path d="M54 216l10-12 8 8 6-6 8 10z" fill="#9DB2BB"/><path d="M180 196h38l-4 32h-30z" fill="#B98A6E"/>'
                + ''.join(f'<path transform="translate({x2} {y2}) rotate({a})" d="M0 0C-5 2-12 0-12-8C-12-15-5-19 0-25C5-19 12-15 12-8C12 0 5 2 0 0Z" fill="#7F9C7B"/>'
                          for x2, y2, a in [(199, 196, 0), (186, 198, -50), (212, 198, 50), (200, 176, 10)]))
    elif kind == 'snow':
        body = (f'<defs><linearGradient id="sn{n}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#C9D4DC"/>'
                f'<stop offset="1" stop-color="#EEF1F2"/></linearGradient></defs><rect width="300" height="300" fill="url(#sn{n})"/>'
                '<path d="M0 170l70-80 50 50 60-80 120 110V300H0z" fill="#B8C4CA"/><path d="M130 110l50-50 40 38-18-6-14 12-16-10z" fill="#FFFFFF"/>'
                '<path d="M40 130l30-40 22 22-12-4-10 10z" fill="#FFFFFF"/><path d="M0 206c80-20 180-24 300-8V300H0z" fill="#F6F7F5"/>'
                '<rect x="118" y="178" width="70" height="46" fill="#8A6A56"/><path d="M110 182l43-34 43 34z" fill="#5B4A40"/>'
                '<path d="M110 182l43-34 43 34" stroke="#FFFFFF" stroke-width="4" fill="none" stroke-linejoin="round"/>'
                '<rect x="130" y="192" width="16" height="14" fill="#F1D29A"/><rect x="160" y="196" width="14" height="28" fill="#5B4A40"/>'
                '<path d="M218 226l14-40 14 40zM240 230l12-34 12 34zM58 230l12-34 12 34z" fill="#6E8277"/>'
                + ''.join(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="#FFFFFF" fill-opacity=".9"/>'
                          for cx, cy, r in [(30, 40, 2), (90, 70, 1.6), (160, 30, 2.2), (250, 60, 1.8), (210, 120, 1.6),
                                            (60, 150, 2), (270, 150, 2), (120, 90, 1.4)]))
    elif kind == 'dinner':
        body = ('<rect width="300" height="300" fill="#E9DFCF"/><path d="M0 0h300v300H0z" fill="none"/>'
                + ''.join(f'<path d="M{x} 0v300" stroke="#DCCDB6" stroke-width="10"/>' for x in (30, 90, 150, 210, 270))
                + '<circle cx="150" cy="150" r="84" fill="#FFFFFF"/><circle cx="150" cy="150" r="66" fill="#F4EFE7"/>'
                '<rect x="112" y="122" width="30" height="24" rx="7" fill="#8E4A3A"/><rect x="146" y="118" width="32" height="26" rx="7" fill="#9A5242"/>'
                '<rect x="124" y="150" width="30" height="24" rx="7" fill="#83412F"/><rect x="158" y="148" width="28" height="24" rx="7" fill="#944B3B"/>'
                '<circle cx="138" cy="130" r="3" fill="#7F9C7B"/><circle cx="170" cy="160" r="3" fill="#7F9C7B"/>'
                '<path d="M40 250l90-60M52 262l90-60" stroke="#7A5A44" stroke-width="5" stroke-linecap="round"/>'
                '<circle cx="250" cy="60" r="26" fill="#FFFFFF"/><circle cx="250" cy="60" r="18" fill="#4F6B7A" fill-opacity=".25"/>'
                '<circle cx="54" cy="56" r="30" fill="#F6E3BF" fill-opacity=".7"/><rect x="48" y="44" width="12" height="26" rx="3" fill="#FFFFFF"/>')
    elif kind == 'rain':
        body = ('<rect width="300" height="300" fill="#2B3440"/>'
                + ''.join(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="#F2D9A6" fill-opacity="{o}"/>'
                          for cx, cy, r, o in [(60, 80, 26, .25), (60, 80, 8, .9), (180, 60, 30, .2), (180, 60, 9, .85),
                                               (260, 110, 22, .2), (260, 110, 7, .8)])
                + '<rect y="210" width="300" height="90" fill="#222A34"/><path d="M40 230h40M150 250h60M230 236h30" stroke="#F2D9A6" stroke-opacity=".35" stroke-width="3"/>'
                + ''.join(f'<path d="M{x} {y}l-8 22" stroke="#9FB0BD" stroke-opacity=".5" stroke-width="1.4"/>'
                          for x, y in [(20, 20), (70, 140), (120, 40), (150, 170), (200, 110), (240, 20), (280, 180), (100, 100), (230, 160)])
                + '<path d="M124 196a30 30 0 0 1 60 0z" fill="#9A5552"/><path d="M154 196v34" stroke="#1B2027" stroke-width="3"/>'
                '<rect x="146" y="204" width="16" height="34" rx="6" fill="#1B2027"/>')
    else:  # fog
        body = (f'<defs><linearGradient id="fg{n}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#D3D9DC"/>'
                f'<stop offset="1" stop-color="#EFEDE7"/></linearGradient></defs><rect width="300" height="300" fill="url(#fg{n})"/>'
                '<circle cx="214" cy="96" r="22" fill="#FFF9EE"/><path d="M0 150c22-12 40-22 62-18s34-14 58-8 38 20 64 8 42-22 66-12 28 12 50 8V300H0z" fill="#B8BFC1"/>'
                '<path d="M0 190c30-12 52-20 80-14s44 12 70-2 46-14 74-2 42 14 76 8V300H0z" fill="#9CA5A8"/>'
                '<path d="M0 224c26-8 56-14 84-6s56 12 86 0 58-10 86-2 26 6 44 4V300H0z" fill="#7E898D"/>')
    return (f'<span style="display:block;flex:none;width:{w}px;height:{h}px;border-radius:{radius}px;overflow:hidden">'
            f'<svg width="{w}" height="{h}" viewBox="0 0 300 300" preserveAspectRatio="xMidYMid slice" role="img" '
            f'aria-label="{alt}" style="display:block">{body}</svg></span>')


# ---------------------------------------------------------------- 装饰
def leaf(x, y, a, s, fill, stroke):
    return (f'<g transform="translate({x} {y}) rotate({a}) scale({s})"><path d="M0 -1C-3 1-9 0-9-6C-9-12-4-15 0-19C4-15 9-12 9-6C9 0 3 1 0-1Z" '
            f'fill="{fill}" stroke="{stroke}" stroke-width="1"/><path d="M0-1V-15" stroke="{stroke}" stroke-width=".8"/></g>')


def sprig(P, w=120, flip=False, key='B', op=1):
    """绿萝枝叶线描。"""
    col = P[key]
    fill, st = rgba(col, .16), rgba(col, .7)
    leaves = [(16, 7, -70, .8), (32, 12, 110, .9), (50, 21, -40, 1), (68, 21, 130, .85), (86, 14, -60, .95),
              (102, 10, 120, .8), (116, 15, -20, .7)]
    g = ''.join(leaf(x, y, a, s, fill, st) for x, y, a, s in leaves)
    tf = ' transform="translate(120 0) scale(-1 1)"' if flip else ''
    return (f'<svg width="{w}" height="{round(w * .4)}" viewBox="0 -14 120 48" aria-hidden="true" style="display:block;'
            f'overflow:visible;opacity:{op}"><g{tf}><path d="M4 8C30 4 44 26 70 22S104 6 116 16" fill="none" stroke="{st}" '
            f'stroke-width="1.2" stroke-linecap="round"/>{g}</g></svg>')


def marker(P, text, key='A', a=.24):
    """荧光笔划线。"""
    c = T(P, key, a)
    return (f'<span style="background:linear-gradient(transparent 56%,{c} 56%,{c} 90%,transparent 90%);padding:0 3px;'
            f'margin:0 -3px;-webkit-box-decoration-break:clone;box-decoration-break:clone">{text}</span>')


def hand(P, text, size=20, key='muted', rot=-3, extra=''):
    return (f'<span style="display:inline-block;font-family:{HAND};font-size:{size}px;line-height:1.2;color:{P[key]};'
            f'transform:rotate({rot}deg);{extra}">{text}</span>')


def tape(P, key='B', w=64, rot=-5, pos='top:-9px;left:50%;margin-left:-32px', a=.3):
    return (f'<span aria-hidden="true" style="position:absolute;{pos};z-index:3;width:{w}px;height:18px;'
            f'background-color:{T(P, key, a)};background-image:repeating-linear-gradient(90deg,rgba(255,255,255,.22) 0 2px,'
            f'transparent 2px 6px);transform:rotate({rot}deg);clip-path:polygon(0 0,100% 0,97% 25%,100% 50%,97% 75%,100% 100%,'
            f'0 100%,3% 75%,0 50%,3% 25%)"></span>')


def polaroid(P, ph, cap='', rot=-2, tp=''):
    capd = (f'<figcaption style="font-family:{HAND};font-size:17px;line-height:1.2;color:{P["muted"]};text-align:center;'
            f'padding-top:5px">{cap}</figcaption>' if cap else '')
    return (f'<figure style="position:relative;flex:none;margin:0;padding:7px 7px {5 if cap else 7}px;background:{P["card"]};'
            f'box-shadow:{P["lift"]};transform:rotate({rot}deg)">{ph}{capd}{tp}</figure>')


def stamp(P, ph, w, h, rot=3):
    dots = f'radial-gradient(circle,{P["bg"]} 2.4px,transparent 2.9px)'

    def strip(pos):
        return f'<span aria-hidden="true" style="position:absolute;{pos};background-image:{dots};background-size:8px 8px"></span>'

    return (f'<span style="position:relative;display:inline-block;flex:none;width:{w}px;height:{h}px;box-sizing:border-box;'
            f'padding:6px;background:{P["card"]};transform:rotate({rot}deg)"><span style="display:block;width:100%;height:100%;'
            f'overflow:hidden">{ph}</span>'
            + strip('left:0;right:0;top:-4px;height:8px') + strip('left:0;right:0;bottom:-4px;height:8px')
            + strip('top:0;bottom:0;left:-4px;width:8px') + strip('top:0;bottom:0;right:-4px;width:8px') + '</span>')


def postmark(P, top, date, s=66, rot=-14, key='accent', lines=True):
    c = T(P, key, .75)
    waves = (f'<svg width="54" height="26" viewBox="0 0 54 26" aria-hidden="true" style="display:block;margin-left:-6px">'
             + ''.join(f'<path d="M0 {y}c4.5-4 9-4 13.5 0s9 4 13.5 0 9-4 13.5 0 9 4 13.5 0" fill="none" stroke="{c}" stroke-width="1.3"/>'
                       for y in (5, 13, 21)) + '</svg>')
    ring = (f'<span style="display:inline-flex;flex-direction:column;align-items:center;justify-content:center;flex:none;'
            f'width:{s}px;height:{s}px;box-sizing:border-box;border-radius:50%;border:1.5px solid {c};box-shadow:inset 0 0 0 3px '
            f'transparent,inset 0 0 0 4px {T(P, key, .3)};color:{c};line-height:1.25"><span style="font-size:10px;'
            f'font-weight:600;letter-spacing:.2em;padding-left:.2em">{top}</span><span style="font-family:{MONO};font-size:9px">{date}'
            f'</span></span>')
    return (f'<span aria-hidden="true" style="display:inline-flex;align-items:center;transform:rotate({rot}deg)">{ring}{waves if lines else ""}</span>')


def seal(P, text='栖迟', s=38, rot=-6):
    return (f'<span aria-hidden="true" style="display:inline-flex;align-items:center;justify-content:center;flex:none;'
            f'width:{s}px;height:{s}px;box-sizing:border-box;border-radius:4px;background:{P["accent"]};color:{P["paper"]};'
            f'font-family:{SERIF};font-weight:600;font-size:{round(s * .32)}px;line-height:1.05;writing-mode:vertical-rl;'
            f'letter-spacing:.06em;transform:rotate({rot}deg);box-shadow:inset 0 0 0 2.5px {P["accent"]},inset 0 0 0 3.6px '
            f'rgba(255,255,255,.55)">{text}</span>')


def wax(P, ch, s=56, key='accent'):
    c = P[key]
    pts = []
    for i in range(28):
        ang = math.pi * 2 * i / 28
        r = 25.5 + (1.8 if i % 2 == 0 else -0.6) + (1.2 if i % 7 == 0 else 0)
        pts.append(f'{30 + r * math.cos(ang):.1f},{30 + r * math.sin(ang):.1f}')
    return (f'<svg width="{s}" height="{s}" viewBox="0 0 60 60" aria-hidden="true" style="flex:none;display:block">'
            f'<polygon points="{" ".join(pts)}" fill="{c}" stroke="{c}" stroke-width="3" stroke-linejoin="round"/>'
            f'<circle cx="30" cy="30" r="18" fill="none" stroke="#FFFFFF" stroke-opacity=".38" stroke-width="1.4"/>'
            f'<circle cx="23" cy="21" r="9" fill="#FFFFFF" fill-opacity=".12"/><text x="30" y="36" text-anchor="middle" '
            f'font-family="Noto Serif SC,serif" font-weight="600" font-size="17" fill="#FFFFFF" fill-opacity=".92">{ch}</text></svg>')


def tile(P, ic, key='accent', s=36):
    return (f'<span aria-hidden="true" style="display:inline-flex;align-items:center;justify-content:center;flex:none;'
            f'width:{s}px;height:{s}px;border-radius:{round(s * .3)}px;background:{T(P, key, .15)};color:{P[key]}">'
            f'{icon(ic, round(s * .56), 1.6)}</span>')


def sticker(P, text, key='accent', rot=-6, size=16):
    return (f'<span style="display:inline-flex;align-items:center;flex:none;padding:1px 10px 3px;border-radius:14px;'
            f'border:1.3px dashed {T(P, key, .6)};background:{T(P, key, .08)};color:{P[key]};font-family:{HAND};'
            f'font-size:{size}px;line-height:1.2;transform:rotate({rot}deg);white-space:nowrap">{text}</span>')


def watermark(P, text, size=130, pos='right:10px;top:-20px', font=None, a=.05):
    return (f'<span aria-hidden="true" style="position:absolute;{pos};z-index:-1;font-family:{font or MONO};'
            f'font-size:{size}px;font-weight:300;line-height:1;color:{T(P, "ink", a)};pointer-events:none">{text}</span>')


def ribbon(P, key='accent', right=26, h=46):
    return (f'<span aria-hidden="true" style="position:absolute;top:-4px;right:{right}px;z-index:2;width:16px;height:{h}px;'
            f'background:{P[key]};clip-path:polygon(0 0,100% 0,100% 100%,50% 78%,0 100%);box-shadow:0 2px 4px rgba(0,0,0,.15)"></span>')


def ruled(P, lh, top, margin=True, mx=40):
    ln = T(P, 'ink', .09 if not P['dark'] else .12)
    mg = T(P, 'accent', .4)
    layers = []
    if margin:
        layers.append(f'linear-gradient(90deg,transparent {mx}px,{mg} {mx}px,{mg} {mx + 1}px,transparent {mx + 1}px)')
    layers.append(f'repeating-linear-gradient(180deg,transparent 0,transparent {lh - 1}px,{ln} {lh - 1}px,{ln} {lh}px)')
    pos = ('0 0,' if margin else '') + f'0 {top}px'
    return f'background-color:{P["paper"]};background-image:{",".join(layers)};background-position:{pos}'


def dots10(P, n, key):
    return ''.join(f'<span style="width:7px;height:7px;border-radius:50%;background:{P[key] if i < n else T(P, key, .18)}"></span>'
                   for i in range(10))


# ---------------------------------------------------------------- 基础组件
def M(text, size=13, color=None, weight=400):
    c = f'color:{color};' if color else ''
    return f'<span style="font-family:{MONO};font-size:{size}px;font-weight:{weight};{c}">{text}</span>'


def aimark(P, size=13):
    return (f'<span style="display:inline-flex;align-items:center;gap:3px;flex:none;color:{P["B"]};line-height:1">'
            f'{icon("spark", size, 1.6)}<span style="font-family:{MONO};font-size:{size}px;font-weight:500">AI</span></span>')


def ref(P, n):
    return (f'<a href="#" aria-label="依据 {n}" style="display:inline-flex;align-items:center;justify-content:center;min-width:22px;'
            f'height:19px;margin:0 2px;padding:0 5px;box-sizing:border-box;border-radius:10px;background:{T(P, "B", .14)};'
            f'color:{P["B"]};font-family:{MONO};font-size:11px;font-weight:500;text-decoration:none;vertical-align:2px">{n}</a>')


def refnum(P, n):
    """不可点的依据编号（放在已经可点的整行里）。"""
    return (f'<span style="display:inline-flex;align-items:center;justify-content:center;flex:none;min-width:22px;height:19px;'
            f'padding:0 5px;box-sizing:border-box;border-radius:10px;background:{T(P, "B", .14)};color:{P["B"]};'
            f'font-family:{MONO};font-size:11px;font-weight:500">{n}</span>')


def mark(P, ch, s=20, hollow=False):
    col = P['A'] if ch == '栖' else P['B']
    fs = max(8, round(s * .46))
    look = (f'background:transparent;color:{col};border:1px dashed {col}' if hollow else f'background:{col};color:{P["on"]}')
    return (f'<span aria-hidden="true" style="display:inline-flex;align-items:center;justify-content:center;flex:none;'
            f'box-sizing:border-box;width:{s}px;height:{s}px;border-radius:50%;font-family:{SANS};font-weight:500;'
            f'font-size:{fs}px;line-height:1;{look}">{ch}</span>')


def pair(P, s=20, ring=None):
    return (f'<span style="display:inline-flex;flex:none">{mark(P, "栖", s)}<span style="margin-left:-{max(4, s // 4)}px;'
            f'display:inline-flex;border-radius:50%;box-shadow:0 0 0 2px {ring or P["bg"]}">{mark(P, "迟", s)}</span></span>')


def who(P, w, s=18, ring=None):
    return pair(P, s, ring) if w == 'both' else mark(P, w, s)


def btn_icon(P, label, name, active=False):
    inner = icon(name)
    if active:
        inner = (f'<span style="display:inline-flex;align-items:center;justify-content:center;width:38px;height:38px;'
                 f'border-radius:50%;background:{T(P, "accent", .14)}">{inner}</span>')
    return (f'<button type="button" aria-label="{label}"'
            f'{pressed(active)} style="display:inline-flex;align-items:center;justify-content:center;flex:none;width:44px;'
            f'height:44px;border:0;padding:0;background:transparent;color:{P["accent"] if active else P["ink"]};'
            f'font-family:inherit">{inner}</button>')


def pressed(on):
    return ' aria-pressed="true"' if on else ''


def back(P, href):
    return (f'<a href="{href}" aria-label="返回" style="display:inline-flex;align-items:center;justify-content:center;flex:none;'
            f'width:44px;height:44px;color:{P["ink"]}">{icon("back")}</a>')


def bar1(P, title, trailing='', hnote='', side='', top=40, orn=True):
    """主页：顶栏一行 + 大标题（荧光笔）+ 一句手写。"""
    handh = hand(P, hnote, 22, 'muted', -4, 'margin-left:12px') if hnote else ''
    ornh = (f'<span aria-hidden="true" style="position:absolute;right:-10px;top:{top + 50}px;pointer-events:none">'
            f'{sprig(P, 132, True)}</span>' if orn and not side else '')
    return (f'<header style="position:relative;flex:none;padding:{top}px 28px 12px">{ornh}<div style="position:relative;'
            f'height:44px;display:flex;align-items:center;justify-content:flex-end;gap:4px">{trailing}</div>'
            f'<div style="position:relative;display:flex;align-items:flex-end;gap:16px;margin-top:6px"><div style="flex:1;'
            f'min-width:0;display:flex;align-items:baseline;flex-wrap:wrap"><h1 style="margin:0;font-size:30px;font-weight:600;'
            f'line-height:1.3;letter-spacing:.02em">{marker(P, title)}</h1>{handh}</div>{side}</div></header>')


def bar2(P, back_href, title, ic, key, trailing='', hnote='', top=40):
    """功能首页：返回 + 图标一行；下面是功能色块 + 大标题。"""
    handh = hand(P, hnote, 20, 'muted', -4, 'margin-left:4px') if hnote else ''
    return (f'<header style="position:relative;flex:none;padding:{top}px 14px 12px 8px"><div style="height:44px;display:flex;'
            f'align-items:center">{back(P, back_href)}<span style="flex:1"></span>{trailing}</div><div style="display:flex;'
            f'align-items:center;gap:12px;margin:6px 20px 0">{tile(P, ic, key, 40)}<h1 style="margin:0;font-size:28px;'
            f'font-weight:600;line-height:1.3;letter-spacing:.02em">{marker(P, title, key)}</h1>{handh}</div></header>')


def bar3(P, back_href, feature, ic, key, title, trailing='', top=40):
    """单项：小字功能名（带功能色）+ 这一项的名字。"""
    return (f'<header style="flex:none;display:flex;align-items:center;gap:2px;padding:{top}px 14px 8px 8px;'
            f'min-height:{top + 52}px;box-sizing:border-box">{back(P, back_href)}<div style="flex:1;min-width:0;display:flex;'
            f'flex-direction:column;padding-left:2px"><span style="display:inline-flex;align-items:center;gap:5px;font-size:12px;'
            f'font-weight:700;line-height:1.4;letter-spacing:.04em;color:{P[key]}">{icon(ic, 13, 1.8)}{feature}</span>'
            f'<h1 style="margin:0;font-size:17px;font-weight:600;line-height:1.45;white-space:nowrap;overflow:hidden;'
            f'text-overflow:ellipsis">{title}</h1></div>{trailing}</header>')


def tabs(P, items, sel, margin='0 28px 12px'):
    """分段切换。"""
    out = []
    for t in items:
        on = t == sel
        look = (f'background:{P["card"]};color:{P["ink"]};font-weight:600;box-shadow:0 1px 3px rgba(0,0,0,.1)' if on
                else f'background:transparent;color:{P["muted"]};font-weight:500')
        out.append(f'<button type="button" role="tab" aria-selected="{"true" if on else "false"}" style="flex:1;min-height:38px;'
                   f'padding:0 10px;border:0;border-radius:19px;{look};font-family:inherit;font-size:14px">{t}</button>')
    return (f'<div role="tablist" style="flex:none;display:flex;gap:2px;margin:{margin};padding:3px;border-radius:22px;'
            f'background:{T(P, "ink", .06)}">{"".join(out)}</div>')


def label(P, text, right='', ic=None, key='accent'):
    icn = f'<span style="display:inline-flex;color:{P[key]}">{icon(ic, 15, 1.8)}</span>' if ic else ''
    lead = (f'<span aria-hidden="true" style="flex:1;height:4px;background-image:radial-gradient(circle,{P["line2"]} 1px,'
            f'transparent 1.4px);background-size:6px 4px;background-repeat:repeat-x"></span>')
    return (f'<div style="display:flex;align-items:center;gap:8px;margin-bottom:8px">{icn}<h2 style="margin:0;font-size:13px;'
            f'font-weight:700;line-height:1.5;letter-spacing:.04em;color:{P["muted"]}">{text}</h2>{lead}{right}</div>')


def pill(P, text, ic=None, key='accent'):
    icn = f'<span style="display:inline-flex;color:{P[key]}">{icon(ic, 16, 1.7)}</span>' if ic else ''
    return (f'<button type="button" style="display:inline-flex;align-items:center;gap:6px;min-height:44px;padding:0 15px;border:0;'
            f'border-radius:22px;background:{P["surface"]};box-shadow:inset 0 0 0 1px {T(P, "ink", .06)};color:{P["ink"]};'
            f'font-family:inherit;font-size:14px;font-weight:500">{icn}{text}</button>')


def primary(P, text, full=False, small=False):
    h = 40 if small else 48
    return (f'<button type="button" style="display:inline-flex;align-items:center;justify-content:center;gap:6px;flex:none;'
            f'{"width:100%;" if full else ""}min-height:{h}px;padding:0 {16 if small else 22}px;border:0;border-radius:{h // 2}px;'
            f'background:{P["ink"]};color:{P["bg"]};font-family:inherit;font-size:15px;font-weight:500;letter-spacing:.04em">'
            f'{text}</button>')


def outline(P, text):
    return (f'<button type="button" style="display:inline-flex;align-items:center;flex:none;min-height:40px;padding:0 16px;'
            f'border:1px solid {P["line2"]};border-radius:20px;background:transparent;color:{P["ink"]};font-family:inherit;'
            f'font-size:14px;font-weight:500">{text}</button>')


def act(P, text, color=None):
    return (f'<button type="button" style="display:inline-flex;align-items:center;flex:none;min-height:44px;padding:0 6px;'
            f'border:0;background:transparent;color:{color or P["accent"]};font-family:inherit;font-size:15px;font-weight:500">'
            f'{text}</button>')


def fab(P, text, href='#'):
    return (f'<a href="{href}" style="position:absolute;right:24px;bottom:28px;z-index:5;display:inline-flex;align-items:center;'
            f'gap:8px;min-height:50px;padding:0 22px 0 18px;border-radius:25px;background:{P["ink"]};color:{P["bg"]};'
            f'text-decoration:none;font-size:15px;font-weight:500;letter-spacing:.04em;box-shadow:{P["fab"]}">'
            f'{icon("plus", 18, 1.8)}<span>{text}</span></a>')


def checkbox(P, done=False, s=22):
    if done:
        vis = (f'<span aria-hidden="true" style="display:inline-flex;align-items:center;justify-content:center;width:{s}px;'
               f'height:{s}px;border-radius:50%;background:{P["A"]};color:{P["on"]}">{icon("check", s - 8, 2)}</span>')
    else:
        vis = (f'<span aria-hidden="true" style="width:{s}px;height:{s}px;box-sizing:border-box;border-radius:50%;'
               f'border:1.4px solid {P["muted"]}"></span>')
    return (f'<span style="position:relative;display:inline-flex;flex:none;width:{s}px;height:{s}px"><input type="checkbox"'
            f'{" checked" if done else ""} style="position:absolute;left:0;top:0;width:100%;height:100%;margin:0;opacity:0">{vis}</span>')


def todo(P, title, right='', meta='', done=False):
    tstyle = f'color:{P["faint"]};text-decoration:line-through;text-decoration-color:{P["faint"]}' if done else ''
    metah = (f'<span style="display:flex;align-items:center;flex-wrap:wrap;gap:6px;font-size:12px;color:{P["muted"]};'
             f'line-height:1.6">{meta}</span>' if meta else '')
    return (f'<label style="display:flex;align-items:{"flex-start" if meta else "center"};gap:14px;min-height:50px;'
            f'box-sizing:border-box;padding:6px 0"><span style="display:inline-flex;{"padding-top:2px;" if meta else ""}">'
            f'{checkbox(P, done)}</span><span style="flex:1;min-width:0;display:flex;flex-direction:column"><span style="font-size:16px;'
            f'line-height:1.55;{tstyle}">{title}</span>{metah}</span><span style="display:inline-flex;align-items:center;gap:10px;'
            f'flex:none;font-size:13px;color:{P["muted"]}">{right}</span></label>')


def dotsep(P):
    return f'<span aria-hidden="true" style="color:{P["faint"]}">·</span>'


def planmeta(P, text):
    return f'<span style="display:inline-flex;align-items:center;gap:4px">{icon("flag", 12)}{text}</span>'


def stage(P, states, labels, mt=14):
    parts = []
    for i, st in enumerate(states):
        if st == 'done':
            node = f'<span style="width:8px;height:8px;border-radius:50%;background:{P["ink"]};flex:none"></span>'
        elif st == 'cur':
            node = (f'<span style="width:12px;height:12px;border-radius:50%;background:{P["A"]};'
                    f'box-shadow:0 0 0 5px {T(P, "A", .16)};flex:none"></span>')
        else:
            node = (f'<span style="width:8px;height:8px;box-sizing:border-box;border-radius:50%;border:1.3px solid {P["muted"]};'
                    f'flex:none"></span>')
        parts.append(node)
        if i < len(states) - 1:
            ln = (f'height:2px;background:{P["ink"]};opacity:.55' if st == 'done' else
                  f'height:2px;background-image:radial-gradient(circle,{P["line2"]} 1px,transparent 1.3px);background-size:6px 2px')
            parts.append(f'<span style="flex:1;{ln}"></span>')
    al = ['left', 'center', 'right']
    labs = ''.join(f'<span style="flex:1;text-align:{al[i]};font-weight:{700 if states[i] == "cur" else 400};'
                   f'color:{P["ink"] if states[i] == "cur" else P["muted"]}">{lb}</span>' for i, lb in enumerate(labels))
    return (f'<div style="display:flex;align-items:center;margin:{mt}px 3px 8px">{"".join(parts)}</div>'
            f'<div style="display:flex;font-size:13px">{labs}</div>')


def nextstep(P, text):
    return (f'<div style="display:flex;align-items:baseline;gap:10px;margin-top:10px"><span style="flex:none;font-size:12px;'
            f'font-weight:700;color:{P["accent"]}">下一步</span><span style="font-size:15px">{text}</span></div>')


def tag(P, text, key='accent'):
    return (f'<span style="display:inline-block;padding:0 7px;border-radius:10px;background:{T(P, key, .12)};color:{P[key]};'
            f'font-family:{MONO};font-size:12.5px;font-weight:500;line-height:1.75;white-space:nowrap">{text}</span>')


def chips(P, items, sel, pad='0 28px 12px'):
    out = []
    for t in items:
        on = t == sel
        look = (f'background:{P["ink"]};color:{P["bg"]}' if on else
                f'background:{P["surface"]};color:{P["ink"]};box-shadow:inset 0 0 0 1px {T(P, "ink", .06)}')
        out.append(f'<button type="button" aria-pressed="{"true" if on else "false"}" style="flex:none;display:inline-flex;'
                   f'align-items:center;min-height:44px;padding:0 16px;border:0;border-radius:22px;{look};font-family:inherit;'
                   f'font-size:14px;font-weight:500">{t}</button>')
    return (f'<div style="flex:none;display:flex;gap:8px;padding:{pad};overflow:hidden;-webkit-mask-image:linear-gradient(90deg,'
            f'#000 86%,transparent);mask-image:linear-gradient(90deg,#000 86%,transparent)">{"".join(out)}</div>')


def sharebar(P, a):
    return (f'<span role="img" aria-label="阿栖写了 {a}%" style="display:inline-flex;flex:none;width:40px;height:5px;'
            f'border-radius:3px;overflow:hidden"><span style="width:{a}%;background:{P["A"]}"></span>'
            f'<span style="flex:1;background:{P["B"]}"></span></span>')


def divtop(P, first):
    return '' if first else f'border-top:1px dashed {P["line2"]};'


TABS = [('今天', 'sun', 'New-Today.dc.html'), ('聊天', 'chat', 'New-Chat.dc.html'),
        ('一起', 'rings', 'New-Together.dc.html'), ('我的', 'user', 'New-Me.dc.html')]


def tabbar(P, active):
    out = []
    for name, ic, href in TABS:
        on = name == active
        badge = (f'<span style="position:absolute;top:-3px;right:6px;display:inline-flex;align-items:center;justify-content:center;'
                 f'min-width:16px;height:16px;padding:0 4px;box-sizing:border-box;border-radius:8px;background:{P["accent"]};'
                 f'color:{P["bg"]};font-family:{MONO};font-size:10px;font-weight:500;box-shadow:0 0 0 2px {P["bg"]}">2</span>'
                 if name == '聊天' and not on else '')
        out.append(f'<a href="{href}"{cur(on)} style="display:flex;flex-direction:column;'
                   f'align-items:center;justify-content:center;gap:3px;min-height:62px;text-decoration:none;'
                   f'color:{P["ink"] if on else P["muted"]};font-size:12px;font-weight:{600 if on else 500}">'
                   f'<span style="position:relative;display:inline-flex;align-items:center;justify-content:center;width:56px;'
                   f'height:30px;border-radius:15px;background:{T(P, "A", .18) if on else "transparent"}">{icon(ic, 22)}{badge}'
                   f'</span><span style="letter-spacing:.06em">{name}</span></a>')
    return (f'<nav aria-label="主导航" style="flex:none;display:grid;grid-template-columns:repeat(4,minmax(0,1fr));'
            f'border-top:1px solid {P["line"]};padding:4px 8px 12px;background:{P["bg"]}">{"".join(out)}</nav>')


def cur(on):
    return ' aria-current="page"' if on else ''


def scroll(inner, pad='4px 28px 32px', gap=26, extra=''):
    return (f'<main style="flex:1;min-height:0;overflow-y:auto;display:flex;flex-direction:column;gap:{gap}px;'
            f'padding:{pad};{extra}">{inner}</main>')


def grain(P):
    return (f'<svg aria-hidden="true" width="100%" height="100%" style="position:absolute;left:0;top:0;z-index:-2;'
            f'pointer-events:none"><filter id="grain" x="0" y="0" width="100%" height="100%"><feTurbulence type="fractalNoise" '
            f'baseFrequency=".9" numOctaves="2" stitchTiles="stitch"/><feColorMatrix type="matrix" values="{P["grain"]}"/>'
            f'</filter><rect width="100%" height="100%" filter="url(#grain)"/></svg>')


def page(P, title, body, w=390, h=844, root_bg=None, flex=True):
    bg = root_bg or P['bg']
    root = (f'width:{w}px;height:{h}px;box-sizing:border-box;{"display:flex;flex-direction:column;" if flex else ""}'
            f'position:relative;z-index:0;background:{bg};color:{P["ink"]};font-family:{SANS};font-size:16px;font-weight:400;'
            f'line-height:1.7;overflow:hidden')
    props = '{"$preview":{"width":%d,"height":%d}}' % (w, h)
    return ('<!doctype html>\n<html lang="zh-CN">\n<head>\n<meta charset="utf-8">\n'
            f'<title>{title}</title>\n<script src="./support.js"></script>\n</head>\n<body>\n<x-dc>\n<helmet>\n{FONTS}\n'
            f'<style>\nbody{{margin:0;background:{bg}}}\n</style>\n</helmet>\n<div style="{root}">\n{grain(P)}{body}\n</div>\n'
            f'</x-dc>\n<script type="text/x-dc" data-dc-script data-props=\'{props}\'>\n'
            'class Component extends DCLogic {\nrenderVals() {\nreturn {};\n}\n}\n</script>\n</body>\n</html>\n')


def card(P, inner, key=None, rot=0, pad='16px 18px', radius=14, extra=''):
    bgc = f'linear-gradient({T(P, key, .1)},{T(P, key, .1)}),{P["card"]}' if key else P['card']
    return (f'<div style="position:relative;padding:{pad};border-radius:{radius}px;background:{bgc};box-shadow:{P["lift"]};'
            f'{"transform:rotate(%sdeg);" % rot if rot else ""}{extra}">{inner}</div>')


# ================================================================ 主标签
GREET = {'day': '午后好', 'dawn': '早安', 'dusk': '黄昏了', 'night': '晚安'}


def b_today(P, fname, title, H):
    top = (f'<div style="position:absolute;left:28px;right:28px;top:40px;height:44px;display:flex;align-items:center;'
           f'justify-content:space-between">{hand(P, GREET[P["name"]], 26, "ink", -5)}{pair(P, 26)}</div>')
    date = (f'<div style="position:absolute;left:22px;right:28px;bottom:16px;display:flex;align-items:flex-end;gap:14px">'
            f'<span aria-hidden="true" style="font-family:{MONO};font-size:96px;font-weight:300;line-height:.76;'
            f'letter-spacing:-.05em">24</span><div style="flex:1"><h1 aria-label="九月二十四日" style="margin:0;font-size:22px;'
            f'font-weight:600;line-height:1.3">九月</h1><div style="font-size:14px;color:{P["muted"]};line-height:1.5">星期四 · '
            f'{M("2026", 14)}</div></div><span style="margin-bottom:6px">{seal(P, "栖迟", 40, -8)}</span></div>')
    header = f'<header style="position:relative;flex:none;height:430px">{HERO[P["name"]]}{top}{date}</header>'

    def mcard(w, name, word, ic, n, key, need=False):
        stk = (f'<span style="position:absolute;right:-6px;top:-12px">{sticker(P, "需要安慰", "accent", 6, 16)}</span>'
               if need else '')
        return (f'<div style="position:relative;min-width:0;padding:14px 14px 12px;border-radius:14px;background:linear-gradient('
                f'{T(P, key, .12)},{T(P, key, .12)}),{P["card"]};box-shadow:{P["lift"]}">{stk}<div style="display:flex;'
                f'align-items:center;gap:8px">{mark(P, w, 20)}<span style="font-size:13px;font-weight:500;color:{P["muted"]}">{name}'
                f'</span><span style="flex:1"></span><span style="display:inline-flex;color:{P[key]}">{icon(ic, 22, 1.6)}</span></div>'
                f'<div style="display:flex;align-items:baseline;gap:8px;margin-top:6px"><span style="font-size:26px;font-weight:700;'
                f'line-height:1.35">{word}</span>{M(n, 15, P["muted"])}</div><div style="display:flex;gap:4px;margin-top:8px">'
                f'{dots10(P, int(n), key)}</div></div>')

    mood = (label(P, '心情', ic='mood', key='A') + '<div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));'
            f'gap:12px">{mcard("迟", "小迟", "疲惫", "moon", "6", "B", True)}{mcard("栖", "我", "平静", "wave", "7", "A")}</div>'
            f'<p style="display:flex;gap:8px;align-items:baseline;margin:14px 0 12px;font-size:15px;line-height:1.7;'
            f'color:{P["muted"]}">{mark(P, "迟", 18)}<span>“今天连开了三个会，晚上想安静待着。”</span></p>'
            f'<div style="display:flex;flex-wrap:wrap;gap:8px">{pill(P, "我在这里")}{pill(P, "给你一个拥抱")}{pill(P, "等你准备好")}</div>')
    qna = (f'<a href="New-Qna.dc.html" style="position:relative;display:block;padding:22px 22px 8px;border-radius:6px;'
           f'background:{P["paper"]};box-shadow:{P["lift"]};color:{P["ink"]};text-decoration:none">{tape(P, "A", 70, -3)}'
           f'<span aria-hidden="true" style="position:absolute;right:16px;top:-6px;font-family:{SERIF};font-size:96px;line-height:1;'
           f'color:{T(P, "accent", .14)}">”</span><div style="display:flex;align-items:center;gap:6px"><span style="display:inline-flex;'
           f'color:{P["B"]}">{icon("qna", 15, 1.8)}</span><span style="font-size:12px;font-weight:700;letter-spacing:.04em;'
           f'color:{P["muted"]}">今日问答</span></div><p style="position:relative;margin:8px 0 0;font-size:19px;font-weight:700;'
           f'line-height:1.6">如果下个月有一整个周末空出来，你最想怎么过？</p><div style="display:flex;align-items:center;gap:8px;'
           f'min-height:44px;margin-top:4px">{mark(P, "栖", 20)}<span style="width:16px;height:1px;background:{P["line2"]}"></span>'
           f'{mark(P, "迟", 20, True)}{hand(P, "等小迟", 17, "muted", -3)}<span style="flex:1"></span><span style="font-size:15px;'
           f'font-weight:500;color:{P["accent"]}">我的回答</span></div></a>')
    ring = (f'<span style="display:inline-flex;align-items:center;gap:6px">{M("1/4", 12, P["muted"])}<svg width="18" height="18" '
            f'viewBox="0 0 36 36" aria-hidden="true"><circle cx="18" cy="18" r="14" fill="none" stroke="{P["line2"]}" '
            f'stroke-width="4"/><circle cx="18" cy="18" r="14" fill="none" stroke="{P["A"]}" stroke-width="4" '
            f'stroke-dasharray="22 88" stroke-linecap="round" transform="rotate(-90 18 18)"/></svg></span>')
    todos = (label(P, '待办', ring, 'todo', 'B')
             + todo(P, '取回干洗的外套', right='今天' + mark(P, '栖', 18))
             + todo(P, '订周六的餐位', right='今天' + pair(P, 18))
             + todo(P, '给阳台的绿萝换盆', right='周六' + mark(P, '迟', 18))
             + todo(P, '缴宽带费', right='昨天' + mark(P, '栖', 18), done=True))

    def srow(time, title, w, past):
        col = P['muted'] if past else P['ink']
        dot = (f'<span style="width:8px;height:8px;box-sizing:border-box;border-radius:50%;border:1.3px solid {P["muted"]};'
               f'background:{P["bg"]}"></span>' if past else
               f'<span style="width:10px;height:10px;border-radius:50%;background:{P["A"]};box-shadow:0 0 0 5px {T(P, "A", .16)}"></span>')
        box = (f'background:linear-gradient({T(P, "A", .1)},{T(P, "A", .1)}),{P["card"]};box-shadow:{P["lift"]};'
               if not past else '')
        return (f'<div style="display:flex;align-items:center;min-height:54px;margin:2px -12px;padding:0 12px;border-radius:12px;'
                f'{box}"><span style="width:60px;flex:none;font-family:{MONO};font-size:17px;color:{col}">{time}</span>'
                f'<span style="width:22px;flex:none;display:flex;justify-content:center;position:relative;z-index:1">{dot}</span>'
                f'<span style="flex:1;padding-left:12px;font-size:16px;color:{col}">{title}</span>{w}</div>')

    sched = (label(P, '安排', ic='cal', key='A') + f'<div style="position:relative"><span aria-hidden="true" style="position:absolute;'
             f'left:70.5px;top:29px;bottom:29px;width:0;border-left:1.5px dotted {P["line2"]}"></span>'
             + srow('10:00', '牙医复诊', mark(P, '迟', 18), True)
             + srow('19:30', '一起做饭', pair(P, 18, T(P, 'A', .1)), False) + '</div>')
    plan = (label(P, '进行中', ic='flag', key='accent') + f'<a href="New-Plan.dc.html" style="display:flex;gap:14px;'
            f'align-items:flex-start;color:{P["ink"]};text-decoration:none"><span style="position:relative;flex:none">'
            f'{scene("sea", 64, 64, 12, "海边")}{tape(P, "accent", 34, 18, "top:-6px;right:-10px", .35)}</span><div style="flex:1;'
            f'min-width:0"><div style="display:flex;align-items:center;gap:10px"><span style="flex:1;font-size:18px;font-weight:700;'
            f'line-height:1.5">秋天去一次海边</span>{mark(P, "迟", 20)}</div>'
            + stage(P, ['done', 'cur', 'todo'], ['选地方', '订住处', '出发前'], 10) + '</div></a>'
            + nextstep(P, '对比三家民宿的价格和交通'))
    otd = (label(P, '一年前的今天', ic='timeline', key='A')
           + f'<a href="New-Timeline.dc.html" style="position:relative;display:flex;gap:22px;align-items:center;'
           f'color:{P["ink"]};text-decoration:none;padding:8px 0 4px 6px">{stamp(P, scene("shelf", 84, 96), 96, 108, -3)}'
           f'<span style="position:absolute;left:70px;top:-6px">{postmark(P, "栖迟", "25.09.24", 60, -16)}</span>'
           f'<div style="flex:1;min-width:0;padding-top:24px"><p style="margin:0;font-size:16px;line-height:1.6">书架终于搬进来了。</p>'
           f'<div style="display:flex;align-items:center;gap:8px;margin-top:6px">{mark(P, "栖", 18)}'
           f'{hand(P, "阿栖 · 去年今天", 18, "muted", -2)}</div></div></a>')
    foot = (f'<div aria-hidden="true" style="display:flex;flex-direction:column;align-items:center;gap:6px;opacity:.9">'
            f'{sprig(P, 110)}{M("— 09.24 —", 12, P["faint"])}</div>')
    secs = (f'<div style="display:flex;flex-direction:column;gap:40px;margin-top:30px"><section style="padding:0 28px">{mood}'
            f'</section><div style="padding:0 16px">{qna}</div><section style="padding:0 28px">{todos}</section>'
            f'<section style="padding:0 28px">{sched}</section><section style="padding:0 28px">{plan}</section>'
            f'<section style="padding:0 28px">{otd}</section>{foot}</div>')
    body = (f'<main style="flex:1;min-height:0;overflow-y:auto;display:flex;flex-direction:column;padding-bottom:36px">'
            f'{header}{secs}</main>' + tabbar(P, '今天'))
    wr(fname, page(P, title, body, 390, H))


def chat_head(P):
    return (f'<header style="flex:none;display:flex;align-items:center;gap:12px;padding:40px 14px 8px 28px;min-height:92px;'
            f'box-sizing:border-box">{mark(P, "迟", 30)}<div style="display:flex;flex-direction:column"><h1 style="margin:0;'
            f'font-size:17px;font-weight:600;line-height:1.35">小迟</h1>{hand(P, "今天有点累", 16, "muted", -2)}</div>'
            f'<span style="flex:1"></span>{btn_icon(P, "搜索", "search")}</header>')


def other(P, inner):
    return (f'<div style="align-self:flex-start;max-width:78%;padding:10px 15px;border-radius:4px 18px 18px 18px;'
            f'background:{P["card"]};box-shadow:{P["lift"]};font-size:15px;line-height:1.65">{inner}</div>')


def mine(P, inner):
    return (f'<div style="align-self:flex-end;max-width:78%;padding:10px 15px;border-radius:18px 4px 18px 18px;'
            f'background:{T(P, "A", .16)};font-size:15px;line-height:1.65">{inner}</div>')


def chat_bottom(P, value='周六早上八点出发？', offline=True):
    off = (f'<div style="flex:none;display:flex;align-items:center;gap:8px;padding:4px 28px 0;font-size:12px;font-weight:500;'
           f'color:{P["accent"]}"><span style="width:6px;height:6px;border-radius:50%;background:{P["accent"]}"></span>离线</div>'
           if offline else '')
    comp = (f'<div style="flex:none;display:flex;align-items:center;gap:8px;padding:8px 16px 12px 10px">'
            f'{btn_icon(P, "添加图片或文件", "plus")}<div style="flex:1;min-width:0;display:flex;align-items:center;height:46px;'
            f'padding:0 6px 0 16px;border-radius:23px;background:{P["card"]};box-shadow:{P["lift"]}"><input aria-label="消息" '
            f'value="{value}" style="flex:1;min-width:0;height:44px;border:0;background:transparent;padding:0;font-family:inherit;'
            f'font-size:15px;color:{P["ink"]}"><button type="button" style="display:inline-flex;align-items:center;gap:4px;flex:none;'
            f'min-height:44px;padding:0 8px;border:0;background:transparent;color:{P["B"]};font-family:inherit;font-size:14px;'
            f'font-weight:500">{icon("spark", 15, 1.6)}问 AI</button></div><button type="button" aria-label="发送" style="flex:none;'
            f'width:46px;height:46px;border:0;border-radius:50%;background:{P["ink"]};color:{P["bg"]};display:inline-flex;'
            f'align-items:center;justify-content:center">{icon("send", 20)}</button></div>')
    return off + comp


def msgs_area(P, inner):
    return (f'<div style="flex:1;min-height:0;position:relative;overflow:hidden"><span aria-hidden="true" style="position:absolute;'
            f'right:-18px;top:18px;opacity:.55;transform:rotate(12deg)">{sprig(P, 150, True)}</span><div data-fit="1" '
            f'style="position:absolute;left:0;right:0;top:0;bottom:0"><div style="position:absolute;left:0;right:0;bottom:0;'
            f'padding:0 20px 12px;display:flex;flex-direction:column;gap:12px">{inner}</div></div><div aria-hidden="true" '
            f'style="position:absolute;left:0;right:0;top:0;height:36px;background:linear-gradient({P["bg"]},{P["bg"]}00)"></div></div>')


def b_chat():
    P = DAY
    time = f'<div style="align-self:flex-start;margin:-6px 4px 0;line-height:1">{M("13:48", 11, P["muted"])}</div>'
    divider = (f'<div style="display:flex;align-items:center;gap:10px;font-size:12px;font-weight:500;color:{P["accent"]};'
               f'line-height:1.5"><span style="flex:1;height:0;border-top:1px dashed {T(P, "accent", .5)}"></span>新消息'
               f'<span style="flex:1;height:0;border-top:1px dashed {T(P, "accent", .5)}"></span></div>')
    pic = (f'<div style="align-self:flex-start;margin:6px 0 2px 6px">'
           f'{polaroid(P, scene("sea", 132, 78, 0, "小迟发来的照片"), "那家民宿的窗外", -3, tape(P, "B", 52, 4))}</div>')
    quote = (f'<button type="button" style="display:flex;align-items:center;gap:8px;width:100%;text-align:left;margin:0 0 6px;'
             f'padding:0 0 6px;border:0;border-bottom:1px dashed {P["line2"]};background:transparent;color:{P["muted"]};'
             f'font-family:inherit;font-size:12px;line-height:1.5">{mark(P, "迟", 16)}<span>周六早上出发怎么样？…</span></button>')
    ai = (f'<div style="align-self:stretch;display:flex;flex-direction:column;gap:6px;margin:2px 2px">'
          f'<div style="display:flex;align-items:center;gap:8px">{aimark(P)}<span style="font-size:12px;color:{P["muted"]}">'
          f'阿栖问：周六适合去哪片海？</span></div><p style="margin:0;font-size:15px;line-height:1.75">想早点到、人少一些，'
          f'可以看看你们收藏过的两个海边小镇{ref(P, 1)}，车程都在两小时内{ref(P, 2)}。</p>'
          f'<div style="display:flex;align-items:center;gap:10px;margin-top:2px;padding:8px 8px 8px 12px;border-radius:12px;'
          f'border:1.3px dashed {T(P, "B", .45)};background:{T(P, "B", .06)}">{tile(P, "cal", "B", 34)}<div style="flex:1;'
          f'min-width:0;line-height:1.45"><div style="font-size:15px;font-weight:500">周六出发去海边</div>'
          f'{M("09.26 08:00", 12, P["muted"])}</div>{act(P, "不用", P["muted"])}{primary(P, "好", small=True)}</div></div>')
    pending = (f'<div style="align-self:flex-end;display:flex;align-items:flex-end;gap:6px"><span role="img" aria-label="待发送" '
               f'style="display:inline-flex;color:{P["muted"]};padding-bottom:10px">{icon("clock", 14)}</span>'
               f'<div style="padding:10px 15px;border-radius:18px 4px 18px 18px;box-shadow:inset 0 0 0 1.3px {P["A"]};'
               f'font-size:15px;line-height:1.65">那我今晚先把行李收好。</div></div>')
    inner = (other(P, '周六早上出发怎么样？海边那家民宿还有房。') + time + divider + pic
             + mine(P, quote + '好呀，我把外套带上。') + ai + pending)
    wr('New-Chat.dc.html', page(P, '聊天', chat_head(P) + msgs_area(P, inner) + chat_bottom(P) + tabbar(P, '聊天')))


def b_chat_stream():
    P = DAY
    think = ''.join(f'<span style="width:6px;height:6px;border-radius:50%;background:{P["B"]};opacity:{o}"></span>'
                    for o in (.9, .6, .3))
    ai = (f'<div style="align-self:stretch;position:relative;display:flex;flex-direction:column;gap:8px;padding:14px 16px 12px;'
          f'border-radius:16px;background:linear-gradient({T(P, "B", .08)},{T(P, "B", .08)}),{P["card"]};box-shadow:{P["lift"]}">'
          f'<span aria-hidden="true" style="position:absolute;right:14px;top:10px;display:flex;gap:6px;color:{P["B"]};opacity:.6">'
          f'{icon("spark", 12, 1.6)}{icon("spark", 18, 1.4)}</span><div style="display:flex;align-items:center;gap:8px">'
          f'{aimark(P)}<span style="font-size:12px;color:{P["muted"]}">小迟问：国庆回家带什么给妈妈？</span></div>'
          f'<div style="display:flex;align-items:center;gap:6px;font-size:12px;color:{P["muted"]}">{icon("search", 13)}'
          f'翻了 {M("3", 12)} 条相关的记录</div><p style="margin:0;font-size:15px;line-height:1.75">你们在档案里记过，'
          f'妈妈最近在学打太极{ref(P, 1)}，也说过家里的茶快喝完了{ref(P, 2)}。可以带一套轻便的练功服，再配一盒她常喝的'
          f'<span aria-hidden="true" style="display:inline-block;width:2px;height:17px;margin-left:2px;background:{P["B"]};'
          f'vertical-align:-3px"></span></p><div style="display:flex;align-items:center;gap:10px"><span style="display:flex;'
          f'gap:4px">{think}</span><span style="font-size:12px;color:{P["muted"]}">正在写</span><span style="flex:1"></span>'
          f'<button type="button" style="display:inline-flex;align-items:center;gap:6px;min-height:40px;padding:0 14px;border:0;'
          f'border-radius:20px;background:{P["surface"]};box-shadow:inset 0 0 0 1px {T(P, "ink", .08)};color:{P["ink"]};'
          f'font-family:inherit;font-size:14px;font-weight:500">{icon("stop", 14, 1.8)}停下</button></div></div>')
    inner = (mine(P, '好呀，我把外套带上。') + other(P, '国庆回家的话，你觉得带什么给妈妈好？')
             + f'<div style="align-self:flex-start;margin:-6px 4px 0;line-height:1">{M("20:12", 11, P["muted"])}</div>' + ai)
    wr('New-Chat-Streaming.dc.html', page(P, '聊天 · AI 正在回答', chat_head(P) + msgs_area(P, inner)
                                          + chat_bottom(P, '', False) + tabbar(P, '聊天')))


HUB_ROWS = {
    '生活': [('mood', 'A', '心情', 'New-Mood.dc.html', 'dot2'), ('qna', 'B', '问答', 'New-Qna.dc.html', '1'),
           ('flag', 'accent', '计划', 'New-Plan-List.dc.html', '3'), ('todo', 'B', '待办', 'New-Todo.dc.html', '7'),
           ('cal', 'A', '日历', 'New-Calendar.dc.html', '19:30'), ('idea', 'accent', '灵感', 'New-Ideas.dc.html', '12')],
    '创作': [('pen', 'B', '写作', 'New-Writing-List.dc.html', '3'), ('mail', 'A', '留言', 'New-Messages.dc.html', '3')],
    '回看': [('archive', 'muted', '档案', 'New-Archive.dc.html', '48'), ('sign', 'accent', '决定', 'New-Decision-List.dc.html', '7'),
           ('timeline', 'A', '时间线', 'New-Timeline.dc.html', '09.24'), ('book', 'B', '阅读', 'New-Reading-Shelf.dc.html', '42%'),
           ('review', 'muted', '审稿', 'New-Review-List.dc.html', '2'), ('summary', 'accent', '总结', 'New-Summary-List.dc.html', '九月')],
}


def countpill(P, v):
    if v == 'dot2':
        return (f'<span style="display:inline-flex;align-items:center;gap:6px;padding:2px 9px;border-radius:12px;'
                f'background:{T(P, "accent", .12)};color:{P["accent"]};font-size:12px;font-weight:500">'
                f'<span style="width:6px;height:6px;border-radius:50%;background:{P["accent"]}"></span>需要安慰</span>')
    inner = M(v, 13, P['muted']) if any(c.isdigit() for c in v) else f'<span style="font-size:13px;color:{P["muted"]}">{v}</span>'
    return f'<span style="display:inline-flex;align-items:center;padding:1px 10px;border-radius:12px;background:{T(P, "ink", .05)}">{inner}</span>'


def hub_row(P, ic, key, name, href, meta, first):
    return (f'<a href="{href}" style="display:flex;align-items:center;gap:14px;min-height:60px;{divtop(P, first)}'
            f'color:{P["ink"]};text-decoration:none">{tile(P, ic, key, 38)}<span style="flex:1;font-size:17px;font-weight:500">'
            f'{name}</span>{countpill(P, meta)}<span style="display:inline-flex;color:{P["faint"]}">{icon("chev", 16)}</span></a>')


def b_hub(fname, title, sel, extra=''):
    P = DAY
    rows = ''.join(hub_row(P, ic, k, n, h, m, i == 0) for i, (ic, k, n, h, m) in enumerate(HUB_ROWS[sel]))
    lst = f'<nav aria-label="{sel}" style="flex:none;padding:0 28px">{rows}</nav>'
    cap = (f'<div style="flex:none;margin:auto 20px 16px;display:flex;align-items:center;gap:10px;height:54px;padding:0 8px 0 10px;'
           f'border-radius:27px;background:{P["card"]};box-shadow:{P["lift"]}">{tile(P, "idea", "accent", 34)}<input '
           f'aria-label="记一个念头" placeholder="一个念头，可以带 #标签" style="flex:1;min-width:0;height:46px;border:0;'
           f'background:transparent;padding:0;font-family:inherit;font-size:15px;color:{P["ink"]}">{act(P, "记下")}</div>')
    body = (bar1(P, '一起', pair(P, 26), '我们的小日子') + tabs(P, ['生活', '创作', '回看'], sel) + lst + extra
            + (cap if sel == '生活' else '<div style="flex:1"></div>') + tabbar(P, '一起'))
    wr(fname, page(P, title, body))


def b_hub_create():
    P = DAY
    doc = (f'<a href="New-Writing.dc.html" style="position:relative;display:block;padding:18px 18px 14px 54px;border-radius:6px;'
           f'{ruled(P, 26, 44)};box-shadow:{P["lift"]};color:{P["ink"]};text-decoration:none;transform:rotate(-1deg)">'
           f'{ribbon(P, "accent", 22, 40)}<div style="font-size:16px;font-weight:700;line-height:26px">给明年秋天的信</div>'
           f'<div style="font-size:14px;line-height:26px;color:{P["muted"]}">窗边的绿萝又长了一截。你说，等它爬到书架顶……</div>'
           f'<div style="display:flex;align-items:center;gap:8px;font-size:12px;line-height:26px;color:{P["muted"]}">'
           f'{M("v8", 12)}{sharebar(P, 79)}{hand(P, "小迟刚改过", 16, "B", -2)}</div></a>')
    letter = (f'<a href="New-Messages.dc.html" style="position:relative;display:flex;gap:14px;align-items:center;padding:16px 18px;'
              f'border-radius:6px;background:{P["card"]};box-shadow:{P["lift"]};color:{P["ink"]};text-decoration:none;'
              f'transform:rotate(1deg)">{wax(P, "迟", 44, "B")}<div style="flex:1;min-width:0"><div style="font-size:16px;'
              f'font-weight:700">谢谢你那天来接我</div><div style="font-size:13px;color:{P["muted"]}">{M("09.22", 12)} · 新留言</div>'
              f'</div>{icon("chev", 16)}</a>')
    extra = (f'<section style="flex:none;padding:18px 28px 0">{label(P, "最近", ic="spark", key="accent")}<div style="display:flex;'
             f'flex-direction:column;gap:18px;padding-top:6px">{doc}{letter}</div></section>')
    b_hub('New-Together-Create.dc.html', '一起 · 创作', '创作', extra)


def b_me():
    P = DAY

    def row(ic, key, t, val='', first=False):
        return (f'<button type="button" style="display:flex;align-items:center;gap:12px;width:100%;min-height:48px;padding:0;'
                f'border:0;{divtop(P, first)}background:transparent;font-family:inherit;font-size:16px;color:{P["ink"]};'
                f'text-align:left">{tile(P, ic, key, 30)}<span style="flex:1">{t}</span>{val}<span style="display:inline-flex;'
                f'color:{P["faint"]}">{icon("chev", 16)}</span></button>')

    def val(t):
        return f'<span style="font-size:14px;color:{P["muted"]}">{t}</span>'

    def sec(name, rows):
        return f'<section>{label(P, name)}<div>{rows}</div></section>'

    content = (sec('内容', row('pen', 'B', '我写下的内容', first=True) + row('spark', 'B', '我发起的 AI 使用'))
               + sec('房间', row('people', 'A', '成员与邀请', M('2', 14, P['muted']), True) + row('rings', 'A', '房间设置')
                     + row('archive', 'muted', '回收站', M('4', 14, P['muted'])))
               + sec('账号', row('user', 'accent', '资料', first=True) + row('eye', 'accent', '显示', val('标准'))
                     + row('mail', 'accent', '通知') + row('spark', 'B', 'AI 能看什么', val('全部')) + row('lock', 'muted', '安全'))
               + f'<p style="margin:0;text-align:center;line-height:1.5">{hand(P, "两份孤独，彼此守护，彼此为界，彼此致意。", 19, "muted", 0)}'
               f'<br>{M("— Rilke", 12, P["faint"])}</p>')
    side = (f'<span style="position:relative;flex:none;margin-bottom:4px">{mark(P, "栖", 56)}<span style="position:absolute;'
            f'right:-12px;bottom:-8px">{seal(P, "栖迟", 28, 8)}</span></span>')
    body = bar1(P, '阿栖', '', '两个人的屋檐', side) + scroll(content, '4px 28px 20px', 18) + tabbar(P, '我的')
    wr('New-Me.dc.html', page(P, '我的', body))


# ================================================================ 生活
EMO = [('平静', 'wave'), ('开心', 'sun'), ('期待', 'spark'), ('疲惫', 'moon'), ('焦虑', 'scribble'), ('低落', 'rain'),
       ('生气', 'flame'), ('委屈', 'drop')]


def b_mood():
    P = DAY
    grid = ''.join(
        f'<button type="button" aria-pressed="{"true" if e == "平静" else "false"}" style="display:flex;flex-direction:column;'
        f'align-items:center;justify-content:center;gap:2px;min-height:62px;border:0;border-radius:16px;font-family:inherit;'
        f'font-size:14px;font-weight:500;'
        + (f'background:{P["ink"]};color:{P["bg"]}' if e == '平静' else
           f'background:{P["card"]};color:{P["ink"]};box-shadow:{P["lift"]}') + f'">{icon(ic, 20, 1.6)}{e}</button>'
        for e, ic in EMO)
    ticks = ''.join(
        f'<button type="button" aria-label="{i}" aria-pressed="{"true" if i == 7 else "false"}" style="display:flex;align-items:center;'
        f'justify-content:center;height:44px;padding:0;border:0;background:transparent"><span style="width:{16 if i == 7 else 10}px;'
        f'height:{16 if i == 7 else 10}px;border-radius:50%;background:{P["A"] if i <= 7 else T(P, "A", .18)};'
        f'{"box-shadow:0 0 0 5px " + T(P, "A", .16) if i == 7 else ""}"></span></button>' for i in range(1, 11))
    partner = card(P, f'<span style="position:absolute;right:-4px;top:-12px">{sticker(P, "需要安慰", "accent", 5)}</span>'
                      f'<div style="display:flex;align-items:center;gap:10px">{mark(P, "迟", 24)}<span style="font-size:22px;'
                      f'font-weight:700;line-height:1.4">疲惫</span>{M("6", 15, P["muted"])}<span style="flex:1"></span>'
                      f'<span style="display:inline-flex;color:{P["B"]}">{icon("moon", 24, 1.5)}</span></div>'
                      f'<div style="display:flex;flex-wrap:wrap;gap:8px;margin-top:12px">{pill(P, "我在这里")}'
                      f'{pill(P, "给你一个拥抱")}{pill(P, "等你准备好")}</div>', 'B', 0, '14px 14px 14px')
    content = (
        '<section>' + label(P, '此刻') + f'<div style="display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px">{grid}</div></section>'
        + '<section>' + label(P, '强度', M('7', 22, P['A'], 500) + M('/10', 12, P['muted']))
        + f'<div style="display:grid;grid-template-columns:repeat(10,minmax(0,1fr))">{ticks}</div></section>'
        + '<section>' + label(P, '几句') + f'<textarea aria-label="几句" style="display:block;width:100%;height:78px;box-sizing:border-box;'
        f'resize:none;padding:10px 16px;border:0;border-radius:12px;{ruled(P, 26, 9, False)};box-shadow:{P["lift"]};'
        f'font-family:inherit;font-size:15px;line-height:26px;color:{P["ink"]}">午后晒了会儿太阳，心里很松。</textarea></section>'
        + f'<div style="display:flex;align-items:center;gap:12px"><label style="flex:1;display:flex;align-items:center;gap:12px;'
        f'min-height:44px">{checkbox(P)}<span style="font-size:15px">需要安慰</span></label>{primary(P, "记下")}</div>'
        + f'<section style="margin-top:4px">{label(P, "小迟", M("14:20", 12, P["muted"]))}{partner}</section>')
    wr('New-Mood.dc.html', page(P, '心情', bar2(P, 'New-Together.dc.html', '心情', 'mood', 'A', '', '今天怎么样')
                                   + scroll(content, '4px 28px 24px', 18)))


def b_qna():
    P = DAY
    q = (f'<div style="position:relative;padding:22px 20px 16px;border-radius:6px;background:{P["paper"]};box-shadow:{P["lift"]}">'
         f'{tape(P, "B", 70, -4)}<span aria-hidden="true" style="position:absolute;left:10px;top:-10px;font-family:{SERIF};'
         f'font-size:90px;line-height:1;color:{T(P, "accent", .15)}">“</span><div style="display:flex;align-items:center;gap:8px">'
         f'{M("09.24", 12, P["muted"])}<span style="flex:1"></span>{sticker(P, "第 41 题", "B", 4, 15)}</div>'
         f'<p style="position:relative;margin:6px 0 0;font-size:20px;font-weight:700;line-height:1.6">如果下个月有一整个周末空出来，'
         f'你最想怎么过？</p></div>')
    mine_ = card(P, f'{tape(P, "A", 48, 6, "top:-8px;right:18px")}<div style="display:flex;align-items:center;gap:8px">'
                    f'{mark(P, "栖", 20)}<span style="font-size:13px;font-weight:500;color:{P["muted"]}">阿栖</span><span style="flex:1">'
                    f'</span>{M("08:12", 12, P["muted"])}</div><p style="margin:8px 0 0;font-size:16px;line-height:1.7">去山里住一晚，'
                    f'什么都不安排，带一本还没读完的书。</p>', 'A', -1, '14px 16px 14px')
    flap = (f'<svg aria-hidden="true" width="100%" height="56" viewBox="0 0 300 56" preserveAspectRatio="none" style="position:absolute;'
            f'left:0;top:0"><path d="M0 0L150 50 300 0" fill="none" stroke="{P["line2"]}" stroke-width="1.2"/></svg>')
    sealed = (f'<div role="img" aria-label="小迟的回答，还没揭晓" style="position:relative;height:118px;border-radius:6px;'
              f'background:{P["card"]};box-shadow:{P["lift"]};overflow:hidden">{flap}<span style="position:absolute;left:50%;'
              f'top:22px;margin-left:-28px">{wax(P, "迟", 56, "B")}</span><span style="position:absolute;left:16px;bottom:12px">'
              f'{hand(P, "两个人都答完，一起拆开", 17, "muted", -2)}</span><span style="position:absolute;right:16px;bottom:14px;'
              f'display:inline-flex;color:{P["muted"]}">{icon("lock", 16)}</span></div>')

    def newq(t, first=False):
        return (f'<div style="display:flex;align-items:center;gap:12px;min-height:56px;{divtop(P, first)}"><span style="flex:1;'
                f'font-size:15px;line-height:1.65">{t}</span>{act(P, "采纳")}</div>')

    content = (q + f'<div style="display:flex;flex-direction:column;gap:16px">{mine_}{sealed}</div>'
               + '<section style="margin-top:4px">' + label(P, '新的问题', aimark(P, 12))
               + newq('你们第一次一起做的决定是什么？现在还会那样选吗？', True)
               + newq('最近有没有一件小事，想被对方多看见一点？') + '</section>')
    body = bar2(P, 'New-Together.dc.html', '问答', 'qna', 'B') + tabs(P, ['今日', '题库'], '今日') + scroll(content, '10px 28px 24px', 20)
    wr('New-Qna.dc.html', page(P, '问答', body))


def b_calendar():
    P = DAY
    dots = {3: 'm', 5: 'A', 9: 'AB', 12: 'B', 14: 'A', 18: 'AB', 19: 'A', 20: 'B', 22: 'B', 24: 'AB', 26: 'AB', 28: 'm'}

    def dot(k):
        if k == 'm':
            return f'<span style="width:6px;height:6px;background:{P["B"]};transform:rotate(45deg)"></span>'
        return f'<span style="width:5px;height:5px;border-radius:50%;background:{P[k]}"></span>'

    def cell(d):
        v = dots.get(d, '')
        dh = ''.join(dot(k) for k in (['m'] if v == 'm' else list(v)))
        if d == 24:
            num = (f'<span style="display:inline-flex;align-items:center;justify-content:center;width:32px;height:32px;'
                   f'border-radius:50%;background:{P["ink"]};color:{P["bg"]};font-weight:500;box-shadow:0 0 0 4px {T(P, "A", .2)}">{d}</span>')
        elif d == 26:
            num = (f'<span style="display:inline-flex;align-items:center;justify-content:center;width:32px;height:32px;'
                   f'border-radius:50%;border:1.3px dashed {P["A"]};box-sizing:border-box">{d}</span>')
        else:
            num = f'<span style="display:inline-flex;align-items:center;justify-content:center;width:32px;height:32px">{d}</span>'
        return (f'<button type="button" aria-label="9月{d}日" style="display:flex;flex-direction:column;align-items:center;gap:2px;'
                f'height:46px;padding:1px 0 0;border:0;background:transparent;font-family:{MONO};font-size:15px;color:{P["ink"]}">'
                f'{num}<span style="display:flex;gap:3px;height:6px;align-items:center">{dh}</span></button>')

    cells = ['<span></span>'] + [cell(d) for d in range(1, 31)]
    while len(cells) % 7:
        cells.append('<span></span>')
    wk = ''.join(f'<span style="text-align:center;font-size:12px;font-weight:600;color:{P["accent"] if d in "六日" else P["muted"]}">'
                 f'{d}</span>' for d in '一二三四五六日')
    month = (f'<div style="position:relative;flex:none;display:flex;align-items:center;padding:0 14px 2px 28px">'
             f'{watermark(P, "09", 110, "right:88px;top:-34px", None, .06)}<span style="font-size:20px;font-weight:700">九月</span>'
             f'<span style="margin-left:8px">{M("2026", 15, P["muted"])}</span>'
             f'<span style="margin-left:12px">{sticker(P, "26 号去海边", "A", -4, 15)}</span><span style="flex:1"></span>'
             f'{btn_icon(P, "上个月", "chevl")}{btn_icon(P, "下个月", "chev")}</div>')
    grid = (f'<div style="flex:none;margin:0 16px;padding:10px 5px 6px;border-radius:16px;background:{P["card"]};'
            f'box-shadow:{P["lift"]}"><div style="display:grid;grid-template-columns:repeat(7,minmax(0,1fr));margin-bottom:4px">{wk}'
            f'</div><div style="display:grid;grid-template-columns:repeat(7,minmax(0,1fr));row-gap:1px">{"".join(cells)}</div></div>')

    def crow(t, title, w, past=False, isdue=False, hi=False):
        col = P['muted'] if past else P['ink']
        lead = (f'<span style="font-size:12px;font-weight:700;color:{P["accent"]}">截止</span>' if isdue else M(t, 15, col))
        box = (f'background:linear-gradient({T(P, "A", .1)},{T(P, "A", .1)}),{P["card"]};box-shadow:{P["lift"]};' if hi else '')
        return (f'<div style="display:flex;align-items:center;min-height:48px;margin:0 -12px;padding:0 12px;border-radius:12px;{box}">'
                f'<span style="width:58px;flex:none">{lead}</span><span style="flex:1;font-size:16px;color:{col}">{title}</span>{w}</div>')

    day = ('<section style="flex:none;padding:16px 28px 0">' + label(P, '今天', M('09.24', 12, P['muted']), 'sun', 'A')
           + crow('10:00', '牙医复诊', mark(P, '迟', 18), True) + crow('19:30', '一起做饭', pair(P, 18, T(P, 'A', .1)), hi=True)
           + crow('', '取回干洗的外套', mark(P, '栖', 18), isdue=True) + '</section>')
    trailing = btn_icon(P, '回到今天', 'today') + btn_icon(P, '更多', 'more')
    body = (bar2(P, 'New-Together.dc.html', '日历', 'cal', 'A', trailing) + tabs(P, ['日', '周', '月'], '月') + month + grid + day
            + fab(P, '新安排'))
    wr('New-Calendar.dc.html', page(P, '日历', body))


def b_todo():
    P = DAY
    content = ('<section>' + label(P, '今天', sticker(P, '还有 3 件', 'B', -3, 15), 'sun', 'B')
               + todo(P, '取回干洗的外套', right=mark(P, '栖', 18))
               + todo(P, '订周六的餐位', right=pair(P, 18))
               + todo(P, '查往返车次', right=mark(P, '迟', 18), meta=planmeta(P, '秋天去一次海边'))
               + '</section><section>' + label(P, '这周', M('3', 13, P['muted']), 'cal', 'B')
               + todo(P, '整理行李清单', right=mark(P, '栖', 18),
                      meta='周五' + dotsep(P) + planmeta(P, '秋天去一次海边') + dotsep(P) + M('1/2', 12))
               + todo(P, '给阳台的绿萝换盆', right=mark(P, '迟', 18), meta='周六')
               + todo(P, '核对预算', right=pair(P, 18), meta='周日' + dotsep(P)
                      + f'<span style="display:inline-flex;align-items:center;gap:4px">{icon("repeat", 12)}每周</span>')
               + '</section><section>' + label(P, '以后', ic='clock', key='B')
               + todo(P, '换季衣服收纳', right=mark(P, '栖', 18), meta=M('10.08', 12))
               + f'</section><button type="button" style="display:flex;align-items:center;gap:10px;width:100%;min-height:48px;'
               f'padding:0;border:0;border-top:1px dashed {P["line2"]};background:transparent;font-family:inherit;font-size:15px;'
               f'font-weight:500;color:{P["muted"]}">{icon("check", 16, 1.8)}已完成 {M("12", 14)}<span style="flex:1"></span>'
               f'{icon("chev", 16)}</button>')
    body = bar2(P, 'New-Together.dc.html', '待办', 'todo', 'B') + scroll(content, '4px 28px 100px', 16) + fab(P, '新待办')
    wr('New-Todo.dc.html', page(P, '待办', body))


def b_plan_list():
    P = DAY

    def item(href, name, w, sc, st, labs, nxt, meta, rot, tk):
        stg = stage(P, st, labs, 10) if st else ''
        return card(P, f'{tape(P, tk, 46, rot * 4, "top:-8px;left:22px")}<a href="{href}" style="display:block;color:{P["ink"]};'
                       f'text-decoration:none"><div style="display:flex;gap:12px;align-items:center">{scene(sc, 52, 52, 10)}'
                       f'<span style="flex:1;font-size:17px;font-weight:700;line-height:1.45">{name}</span>{w}</div>{stg}'
                       f'{nextstep(P, nxt)}<div style="margin-top:6px;font-size:12px;color:{P["muted"]}">{meta}</div></a>',
                    None, rot, '16px 16px 12px')

    items = (item('New-Plan.dc.html', '秋天去一次海边', mark(P, '迟', 22), 'sea', ['done', 'cur', 'todo'], ['选地方', '订住处', '出发前'],
                  '对比三家民宿的价格和交通', f'{M("4", 12)} 个待办 · 里程碑 {M("09.28", 12)}', -.6, 'B')
             + item('#', '把阳台改成茶座', mark(P, '栖', 22), 'window', ['cur', 'todo', 'todo'], ['量尺寸', '选家具', '布置'],
                    '量阳台的长和宽', f'{M("2", 12)} 个待办', .5, 'A')
             + item('#', '一起读完《海边的旅店》', pair(P, 22, P['card']), 'shelf', None, None, '每晚读一章',
                    f'读到 {M("42%", 12)} · 没有截止', -.4, 'accent'))
    body = (bar2(P, 'New-Together.dc.html', '计划', 'flag', 'accent') + tabs(P, ['进行中', '已完成'], '进行中')
            + scroll(items, '12px 24px 100px', 22) + fab(P, '新计划'))
    wr('New-Plan-List.dc.html', page(P, '计划', body))


def b_plan():
    P = DAY

    def mile(text, date, done):
        ic = (checkbox(P, True, 20) if done else
              f'<span style="width:20px;display:inline-flex;justify-content:center;flex:none"><span style="width:9px;height:9px;'
              f'background:{P["B"]};transform:rotate(45deg)"></span></span>')
        return (f'<div style="display:flex;align-items:center;gap:14px;min-height:44px">{ic}<span style="flex:1;font-size:16px;'
                f'{"color:" + P["faint"] if done else ""}">{text}</span>{M(date, 13, P["muted"])}</div>')

    banner = (f'<div style="position:relative;flex:none">{scene("sea", 334, 116, 14, "海边")}{tape(P, "accent", 60, -6, "top:-8px;left:18px", .35)}'
              f'<span style="position:absolute;right:14px;bottom:10px">{sticker(P, "还有 2 天", "accent", -4, 16).replace("background:" + T(P, "accent", .08), "background:" + P["card"])}</span></div>')
    nxt = card(P, f'<div style="font-size:12px;font-weight:700;color:{P["accent"]}">下一步</div><div style="font-size:17px;font-weight:600;'
                  f'line-height:1.5;margin:2px 0">对比三家民宿的价格和交通</div><div style="display:flex;align-items:center;gap:8px">'
                  f'{mark(P, "栖", 18)}<span style="font-size:13px;color:{P["muted"]}">周五截止</span><span style="flex:1"></span>'
                  f'{act(P, "完成")}</div>', 'accent', 0, '14px 16px 4px')
    content = (banner + '<div>' + stage(P, ['done', 'cur', 'todo'], ['选地方', '订住处', '出发前'], 2) + '</div>' + nxt
               + '<section>' + label(P, '里程碑', ic='flag', key='B') + mile('确定目的地', '09.03', True) + mile('订好住处', '09.28', False)
               + '</section><section>' + label(P, '待办', M('3', 13, P['muted']), 'todo', 'B')
               + todo(P, '查往返车次', right='周四' + mark(P, '迟', 18))
               + todo(P, '整理行李清单', right='周五' + mark(P, '栖', 18), meta=M('1/2', 12) + ' 衣物已装好')
               + todo(P, '核对预算', right='周日' + pair(P, 18)) + '</section>')
    trailing = f'<span style="display:inline-flex;margin-right:4px">{mark(P, "迟", 24)}</span>' + btn_icon(P, '更多', 'more')
    body = bar3(P, 'New-Plan-List.dc.html', '计划', 'flag', 'accent', '秋天去一次海边', trailing) + scroll(content, '6px 28px 24px', 18)
    wr('New-Plan.dc.html', page(P, '计划 · 秋天去一次海边', body))


IDEAS = [('给对方写一封信，约好明年秋天再拆。', ['#送给对方'], '栖', '09.21', 'A', True),
         ('冬天去看一次雪，住有壁炉的小木屋。', ['#旅行/北方'], '迟', '09.23', 'B', False),
         ('阳台放一张能晒太阳的躺椅。', ['#家/阳台'], '栖', '09.20', 'accent', False),
         ('学做外婆的红烧肉，过年做给她吃。', ['#吃'], '迟', '09.16', None, False),
         ('周末早上去菜市场，顺手买一束花回家。', ['#家'], '栖', '09.12', 'B', False),
         ('海边那家书店，据说二楼能看到灯塔。', ['#旅行/海边'], '迟', '09.08', 'A', False)]


def b_ideas():
    P = DAY

    def note(text, tags, w, date, key, pinned, rot, tk, sc=None):
        pin = (f'<span style="position:absolute;right:10px;top:10px;display:inline-flex;color:{P["accent"]}">{icon("pin", 16, 1.7)}'
               f'</span>' if pinned else '')
        pic = f'<div style="margin:-2px -2px 10px">{scene(sc, 142, 80, 8)}</div>' if sc else ''
        return card(P, f'{tape(P, tk, 40, rot * 5, "top:-8px;left:50%;margin-left:-20px") if tk else ""}{pin}{pic}'
                       f'<p style="margin:0;font-size:15px;line-height:1.65">{text}</p><div style="display:flex;flex-wrap:wrap;gap:4px;'
                       f'margin-top:8px">{"".join(tag(P, x) for x in tags)}</div><div style="display:flex;align-items:center;gap:6px;'
                       f'margin-top:8px">{mark(P, w, 18)}{M(date, 12, P["muted"])}</div>', key, rot, '14px 14px 12px', 12)

    n = [(*IDEAS[0], -1.2, None, None), (*IDEAS[1], 1, 'B', 'snow'), (*IDEAS[2], .8, 'accent', None),
         (*IDEAS[3], -.8, None, 'dinner'), (*IDEAS[4], -1, 'A', None), (*IDEAS[5], .6, None, None)]
    col1 = ''.join(note(*n[i][:6], n[i][6], n[i][7], n[i][8]) for i in (0, 2, 4))
    col2 = ''.join(note(*n[i][:6], n[i][6], n[i][7], n[i][8]) for i in (1, 3, 5))
    board = (f'<div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;align-items:start">'
             f'<div style="display:flex;flex-direction:column;gap:16px">{col1}</div><div style="display:flex;flex-direction:column;'
             f'gap:16px;padding-top:18px">{col2}</div></div>')
    trailing = btn_icon(P, '标签', 'tag') + btn_icon(P, '搜索', 'search')
    body = (bar2(P, 'New-Together.dc.html', '灵感', 'idea', 'accent', trailing, '随手记')
            + chips(P, ['全部', '#旅行', '#家', '#吃', '#送给对方'], '全部') + scroll(board, '8px 24px 100px', 0) + fab(P, '记一条'))
    wr('New-Ideas.dc.html', page(P, '灵感', body))


def b_ideas_empty():
    P = DAY
    art = (f'<div style="position:relative;width:220px;height:170px;margin:0 auto">'
           f'<span style="position:absolute;left:36px;top:30px">{card(P, "", "accent", -6, "0", 12, "width:120px;height:120px")}</span>'
           f'<span style="position:absolute;left:70px;top:20px">{card(P, tape(P, "B", 46, -8, "top:-8px;left:36px") + hand(P, "一个念头……", 20, "muted", -4, "margin:44px 0 0 12px"), None, 4, "0", 12, "width:124px;height:124px")}</span>'
           f'<span style="position:absolute;left:-16px;top:120px;transform:rotate(-12deg)">{sprig(P, 130)}</span>'
           f'<span style="position:absolute;right:6px;top:4px;color:{P["accent"]}">{icon("spark", 22, 1.5)}</span></div>')
    content = (f'<div style="margin:auto 0;display:flex;flex-direction:column;align-items:center;gap:18px;text-align:center">{art}'
               f'<div><div style="font-size:18px;font-weight:600">还没有灵感</div><div style="margin-top:4px;font-size:14px;'
               f'color:{P["muted"]}">想到什么就记一条，带上 {tag(P, "#标签")} 以后好找</div></div></div>')
    body = (bar2(P, 'New-Together.dc.html', '灵感', 'idea', 'accent', btn_icon(P, '标签', 'tag'), '随手记')
            + scroll(content, '0 28px 100px', 0) + fab(P, '记一条'))
    wr('New-Ideas-Empty.dc.html', page(P, '灵感 · 空', body))


def b_tags():
    P = DAY

    def row(name, ideas, facts, key, depth=0, last=False, first=False):
        conn = ''
        if depth:
            conn = (f'<span aria-hidden="true" style="position:relative;width:22px;align-self:stretch;flex:none">'
                    f'<span style="position:absolute;left:8px;top:0;{"height:50%" if last else "bottom:0"};border-left:1.3px dashed '
                    f'{P["line2"]}"></span><span style="position:absolute;left:8px;top:50%;width:12px;border-top:1.3px dashed '
                    f'{P["line2"]}"></span></span>')
        cnt = (f'<span style="display:flex;gap:6px;font-size:12px;color:{P["muted"]}"><span>灵感 {M(ideas, 12)}</span>'
               f'<span>档案 {M(facts, 12)}</span></span>')
        return (f'<a href="#" style="display:flex;align-items:center;gap:12px;min-height:{50 if depth else 58}px;'
                f'{divtop(P, first or depth)}color:{P["ink"]};text-decoration:none;{"padding-left:14px;" if depth else ""}">{conn}'
                f'{tile(P, "tag", key, 28 if depth else 34)}<span style="flex:1;font-family:{MONO};font-size:{14 if depth else 15}px;'
                f'font-weight:500">{name}</span>{cnt}</a>')

    tree = (row('#旅行', '4', '1', 'B', first=True) + row('/北方', '1', '0', 'B', 1) + row('/海边', '3', '1', 'B', 1, True)
            + row('#家', '5', '3', 'accent') + row('/阳台', '2', '0', 'accent', 1, True)
            + row('#吃', '2', '1', 'A') + row('#日常', '0', '2', 'muted') + row('#送给对方', '1', '0', 'A'))
    note = (f'<div style="display:flex;align-items:center;gap:10px;margin-top:6px">{hand(P, "写的时候打 # 就是标签，", 18, "muted", -2)}'
            f'{hand(P, "打 / 能分层", 18, "accent", -2)}</div>')
    body = (bar2(P, 'New-Ideas.dc.html', '标签', 'tag', 'accent', btn_icon(P, '更多', 'more'))
            + scroll(f'<div>{tree}</div>{note}', '4px 28px 24px', 12))
    wr('New-Tags.dc.html', page(P, '标签', body))


# ================================================================ 创作
def paper_thumb(P, pinned=False):
    lines = ''.join(f'<span style="display:block;height:3px;width:{w}%;margin-top:6px;border-radius:2px;background:{T(P, "ink", .16)}"></span>'
                    for w in (70, 90, 84, 60))
    return (f'<span aria-hidden="true" style="position:relative;flex:none;width:56px;height:70px;box-sizing:border-box;padding:10px 8px;'
            f'border-radius:4px;{ruled(P, 9, 8)};box-shadow:{P["lift"]}">{ribbon(P, "accent", 6, 22) if pinned else ""}{lines}</span>')


def b_writing_list():
    P = DAY

    def doc(href, title, preview, kind, ver, share, words, date, first=False, pinned=False):
        return (f'<a href="{href}" style="display:flex;gap:14px;align-items:flex-start;padding:14px 0;{divtop(P, first)}'
                f'color:{P["ink"]};text-decoration:none">{paper_thumb(P, pinned)}<div style="flex:1;min-width:0">'
                f'<div style="display:flex;align-items:baseline;gap:10px"><span style="flex:1;min-width:0;font-size:17px;font-weight:700;'
                f'line-height:1.45">{title}</span><span style="flex:none;font-size:12px;color:{P["muted"]}">{M(words, 12)} 字</span></div>'
                f'<p style="margin:2px 0 0;font-size:14px;line-height:1.6;color:{P["muted"]};display:-webkit-box;-webkit-line-clamp:2;'
                f'-webkit-box-orient:vertical;overflow:hidden">{preview}</p><div style="display:flex;align-items:center;gap:8px;'
                f'margin-top:6px;font-size:12px;color:{P["muted"]}">{tag(P, kind, "B")}{M(ver, 12)}{sharebar(P, share)}'
                f'<span style="flex:1"></span>{M(date, 12)}</div></div></a>')

    content = ('<section>' + label(P, '置顶', ic='pin', key='accent')
               + doc('New-Writing.dc.html', '给明年秋天的信', '窗边的绿萝又长了一截。你说，等它爬到书架顶，我们就去看一次海。',
                     '信', 'v8', 79, '1,286', '09.21', True, True) + '</section>'
               + '<section>' + label(P, '最近', ic='clock', key='B')
               + doc('#', '搬家那天', '那天的纸箱比想象中多。你先把我的书收好，说这些最重的要最后搬。', '日记', 'v3', 100, '2,040', '09.14', True)
               + doc('#', '八月回顾', '八月我们一起做了四顿饭，看了两场电影，还有一次在雨里等了很久的车。', '回顾', 'v2', 50, '860', '09.01')
               + '</section>')
    body = (bar2(P, 'New-Together-Create.dc.html', '写作', 'pen', 'B', btn_icon(P, '搜索', 'search'), '一起写')
            + chips(P, ['全部', '信', '游记', '日记', '回顾', '其它'], '全部') + scroll(content, '0 28px 100px', 14) + fab(P, '新文稿'))
    wr('New-Writing-List.dc.html', page(P, '写作', body))


def syn(P, t):
    return f'<span style="font-family:{MONO};font-weight:400;color:{P["faint"]}">{t}</span>'


def caret(P):
    return (f'<span aria-hidden="true" style="display:inline-block;width:2px;height:20px;background:{P["accent"]};'
            f'vertical-align:-4px;margin-left:1px"></span>')


LH = 33


def editor_paper(P, parts, top=24, foot=''):
    """P12-02：横线纸铺到两边（没有留边和阴影），页边线 28px；[foot] 浮在纸面底部。"""
    return (f'<div style="position:relative;flex:1;min-height:0;padding:{top}px 24px 20px 40px;'
            f'{ruled(P, LH, top + LH - 1, mx=28)};overflow:hidden;font-size:16px;line-height:{LH}px">'
            f'{parts}{foot}</div>')


def editor_foot(P, count, minutes, status, version, dot=True):
    """浮在纸面上的底栏：左边一行字数 · 分钟 · 保存状态，右边字号和「存为 vN」，背后纸色渐隐。"""
    return (f'<div style="position:absolute;left:0;right:0;bottom:0;display:flex;align-items:center;gap:6px;'
            f'padding:24px 16px 20px 40px;background:linear-gradient(180deg,{T(P, "paper", 0)} 0,{T(P, "paper", .92)} 35%,{P["paper"]} 100%)">'
            f'{"<span style=%swidth:6px;height:6px;border-radius:50%%;background:%s%s></span>" % (chr(34), P["A"], chr(34)) if dot else ""}'
            f'<div style="flex:1;min-width:0;font-size:12px;color:{P["muted"]};line-height:1.4;white-space:nowrap">{M(count, 15, P["ink"])} 字 · '
            f'{M(minutes, 15, P["ink"])} 分钟 · {status}</div><span aria-label="字号与行距" style="display:inline-flex;align-items:center;justify-content:center;width:44px;height:44px;font-size:17px;color:{P["ink"]}">Aa</span>{primary(P, "存为 " + M(version, 15))}</div>')


def ed_head(P, text, lead='## '):
    return f'<div style="font-size:20px;font-weight:700;line-height:{LH}px">{syn(P, lead)}{text}</div>'


def b_writing():
    P = DAY

    def them(text):
        return (f'<span style="background:{T(P, "B", .16)};border-radius:3px;-webkit-box-decoration-break:clone;'
                f'box-decoration-break:clone;padding:3px 1px;box-shadow:inset 0 -2px 0 {T(P, "B", .55)}">{text}</span>')

    blank = f'<div style="height:{LH}px"></div>'
    parts = (ed_head(P, '我们现在的样子') + f'<div>窗边的绿萝又长了一截。{them("你说，等它爬到书架顶，我们就去看一次海。")}</div>'
             + blank + ed_head(P, '明年想做的事')
             + f'<div>{syn(P, "- ")}{them("把阳台改成能坐下来喝茶的地方")}</div>'
             + f'<div>{syn(P, "- ")}每个月留出一个不安排任何事的周末</div>'
             + f'<div>{syn(P, "- ")}一起把那本读到一半的书读完{caret(P)}</div>'
             + f'<span style="position:absolute;right:30px;top:{24 + LH * 3 + 4}px">{hand(P, "↑ 小迟加的", 18, "B", -4)}</span>')
    legend = (f'<div style="flex:none;display:flex;align-items:center;gap:14px;padding:2px 28px 8px">'
              f'<span style="display:inline-flex;align-items:center;gap:8px;font-size:13px;color:{P["muted"]}"><span style="width:22px;'
              f'height:12px;border-radius:3px;background:{T(P, "B", .16)};box-shadow:inset 0 -2px 0 {T(P, "B", .55)}"></span>小迟写的 '
              f'{M("266", 13, P["ink"])} 字</span><span style="font-size:13px;color:{P["muted"]}">我写的 {M("1,020", 13, P["ink"])} 字</span>'
              f'<span style="flex:1"></span>{M("v8", 14, P["muted"])}</div>')
    trailing = btn_icon(P, '署名', 'people', active=True) + btn_icon(P, '预览', 'eye') + btn_icon(P, '更多', 'more')
    body = (bar3(P, 'New-Writing-List.dc.html', '写作', 'pen', 'B', '给明年秋天的信', trailing) + legend
            + editor_paper(P, parts, foot=editor_foot(P, "1,286", "4", "未保存", "v9")))
    wr('New-Writing.dc.html', page(P, '写作 · 署名', body))


def b_writing_behind():
    P = DAY
    banner = (f'<div style="flex:none;margin:0 16px 14px">'
              + card(P, f'<div style="display:flex;gap:12px;align-items:flex-start">{tile(P, "refresh", "B", 36)}<div style="flex:1;'
                        f'min-width:0"><div style="font-size:15px;font-weight:600">小迟刚存了 {M("v9", 15)}</div><div style="font-size:13px;'
                        f'color:{P["muted"]};line-height:1.6">你在 {M("v8", 13)} 上改的 {M("3", 13)} 处还在，先合到新版再存</div></div></div>'
                        f'<div style="display:flex;justify-content:flex-end;gap:8px;margin-top:8px">{act(P, "看看改了什么", P["muted"])}'
                        f'{primary(P, "重基线", small=True)}</div>', 'B', 0, '14px 14px 10px') + '</div>')
    blank = f'<div style="height:{LH}px"></div>'
    parts = (ed_head(P, '我们现在的样子') + '<div>窗边的绿萝又长了一截。你说，等它爬到书架顶，我们就去看一次海。</div>'
             + blank + ed_head(P, '明年想做的事') + f'<div>{syn(P, "- ")}把阳台改成能坐下来喝茶的地方</div>'
             + f'<div>{syn(P, "- ")}每个月留出一个不安排任何事的周末</div>')
    foot = (f'<div style="flex:none;display:flex;align-items:center;gap:10px;padding:12px 16px 20px 28px"><div style="flex:1;'
            f'font-size:13px;color:{P["muted"]}">{M("1,286", 15, P["ink"])} 字 · 基于 {M("v8", 13)}</div>'
            f'<button type="button" disabled style="display:inline-flex;align-items:center;min-height:48px;padding:0 22px;border:0;'
            f'border-radius:24px;background:{T(P, "ink", .12)};color:{P["muted"]};font-family:inherit;font-size:15px;font-weight:500">'
            f'存为 {M("v9", 15)}</button></div>')
    body = (bar3(P, 'New-Writing-List.dc.html', '写作', 'pen', 'B', '给明年秋天的信', btn_icon(P, '更多', 'more')) + banner
            + editor_paper(P, parts) + foot)
    wr('New-Writing-Behind.dc.html', page(P, '写作 · 基线落后', body))


def b_focus():
    P = NIGHT
    dim = P['faint']
    blank = f'<div style="height:{LH}px"></div>'
    parts = (f'<div style="color:{dim}">' + ed_head(P, '我们现在的样子')
             + f'<div>窗边的绿萝又长了一截。<span style="color:{P["ink"]};background:{T(P, "accent", .08)};border-radius:3px;'
             f'padding:3px 0">你说，等它爬到书架顶，我们就去看一次海。</span>{caret(P)}</div>'
             + blank + ed_head(P, '明年想做的事') + f'<div>{syn(P, "- ")}把阳台改成能坐下来喝茶的地方</div>'
             + f'<div>{syn(P, "- ")}每个月留出一个不安排任何事的周末</div><div>{syn(P, "- ")}一起把那本读到一半的书读完</div>'
             + blank + ed_head(P, '写给明年的你') + '<div>希望那时候，我们还会在周末的早上慢慢醒来，谁也不急着看手机。</div></div>')

    def fb(lab, inner):
        return (f'<button type="button" aria-label="{lab}" style="display:inline-flex;align-items:center;justify-content:center;'
                f'flex:none;width:44px;height:44px;border:0;padding:0;border-radius:12px;background:transparent;color:{P["ink"]};'
                f'font-family:inherit">{inner}</button>')

    toolbar = (f'<div role="toolbar" aria-label="格式" style="flex:none;display:flex;align-items:center;margin:12px 10px 14px;'
               f'padding:2px 4px;border-radius:16px;background:{P["card"]};box-shadow:{P["lift"]}">'
               + fb('标题', M('#', 18, None, 500)) + fb('加粗', '<span style="font-size:17px;font-weight:700">B</span>')
               + fb('列表', icon('list', 20)) + fb('勾选框', icon('checkbox', 20)) + fb('引用', icon('quote', 20))
               + fb('照片', icon('photo', 20)) + '<span style="flex:1"></span>' + fb('撤销', icon('undo', 20)) + fb('重做', icon('redo', 20))
               + '</div>')
    trailing = (f'<span style="display:inline-flex;margin-right:2px">{M("1,286", 13, P["muted"])}</span>'
                + btn_icon(P, '专注', 'focus', active=True) + btn_icon(P, '更多', 'more'))
    body = bar3(P, 'New-Writing-List.dc.html', '写作', 'pen', 'B', '给明年秋天的信', trailing) + editor_paper(P, parts) + toolbar
    wr('New-Writing-Focus.dc.html', page(P, '写作 · 专注', body))


def b_messages():
    P = DAY

    def letter(title, preview, w, date, extra, sc, rot, pinned=False):
        corner = (f'<span style="position:absolute;right:14px;top:14px">{stamp(P, scene(sc, 40, 46), 50, 58, 4)}</span>'
                  f'<span style="position:absolute;right:34px;top:44px">{postmark(P, "栖迟", date[:5], 40, -18, "B" if w == "迟" else "accent", False)}</span>')
        seal_ = f'<span style="position:absolute;left:-10px;top:-12px">{wax(P, w, 40, "A" if w == "栖" else "B")}</span>' if pinned else ''
        flap = (f'<svg aria-hidden="true" width="100%" height="28" viewBox="0 0 300 28" preserveAspectRatio="none" style="position:absolute;'
                f'left:0;top:0"><path d="M0 0L150 26 300 0" fill="none" stroke="{P["line2"]}" stroke-width="1"/></svg>')
        return card(P, f'{flap}{corner}{seal_}<div style="padding-right:84px;padding-top:12px"><div style="font-size:17px;'
                       f'font-weight:700;line-height:1.45">{title}</div><p style="margin:4px 0 0;font-size:14px;line-height:1.6;'
                       f'color:{P["muted"]};display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden">'
                       f'{preview}</p></div><div style="display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-top:8px;'
                       f'font-size:12px;color:{P["muted"]}">{extra}<span style="flex:1"></span>'
                       f'{hand(P, "—— " + ("阿栖" if w == "栖" else "小迟"), 18, "A" if w == "栖" else "B", -3)}</div>',
                    None, rot, '16px 18px 12px', 6)

    content = (letter('关于搬家后的第一个冬天', '暖气要不要早点开？我想了几个办法，先把窗缝贴上，再看看要不要添一台小电暖器。',
                      '栖', '09.10', f'<span>{M("4", 12)} 回复</span>{dotsep(P)}<span>拥抱 {M("2", 12)}</span>', 'snow', -.8, True)
               + letter('谢谢你那天来接我', '下那么大的雨，你还是来了。我在出站口看到你的时候，其实差点哭出来。', '迟', '09.22',
                        f'<span>{M("1", 12)} 回复</span>{dotsep(P)}<span>喜欢 {M("1", 12)}</span>', 'rain', .7)
               + letter('下个月的钱怎么花', '列了一下固定开销和想存的钱，有几项想和你商量，尤其是旅行那一笔。', '栖', '09.15',
                        f'<span>{M("3", 12)} 回复</span>{dotsep(P)}<span>改过 {M("2", 12)} 次</span>', 'window', -.5))
    body = (bar2(P, 'New-Together-Create.dc.html', '留言', 'mail', 'A', btn_icon(P, '搜索', 'search'), '慢慢说')
            + scroll(content, '14px 24px 100px', 24) + fab(P, '写留言'))
    wr('New-Messages.dc.html', page(P, '留言', body))


# ================================================================ 回看
def mood_chip(P, w, word, n):
    key = 'A' if w == '栖' else 'B'
    ic = dict(EMO)[word]
    return (f'<span style="display:inline-flex;align-items:center;gap:6px;padding:3px 10px 3px 4px;border-radius:16px;'
            f'background:{T(P, key, .12)}">{mark(P, w, 20)}<span style="display:inline-flex;color:{P[key]}">{icon(ic, 15, 1.7)}</span>'
            f'<span style="font-size:14px">{word}</span>{M(n, 12, P["muted"])}</span>')


def b_timeline():
    P = DAY

    def day(wd, d, inner, first=False, today=False):
        num = (f'<span style="display:inline-flex;align-items:center;justify-content:center;width:40px;height:40px;border-radius:50%;'
               f'background:{P["ink"]};color:{P["bg"]};font-family:{MONO};font-size:20px">{d}</span>' if today else
               f'<span style="font-family:{MONO};font-size:26px;line-height:1.1">{d}</span>')
        return (f'<article style="position:relative;display:flex;gap:14px;padding:16px 0;{divtop(P, first)}"><div style="width:44px;'
                f'flex:none;display:flex;flex-direction:column;align-items:center;gap:2px"><span style="font-size:12px;font-weight:500;'
                f'color:{P["muted"]};line-height:1.5">{wd}</span>{num}</div><div style="flex:1;min-width:0;display:flex;'
                f'flex-direction:column;gap:10px">{inner}</div></article>')

    def item(ic, key, lab, content):
        return (f'<div><div style="display:flex;align-items:center;gap:5px;font-size:12px;font-weight:700;color:{P[key]};'
                f'line-height:1.5">{icon(ic, 13, 1.8)}{lab}</div><div style="font-size:15px;line-height:1.6">{content}</div></div>')

    moods = f'<div style="display:flex;flex-wrap:wrap;gap:6px">{mood_chip(P, "栖", "平静", "7")}{mood_chip(P, "迟", "疲惫", "6")}</div>'
    photos = (f'<div style="display:flex;gap:10px;padding:6px 0 2px 4px">{polaroid(P, scene("window", 112, 84), "绿萝又长了", -3, tape(P, "A", 40, -6))}'
              f'{polaroid(P, scene("dinner", 112, 84), "一起做的饭", 2.5)}</div>')
    qa = (f'<div style="font-weight:500">搬家那天，你最记得什么？</div><div style="display:flex;gap:8px;align-items:baseline;margin-top:2px;'
          f'font-size:14px;color:{P["muted"]}">{mark(P, "栖", 16)}<span>你先把我的书都收好了。</span></div>')
    dec = (f'<div style="display:flex;align-items:center;gap:10px"><span style="flex:1">国庆先回家两天，再去海边三天</span>'
           f'{seal(P, "定", 26, 8)}</div>')
    content = (f'<div style="position:relative;display:flex;align-items:baseline;gap:8px">{watermark(P, "09", 120, "right:-6px;top:-44px")}'
               f'<span style="font-size:20px;font-weight:700">九月</span>{M("2026", 15, P["muted"])}</div><div>'
               + day('周四', '24', item('mood', 'A', '心情', moods), True, True)
               + day('周一', '21', photos)
               + day('周日', '20', item('sign', 'accent', '决定', dec))
               + day('周五', '18', item('qna', 'B', '问答', qa)) + '</div>')
    body = (bar2(P, 'New-Together-Look.dc.html', '时间线', 'timeline', 'A', btn_icon(P, '搜索', 'search'), '我们的日记')
            + tabs(P, ['按天', '照片'], '按天') + scroll(content, '8px 28px 24px', 2))
    wr('New-Timeline.dc.html', page(P, '时间线', body))


def b_timeline_loading():
    P = DAY

    def bar_(w, h=12, r=6):
        return f'<span style="display:block;width:{w};height:{h}px;border-radius:{r}px;background:{T(P, "ink", .08)}"></span>'

    def sk(first=False):
        return (f'<div style="display:flex;gap:14px;padding:18px 0;{divtop(P, first)}"><div style="width:44px;flex:none;display:flex;'
                f'flex-direction:column;align-items:center;gap:8px">{bar_("24px", 10)}{bar_("34px", 26, 8)}</div><div style="flex:1;'
                f'display:flex;flex-direction:column;gap:10px">{bar_("40%", 10)}{bar_("92%")}{bar_("70%")}'
                f'<div style="display:flex;gap:10px">{bar_("112px", 84, 4)}{bar_("112px", 84, 4)}</div></div></div>')

    content = (f'<div style="display:flex;align-items:center;gap:10px;padding:4px 0 6px">{sprig(P, 70)}{hand(P, "正在翻找以前的日子……", 20, "muted", -2)}</div>'
               + f'<div style="opacity:.9">{sk(True)}{sk()}{sk()}</div>')
    body = (bar2(P, 'New-Together-Look.dc.html', '时间线', 'timeline', 'A', btn_icon(P, '搜索', 'search'), '我们的日记')
            + tabs(P, ['按天', '照片'], '按天') + scroll(content, '8px 28px 24px', 0))
    wr('New-Timeline-Loading.dc.html', page(P, '时间线 · 加载中', body))


def b_archive():
    P = DAY
    TYPES = {'偏好': ('heart', 'A'), '共识': ('rings', 'B'), '边界': ('lock', 'accent'), '里程碑': ('flag', 'B')}

    def fact(typ, text, tags, src, date, w, rev='', rot=0):
        ic, key = TYPES[typ]
        t = ''.join(tag(P, x) for x in tags)
        return (f'<a href="#" style="position:relative;display:block;padding:14px 16px 12px;border-radius:6px;background:{P["card"]};'
                f'box-shadow:{P["lift"]};border-top:3px solid {T(P, key, .7)};color:{P["ink"]};text-decoration:none;'
                f'{"transform:rotate(%sdeg);" % rot if rot else ""}"><div style="display:flex;align-items:center;gap:6px;font-size:12px;'
                f'font-weight:700;color:{P[key]}">{icon(ic, 13, 1.8)}{typ}<span style="flex:1"></span>{who(P, w, 18, P["card"])}</div>'
                f'<p style="margin:4px 0 0;font-size:16px;line-height:1.65">{text}</p><div style="display:flex;align-items:center;'
                f'flex-wrap:wrap;gap:8px;margin-top:6px">{t}<span style="display:inline-flex;align-items:center;gap:3px;font-size:12px;'
                f'color:{P["muted"]}">{icon("arrow", 12)}来自{src}</span>{M(date, 12, P["muted"])}{M(rev, 12, P["muted"]) if rev else ""}'
                f'</div></a>')

    content = (fact('偏好', '小迟喝咖啡不加糖，下午三点以后不喝。', ['#吃'], '聊天', '08.02', '迟', 'v2', -.4)
               + fact('偏好', '阿栖怕冷，出门记得多带一件外套。', ['#日常'], '聊天', '09.15', '栖', '', .4)
               + fact('共识', '每个月留出一个不安排任何事的周末。', ['#家'], '写作', '09.21', 'both', '', -.3)
               + fact('边界', '吵架不过夜，但可以先各自安静一会儿。', [], '留言', '06.30', 'both', '', .3)
               + fact('里程碑', '书架搬进新家。', ['#家'], '时间线', '2025.09.24', 'both'))
    trailing = btn_icon(P, '标签', 'tag') + btn_icon(P, '搜索', 'search')
    body = (bar2(P, 'New-Together-Look.dc.html', '档案', 'archive', 'muted', trailing)
            + chips(P, ['全部', '#家', '#吃', '#日常', '#旅行'], '全部') + scroll(content, '6px 24px 24px', 14))
    wr('New-Archive.dc.html', page(P, '档案', body))


def b_decision_list():
    P = DAY

    def item(q, status, key, meta, w, rot, done):
        st = seal(P, '定', 30, 10) if done else sticker(P, '还在想', 'B', -5, 15)
        return card(P, f'<div style="display:flex;gap:12px;align-items:flex-start"><div style="flex:1;min-width:0"><div style="font-size:17px;'
                       f'font-weight:700;line-height:1.5">{q}</div><div style="margin-top:4px;font-size:13px;color:{P["muted"]}">{meta}</div>'
                       f'</div>{st}</div><div style="display:flex;align-items:center;gap:8px;margin-top:10px">{who(P, w, 18, P["card"])}'
                       f'<span style="font-size:12px;color:{P[key]};font-weight:500">{status}</span></div>', None, rot, '16px 18px 14px', 12)

    content = (item('国庆七天，去海边还是回家？', '选了 C：先回家两天，再去海边三天', 'accent', f'{M("10.08", 12)} 回头看看', 'both', -.5, True)
               + item('要不要养一只猫？', '两个人各自写了在意的事', 'B', f'{M("09.02", 12)} 提出', 'both', .6, False)
               + item('换不换大一点的房子', '先不换，把阳台用起来', 'accent', f'明年 {M("03.01", 12)} 回头看看', 'both', -.3, True))
    body = (bar2(P, 'New-Together-Look.dc.html', '决定', 'sign', 'accent', '', '想清楚再定')
            + scroll(content, '10px 24px 100px', 18) + fab(P, '新决定'))
    wr('New-Decision-List.dc.html', page(P, '决定', body))


def b_decision():
    P = DAY

    def opt(k, v, chosen=False):
        look = (f'background:linear-gradient({T(P, "accent", .1)},{T(P, "accent", .1)}),{P["card"]};box-shadow:{P["lift"]},inset 0 0 0 1.3px {T(P, "accent", .5)}'
                if chosen else f'background:{P["card"]};box-shadow:{P["lift"]}')
        stamp_ = (f'<span style="position:absolute;right:10px;top:50%;margin-top:-26px;display:inline-flex;align-items:center;'
                  f'justify-content:center;width:52px;height:52px;border-radius:50%;border:2px solid {T(P, "accent", .75)};'
                  f'color:{T(P, "accent", .85)};font-size:15px;font-weight:700;letter-spacing:.1em;transform:rotate(-16deg);'
                  f'box-shadow:inset 0 0 0 3px {T(P, "accent", .15)}">定了</span>' if chosen else '')
        return (f'<div style="position:relative;display:flex;gap:12px;align-items:center;min-height:48px;padding:6px 14px;'
                f'border-radius:10px;{look}">{M(k, 15, P["accent"], 500)}<span style="flex:1;font-size:15px;line-height:1.55;'
                f'padding-right:{56 if chosen else 0}px">{v}</span>{stamp_}</div>')

    def care(w, t, rot):
        key = 'A' if w == '栖' else 'B'
        return card(P, f'{mark(P, w, 22)}<div style="margin-top:6px;font-size:15px;line-height:1.6">{t}</div>', key, rot, '12px 14px', 10)

    content = (f'<div style="position:relative"><span aria-hidden="true" style="position:absolute;right:-6px;top:-30px;font-family:{SERIF};'
               f'font-size:120px;line-height:1;color:{T(P, "accent", .1)}">？</span><p style="position:relative;margin:0;font-size:22px;'
               f'font-weight:700;line-height:1.5">国庆七天，去海边还是回家？</p><div style="margin-top:4px;font-size:13px;'
               f'color:{P["muted"]}">{M("09.18", 13)} 提出 · 来自聊天</div></div>'
               + '<section>' + label(P, '备选', ic='list', key='accent') + '<div style="display:flex;flex-direction:column;gap:8px">'
               + opt('A', '去海边住三天，只有我们两个') + opt('B', '各自回家陪父母') + opt('C', '先回家两天，再去海边三天', True) + '</div></section>'
               + '<section>' + label(P, '各自在意', ic='heart', key='A') + '<div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));'
               f'gap:14px">{care("栖", "想有一段只属于两个人的时间", -1)}{care("迟", "妈妈说想见见我们", 1)}</div></section>'
               + '<section>' + label(P, '复查', ic='cal', key='B') + f'<div style="display:flex;align-items:center;gap:12px;min-height:44px">'
               f'{tile(P, "cal", "B", 34)}<span style="flex:1;font-size:15px">到时候回头看看这个决定</span>{M("10.08", 14, P["muted"])}</div></section>')
    body = (bar3(P, 'New-Decision-List.dc.html', '决定', 'sign', 'accent', '国庆怎么过', btn_icon(P, '更多', 'more'))
            + scroll(content, '8px 28px 24px', 20))
    wr('New-Decision.dc.html', page(P, '决定 · 国庆怎么过', body))


def cover(P, title, w, h, fs, key=None, rib=False):
    bg = f'linear-gradient({T(P, key, .22)},{T(P, key, .22)}),{P["card"]}' if key else P['card']
    return (f'<span aria-hidden="true" style="position:relative;display:flex;justify-content:center;flex:none;box-sizing:border-box;'
            f'width:{w}px;height:{h}px;padding-top:{int(h * .14)}px;border-radius:3px 6px 6px 3px;background:{bg};'
            f'box-shadow:inset 6px 0 0 {T(P, "ink", .08)},{P["lift"]};writing-mode:vertical-rl;font-family:{SERIF};font-size:{fs}px;'
            f'letter-spacing:.3em;color:{P["ink"]}">{title}{ribbon(P, "accent", 10, int(h * .35)) if rib else ""}'
            f'<span style="position:absolute;left:50%;bottom:10px;margin-left:-6px;width:12px;height:12px;border-radius:50%;'
            f'border:1px solid {T(P, "ink", .3)}"></span></span>')


def prog(P, w, pct):
    col = P['A'] if w == '栖' else P['B']
    return (f'<div style="display:flex;align-items:center;gap:10px">{mark(P, w, 18)}<span style="flex:1;position:relative;'
            f'height:6px;border-radius:3px;background:{T(P, "A" if w == "栖" else "B", .15)}"><span style="position:absolute;left:0;top:0;'
            f'height:6px;border-radius:3px;width:{pct}%;background:{col}"></span></span>{M(str(pct) + "%", 13, P["muted"])}</div>')


def b_shelf():
    P = DAY
    cur_ = (f'<a href="New-Reading.dc.html" style="display:flex;gap:20px;align-items:flex-end;color:{P["ink"]};text-decoration:none">'
            f'{cover(P, "海边的旅店", 104, 148, 15, "B", True)}<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:10px">'
            f'<div><div style="font-size:18px;font-weight:700;line-height:1.4">海边的旅店</div>{M("iii / xii", 13, P["muted"])}</div>'
            f'{prog(P, "栖", 42)}{prog(P, "迟", 61)}<span>{sticker(P, "共读 · 每晚一章", "accent", -3, 16)}</span></div></a>')

    def shelf(t, st, key):
        return (f'<a href="#" style="display:flex;flex-direction:column;align-items:center;gap:8px;color:{P["ink"]};'
                f'text-decoration:none">{cover(P, t, 86, 122, 13, key)}<span style="font-size:12px;color:{P["muted"]}">{st}</span></a>')

    plank = (f'<div aria-hidden="true" style="height:8px;margin:-34px -6px 0;border-radius:3px;background:{T(P, "ink", .12)};'
             f'box-shadow:0 6px 10px {T(P, "ink", .06)}"></div>')
    content = ('<section>' + label(P, '在读', ic='bookmark', key='accent') + cur_ + '</section><section>'
               + label(P, '书架', M('3', 13, P['muted']), 'book', 'B')
               + f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px 12px;margin-top:10px">'
               f'{shelf("雾中的灯塔", "想读", "A")}{shelf("慢慢来", "读完", None)}{shelf("山居笔记", "想读", "accent")}</div>'
               + plank + '</section>')
    body = (bar2(P, 'New-Together-Look.dc.html', '阅读', 'book', 'B', btn_icon(P, '搜索', 'search'), '一起读')
            + scroll(content, '8px 28px 100px', 30) + fab(P, '加书'))
    wr('New-Reading-Shelf.dc.html', page(P, '阅读', body))


def b_reading():
    P = DAY

    def tb(x):
        return (f'<button type="button" style="min-height:44px;padding:0 9px;border:0;background:transparent;color:inherit;'
                f'font-family:inherit;font-size:14px;font-weight:500">{x}</button>')

    toolbar = (f'<span role="toolbar" aria-label="选中的文字" style="position:absolute;left:14px;top:calc(100% + 4px);z-index:4;'
               f'display:inline-flex;align-items:center;padding:0 6px;border-radius:22px;background:{P["ink"]};color:{P["bg"]};'
               f'font-family:{SANS};line-height:1;box-shadow:{P["fab"]};white-space:nowrap">{tb("标注")}{tb("摘录")}<span '
               f'aria-hidden="true" style="width:1px;height:16px;margin:0 4px;background:{P["bg"]};opacity:.3"></span><span '
               f'style="display:inline-flex;align-items:center;padding-left:6px;opacity:.8">{icon("spark", 13, 1.6)}</span>{tb("解释")}'
               f'{tb("对比")}</span>')
    note = (f'<div style="display:flex;align-items:center;gap:6px;margin:0 0 14px;color:{P["B"]}"><svg width="30" height="20" '
            f'viewBox="0 0 30 20" aria-hidden="true"><path d="M26 2C18 4 10 8 4 16M4 16l1-6M4 16l6-1" fill="none" stroke="{P["B"]}" '
            f'stroke-width="1.3" stroke-linecap="round"/></svg>{mark(P, "迟", 18)}{hand(P, "我也喜欢这一句", 19, "B", -2)}</div>')
    # P12-01 沉浸阅读：书页铺满整屏，顶栏、进度、状态栏收起（点中间叫出）；右上角是这一页的书签
    pg = (f'<div style="position:relative;flex:1;min-height:0;padding:56px 26px 20px;'
          f'background:{P["paper"]};font-family:{SERIF};font-size:18px;line-height:2">{ribbon(P, "accent", 28, 74)}'
          f'<div style="font-family:{MONO};font-size:14px;color:{P["muted"]};margin-bottom:12px;line-height:1">— iii —</div>'
          f'<p style="margin:0 0 12px"><span style="float:left;font-size:56px;line-height:1;margin:6px 10px 0 0;color:{P["accent"]}">雨</span>'
          f'是从傍晚开始下的。旅店的老板把门口的灯提前点亮，玻璃上很快起了一层薄薄的雾。</p>'
          f'<p style="margin:0"><span style="text-decoration:underline;text-decoration-color:{P["B"]};text-decoration-thickness:2px;'
          f'text-underline-offset:6px;text-decoration-style:wavy">不必急着去哪里，先在这里坐一会儿。</span></p>{note}'
          f'<p style="margin:0 0 60px;position:relative">她于是真的坐了很久。窗外的雨没有停，<span style="background:{T(P, "accent", .18)};'
          f'border-radius:2px">楼下的灯却一直亮着</span>，像有人特意为她留的。{toolbar}</p>'
          f'<p style="margin:0">她把信重新展开，又慢慢折好。</p></div>')
    # 收起时底部只留一行小页码
    foot = (f'<div style="flex:none;display:flex;justify-content:center;align-items:center;height:28px;padding-bottom:14px;'
            f'background:{P["paper"]}">{M("118 / 286", 11, P["faint"])}</div>')
    body = pg + foot
    wr('New-Reading.dc.html', page(P, '阅读 · 海边的旅店', body, root_bg=P['paper']))


def docthumb(P):
    return (f'<span aria-hidden="true" style="position:relative;flex:none;width:44px;height:56px;box-sizing:border-box;padding:9px 7px;'
            f'border-radius:3px;background:#FFFFFF;box-shadow:{P["lift"]};display:flex;flex-direction:column;gap:4px;'
            f'clip-path:polygon(0 0,75% 0,100% 20%,100% 100%,0 100%)"><span style="height:3px;width:60%;background:#CDD2D1;border-radius:2px">'
            f'</span><span style="height:2px;background:#DFE2E0;border-radius:2px"></span><span style="height:2px;background:#DFE2E0;'
            f'border-radius:2px"></span><span style="position:absolute;left:14px;top:26px;width:20px;height:12px;border-radius:50%;'
            f'border:1.3px solid {P["accent"]};transform:rotate(-8deg)"></span></span>')


def b_review_list():
    P = DAY

    def filerow(href, title, ver, kind, notes, w, date, stk, first=False):
        return (f'<a href="{href}" style="display:flex;align-items:center;gap:16px;min-height:80px;{divtop(P, first)}'
                f'color:{P["ink"]};text-decoration:none">{docthumb(P)}<div style="flex:1;min-width:0"><div style="display:flex;'
                f'align-items:center;gap:10px"><span style="font-size:17px;font-weight:700">{title}</span>{M(ver, 14, P["muted"])}{stk}'
                f'</div><div style="display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-top:4px;font-size:13px;'
                f'color:{P["muted"]}">{kind}{dotsep(P)}{notes}{who(P, w, 16)}</div></div>{M(date, 12, P["muted"])}</a>')

    rows = (filerow('New-Review.dc.html', '报价方案', 'v3', 'PDF · 5 页',
                    '<span>1 条讨论</span>', '迟', '09.20', sticker(P, '讨论中', 'accent', -4, 14), True)
            + filerow('#', '装修预算', 'v2', '表格', '<span>3 条讨论</span>', '栖', '09.16', '')
            + filerow('#', '租房合同', 'v1', 'PDF · 9 页', '<span>还没看</span>', 'both', '09.09', sticker(P, '新', 'B', 4, 14)))
    body = (bar2(P, 'New-Together-Look.dc.html', '审稿', 'review', 'muted', btn_icon(P, '搜索', 'search'))
            + tabs(P, ['进行中', '已归档'], '进行中') + scroll(f'<div>{rows}</div>', '0 28px 100px', 0) + fab(P, '上传'))
    wr('New-Review-List.dc.html', page(P, '审稿', body))


def b_review():
    P = DAY
    strip = (f'<div style="flex:none;display:flex;align-items:center;gap:10px;min-height:44px;padding:0 28px 6px">'
             f'{M("v3", 17, None, 500)}{act(P, "对比 v2", P["muted"])}<span style="flex:1"></span>{M("2 / 5", 14, P["muted"])}</div>')
    prev = (f'<div style="position:relative;flex:none;margin:0 28px;border-radius:6px;overflow:visible;box-shadow:{P["lift"]}">{DOC}'
            f'<span style="position:absolute;right:-10px;top:-12px">{sticker(P, "第 2 页", "muted", 6, 15).replace("background:" + T(P, "muted", .08), "background:" + P["card"])}</span></div>')
    ann = (f'<div style="flex:none;padding:0 28px"><div style="display:flex;gap:14px;padding:8px 0 4px"><span style="display:inline-flex;'
           f'align-items:center;justify-content:center;flex:none;width:24px;height:24px;border-radius:50%;border:1.3px solid '
           f'{P["accent"]}">{M("1", 13, P["accent"], 500)}</span><div style="flex:1"><div style="font-size:16px;line-height:1.65">'
           f'C4 的单价与合同里的不一致</div><div style="display:flex;align-items:center;gap:8px;margin-top:2px;font-size:12px;'
           f'color:{P["muted"]}">{mark(P, "迟", 16)}<span>讨论中</span>{dotsep(P)}<span>{M("2", 12)} 条回复</span></div></div></div></div>')
    ai = (f'<div style="flex:none;margin:auto 16px 18px;padding:14px 18px 6px;border-radius:14px;border:1.3px dashed {T(P, "B", .45)};'
          f'background:linear-gradient({T(P, "B", .06)},{T(P, "B", .06)}),{P["card"]}"><div style="display:flex;align-items:center;gap:10px">'
          f'{aimark(P)}<span style="font-size:16px;font-weight:600;line-height:1.6">付款条款前后矛盾</span></div><div style="display:grid;'
          f'grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin:8px 0 2px"><div style="padding:8px 10px;border-radius:8px;'
          f'background:{P["card"]}"><div style="font-size:15px">“首付 30%”</div>{M("p.2 ¶3", 12, P["muted"])}</div><div style="padding:8px 10px;'
          f'border-radius:8px;background:{P["card"]}"><div style="font-size:15px">“首付 40%”</div>{M("p.4 ¶1", 12, P["muted"])}</div></div>'
          f'<div style="display:flex;justify-content:flex-end;align-items:center;gap:8px">{act(P, "忽略", P["muted"])}'
          f'{outline(P, "转为批注")}</div></div>')
    body = (bar3(P, 'New-Review-List.dc.html', '审稿', 'review', 'muted', '报价方案', btn_icon(P, '更多', 'more')) + strip + prev
            + tabs(P, ['批注', 'AI'], '批注', '14px 28px 4px') + ann + ai)
    wr('New-Review.dc.html', page(P, '审稿 · 报价方案', body))


def b_summary_list():
    P = DAY

    def item(href, sc, big, title, meta, stk, rot, extra=''):
        return card(P, f'<a href="{href}" style="display:flex;gap:14px;align-items:center;color:{P["ink"]};text-decoration:none">'
                       f'<span style="position:relative;flex:none">{scene(sc, 72, 72, 10)}<span style="position:absolute;left:6px;'
                       f'bottom:2px;font-family:{MONO};font-size:26px;font-weight:500;color:#FFFFFF;text-shadow:0 1px 6px rgba(0,0,0,.35)">'
                       f'{big}</span></span><div style="flex:1;min-width:0"><div style="display:flex;align-items:center;gap:8px">'
                       f'<span style="font-size:17px;font-weight:700">{title}</span>{stk}</div><div style="margin-top:4px;font-size:13px;'
                       f'color:{P["muted"]}">{meta}</div></div>{extra}</a>', None, rot, '12px 14px', 14)

    content = ('<section>' + label(P, '年度', ic='spark', key='accent')
               + item('#', 'fog', '25', '2025 年度回顾', f'{aimark(P, 12)} 派生 · 不可撤回', '', -.4,
                      f'<span style="flex:none">{wax(P, "年", 44, "accent")}</span>') + '</section>'
               + '<section>' + label(P, '每月', ic='cal', key='A')
               + item('New-Summary.dc.html', 'sea', '09', '九月', f'{M("09.01 – 09.24", 12)} · 还在写', sticker(P, '新', 'A', -4, 14), .5)
               + '<div style="height:14px"></div>'
               + item('#', 'window', '08', '八月', f'{M("08.01 – 08.31", 12)}', '', -.5) + '</section>'
               + '<section>' + label(P, '每周', ic='clock', key='B')
               + item('#', 'dinner', '38', '第 38 周', f'{M("09.14 – 09.20", 12)}', '', .3) + '</section>')
    body = (bar2(P, 'New-Together-Look.dc.html', '总结', 'summary', 'accent', btn_icon(P, '更多', 'more'), '回头看看')
            + scroll(content, '8px 24px 24px', 16))
    wr('New-Summary-List.dc.html', page(P, '总结', body))


def b_summary():
    P = DAY

    def src(n, ic, key, feat, text, date, first=False):
        return (f'<a href="#" style="display:flex;align-items:center;gap:12px;padding:10px 0;{divtop(P, first)}color:{P["ink"]};'
                f'text-decoration:none">{refnum(P, n)}{tile(P, ic, key, 30)}<span style="flex:1;min-width:0"><span style="display:block;'
                f'font-size:12px;font-weight:700;color:{P[key]};line-height:1.5">{feat}</span><span style="display:block;font-size:14px;'
                f'line-height:1.55">{text}</span></span>{M(date, 12, P["muted"])}</a>')

    hero = (f'<div style="position:relative;flex:none">{scene("sea", 334, 128, 14, "九月")}<span style="position:absolute;left:16px;'
            f'bottom:8px;font-family:{MONO};font-size:54px;font-weight:300;line-height:1;color:#FFFFFF;text-shadow:0 2px 10px rgba(0,0,0,.3)">'
            f'09</span><span style="position:absolute;right:14px;bottom:12px">{seal(P, "栖迟", 34, -8)}</span>'
            f'{tape(P, "B", 56, 5, "top:-8px;right:40px", .35)}</div>')
    content = (hero + f'<div><div style="display:flex;align-items:center;gap:8px">{aimark(P)}<span style="font-size:13px;color:{P["muted"]}">'
               f'整理</span><span style="flex:1"></span>{M("09.01 – 09.24", 13, P["muted"])}</div><p style="margin:10px 0 0;font-size:21px;'
               f'font-weight:700;line-height:1.5">这个月，{marker(P, "海边从念头变成了计划", "A", .22)}。</p><p style="margin:10px 0 0;'
               f'font-size:16px;line-height:1.85">九月初，你们把目的地收窄到两个海边小镇{ref(P, 1)}，又定下国庆先回家两天、再去海边三天'
               f'{ref(P, 2)}。小迟有几天很累{ref(P, 3)}，阿栖在信里写下了明年想一起做的事{ref(P, 4)}。</p></div>'
               + '<section>' + label(P, '来源', ic='arrow', key='B')
               + src(1, 'flag', 'accent', '计划', '收窄到两个海边小镇', '09.18', True) + src(2, 'sign', 'accent', '决定', '国庆怎么过', '09.20')
               + src(3, 'mood', 'A', '心情', '小迟 · 疲惫 6', '09.22') + src(4, 'pen', 'B', '写作', '给明年秋天的信 v8', '09.21') + '</section>')
    body = (bar3(P, 'New-Summary-List.dc.html', '总结', 'summary', 'accent', '九月', btn_icon(P, '更多', 'more'))
            + scroll(content, '6px 28px 24px', 20))
    wr('New-Summary.dc.html', page(P, '总结 · 九月', body))


# ================================================================ 字与层次、装饰
def b_spec():
    P = DAY
    W, H = 1280, 2600

    def spec(name, detail, sample):
        return (f'<div style="display:grid;grid-template-columns:136px minmax(0,1fr);gap:20px;align-items:center;min-height:64px;'
                f'padding:14px 0;border-top:1px dashed {P["line2"]}"><div><div style="font-size:14px;font-weight:700;line-height:1.5">'
                f'{name}</div><div style="font-family:{MONO};font-size:12px;color:{P["muted"]};line-height:1.6">{detail}</div></div>'
                f'<div style="min-width:0">{sample}</div></div>')

    col1 = ''.join([
        spec('日期大字', 'Mono 300 · 96', f'<span style="font-family:{MONO};font-size:96px;font-weight:300;line-height:.8;letter-spacing:-.05em">24</span>'),
        spec('页面大标题', 'Sans 600 · 30 + 荧光笔', f'<span style="font-size:30px;font-weight:600;line-height:1.3">{marker(P, "一起")}</span>'),
        spec('单项顶栏', 'Sans 700 12 + 600 17', f'<div style="display:flex;flex-direction:column"><span style="display:inline-flex;'
             f'align-items:center;gap:5px;font-size:12px;font-weight:700;color:{P["accent"]}">{icon("flag", 13, 1.8)}计划</span>'
             f'<span style="font-size:17px;font-weight:600">秋天去一次海边</span></div>'),
        spec('正文', 'Sans 400 · 16/1.7', '<p style="margin:0;font-size:16px;line-height:1.7">窗边的绿萝又长了一截。你说，等它爬到书架顶，我们就去看一次海。</p>'),
        spec('手写', 'Long Cang · 16–26', f'{hand(P, "午后好", 26, "ink", -5)}　{hand(P, "我也喜欢这一句", 19, "B", -2)}'),
    ])
    col2 = ''.join([
        spec('小标题', 'Sans 700 · 13 + 点线', label(P, '待办', M('1/4', 12, P['muted']), 'todo', 'B')),
        spec('数字与时间', 'Mono 400 · 12–26', f'<div style="display:flex;align-items:baseline;gap:18px">{M("19:30", 26)}{M("v8", 17)}'
             f'{M("1,286", 17)}{M("09.24", 13, P["muted"])}</div>'),
        spec('标签', 'Mono 500 · 12.5', f'<div style="display:flex;gap:8px">{tag(P, "#旅行/北方")}{tag(P, "#家")}{tag(P, "信", "B")}</div>'),
        spec('书页', 'Serif 400 · 18/2.0', f'<p style="margin:0;font-family:{SERIF};font-size:18px;line-height:2">雨是从傍晚开始下的。</p>'),
        spec('按钮', 'Sans 500 · 15', f'<div style="display:flex;align-items:center;gap:12px">{primary(P, "记下")}{pill(P, "拥抱", "heart")}'
             f'{act(P, "我的回答")}</div>'),
    ])

    def demo(title, inner, h=190, pad='22px'):
        return (f'<div style="display:flex;flex-direction:column;gap:10px"><div style="font-size:14px;font-weight:700">{title}</div>'
                f'<div style="position:relative;display:flex;align-items:center;justify-content:center;gap:18px;width:260px;height:{h}px;'
                f'box-sizing:border-box;padding:{pad};border-radius:14px;background:{P["bg"]};overflow:hidden">{inner}</div></div>')

    decor = ''.join([
        demo('胶带 + 拍立得', polaroid(P, scene('window', 118, 88), '绿萝又长了', -4, tape(P, 'A', 46, -6))),
        demo('邮票 + 邮戳', f'<span style="position:relative">{stamp(P, scene("shelf", 84, 96), 96, 108, -3)}<span style="position:absolute;'
                          f'left:58px;top:-10px">{postmark(P, "栖迟", "25.09.24", 60, -16)}</span></span>'),
        demo('印章', f'{seal(P, "栖迟", 48, -8)}{seal(P, "定", 34, 8)}'),
        demo('蜡封', f'{wax(P, "迟", 64, "B")}{wax(P, "年", 52, "accent")}'),
        demo('绿萝线描', sprig(P, 200)),
        demo('荧光笔 + 手写', f'<div style="text-align:center"><div style="font-size:26px;font-weight:600">{marker(P, "一起")}</div>'
                          f'{hand(P, "我们的小日子", 22, "muted", -4)}</div>'),
        demo('功能色块', ''.join(tile(P, ic, k, 40) for ic, k in [('mood', 'A'), ('qna', 'B'), ('flag', 'accent'), ('archive', 'muted')])),
        demo('贴纸', f'<div style="display:flex;flex-direction:column;gap:14px;align-items:center">{sticker(P, "需要安慰", "accent", -6)}'
                   f'{sticker(P, "第 41 题", "B", 4, 15)}</div>'),
        demo('横线纸 + 书签带', f'<div style="position:relative;width:200px;height:130px;padding:14px 12px 0 54px;box-sizing:border-box;'
                             f'border-radius:6px;{ruled(P, 26, 39)};box-shadow:{P["lift"]};font-size:14px;line-height:26px">{ribbon(P, "accent", 16, 40)}'
                             f'窗边的绿萝又长了一截。</div>'),
        demo('照片占位', ''.join(scene(k, 44, 60, 6) for k in ('sea', 'snow', 'dinner', 'rain')), 190, '12px'),
    ])
    names = [('bg', 'background'), ('surface', 'surface'), ('paper', 'paper'), ('ink', 'ink'), ('muted', 'muted'), ('faint', 'faint'),
             ('line', 'line'), ('line2', 'line2'), ('accent', 'accent'), ('A', 'personA'), ('B', 'personB'), ('on', 'onPerson')]

    def swatches(Q, lab):
        items = ''.join(f'<div style="display:flex;flex-direction:column;gap:6px;width:76px"><span style="height:44px;border-radius:6px;'
                        f'background:{Q[k]};box-shadow:inset 0 0 0 1px rgba(42,47,53,.1)"></span><span style="font-family:{MONO};'
                        f'font-size:11px;color:{P["muted"]}">{n}</span></div>' for k, n in names)
        return (f'<div style="display:flex;align-items:flex-end;gap:12px"><span style="width:60px;flex:none;font-size:14px;font-weight:700;'
                f'padding-bottom:26px">{lab}</span>{items}</div>')

    def sec_title(t, sub):
        return (f'<div style="display:flex;align-items:baseline;gap:16px;margin-bottom:12px"><h2 style="margin:0;font-size:22px;'
                f'font-weight:700">{marker(P, t, "accent", .2)}</h2><span style="font-size:14px;color:{P["muted"]}">{sub}</span></div>')

    rules = ''.join(f'<li style="display:flex;gap:10px"><span style="flex:none;color:{P["accent"]}">{icon("spark", 14, 1.6)}</span>'
                    f'<span>{t}</span></li>' for t in [
                        '每屏最多三种装饰；装饰贴在内容边上，不压住要读的字。',
                        '只用晨雾的颜色：玫瑰（阿栖）、雾蓝（小迟）、暮玫瑰（强调），淡色都是它们加透明。',
                        '功能的颜色固定：心情、日历、时间线、留言用玫瑰；问答、待办、写作、阅读用雾蓝；计划、灵感、决定、总结用暮玫瑰。',
                        '手写字只写短句：问候、批注、贴纸、签名；正文永远用黑体。',
                        '「减少动画」时装饰不动；深夜时胶带、贴纸都跟着变暗。'])
    body = (f'<div style="position:relative;display:flex;flex-direction:column;gap:56px;padding:72px">'
            f'<span aria-hidden="true" style="position:absolute;right:40px;top:40px">{sprig(P, 260, True)}</span>'
            f'<header><h1 style="margin:0;font-size:40px;font-weight:700;line-height:1.3">{marker(P, "字、层次与装饰")}</h1>'
            f'<p style="margin:10px 0 0;font-size:16px;color:{P["muted"]}">颜色沿用晨雾；正文黑体，数字等宽，标题靠粗细和荧光笔分层；'
            f'装饰只从下面这一套里挑。</p></header>'
            f'<section>{sec_title("字", "黑体管界面和正文，等宽管数字，宋体只留给书页，手写只写短句")}'
            f'<div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));column-gap:56px"><div>{col1}</div><div>{col2}</div></div></section>'
            f'<section>{sec_title("装饰", "每个功能都从这一套里取，不各画各的")}<div style="display:grid;grid-template-columns:repeat(4,260px);'
            f'justify-content:space-between;row-gap:34px">{decor}</div></section>'
            f'<section>{sec_title("规矩", "")}<ul style="margin:0;padding:0;list-style:none;display:flex;flex-direction:column;gap:10px;'
            f'font-size:16px;line-height:1.7">{rules}</ul></section>'
            f'<section>{sec_title("颜色", "一个都没改")}<div style="display:flex;flex-direction:column;gap:18px">{swatches(DAY, "白天")}'
            f'{swatches(NIGHT, "深夜")}</div></section></div>')
    wr('New-Spec.dc.html', page(P, '字、层次与装饰', body, W, H, root_bg=P['paper'], flex=False))


# ================================================================ 生成
def build():
    b_today(DAY, 'New-Today.dc.html', '今天', 1990)
    b_today(DAWN, 'New-Today-Dawn.dc.html', '今天 · 清晨', 844)
    b_today(DUSK, 'New-Today-Dusk.dc.html', '今天 · 黄昏', 844)
    b_today(NIGHT, 'New-Today-Night.dc.html', '今天 · 深夜', 844)
    b_chat()
    b_chat_stream()
    b_hub('New-Together.dc.html', '一起 · 生活', '生活')
    b_hub_create()
    b_hub('New-Together-Look.dc.html', '一起 · 回看', '回看')
    b_me()
    b_mood()
    b_qna()
    b_calendar()
    b_todo()
    b_plan_list()
    b_plan()
    b_ideas()
    b_ideas_empty()
    b_tags()
    b_writing_list()
    b_writing()
    b_writing_behind()
    b_focus()
    b_messages()
    b_timeline()
    b_timeline_loading()
    b_archive()
    b_decision_list()
    b_decision()
    b_shelf()
    b_reading()
    b_review_list()
    b_review()
    b_summary_list()
    b_summary()
    b_spec()


VOID = {'meta', 'link', 'input', 'br', 'img', 'hr', 'source', 'area', 'base', 'col', 'embed', 'param', 'track', 'wbr'}


class Check(HTMLParser):
    def __init__(self):
        super().__init__()
        self.stack, self.err = [], []

    def handle_starttag(self, tag, attrs):
        if tag not in VOID:
            self.stack.append(tag)

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        if self.stack and self.stack[-1] == tag:
            self.stack.pop()
        else:
            self.err.append((tag, self.stack[-4:]))


def check():
    bad = 0
    for name, text in OUT.items():
        c = Check()
        c.feed(text)
        left = re.findall(r'\{[A-Za-z_]+\(|\{P\[|\bNone\b', text)
        if c.err or c.stack or left:
            bad += 1
            print('!!', name, c.err[:3], c.stack[:6], left[:3])
    print(f'{len(OUT)} 张画板，{bad} 张有问题')


if __name__ == '__main__':
    build()
    check()
