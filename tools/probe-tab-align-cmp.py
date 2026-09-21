#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
出「底栏对齐修复」的前后对比图（真实应用截图，两张并排）。

改前那份是用 CSS 里旧的两个常数复现出来的（指示器 (栏宽-8)/n + 图标 translateY(-1px)），
改后是当前代码。两张都放大量出来，让偏差看得见。
"""
import os, threading, functools, http.server, socketserver
from PIL import Image, ImageDraw
from playwright.sync_api import sync_playwright

WEB = '/tmp/work/rel/app/src/main/assets/web'
OUT = '/workspace'
PORT = 8851

Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=WEB)


class Q(socketserver.TCPServer):
    allow_reuse_address = True


httpd = Q(('127.0.0.1', PORT), Handler)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
URL = 'http://127.0.0.1:%d/index.html' % PORT

# 改前：把 CSS 变量改回旧行为
BEFORE_CSS = """
document.documentElement.style.setProperty('--nav-cell-inset', '4px');
var st = document.createElement('style');
st.textContent = `
  .tab-ind { left: 4px !important; top: 4px !important; bottom: 4px !important;
             width: calc((100% - 8px) / var(--tab-count, 5)) !important; }
  .tab { margin-left: 0 !important; margin-right: 0 !important; }
  .tab.active .tab-ico { transform: translateY(-1px) !important; }
  html[data-nav="classic"] .tab-ind { left: 4px !important; top: 4px !important;
             bottom: 4px !important; width: calc((100% - 8px) / var(--tab-count, 5)) !important; }
  html[data-nav="classic"] .tab.active .tab-ico { transform: translateY(-1px) !important; }
`;
document.head.appendChild(st);
"""


def snap(pg, theme, nav, before, path):
    pg.evaluate("window.Onboarding.quit()"); pg.wait_for_timeout(200)
    pg.evaluate("window.App.setNavGlass(%s)" % ('true' if nav == 'glass' else 'false'))
    pg.wait_for_timeout(400)
    if theme == 'dark':
        pg.evaluate("window.Store.set('theme','dark'); window.App.applyTheme()")
        pg.wait_for_timeout(400)
    if before:
        pg.evaluate(BEFORE_CSS)
        # JS 的 _tabStep 也要回到旧算法，否则只有 CSS 复原、位移还是新的
        pg.evaluate("window.App._cellInset = function () { return 4; };")
    pg.evaluate("window.App.setTab('profile')"); pg.wait_for_timeout(600)
    bar = pg.evaluate("()=>{var b=document.getElementById('tabbar').getBoundingClientRect();return [b.left,b.top,b.width,b.height]}")
    clip = {'x': max(0, bar[0] - 8), 'y': bar[1] - 8, 'width': bar[2] + 16, 'height': bar[3] + 16}
    pg.screenshot(path=path, clip=clip)
    return pg.evaluate("""() => {
      var out = [], ind = document.getElementById('tab-ind').getBoundingClientRect();
      [].slice.call(document.querySelectorAll('#tabbar .tab')).forEach(function (t, i) {
        var ico = t.querySelector('.tab-ico').getBoundingClientRect();
        var lab = t.querySelector('.tab-label').getBoundingClientRect();
        out.push({ i: i, active: t.classList.contains('active'),
                   cx: (Math.min(ico.left, lab.left) + Math.max(ico.right, lab.right)) / 2,
                   cy: (Math.min(ico.top, lab.top) + Math.max(ico.bottom, lab.bottom)) / 2,
                   icx: (ind.left + ind.right) / 2, icy: (ind.top + ind.bottom) / 2 });
      });
      return out;
    }""")


with sync_playwright() as p:
    b = p.chromium.launch()
    shots = {}
    devs = {}
    for nav in ('classic', 'glass'):
        for theme in ('light', 'dark'):
            for before in (True, False):
                # ⚠️ 改前/改后必须各用一个**全新的页面**：
                # 注入的复现样式留在 head 里，同一页面再切到「改后」是切不回去的
                # （第一版就是这么把两张图截成一样的）。
                ctx = b.new_context(viewport={'width': 393, 'height': 852}, device_scale_factor=3)
                pg = ctx.new_page()
                pg.goto(URL); pg.wait_for_timeout(1200)
                tag = ('改前' if before else '改后')
                f = '/tmp/tabcmp-%s-%s-%s.png' % (nav, theme, tag)
                rows = snap(pg, theme, nav, before, f)
                if nav == 'classic':
                    shots[(theme, tag)] = f
                    devs[(theme, tag)] = rows
                ctx.close()

    # 拼对比图：浅色一组、深色一组，每组「改前 | 改后」上下排
    for theme in ('light', 'dark'):
        ims = [Image.open(shots[(theme, t)]).convert('RGB') for t in ('改前', '改后')]
        W = max(i.width for i in ims)
        H = sum(i.height for i in ims) + 30
        canvas = Image.new('RGB', (W, H), (255, 255, 255) if theme == 'light' else (13, 17, 23))
        y = 0
        d = ImageDraw.Draw(canvas)
        for t, im in zip(('改前', '改后'), ims):
            canvas.paste(im, (0, y))
            d.text((8, y + 4), t, fill=(200, 40, 40) if t == '改前' else (20, 130, 60))
            y += im.height + 15
        canvas.save(os.path.join(OUT, '底栏对齐-朴素模式-%s.png' % ('浅色' if theme == 'light' else '深色')))
        print('出图：底栏对齐-朴素模式-%s.png' % ('浅色' if theme == 'light' else '深色'))

    # 数值对照
    print('\n=== 朴素模式：改前 / 改后 各格偏差（内容中心 − 圈中心）===')
    for theme in ('light', 'dark'):
        for tag in ('改前', '改后'):
            rows = devs[(theme, tag)]
            own = [r for r in rows if r['active']][0]
            print('  %s / %-5s  选中格=第%d格  dx=%+6.2f  dy=%+6.2f   %s'
                  % (theme, tag, own['i'] + 1, own['cx'] - own['icx'], own['cy'] - own['icy'],
                     '✗' if abs(own['cx'] - own['icx']) > 1.5 or abs(own['cy'] - own['icy']) > 1.0 else '✓'))
        print()
    b.close()
