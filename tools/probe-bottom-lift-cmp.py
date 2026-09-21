#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""浮窗里底栏「贴地」：修复前后对比。

改前 = 把 --nav-bottom-min 设为 0，算式退化成原来的 var(--nav-lift) + var(--safe-b)；
改后 = 当前代码。

场景取三种真实的：
  1. 浮窗（窗口自己没有导航栏，inset 为 0）
  2. 全屏 + 悬浮手势条（ROM 不给 inset，同样为 0）
  3. 全屏 + 三按键导航（inset 48，这一档应该前后都不变）
"""
import threading, functools, http.server, socketserver
from playwright.sync_api import sync_playwright
from PIL import Image

WEB = '/tmp/work/rel/app/src/main/assets/web'
PORT = 8899
H = functools.partial(http.server.SimpleHTTPRequestHandler, directory=WEB)


class Q(socketserver.TCPServer):
    allow_reuse_address = True


httpd = Q(('127.0.0.1', PORT), H)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
URL = 'http://127.0.0.1:%d/index.html' % PORT

BEFORE = """
var st = document.createElement('style');
st.textContent = ':root{--nav-bottom-min:0px!important}html[data-nav="glass"]{--nav-bottom-min:0px!important}';
document.head.appendChild(st);
"""

PROBE = """() => {
  var bar = document.getElementById('tabbar');
  var b = bar.getBoundingClientRect();
  return {
    lift: Math.round(window.innerHeight - b.bottom),
    h: Math.round(b.height),
    overflowX: document.documentElement.scrollWidth > window.innerWidth + 1
  };
}"""

CASES = [
    ('float320', '浮窗 320（系统不给底部 inset）', 320, 620, 0),
    ('gest393', '全屏 + 悬浮手势条（inset 为 0）', 393, 852, 0),
    ('navkey393', '全屏 + 三按键导航（inset 48）', 393, 852, 48),
]

rows = []
with sync_playwright() as p:
    br = p.chromium.launch()
    for nav in ('classic', 'glass'):
        for key, name, w, h, safe_b in CASES:
            rec = {}
            for before in (True, False):
                ctx = br.new_context(viewport={'width': w, 'height': h},
                                     device_scale_factor=2)
                pg = ctx.new_page()
                pg.goto(URL)
                pg.wait_for_timeout(1100)
                pg.evaluate("window.Onboarding.quit()")
                pg.wait_for_timeout(200)
                pg.evaluate("window.App.setNavGlass(%s)"
                            % ('true' if nav == 'glass' else 'false'))
                pg.wait_for_timeout(300)
                pg.evaluate("(v) => document.documentElement.style"
                            ".setProperty('--safe-b', v + 'px')", safe_b)
                if before:
                    pg.evaluate(BEFORE)
                pg.wait_for_timeout(250)
                r = pg.evaluate(PROBE)
                tag = 'before' if before else 'after'
                if nav == 'classic':
                    rec[tag] = r['lift']
                    rows.append((nav, name, tag, r['lift'], r['h'], r['overflowX']))
                f = '/tmp/bt-%s-%s-%s.png' % (nav, key, tag)
                pg.screenshot(path=f, clip={'x': 0, 'y': h - 120,
                                            'width': w, 'height': 120})
                ctx.close()
    br.close()

for r in rows:
    print('  %-8s %-30s %-6s 离底 %3dpx  底栏高 %2d  横向溢出=%s' % r)
