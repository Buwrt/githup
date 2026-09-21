#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""复现用户浮窗（自由窗口）场景：把 WebView 宽度压到各种「窄」尺寸，
看顶栏/底栏/断点是怎么处理的。"""
import threading, functools, http.server, socketserver
from playwright.sync_api import sync_playwright

WEB = '/tmp/work/rel/app/src/main/assets/web'
PORT = 8821
H = functools.partial(http.server.SimpleHTTPRequestHandler, directory=WEB)
class Q(socketserver.TCPServer): allow_reuse_address = True
httpd = Q(('127.0.0.1', PORT), H)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
URL = 'http://127.0.0.1:%d/index.html' % PORT

PROBE = """() => {
  var r = {};
  var g = (s) => document.querySelector(s);
  var rect = (e) => { if(!e) return null; var b = e.getBoundingClientRect();
    return {l:Math.round(b.left), r:Math.round(b.right), w:Math.round(b.width),
            t:Math.round(b.top), b:Math.round(b.bottom), h:Math.round(b.height)}; };
  r.win = {w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio};
  r.html = {sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth,
            overflowX: document.documentElement.scrollWidth > window.innerWidth + 1};
  r.appbar = rect(g('#appbar'));
  r.tabbar = rect(g('#tabbar'));
  var cs = getComputedStyle(document.documentElement);
  r.vars = {};
  ['--tabbar-h','--tabbar-gutter','--tabbar-lift','--tabbar-max','--appbar-h',
   '--nav-pad','--nav-gutter','--nav-lift'].forEach(function(k){ r.vars[k] = cs.getPropertyValue(k).trim(); });
  var lab = g('#tabbar .tab-label');
  r.label = lab ? {fs: getComputedStyle(lab).fontSize, txt: lab.textContent.trim()} : null;
  var ico = g('#tabbar .tab-ico');
  r.ico = ico ? {h: Math.round(ico.getBoundingClientRect().height)} : null;
  var ttl = g('#appbar-title');
  r.title = ttl ? {fs: getComputedStyle(ttl).fontSize, txt: ttl.textContent.trim()} : null;
  var tab = g('#tabbar .tab');
  r.tabW = tab ? tab.getBoundingClientRect().width : null;
  // 「首页」标题是否被截断
  var h = g('#appbar-title');
  r.titleClipped = h ? (h.scrollWidth > h.clientWidth + 1) : null;
  return r;
}"""

# 自由窗口能出现的各种宽度（含用户浮窗那一档）
SIZES = [
    ('浮窗 极窄 280x520', 280, 520),
    ('浮窗 窄 300x560',   300, 560),
    ('浮窗 320x600',      320, 600),
    ('浮窗 360x640',      360, 640),
    ('浮窗 380x680',      380, 680),
    ('浮窗 400x700',      400, 700),
    ('浮窗 440x760',      440, 760),
    ('浮窗 宽 520x820',   520, 820),
]

with sync_playwright() as p:
    b = p.chromium.launch()
    for nav in ('classic', 'glass'):
        print('\n########## data-nav=%s ##########' % nav)
        for name, w, h in SIZES:
            ctx = b.new_context(viewport={'width': w, 'height': h}, device_scale_factor=2)
            pg = ctx.new_page()
            errs = []
            pg.on('pageerror', lambda e: errs.append(str(e)))
            pg.goto(URL); pg.wait_for_timeout(1000)
            pg.evaluate("window.Onboarding.quit()"); pg.wait_for_timeout(200)
            pg.evaluate("window.App.setNavGlass(%s)" % ('true' if nav=='glass' else 'false'))
            pg.wait_for_timeout(350)
            r = pg.evaluate(PROBE)
            print('%-20s 底栏 %3dx%-3d 左右%d/%d 离底%d | 格宽%.1f 字%s 图标%d | 标题%s 截断=%s | 横向溢出=%s %s'
                  % (name, r['tabbar']['w'], r['tabbar']['h'], r['tabbar']['l'], r['tabbar']['r'],
                     r['win']['h'] - r['tabbar']['b'],
                     r['tabW'] or 0,
                     r['label']['fs'] if r['label'] else '-',
                     r['ico']['h'] if r['ico'] else -1,
                     r['title']['fs'] if r['title'] else '-',
                     r['titleClipped'],
                     r['html']['overflowX'],
                     ('ERR:'+str(errs[:1])) if errs else ''))
            ctx.close()
    b.close()
