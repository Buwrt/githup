#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
本轮三处改动的探针：
  1) 朴素样式下底栏「图标+文字」是否落在版心正中（几何中心），且与指示器同心
  2) iOS 风格下仓库页页签条：纯文字、无图标、高度 49px、文字垂直居中
  3) 两种风格下 #view 顶部都有让开顶栏的留白（朴素样式原来漏了这条）

真实应用 + 十档机型矩阵，量的是 getBoundingClientRect 的实际像素，
不是看 CSS 里写了什么。
"""
import threading, functools, http.server, socketserver, json
from playwright.sync_api import sync_playwright

WEB = '/tmp/work/rel/app/src/main/assets/web'
PORT = 8771
Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=WEB)
class Q(socketserver.TCPServer):
    allow_reuse_address = True
httpd = Q(('127.0.0.1', PORT), Handler)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
URL = 'http://127.0.0.1:%d/index.html' % PORT

# 底栏版心：量第一格（首页）的图标盒、文字行盒、格盒，以及指示器盒
TABBAR = """() => {
  var bar = document.getElementById('tabbar');
  var tab = bar.querySelector('.tab');
  var ico = tab.querySelector('.tab-ico');
  var lab = tab.querySelector('.tab-label');
  var ind = bar.querySelector('.tab-ind');
  var bb = bar.getBoundingClientRect(), tb = tab.getBoundingClientRect();
  var ib = ico.getBoundingClientRect(), lb = lab.getBoundingClientRect();
  var db = ind ? ind.getBoundingClientRect() : null;
  var inkTop = Math.min(ib.top, lb.top), inkBot = Math.max(ib.bottom, lb.bottom);
  return {
    barH: +bb.height.toFixed(1),
    barTop: +bb.top.toFixed(1),
    tabH: +tb.height.toFixed(1),
    inkTop: +(inkTop - bb.top).toFixed(1),
    inkBot: +(bb.bottom - inkBot).toFixed(1),
    inkH: +(inkBot - inkTop).toFixed(1),
    inkCenter: +(((inkTop + inkBot) / 2) - bb.top).toFixed(1),
    barCenter: +(bb.height / 2).toFixed(1),
    // 文字行的中心相对整条底栏：这个才是「文字是否居中」的判据
    labelCenter: +(((lb.top + lb.bottom) / 2) - bb.top).toFixed(1),
    indTop: db ? +(db.top - bb.top).toFixed(1) : null,
    indBot: db ? +(bb.bottom - db.bottom).toFixed(1) : null,
    indH: db ? +db.height.toFixed(1) : null,
    indW: db ? +db.width.toFixed(1) : null,
    accent: getComputedStyle(tab).color,
    padY: getComputedStyle(tab).paddingTop
  };
}"""

TABS = """() => {
  var t = document.getElementById('rtabs');
  if (!t) return null;
  var tb = t.getBoundingClientRect();
  var btn = t.querySelector('button');
  var b = btn.getBoundingClientRect();
  var span = btn.querySelector('span');
  var s = span.getBoundingClientRect();
  return {
    tabsH: +tb.height.toFixed(1),
    tabW: +b.width.toFixed(1),
    iconCount: t.querySelectorAll('svg').length,
    labels: Array.from(t.querySelectorAll('button')).map(function (x) { return x.textContent.trim(); }),
    textCenter: +(((s.top + s.bottom) / 2) - tb.top).toFixed(1),
    tabsCenter: +(tb.height / 2).toFixed(1),
    btnPadY: getComputedStyle(btn).paddingTop
  };
}"""

VIEW = """() => {
  var v = document.getElementById('view');
  var cs = getComputedStyle(v);
  var bar = document.getElementById('appbar').getBoundingClientRect();
  var vv = v.getBoundingClientRect();
  return { padTop: cs.paddingTop, appbarBottom: +bar.bottom.toFixed(1), viewTop: +vv.top.toFixed(1) };
}"""

DEVICES = [
    ('魅族20Pro 393x852', 393, 852, 2.75),
    ('魅族20 360x780',    360, 780, 3.0),
    ('小屏 320x569',      320, 569, 1.5),
    ('大屏 480x1040',     480, 1040, 3.0),
    ('横屏 852x393',      852, 393, 2.75),
]

with sync_playwright() as p:
    b = p.chromium.launch()
    for nav in ('classic', 'glass'):
        print('\n############ 底栏风格 = %s ############' % nav)
        for name, w, h, dpr in DEVICES:
            ctx = b.new_context(viewport={'width': w, 'height': h}, device_scale_factor=dpr)
            pg = ctx.new_page()
            errs = []
            pg.on('pageerror', lambda e: errs.append(str(e)))
            pg.goto(URL)
            pg.wait_for_timeout(1100)
            pg.evaluate("window.Onboarding.quit()")
            pg.wait_for_timeout(250)
            pg.evaluate("(n)=>{document.documentElement.setAttribute('data-nav', n);}", nav)
            pg.wait_for_timeout(250)

            r = pg.evaluate(TABBAR)
            # 判据：字形组的中心 与 底栏几何中心 之差 ≤ 1.5px
            inkOff = abs(r['inkCenter'] - r['barCenter'])
            # 指示器中心
            indOff = None
            if r['indTop'] is not None:
                indC = r['indTop'] + r['indH'] / 2
                indOff = abs(indC - r['barCenter'])
            print('  %-18s 底栏高 %-5s 字形中心 %-5s / 条中心 %-5s  偏差 %-5s %s'
                  % (name, r['barH'], r['inkCenter'], r['barCenter'], round(inkOff, 2),
                     '✓' if inkOff <= 1.5 else '✗ 不居中'))
            print('  %-18s 指示器 高%-5s 宽%-6s 上下留白 %s/%s  中心偏差 %s'
                  % ('', r['indH'], r['indW'], r['indTop'], r['indBot'],
                     round(indOff, 2) if indOff is not None else 'n/a'))
            print('  %-18s 选中色 %s   内衬 %s' % ('', r['accent'], r['padY']))
            if errs:
                print('   页面错误:', errs[:2])
            ctx.close()

    # ---- 仓库页页签 ----
    print('\n############ 仓库页页签条 ############')
    for nav in ('classic', 'glass'):
        ctx = b.new_context(viewport={'width': 393, 'height': 852}, device_scale_factor=2.75)
        pg = ctx.new_page()
        pg.goto(URL); pg.wait_for_timeout(1100)
        pg.evaluate("window.Onboarding.quit()"); pg.wait_for_timeout(250)
        pg.evaluate("(n)=>{document.documentElement.setAttribute('data-nav', n);}", nav)
        # 用离线渲染的仓库头，避免真去打 API
        pg.evaluate("""() => {
          var box = document.getElementById('view');
          box.innerHTML = '<div class="repo-head">' +
            '<div class="repo-name"><span class="owner">Buwrt</span><span class="slash">/</span><span class="name">githup</span></div>' +
            '<div class="repo-desc">把 GitHub 装进口袋。</div>' +
            '</div>' +
            '<div class="tabs" id="rtabs">' +
            ['代码','议题','拉取请求','Actions','发布','更多'].map(function (t, i) {
              return '<button data-t="' + i + '" class="' + (i === 0 ? 'active' : '') + '"><span>' + t + '</span></button>';
            }).join('') + '</div>' +
            '<div class="page"><div class="card">内容</div></div>';
        }""")
        pg.wait_for_timeout(300)
        r = pg.evaluate(TABS)
        v = pg.evaluate(VIEW)
        print('  [%s] 页签条高 %s  图标 svg 个数 %s（应为 0）' % (nav, r['tabsH'], r['iconCount']))
        print('        文字中心 %s / 条中心 %s  偏差 %s'
              % (r['textCenter'], r['tabsCenter'], round(abs(r['textCenter'] - r['tabsCenter']), 2)))
        print('        内衬 %s   页签: %s' % (r['btnPadY'], ' / '.join(r['labels'])))
        print('        #view padding-top %s（顶栏底边 %s，内容从 %s 开始）'
              % (v['padTop'], v['appbarBottom'], v['viewTop']))
        ctx.close()
    b.close()
