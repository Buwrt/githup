#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
防护链常量生成器（打包前跑，在 gradle 之前）

它做两件事：

1. 生成 GuardKeys.java
   —— 把「防护链每一环的期望值」用官方私钥签名后内置。
      运行时用官方公钥验签：谁改了任何一个期望值，验签就失败。
      别人没有你的私钥，就签不出一份能通过验签的假常量。

2. 生成 assets/guard/assets.sha
   —— 前端 JS / HTML 的哈希清单，防止有人只替换前端文件
      （比如把更新检测改掉、往请求里塞点东西）。

用法：python3 tools/gen-guard.py <项目根目录>
"""
import base64
import hashlib
import hmac
import os
import subprocess
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else '.'
KS = os.path.join(ROOT, 'keystore', 'githup-release.jks')
PASS_FILE = os.path.join(ROOT, 'keystore', 'pass.txt')
ALIAS = 'githup'

PKG = 'com.hubmobile.app'
APP_CLASS = 'com.hubmobile.app.App'
LABEL = 'githup'
VERSION_NAME = '1.1.2'
VERSION_CODE = 1001002

# 链的种子：参与每一环 token 的计算（与 Java 端保持一致）
SEED = 'githup-guard-chain-v1'


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=True, capture_output=True, check=True, **kw)


def main():
    if not os.path.exists(KS):
        sys.exit('找不到签名密钥：%s（密钥丢了就发不了更新，请先恢复 keystore/）' % KS)
    pw = open(PASS_FILE).read().strip()

    tmp = '/tmp/guard-keys'
    os.makedirs(tmp, exist_ok=True)
    sh("openssl pkcs12 -in '%s' -nocerts -nodes -passin pass:'%s' -out %s/key.pem 2>/dev/null" % (KS, pw, tmp))
    sh("openssl pkcs12 -in '%s' -clcerts -nokeys -passin pass:'%s' -out %s/cert.pem 2>/dev/null" % (KS, pw, tmp))
    sh("openssl x509 -in %s/cert.pem -pubkey -noout > %s/pub.pem" % (tmp, tmp))

    # 官方证书指纹（小写十六进制，无冒号）
    fp = sh("openssl x509 -in %s/cert.pem -noout -fingerprint -sha256" % tmp).stdout.decode()
    fp = fp.split('=')[1].strip().replace(':', '').lower()

    # 官方公钥（SubjectPublicKeyInfo DER 的 base64，Java 用 X509EncodedKeySpec 还原）
    pub_der = sh("openssl rsa -pubin -in %s/pub.pem -outform DER 2>/dev/null" % tmp).stdout
    pub_b64 = base64.b64encode(pub_der).decode()

    # ---- 计算防护链每一环的期望 token ----
    # T0 是种子，T(n+1) = HMAC-SHA256(key=Tn, msg=本环标识 + 本环校验对象)
    # 官方包运行时每一步算出来的 Tn 是确定值，先在这里算好写进代码。
    def tok(prev, tag, payload):
        return hmac.new(prev.encode(), (tag + '|' + payload).encode(), hashlib.sha256).hexdigest()

    t0 = hashlib.sha256(SEED.encode()).hexdigest()
    t1 = tok(t0, 'ring1-signature', fp)
    t2 = tok(t1, 'ring2-blob', pub_b64[:32])
    t3 = tok(t2, 'ring3-assets', 'assets')
    t4 = tok(t3, 'ring4-identity', PKG + '|' + LABEL + '|' + VERSION_NAME + '|' + str(VERSION_CODE))
    t5 = tok(t4, 'ring5-env', 'env')
    chain = [t1, t2, t3, t4, t5]

    # ---- 用官方私钥给「整条链的期望值」签名 ----
    payload = '|'.join(chain)
    open('%s/payload.txt' % tmp, 'w').write(payload)
    sig = sh("openssl dgst -sha256 -sign %s/key.pem %s/payload.txt" % (tmp, tmp)).stdout
    sig_b64 = base64.b64encode(sig).decode()

    java = '''package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "%s";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "%s";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "%s";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "%s";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
%s
    };

    static final String PKG = "%s";
    static final String APP_CLASS = "%s";
    static final String LABEL = "%s";
    static final String VERSION_NAME = "%s";
    static final int VERSION_CODE = %d;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.1.2/githup-V7.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
''' % (fp, pub_b64, sig_b64, SEED,
       '\n'.join('        "%s",' % t for t in chain),
       PKG, APP_CLASS, LABEL, VERSION_NAME, VERSION_CODE)

    out = os.path.join(ROOT, 'app/src/main/java/com/hubmobile/app/GuardKeys.java')
    open(out, 'w').write(java)

    # ---- 前端资源清单 ----
    assets_root = os.path.join(ROOT, 'app/src/main/assets/web')
    lines = []
    for dirpath, _, files in os.walk(assets_root):
        for f in sorted(files):
            p = os.path.join(dirpath, f)
            rel = os.path.relpath(p, os.path.join(ROOT, 'app/src/main/assets')).replace(os.sep, '/')
            # vendor 下的第三方库不参与（体积大且与校验目标无关）
            if rel.startswith('web/vendor/'):
                continue
            h = hashlib.sha256(open(p, 'rb').read()).hexdigest()
            lines.append('%s  %s' % (h, rel))
    guard_dir = os.path.join(ROOT, 'app/src/main/assets/guard')
    os.makedirs(guard_dir, exist_ok=True)
    open(os.path.join(guard_dir, 'assets.sha'), 'w').write('\n'.join(sorted(lines)) + '\n')

    print('证书指纹 : %s' % fp)
    print('防护链    : %d 环，已用官方私钥签名' % len(chain))
    print('资源清单  : %d 个文件' % len(lines))
    print('已生成    : %s' % out)


if __name__ == '__main__':
    main()
