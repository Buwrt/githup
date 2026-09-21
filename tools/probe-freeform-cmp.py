#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""浮窗（自由窗口）适配对比：改前只有 ≤360 一档，极窄窗口顶栏标题被截、底栏挤。
改后补了 ≤330 一档。出 280 / 300 / 320 三档的并排图。"""
import os, threading, functools, http.server, socketserver
from PIL import Image, ImageDraw
from playwright.sync_api import sync_playwright

WEB = '/tmp/work/rel/app/src/main/assets/web'
OUT = '/workspace'
PORT = 8863

Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=WEB)


class Q(socketserver.TCPServer):
    allow_reuse_address = True


httpd = Q(('127.0.0.1', PORT), Handler)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
URL = 'http://127.0.0.1:%d/index.html' % PORT

# 改前 = 没有 ≤330 这一档（把那一档的两条规则抵掉）
BEFORE = """
var st = document.createElement('style');
st.textContent = `
  :root { --tabbar-gutter: 10px !important; --tabbar-lift: 10px !important;
          --nav-cell-inset: 4px !important; }
  .icon-btn { width: 36px !important; height: 36px !important; }
  .appbar-title { font-size: 16px !important; }
  html[data-nav="glass"] .appbar-title { font-size: 16px !important; padding-left: 4px !important; }
  .appbar-inner { padding: 0 2px !important; gap: 0 !important; }
  html[data-nav="glass"] .appbar-inner { padding: 0 4px !important; }
  .tab-ico, .tab-ico svg { height: 22px !important; }
  .tab-ico svg { width: 22px !important; }
  .tab-label { font-size: 10px !important; }
  .tab { padding: 0 2px !important; gap: 3px !important; }
  .card, .set-group, .list { margin-left: 12px !important; margin-right: 12px !important; }
  html[data-nav="glass"] .card, html[data-nav="glass"] .set-group,
  html[data-nav="glass"] .list { margin-left: 12px !important; margin-right: 12px !important; }
`;
document.head.appendChild(st);
"""

SIZES = [(280, 560), (320, 620), (360, 680)]

with sync_playwright() as p:
    b = p.chromium.launch()
    for nav, navname in (('classic', '朴素'), ('glass', 'iOS')):
        cols = []
        for before in (True, False):
            for w, h in SIZES:
                ctx = b.new_context(viewport={'width': w, 'height': h}, device_scale_factor=2)
                pg = ctx.new_page()
                pg.goto(URL); pg.wait_for_timeout(1100)
                pg.evaluate("window.Onboarding.quit()"); pg.wait_for_timeout(200)
                pg.evaluate("window.App.setNavGlass(%s)" % ('true' if nav == 'glass' else 'false'))
                pg.wait_for_timeout(400)
                if before:
                    pg.evaluate(BEFORE)
                pg.wait_for_timeout(200)
                f = '/tmp/ff-%s-%s-%d.png' % (nav, 'before' if before else 'after', w)
                pg.screenshot(path=f)
                cols.append((('改前' if before else '改后'), w, f))
                ctx.close()
        # 拼：上排改前 3 档，下排改后 3 档
        ims = [Image.open(c[2]).convert('RGB') for c in cols]
        gap, top = 12, 26
        W = sum(i.width for i in ims[:3]) + gap * 4
        H = sum(max(ims[i].height, ims[i + 3].height) for i in (0,)) * 2 + top * 2 + gap * 3
        canvas = Image.new('RGB', (W, H), (245, 246, 248))
        d = ImageDraw.Draw(canvas)
        y = top
        for row in (0, 1):
            x = gap
            for c in range(3):
                im = ims[row * 3 + c]
                canvas.paste(im, (x, y))
                d.rectangle([x - 1, y - 1, x + im.width, y + im.height], outline=(180, 184, 190))
                d.text((x + 4, y - 18), '%s  %dpx' % (('改前' if row == 0 else '改后'), im.width // 2), 
                       fill=(200, 40, 40) if row == 0 else (20, 130, 60))
                x += im.width + gap
            y += max(ims[row * 3 + i].height for i in range(3)) + gap + top
        canvas.save(os.path.join(OUT, '浮窗自适应-%s.png' % navname))
        print('出图：浮窗自适应-%s.png  %s' % (navname, canvas.size))
    b.close()
