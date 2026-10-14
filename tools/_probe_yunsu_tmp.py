#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""临时探测：komi-store 内置镜像里，githup 还没有的那几条到底能不能用。

komi-store 的 BundledMirrors 内置：
    direct / fastly.jsdelivr.net / ghfast.top / gh-proxy.com /
    github.moeyy.xyz / ghps.cc / gh.api.99988866.xyz

其中 ghfast.top 与 gh-proxy.com githup 已经有了。剩下四条里
github.moeyy.xyz 与 ghps.cc 在 githup 的历史记录里是被排除过的
（DNS 不通 / 下到一半断），这次一并复核，免得凭旧印象做决定。

判据跟 probe-channels.py 一致：200/206 + 真读到字节 + 不是 HTML。
"""
import json
import time
import urllib.request
import urllib.error

APK = ("https://github.com/Buwrt/githup/releases/download/"
       "v1.2.15/githup-v1.2.15.apk")
# jsdelivr 只能拼 raw 文件（/gh/{owner}/{repo}@{ref}/{path}），
# 拿 README 做目标才测得出来
RAW = "https://raw.githubusercontent.com/Buwrt/githup/main/README.md"

# (名字, 模板, 目标)  模板里 {url} 或 {owner}/{repo}/{ref}/{path}
CANDIDATES = [
    ("云析(对照)",            "https://cdn.gh-proxy.org/{url}",                          APK),
    ("gh.api.99988866.xyz",   "https://gh.api.99988866.xyz/{url}",                      APK),
    ("github.moeyy.xyz",      "https://github.moeyy.xyz/{url}",                         APK),
    ("ghps.cc",               "https://ghps.cc/{url}",                                   APK),
    ("fastly.jsdelivr(raw)",  "https://fastly.jsdelivr.net/gh/Buwrt/githup@main/README.md", RAW),
]

UA = "githup"


def probe(name, tmpl, target):
    url = tmpl.replace("{url}", target)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    t0 = time.time()
    first = None
    got = 0
    ctype = ""
    code = 0
    try:
        with urllib.request.urlopen(req, timeout=30) as z:
            code = z.status
            ctype = z.headers.get("Content-Type", "") or ""
            while True:
                chunk = z.read(65536)
                if not chunk:
                    break
                if first is None:
                    first = time.time()
                got += len(chunk)
                if got >= 65536 * 8:   # 读 512KB 就够判速度了
                    break
    except urllib.error.HTTPError as e:
        code = e.code
    except Exception as e:
        return {"name": name, "url": url, "ok": False, "err": type(e).__name__ + ": " + str(e)[:80]}
    now = time.time()
    ttfb = int((first - t0) * 1000) if first else None
    span = (now - first) if first else (now - t0)
    bps = int(got / span) if span > 0 and got else 0
    html = "html" in ctype.lower()
    ok = code in (200, 206) and got > 0 and not html
    return {
        "name": name, "url": url, "ok": ok, "code": code,
        "bytes": got, "ctype": ctype[:40],
        "ttfb_ms": ttfb, "kb_s": int(bps / 1024),
    }


print("目标 APK :", APK)
print("目标 RAW :", RAW)
print()
for n, t, g in CANDIDATES:
    r = probe(n, t, g)
    if r.get("ok"):
        print(f"✅ {r['name']:<22} HTTP {r['code']}  ttfb {r['ttfb_ms']}ms  "
              f"{r['kb_s']} KB/s  {r['bytes']}B  {r['ctype']}")
    else:
        print(f"❌ {r['name']:<22} {r.get('err') or ('HTTP ' + str(r.get('code')) + ' ' + str(r.get('ctype')))}")
