#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
浮窗（自由窗口）底栏贴地探针 —— 这轮真正要验证的东西。

⚠️ 上一轮我犯的错：把 Chromium 的 viewport 压窄就当成浮窗，量到「没问题」。
真机浮窗和「窄 viewport」的**本质区别**是：浮窗里窗口自己的
navigationBars inset = 0，也就是前端拿到的 --safe-b 是 0（不是 18px 那种值）。
上一轮所有尺寸都喂了正常的 safe-b，所以完全没测到这个条件。

这个探针就把那个条件摆出来：
  同一个宽度，分别喂 safe-b = 0 / 8 / 18 / 48，看底栏离窗口底边多远、
  有没有低于「手势条会压住」的阈值。

再加一件上一轮也没做的事：
  量底栏底边与 #view 最后一行内容的关系 —— 内容是不是被底栏压住。
"""
import os, threading, functools, http.server, socketserver
from playwright.sync_api import sync_playwright

WEB = '/tmp/work/rel/app/src/main/assets/web'
PORT = 8883

Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=WEB)


class Q(socketserver.TCPServer):
    allow_reuse_address = True


httpd = Q(('127.0.0.1', PORT), Handler)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
URL = 'http://127.0.0.1:%d/index.html' % PORT

PROBE = """() => {
  var bar = document.getElementById('tabbar');
  var b = bar.getBoundingClientRect();
  var cs = getComputedStyle(document.documentElement);
  var view = document.getElementById('view');
  return {
    gap: Math.round(window.innerHeight - b.bottom),      // 底栏底边离窗口底边
    barH: Math.round(b.height),
    barL: Math.round(b.left), barR: Math.round(b.right),
    safeB: cs.getPropertyValue('--safe-b').trim(),
    minB: cs.getPropertyValue('--nav-bottom-min').trim(),
    lift: cs.getPropertyValue('--nav-lift').trim(),
    viewPadBottom: getComputedStyle(view).paddingBottom,
    winH: window.innerHeight,
    overflowX: document.documentElement.scrollWidth > window.innerWidth + 1
  };
}"""

# 浮窗里真实出现的组合：宽度是窗口宽度，safe-b 是 0（这就是真机的情况）
CASES = [
    ('浮窗 280  safe-b=0',  280, 560, {'--safe-b': '0px',  '--safe-t': '28px'}),
    ('浮窗 320  safe-b=0',  320, 620, {'--safe-b': '0px',  '--safe-t': '28px'}),
    ('浮窗 393  safe-b=0',  393, 700, {'--safe-b': '0px',  '--safe-t': '28px'}),
    ('浮窗 393  safe-b=8',  393, 700, {'--safe-b': '8px',  '--safe-t': '28px'}),
    ('全屏 393  safe-b=18', 393, 852, {'--safe-b': '18px', '--safe-t': '28px'}),
    ('全屏 393  safe-b=48', 393, 852, {'--safe-b': '48px', '--safe-t': '28px'}),
    ('全屏 393  safe-b=0',  393, 852, {'--safe-b': '0px',  '--safe-t': '28px'}),
]

with sync_playwright() as p:
    b = p.chromium.launch()
    for nav, navname in (('classic', '朴素'), ('glass', 'iOS风格')):
        print('\n########## %s ##########' % navname)
        print('  %-20s %-8s %-8s %-7s %-9s %s' % ('场景', '离底', '底栏高', 'safe-b', 'view让位', '横向溢出'))
        for name, w, h, ins in CASES:
            ctx = b.new_context(viewport={'width': w, 'height': h}, device_scale_factor=2.75)
            pg = ctx.new_page()
            errs = []
            pg.on('pageerror', lambda e: errs.append(str(e)))
            pg.goto(URL); pg.wait_for_timeout(900)
            pg.evaluate("window.Onboarding.quit()"); pg.wait_for_timeout(200)
            pg.evaluate("window.App.setNavGlass(%s)" % ('true' if nav == 'glass' else 'false'))
            pg.wait_for_timeout(300)
            # 关键：模拟原生注入（含浮窗下 bottom=0 的情况）
            pg.evaluate("""(ins) => {
                var r = document.documentElement;
                for (var k in ins) r.style.setProperty(k, ins[k]);
            }""", ins)
            pg.wait_for_timeout(250)
            r = pg.evaluate(PROBE)
            warn = ''
            if r['gap'] < 10:
                warn = '  ← 太贴底！'
            print('  %-20s %-8s %-8s %-7s %-9s %s%s'
                  % (name, '%dpx' % r['gap'], '%dpx' % r['barH'], r['safeB'],
                     r['viewPadBottom'], r['overflowX'], warn))
            if errs:
                print('      页面错误:', errs[:1])
            ctx.close()
    b.close()
