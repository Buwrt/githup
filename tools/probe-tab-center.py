#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
底栏对齐探针：朴素模式下「图标 + 文字」是不是真的落在选中圈的中间。

用户报的现象（真机经典模式，浅色/深色都有）：
    选中项那个浅色圈，跟上面的图标、下面的字对不上 —— 字没在圈中间。

这个探针只做一件事：把每一格量出来，看两个中心差多少。
  圈的中心      = #tab-ind 的 getBoundingClientRect 中心
  页签内容的中心 = .tab 内部实际内容（图标 + 文字）的联合包围盒中心
两套坐标都是相对视口的，可以直接比。

量四组：
  1. 朴素模式 浅色 / 深色  —— 每个页签都量，含未选中的
  2. iOS 风格             —— 回归，别把这边的对齐弄坏
  3. 窄屏 320（底栏被压扁那一档）—— 朴素模式下最容易露馅的尺寸
"""
import os, json, threading, functools, http.server, socketserver
from playwright.sync_api import sync_playwright

WEB = '/tmp/work/rel/app/src/main/assets/web'
PORT = 8813

Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=WEB)


class Q(socketserver.TCPServer):
    allow_reuse_address = True


httpd = Q(('127.0.0.1', PORT), Handler)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
URL = 'http://127.0.0.1:%d/index.html' % PORT

# 每个页签：指示器矩形、内容联合包围盒、图标矩形、文字矩形
MEASURE = """() => {
  var out = [];
  var tabs = [].slice.call(document.querySelectorAll('#tabbar .tab'));
  var ind = document.getElementById('tab-ind');
  var ir = ind.getBoundingClientRect();
  tabs.forEach(function (t, i) {
    var ico = t.querySelector('.tab-ico'), lab = t.querySelector('.tab-label');
    if (!ico || !lab) return;
    var a = ico.getBoundingClientRect(), b = lab.getBoundingClientRect(), tr = t.getBoundingClientRect();
    out.push({
      i: i,
      label: (lab.textContent || '').trim(),
      active: t.classList.contains('active'),
      tab:  { l: tr.left, r: tr.right, cx: (tr.left + tr.right) / 2, t: tr.top, b: tr.bottom },
      ind:  { l: ir.left, r: ir.right, cx: (ir.left + ir.right) / 2, t: ir.top, b: ir.bottom,
              cy: (ir.top + ir.bottom) / 2 },
      ico:  { l: a.left, r: a.right, cx: (a.left + a.right) / 2, t: a.top, b: a.bottom },
      lab:  { l: b.left, r: b.right, cx: (b.left + b.right) / 2, t: b.top, b: b.bottom },
      // 图标 + 文字合起来的包围盒（这才是「看起来的内容」）
      content: {
        l: Math.min(a.left, b.left), r: Math.max(a.right, b.right),
        t: Math.min(a.top, b.top), b: Math.max(a.bottom, b.bottom),
        cx: (Math.min(a.left, b.left) + Math.max(a.right, b.right)) / 2,
        cy: (Math.min(a.top, b.top) + Math.max(a.bottom, b.bottom)) / 2
      }
    });
  });
  // 指示器所属格：按中心最近的算
  var best = -1, bd = 1e9;
  out.forEach(function (o) { var d = Math.abs(o.tab.cx - (ir.left + ir.right) / 2); if (d < bd) { bd = d; best = o.i; } });
  return { bar: (function () { var r = document.getElementById('tabbar').getBoundingClientRect();
             return { l: r.left, r: r.right, h: r.height, cy: (r.top + r.bottom) / 2 }; })(),
           tabs: out, indOwner: best };
}"""


def report(pg, title):
    r = pg.evaluate(MEASURE)
    bar = r['bar']
    print('\n===== %s =====' % title)
    print('  底栏  left=%.1f right=%.1f 高=%.1f 中心y=%.1f' % (bar['l'], bar['r'], bar['h'], bar['cy']))
    print('  %-6s %-5s | %-16s | %-16s | %-22s' % ('页签', '选中', '圈中心 x / y', '内容中心 x / y', '偏差 dx / dy'))
    worst = 0.0
    for t in r['tabs']:
        dx = t['content']['cx'] - t['ind']['cx']
        # y 只在选中那一格有意义 —— 未选中没有圈，指示器还停在别处
        dy = t['content']['cy'] - t['ind']['cy']
        mark = ()
        if t['i'] == r['indOwner']:
            worst = max(abs(dx), abs(dy))
            verdict = '✓' if abs(dx) < 1.0 and abs(dy) < 1.5 else '✗'
        else:
            verdict = ''
        print('  %-6s %-5s | %7.1f / %7.1f | %7.1f / %7.1f | %+7.2f / %+7.2f  %s'
              % ('%d %s' % (t['i'], t['label']), '✓' if t['active'] else '',
                 t['ind']['cx'], t['ind']['cy'], t['content']['cx'], t['content']['cy'],
                 dx, dy, verdict))
    # 文字是不是落在圈里（左右各留一点余量）
    own = [t for t in r['tabs'] if t['i'] == r['indOwner']][0]
    inside_x = own['lab']['l'] >= own['ind']['l'] - 0.5 and own['lab']['r'] <= own['ind']['r'] + 0.5
    inside_y = own['content']['t'] >= own['ind']['t'] - 0.5 and own['content']['b'] <= own['ind']['b'] + 0.5
    print('  选中格：内容完全落在圈内  左右 %s / 上下 %s' % ('是' if inside_x else '否', '是' if inside_y else '否'))
    return worst, inside_x, inside_y


with sync_playwright() as p:
    b = p.chromium.launch()
    results = []
    for nav in ('classic', 'glass'):
        for theme in ('light', 'dark'):
            ctx = b.new_context(viewport={'width': 393, 'height': 852}, device_scale_factor=2.75)
            pg = ctx.new_page()
            errs = []
            pg.on('pageerror', lambda e: errs.append(str(e)))
            pg.goto(URL); pg.wait_for_timeout(1200)
            pg.evaluate("window.Onboarding.quit()"); pg.wait_for_timeout(250)
            pg.evaluate("window.App.setNavGlass(%s)" % ('true' if nav == 'glass' else 'false'))
            pg.wait_for_timeout(500)
            if theme == 'dark':
                pg.evaluate("window.Store.set('theme','dark'); window.App.applyTheme()")
                pg.wait_for_timeout(400)
            # 逐个页签看：每个都应该是「内容居中于自己那一格」
            for t in ('home', 'notifications', 'explore', 'search', 'profile'):
                pg.evaluate("window.App.setTab('%s')" % t); pg.wait_for_timeout(320)
                r = pg.evaluate(MEASURE)
                own = [x for x in r['tabs'] if x['i'] == r['indOwner']][0]
                dx = own['content']['cx'] - own['ind']['cx']
                dy = own['content']['cy'] - own['ind']['cy']
                ctxdx = own['content']['cx'] - own['tab']['cx']
                results.append((nav, theme, t, dx, dy, ctxdx))
            w, ix, iy = report(pg, '%s / %s（停在最后一格）' % (nav, '浅色' if theme == 'light' else '深色'))
            print('  页面错误：', errs[:2] if errs else '无')
            pg.screenshot(path='/tmp/probe-tab-%s-%s.png' % (nav, theme),
                          clip={'x': 0, 'y': 852 - 140, 'width': 393, 'height': 130})
            ctx.close()

    # 窄屏那档：底栏高度被压到 52 的情况（横屏），以及 320 宽
    for w_, h_, sizename in ((320, 569, '窄屏 320x569'), (360, 780, '小屏 360x780')):
        ctx = b.new_context(viewport={'width': w_, 'height': h_}, device_scale_factor=2)
        pg = ctx.new_page()
        pg.goto(URL); pg.wait_for_timeout(1100)
        pg.evaluate("window.Onboarding.quit()"); pg.wait_for_timeout(250)
        pg.evaluate("window.App.setNavGlass(false)"); pg.wait_for_timeout(450)
        pg.evaluate("window.App.setTab('notifications')"); pg.wait_for_timeout(400)
        report(pg, 'classic / 浅色 / %s' % sizename)
        ctx.close()

    b.close()

print('\n===== 汇总：每个页签选中时的偏差 =====')
print('  %-9s %-6s %-14s %-12s %-14s' % ('底栏', '主题', '页签', '内容-圈 dx/dy', '内容-格中心 dx'))
bad = 0
for nav, theme, t, dx, dy, ctxdx in results:
    flag = '✓' if abs(dx) < 1.0 and abs(dy) < 1.5 else '✗'
    if flag == '✗':
        bad += 1
    print('  %-9s %-6s %-14s %+6.2f / %+6.2f  %+7.2f   %s'
          % (nav, theme, t, dx, dy, ctxdx, flag))
print('\n  不合格项：%d / %d' % (bad, len(results)))
