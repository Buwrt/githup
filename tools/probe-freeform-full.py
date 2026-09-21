#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
浮窗 / 自由窗口全量探针 —— 一次把「还能量得出来的」都量了。

⚠️ 上一轮的教训（写在这，免得下次再犯）：
  1. 把 Chromium 的 viewport 压窄 ≠ 浮窗。真机浮窗的本质是窗口自己的
     navigationBars inset = 0，前端拿到的 --safe-b 就是 0。上一轮所有
     尺寸都喂了正常的 safe-b，所以压根没测到这个条件。
  2. 只压宽度没压高度。自由窗口是可以拖成很矮的，硬的 part 是
     「内容区还剩多少可用高度」，不是宽度。

这一版覆盖：
  A. 离底距离       —— 包含 --safe-b = 0 这一档真机条件
  B. 五个标签的对中 —— 上一轮修的那具 nullptr 是不是在极端宽度下也成立
  C. 极端尺寸       —— 280×420 这种很矮的窗口，内容区还剩多少
  D. 顶栏           —— 在浮窗宽度下标题会不会撞上动作按钮
"""
import threading, functools, http.server, socketserver
from playwright.sync_api import sync_playwright

WEB = '/tmp/work/rel/app/src/main/assets/web'
PORT = 8891
Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=WEB)


class Q(socketserver.TCPServer):
    allow_reuse_address = True


httpd = Q(('127.0.0.1', PORT), Handler)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
URL = 'http://127.0.0.1:%d/index.html' % PORT

PROBE = """() => {
  var q = (s) => document.querySelector(s);
  var R = (e) => { if (!e) return null; var b = e.getBoundingClientRect();
    return {l:b.left, r:b.right, t:b.top, b:b.bottom, w:b.width, h:b.height,
            cx:(b.left+b.right)/2, cy:(b.top+b.bottom)/2}; };
  var out = {};
  var win = {w: window.innerWidth, h: window.innerHeight};
  out.win = win;

  /* ---- A. 底栏离底 ---- */
  var bar = R(q('#tabbar'));
  if (bar) {
    out.gap = Math.round(win.h - bar.b);
    out.barH = Math.round(bar.h);
  }
  var cs = getComputedStyle(document.documentElement);
  out.safeB = cs.getPropertyValue('--safe-b').trim();
  out.minB  = cs.getPropertyValue('--nav-bottom-min').trim();
  out.lift  = cs.getPropertyValue('--nav-lift').trim();

  /* ---- B. 五个标签的对中 ---- */
  var ind = R(q('#tabbar .tab-ind'));
  out.tabs = [];
  var tabs = [].slice.call(document.querySelectorAll('#tabbar .tab'));
  tabs.forEach(function (t) {
    var ico = R(t.querySelector('.tab-ico'));
    var lab = R(t.querySelector('.tab-label'));
    var box = R(t);
    if (!ico || !lab) return;
    // 图标 + 文字合起来的包围盒才是「看起来的内容」
    var top = Math.min(ico.t, lab.t), bot = Math.max(ico.b, lab.b);
    out.tabs.push({
      name: (t.querySelector('.tab-label').textContent || '').trim(),
      dx: +((ico.cx + lab.cx) / 2 - box.cx).toFixed(2),   // 横向偏离格中心
      dy: +(((top + bot) / 2) - box.cy).toFixed(2),       // 纵向偏离格中心
      cell: Math.round(box.w), ind: ind ? Math.round(ind.w) : null
    });
  });
  if (ind) out.ind = {w: Math.round(ind.w), cx: +ind.cx.toFixed(1)};

  /* ---- C. 内容区可用高度 ---- */
  var view = q('#view');
  if (view) {
    var vr = R(view), vcs = getComputedStyle(view);
    out.view = {h: Math.round(vr.h),
                padB: vcs.paddingBottom,
                avail: Math.round(vr.h - parseFloat(vcs.paddingBottom)
                                       - parseFloat(vcs.paddingTop))};
  }

  /* ---- D. 顶栏 ---- */
  var abar = R(q('#appbar'));
  var ttl  = q('#appbar-title');
  if (abar) out.appbarTop = Math.round(abar.t);
  if (ttl) {
    var acts = [].slice.call(document.querySelectorAll('#appbar .icon-btn'));
    var lastRight = acts.length ? Math.max.apply(null, acts.map(function(a){
      var r = a.getBoundingClientRect(); return r.left; })) : win.w;
    var tb = ttl.getBoundingClientRect();
    out.title = {need: Math.round(ttl.scrollWidth), have: Math.round(ttl.clientWidth),
                 truncated: ttl.scrollWidth > ttl.clientWidth + 1,
                 clash: Math.round(tb.right - lastRight)};  // >0 = 压到按钮
  }

  out.overflowX = document.documentElement.scrollWidth > window.innerWidth + 1;
  return out;
}"""

# (名字, 宽, 高, safe-b)  —— safe-b 用 None 表示「不注入」，吃 CSS 自己的兜底
CASES = [
    ('浮窗 280x420 极窄矮', 280, 420, '0px'),
    ('浮窗 300x560',        300, 560, '0px'),
    ('浮窗 320x620',        320, 620, '0px'),
    ('浮窗 360x700',        360, 700, '0px'),
    ('浮窗 393x700',        393, 700, '0px'),
    ('浮窗 520x820',        520, 820, '0px'),
    ('浮窗 393 常规inset',  393, 700, '18px'),
    ('全屏 393 手势 safe0', 393, 852, '0px'),
    ('全屏 393 手势 safe8', 393, 852, '8px'),
    ('全屏 393 按键 safe48',393, 852, '48px'),
]

MIN_GAP = {'classic': 16, 'glass': 22}     # 期望的最小离底
rows = []
with sync_playwright() as p:
    br = p.chromium.launch()
    for nav, navname in (('classic', '朴素'), ('glass', 'iOS风格')):
        print('\n########## %s ##########' % navname)
        print('  %-22s %-5s %-7s %-6s %-9s %s'
              % ('场景', '离底', '〔差〕', '底栏高', '内容可用', '标题截断/撞按钮'))
        for name, w, h, sb in CASES:
            ctx = br.new_context(viewport={'width': w, 'height': h}, device_scale_factor=2)
            pg = ctx.new_page()
            errs = []
            pg.on('pageerror', lambda e: errs.append(str(e)))
            pg.goto(URL); pg.wait_for_timeout(900)
            pg.evaluate("window.Onboarding.quit()"); pg.wait_for_timeout(200)
            pg.evaluate("window.App.setNavGlass(%s)" % ('true' if nav == 'glass' else 'false'))
            pg.wait_for_timeout(300)
            # 模拟原生注入 —— 关键就是这里能喂 --safe-b: 0
            pg.evaluate("""(ins) => {
                var r = document.documentElement;
                r.style.setProperty('--safe-b', ins.b);
                r.style.setProperty('--safe-t', '28px');
                r.style.setProperty('--safe-l', '0px');
                r.style.setProperty('--safe-r', '0px');
            }""", {'b': sb})
            pg.wait_for_timeout(250)
            o = pg.evaluate(PROBE)
            ok_gap = o['gap'] >= MIN_GAP[nav]
            dxmax = max([abs(t['dx']) for t in o['tabs']] or [0])
            dymax = max([abs(t['dy']) for t in o['tabs']] or [0])
            print('  %-22s %-5s %-7s %-6s %-9s %s%s%s'
                  % (name,
                     str(o['gap']) + 'px' + ('' if ok_gap else '✗'),
                     'd±%.1f' % max(dxmax, dymax),
                     str(o['barH']) + 'px',
                     str(o['view']['avail']) + 'px',
                     ('断' if o.get('title', {}).get('truncated') else '—'),
                     ('/撞%d' % o['title']['clash'] if o.get('title', {}).get('clash', -999) > 0 else ''),
                     ('  ERR:' + errs[0][:40] if errs else '')))
            rows.append((navname, name, o['gap'], dxmax, dymax, ok_gap, o['overflowX']))
            ctx.close()
    br.close()

print('\n########## 汇总 ##########')
bad = [r for r in rows if not r[5]]
badx = [r for r in rows if r[6]]
print('  离底不达标  : %d / %d' % (len(bad), len(rows)))
for r in bad:
    print('     ', r[0], r[1], '→', r[2], 'px')
print('  横向溢出    : %d / %d' % (len(badx), len(rows)))
for r in badx:
    print('     ', r[0], r[1])
worst = max(max(r[3], r[4]) for r in rows)
print('  对中最大偏差: %.2f px（of %d 组）' % (worst, len(rows)))
