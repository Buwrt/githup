import threading, functools, http.server, socketserver
from playwright.sync_api import sync_playwright
WEB='/tmp/work/rel/app/src/main/assets/web'; PORT=8893
H=functools.partial(http.server.SimpleHTTPRequestHandler, directory=WEB)
class Q(socketserver.TCPServer): allow_reuse_address=True
httpd=Q(('127.0.0.1',PORT),H); threading.Thread(target=httpd.serve_forever,daemon=True).start()
URL='http://127.0.0.1:%d/index.html'%PORT
P="""() => {
  var R=(s)=>{var e=document.querySelector(s); if(!e) return null; var b=e.getBoundingClientRect();
    return {t:Math.round(b.top),b:Math.round(b.bottom),h:Math.round(b.height)};};
  var bar=R('#tabbar'), ab=R('#appbar'), v=R('#view'), vcs=getComputedStyle(document.getElementById('view'));
  return {winH:window.innerHeight, ab:ab, bar:bar, gap:window.innerHeight-bar.b,
    viewH:v.h, avail: Math.round(v.h-parseFloat(vcs.paddingTop)-parseFloat(vcs.paddingBottom)),
    overlapping: bar.t < v.b - 1 && bar.t < window.innerHeight,
    overflowY: document.documentElement.scrollHeight > window.innerHeight + 1};
}"""
print("极矮窗口（自由窗口可以被拖到很小）")
print("  %-14s %-6s %-7s %-6s %-8s %s" % ('尺寸','顶栏','离底','内容可用','仍在屏内','纵向溢出'))
with sync_playwright() as p:
    b=p.chromium.launch()
    for nav in ('classic','glass'):
        print('  --- %s ---' % nav)
        for w,h in ((280,180),(280,240),(300,300),(360,400),(400,480)):
            ctx=b.new_context(viewport={'width':w,'height':h},device_scale_factor=2)
            pg=ctx.new_page(); pg.goto(URL); pg.wait_for_timeout(800)
            pg.evaluate("window.Onboarding.quit()"); pg.wait_for_timeout(200)
            pg.evaluate("window.App.setNavGlass(%s)"%('true' if nav=='glass' else 'false')); pg.wait_for_timeout(300)
            pg.evaluate("document.documentElement.style.setProperty('--safe-b','0px')"); pg.wait_for_timeout(150)
            o=pg.evaluate(P)
            print('  %-14s %-6s %-7s %-6s %-8s %s' % ('%dx%d'%(w,h),
                str(o['ab']['h'])+'px', str(o['gap'])+'px', str(o['avail'])+'px',
                o['bar']['b']<=o['winH']+1, o['overflowY']))
            ctx.close()
    b.close()
