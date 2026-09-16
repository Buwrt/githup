/* ============================================================
 * updater.js — 内置更新检测
 *
 * 版本号规则（x.y.z 三段，更新源为 GitHub Release 的 tag）：
 *
 *   x 第一位 —— 大版本。只要这一位变了，必须强制更新：
 *               弹层不可关闭，必须下载安装才能继续用。
 *               例：1.1.1 -> 2.0.0
 *   y 第二位 —— 功能更新。有新内容，可更可不更。
 *               例：1.1.1 -> 1.2.0
 *   z 第三位 —— 修复更新。修 bug，可更可不更。
 *               例：1.1.1 -> 1.1.2
 *
 * 判定方式：从高位往下比，「第一个出现差异的那一位」决定更新级别。
 * 比不出大小（版本相同、或服务端版本更旧）就不提示。
 *
 * 更新有两个数据来源，先试 Release，拿不到就用仓库里的版本清单：
 *
 *   1. Release —— 仓库 Buwrt/githup 最新一个正式版（跳过 draft / prerelease），
 *      取其 .apk 附件，交给原生层的 installApk 下载并拉起安装器。
 *   2. version.json —— 仓库根目录的版本清单。用于仓库还没发 Release、
 *      或当前 Token 没有 Release 读取权限的情况。内容形如：
 *        { "version": "1.2.0", "apk": "apk/githup-V5.apk", "notes": "..." }
 *      APK 直接走 raw 地址下载。
 *
 * 两条路拿到的结果结构完全一致，界面层不需要区分。
 * ============================================================ */
(function () {
  'use strict';

  var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); };

  var OWNER = 'Buwrt';
  var REPO = 'githup';
  var MANIFEST = 'version.json';   // 仓库根目录的版本清单（Release 的备用来源）
  var RAW_BASE = 'https://raw.githubusercontent.com/' + OWNER + '/' + REPO + '/main';
  var SKIP_KEY = 'updSkipVersion'; // 用户主动跳过的新版本号
  var SKIP_SHA_KEY = 'updSkipSha'; // 用户主动跳过的那个包的指纹（版本号不变但又重新打包时用）
  var LAST_KEY = 'updLastCheck';   // 上次「回到前台」检查的时间戳（仅用于切后台，不限制冷启动）

  /**
   * 内置的官方安装包指纹表（精确版本钉扎）。
   *
   * 表里是「包名 -> 该文件的 SHA-256」。查得到就用它，跟 version.json 里的
   * 清单值交叉比对，两边不一致说明有一方被改了。
   *
   * ⚠️ 为什么这里不写当前这个包自己的哈希 ——
   *    APK 不可能在内部写下自己的哈希：把哈希写进去，包的内容就变了，
   *    哈希也随之改变，永远对不上（鸡生蛋问题）。
   *
   *   真正与字节无关的、能钉死「官方身份」的是**签名证书指纹**，
   *    它由原生层在装包前校验（见 JsBridge.verifyApk），不依赖包本身的哈希。
   *    所以：证书指纹负责「是不是官方签的」，这张表 + 清单负责「是不是该装的那一份」。
   *
   * 用法：发新版时把「下一个版本」的哈希加进来即可（比如发 V8 时填 V8 的）。
   */
  var PINNED_SHA = {
    // 'githup-V8.apk': '将来发新版时填这一份的 SHA-256'
  };

  /** 取某个安装包的内置指纹；查不到返回空（此时退化为只信清单） */
  function pinnedSha(name) {
    if (!name) return '';
    var s = PINNED_SHA[String(name)];
    return s ? String(s).trim().toLowerCase() : '';
  }

  var LEVEL_TEXT = {
    major: '重要更新，安装后才能继续使用',
    minor: '功能更新，可以稍后再装',
    patch: '修复更新，可以稍后再装',
    content: '内容有更新，可以稍后再装'
  };

  /* ---------- 版本号解析 ---------- */

  /**
   * 解析成 {major, minor, patch}。
   * 'v1.2.3' / '1.2.3' -> {1,2,3}；老代号 'V3' -> {0,0,3}，
   * 这样从代号版升到正规三段号时会被判成大版本变化（强制更新），符合预期。
   */
  function parse(v) {
    var s = String(v == null ? '' : v).trim();
    var m = s.match(/(\d+)\.(\d+)\.(\d+)/);
    if (m) return { major: +m[1], minor: +m[2], patch: +m[3] };
    m = s.match(/(\d+)/);
    return m ? { major: 0, minor: 0, patch: +m[1] } : { major: 0, minor: 0, patch: 0 };
  }

  /** 版本号大小：a > b 返回 1，a < b 返回 -1，相等返回 0 */
  function cmp(a, b) {
    var x = parse(a), y = parse(b);
    var ka = [x.major, x.minor, x.patch], kb = [y.major, y.minor, y.patch];
    for (var i = 0; i < 3; i++) {
      if (ka[i] > kb[i]) return 1;
      if (ka[i] < kb[i]) return -1;
    }
    return 0;
  }

  /**
   * 从 cur 到 next 是哪一级别的更新。
   * @return null（无需更新 / 无法比较）| 'major'（强制）| 'minor' | 'patch'
   */
  function diffLevel(cur, next) {
    if (!cur || !next) return null;         // 任一版本号读不到：判定为「无法比较」，不打扰用户
    if (!/\d+\.\d+\.\d+/.test(String(cur))) return null;
    if (cmp(next, cur) <= 0) return null;   // 没更新 / 服务端版本更旧：不打扰
    var a = parse(cur), b = parse(next);
    if (b.major > a.major) return 'major';
    if (b.minor > a.minor) return 'minor';
    return 'patch';
  }

  /* ---------- 数据来源 ---------- */

  /**
   * 当前版本号。两处来源依次尝试：
   *   1. window.Native.appVersion()（原生 BuildConfig.VERSION_NAME）
   *   2. window.API.appVersion()（同源，但兼容旧版本的前端封装）
   * 都拿不到就返回空串。空串在 diffLevel 里会被判定为「无法比较」而静默跳过，
   * 绝不会编造 0.0.0 之类的假版本号 —— 那会让更新检测误报大版本升级。
   */
  function current() {
    var v = '';
    try {
      if (window.Native && typeof window.Native.appVersion === 'function') {
        v = String(window.Native.appVersion() || '').trim();
      }
    } catch (e) {}
    if (!v) {
      try {
        if (window.API && typeof window.API.appVersion === 'function') {
          v = String(window.API.appVersion() || '').trim();
        }
      } catch (e) {}
    }
    return v;
  }

  /** 从 Release 资产里挑出 APK：优先正式包名，退而求其次取第一个 .apk */
  function pickApk(assets) {
    var list = assets || [];
    for (var i = 0; i < list.length; i++) {
      var a = list[i];
      if (!a) continue;
      if (/\.apk$/i.test(a.name || '') || /android\.package-archive/i.test(a.content_type || '')) return a;
    }
    return null;
  }

  function fmtSize(n) {
    n = +n || 0;
    if (n <= 0) return '';
    if (n < 1024 * 1024) return Math.round(n / 1024) + ' KB';
    return (n / 1024 / 1024).toFixed(1) + ' MB';
  }

  function fmtDate(s) {
    if (!s) return '';
    var d = new Date(s);
    if (isNaN(d.getTime())) return '';
    return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
  }

  /** 打包成统一的检查结果 */
  function pack(cur, latest, level, extra) {
    var info = {
      ok: true, current: cur, latest: latest, level: level,
      hasUpdate: !!level, force: level === 'major'
    };
    for (var k in extra) if (extra.hasOwnProperty(k)) info[k] = extra[k];
    return info;
  }

  /**
   * 本机安装包的指纹（SHA-256）。
   * 版本号冻结不变、但包里内容已经换掉的情况下，靠它才能发现「其实是新包」。
   * 读不到就返回空串 —— 前端据此跳过指纹比对，不当成错误。
   */
  function localSha() {
    var v = '';
    try {
      if (window.Native && typeof window.Native.apkSha256 === 'function') {
        v = String(window.Native.apkSha256() || '');
      }
    } catch (e) {}
    return v ? v.trim().toLowerCase() : '';
  }

  function b64(s) {
    try {
      return decodeURIComponent(escape(window.atob(String(s).replace(/\s/g, ''))));
    } catch (e) {
      try { return window.atob(String(s).replace(/\s/g, '')); } catch (e2) { return ''; }
    }
  }

  /** 来源一：最新正式版 Release */
  function fromRelease(cur) {
    return window.API.get('/repos/' + OWNER + '/' + REPO + '/releases/latest').then(function (r) {
      if (!r || r.status >= 400 || !r.data) {
        return { ok: false, current: cur, reason: r && r.status === 404 ? 'no-release' : 'failed' };
      }
      var d = r.data;
      if (d.draft || d.prerelease) return { ok: false, current: cur, reason: 'no-release' };
      var latest = String(d.tag_name || d.name || '').replace(/^[Vv]/, '');
      var asset = pickApk(d.assets);
      if (!asset) return { ok: false, current: cur, reason: 'no-release' };
      return pack(cur, latest, diffLevel(cur, latest), {
        source: 'release', asset: asset, name: d.name || latest,
        notes: d.body || '', url: d.html_url || '', published: d.published_at || '',
        size: fmtSize(asset.size),
        // Release 里通常没有校验和，就用内置表里对这个文件名的记录。
        // 两个来源都拿不到的话，install() 会拒绝自动安装、改走官方下载页。
        sha256: pinnedSha(asset.name)
      });
    }).catch(function (e) {
      return { ok: false, current: cur, reason: 'failed', error: e };
    });
  }

  /** 来源二：仓库里的 version.json（没有 Release 时的备用通道） */
  function fromManifest(cur) {
    var p = '/repos/' + OWNER + '/' + REPO + '/contents/' + MANIFEST + '?ref=main';
    return window.API.get(p).then(function (r) {
      if (!r || r.status >= 400 || !r.data || !r.data.content) {
        return { ok: false, current: cur, reason: 'no-release' };
      }
      var m;
      try { m = JSON.parse(b64(r.data.content)); }
      catch (e) { return { ok: false, current: cur, reason: 'failed' }; }
      if (!m || !m.version) return { ok: false, current: cur, reason: 'no-release' };
      var latest = String(m.version).replace(/^[Vv]/, '');
      var apkPath = String(m.apk || ('apk/githup-' + latest + '.apk'));
      // apk 字段可以是「仓库内的相对路径」，也可以是完整的 http(s) 直链。
      // 安装包不再提交进仓库（只作为 Release 附件发布），所以这里优先写直链；
      // 相对路径仍然支持，方便老清单继续工作。
      var isAbs = /^https?:\/\//i.test(apkPath);
      var dl = isAbs ? apkPath : (RAW_BASE + '/' + apkPath.replace(/^\/+/, ''));
      return pack(cur, latest, diffLevel(cur, latest), {
        source: 'manifest', name: m.name || latest,
        asset: { name: apkPath.split('/').pop(), browser_download_url: dl },
        notes: m.notes || '', url: 'https://github.com/' + OWNER + '/' + REPO + '/releases',
        published: m.published || '', size: fmtSize(m.size),
        sha256: String(m.sha256 || '').trim().toLowerCase()
      });
    }).catch(function (e) {
      return { ok: false, current: cur, reason: 'failed', error: e };
    });
  }

  /**
   * 拉取最新版本信息，并判断要不要更新。
   *
   * 两条信号，命中任意一条就算「有新版本」：
   *   1. 版本号变大      —— 按 x.y.z 规则决定是强制还是可选
   *   2. 安装包内容变了  —— 版本号完全相同、但校验和不一致，说明是重新打的包
   *
   * 第 2 条是关键：版本号可以冻结不动（比如一直是 1.1.1），
   * 但只要包里的内容换了，用户打开软件照样能收到更新提示。
   *
   * 数据来源：Release 负责 APK 附件与说明，version.json 负责校验和，两者并行请求。
   */
  function check() {
    var cur = current();
    return Promise.all([fromRelease(cur), fromManifest(cur)]).then(function (pair) {
      var rel = pair[0], man = pair[1];

      // 选一个作为主结果：Release 优先（它的 APK 是正式附件），拿不到就用清单
      var info = rel.ok ? rel : (man.ok ? man : rel);
      if (!info.ok) return info;                    // 两条路都没数据

      // Release 里没有校验和时，借用清单里记的那个
      var remoteSha = info.sha256 || (man.ok ? man.sha256 : '') || '';
      var mine = localSha();

      var byVersion = info.level;                   // 版本号比对的结果
      var byContent = null;
      if (mine && remoteSha && mine !== remoteSha) {
        byContent = 'content';                      // 版本号没变，但包不一样
      }

      if (!byVersion && byContent) {
        // 只是内容变了 —— 属于可选更新，给「立即更新 / 稍后提醒 / 跳过此版」
        info.level = 'content';
        info.hasUpdate = true;
        info.force = false;
        info.fromContent = true;
      }
      info.sha256 = remoteSha;
      info.localSha = mine;
      return info;
    });
  }

  /* ---------- 下载安装 ---------- */

  /**
   * 下载 APK 并拉起系统安装器。
   *
   * 关键：装之前必须验指纹。道理很简单 ——
   *   HTTPS 只保证「传输路上没被人改」，不保证「服务端给的包就是对的」。
   *   如果 Release 资产或仓库被替换，用户就会装上一个假的 githup。
   * 所以这里把期望的 SHA-256 一起交给原生层，原生下载完先算哈希比对，
   * 一致才拉起安装器，不一致直接删除并提示 —— 装不上，总比装错强。
   *
   * 期望值取两处，两边都得对得上（任何一处为空则退化为只看另一处）：
   *   1. 本机内置的官方指纹表（编译进 APK，改不动）
   *   2. version.json 清单里的 sha256
   */
  function install(info) {
    var a = info && info.asset;
    if (!a) {
      if (window.UI) window.UI.toast('这个版本没有附带 APK，已打开下载页');
      if (info && info.url) openOut(info.url);
      return false;
    }

    // ---- 确定期望指纹 ----
    var pinned = pinnedSha(a.name);                 // 内置表：这个包名对应的官方指纹
    var listed = String((info && info.sha256) || '').trim().toLowerCase();
    var expect = '';

    if (pinned && listed) {
      if (pinned !== listed) {
        // 内置的和清单对不上 —— 有一方被改了，直接拒绝
        if (window.UI) window.UI.toast('安装包校验信息不一致，已阻止安装');
        return false;
      }
      expect = pinned;
    } else if (pinned) {
      expect = pinned;
    } else if (listed) {
      expect = listed;
    }

    if (!expect) {
      // 拿不到任何指纹：不开这个口子，让用户走官方 Release 页面手动装
      if (window.UI) window.UI.toast('缺少校验信息，已打开官方下载页');
      openOut(info.url || a.browser_download_url);
      return false;
    }

    if (window.NativeBridge && typeof NativeBridge.installApkChecked === 'function') {
      try {
        NativeBridge.installApkChecked(a.browser_download_url, a.name,
          JSON.stringify({ Accept: 'application/vnd.android.package-archive' }), expect);
        window.UI.toast('正在下载并校验 ' + a.name);
        return true;
      } catch (e) { /* 落到下面 */ }
    }

    // 老版本原生层没有校验能力：宁可不自动装，也不能装个没法验的包。
    // 统一走 openOut，保证这种情况下也一定有个出口，不会点了没反应。
    if (window.UI) window.UI.toast('当前版本不支持安全校验，已打开官方下载页');
    openOut(info.url || a.browser_download_url);
    return false;
  }

  function openOut(url) {
    if (!url) return;
    if (window.NativeBridge && NativeBridge.openExternal) NativeBridge.openExternal(url);
    else window.open(url, '_blank');
  }

  /* ---------- 界面 ---------- */

  function notesHtml(notes) {
    var s = String(notes || '').trim();
    if (!s) return '<div class="muted tiny" style="padding:0 2px">这个版本没有写更新说明。</div>';
    if (window.MD && typeof MD.render === 'function') {
      try { return '<div class="md" style="font-size:14px">' + MD.render(s) + '</div>'; } catch (e) { /* 退化 */ }
    }
    return '<div style="font-size:13px;line-height:1.7;white-space:pre-wrap">' + esc(s) + '</div>';
  }

  /**
   * 弹出更新提示。
   * @param info  check() 的结果
   * @param opt   { onDone:function } 关闭后回调
   */
  function prompt(info, opt) {
    opt = opt || {};
    if (!info || !info.hasUpdate) return;
    var UI = window.UI;
    var force = !!info.force;
    var byContent = !!info.fromContent;   // 版本号没变，只是包里的内容换了
    var head = force ? '必须更新才能继续使用' : (byContent ? '有新内容可用' : '发现新版本 ' + info.latest);

    UI.sheet({
      title: head,
      icon: force ? 'rocket' : 'sync',
      dismissible: !force,        // 强制更新时不给关闭按钮、点遮罩无效
      body:
        '<div class="center" style="padding:4px 0 2px">' +
          '<div style="display:flex;justify-content:center;color:var(--fg)">' + window.icon(force ? 'rocket' : 'package', 40) + '</div>' +
          '<div style="font-size:17px;font-weight:600;margin-top:10px">' + esc(info.latest) + '</div>' +
          '<div class="muted tiny" style="margin-top:4px">当前 ' + esc(info.current) +
            (info.size ? ' · APK ' + esc(info.size) : '') +
            (info.published ? ' · ' + esc(fmtDate(info.published)) : '') + '</div>' +
        '</div>' +
        '<div class="card" style="margin-top:14px;padding:12px">' +
          '<div style="font-weight:600;margin-bottom:8px;font-size:14px">' + esc(LEVEL_TEXT[info.level] || '有新版本') + '</div>' +
          '<div class="muted" style="font-size:13px;line-height:1.6;margin-bottom:10px">' +
            (force ? '第一位版本号从 ' + esc(parse(info.current).major + '.' + parse(info.current).minor) + ' 升到了 ' +
                     esc(parse(info.latest).major + '.' + parse(info.latest).minor) + '，属于必须安装的大版本。'
                   : byContent ? '版本号仍是 ' + esc(info.latest) + '，但安装包的内容已经变了 —— 检测到校验和不一致。' +
                                 '你可以现在装，也可以留在当前版本。'
                   : '这一版改的是第' + (info.level === 'minor' ? '二' : '三') + '位版本号，你可以现在装，也可以留在当前版本。') +
          '</div>' +
          '<div style="border-top:1px solid var(--border-muted);padding-top:10px">' + notesHtml(info.notes) + '</div>' +
        '</div>',
      foot: force
        ? '<button class="btn primary" data-up>立即更新</button>'
        : '<button class="btn" data-skip>跳过此版</button><button class="btn" data-later>稍后提醒</button><button class="btn primary" data-up>立即更新</button>',
      onMount: function (body, close) {
        var root = document.getElementById('sheet-root');
        UI.$('[data-up]', root).onclick = function () {
          install(info);
          close();
          if (opt.onDone) opt.onDone('update');
        };
        var later = UI.$('[data-later]', root);
        if (later) later.onclick = function () { close(); if (opt.onDone) opt.onDone('later'); };
        var skip = UI.$('[data-skip]', root);
        if (skip) skip.onclick = function () {
          // 内容更新时版本号没变，只能按指纹记录跳过；否则版本号一样会永久屏蔽后续更新
          if (info.fromContent && info.sha256) {
            window.Store.set(SKIP_SHA_KEY, info.sha256);
            UI.toast('已跳过这个包，下次换内容时仍会提醒');
          } else {
            window.Store.set(SKIP_KEY, info.latest);
            UI.toast('已跳过 ' + info.latest + '，发布大版本时仍会提醒');
          }
          close();
          if (opt.onDone) opt.onDone('skip');
        };
      }
    });
  }

  /**
   * 「您已是最新版本」提示（只有手动点检查更新才出现，启动时静默不打扰）。
   *
   * 用小浮条而不是弹层：贴在底部正中间，两三秒自己消失，
   * 期间照常能点页面上的东西，不打断你正在做的事。
   */
  function upToDateToast(info) {
    var msg = '您已是最新版本' + (info && info.current ? '（当前 ' + info.current + '）' : '');
    if (window.UI.toastOk) window.UI.toastOk(msg, 2600);
    else window.UI.toast(msg, 2600);
  }

  /** 手动检查（设置页入口）：无论有没有更新都要给出明确反馈 */
  function manualCheck() {
    var UI = window.UI;
    UI.loading(true);
    return check().then(function (info) {
      UI.loading(false);
      if (!info.ok) {
        UI.toast(info.reason === 'no-release' ? '还没有发布正式版本' : '检查更新失败，请稍后再试');
        return info;
      }
      if (!info.hasUpdate) { upToDateToast(info); return info; }
      prompt(info);
      return info;
    });
  }

  /**
   * 打开软件时的自动检查（每次打开都会执行，不等、不节流）。
   *
   * 三条规则：
   *   - **已是最新版本 → 什么都不做**，不弹窗、不提示，跟没检查过一样。
   *   - **有更新 → 按版本号规则处理**：第一位变化弹不可关闭的强制更新，
   *     第二、三位变化给「立即更新 / 稍后提醒 / 跳过此版」。
   *   - **拿不到数据（断网 / 没有 Release / 版本号读不到）→ 静默**，
   *     失败原因只在手动检查时才告诉用户。
   *
   * 「跳过此版」记下来的版本不再重复提示（内容更新则记指纹），但强制更新永远会拦。
   * 注意：这里没有「几小时内不重复检查」的限制 —— 每次打开都真的去查，
   * 只有同一毫秒级的重复触发（同一次会话里多个触发点）才会合并成一次请求。
   */
  /** 这个更新是不是已经被用户跳过过了（版本号 / 指纹两种记法都要查） */
  function skipped(info) {
    if (window.Store.get(SKIP_KEY) === info.latest) return true;
    if (info.fromContent && info.sha256 && window.Store.get(SKIP_SHA_KEY) === info.sha256) return true;
    return false;
  }

  var pending = null;                    // 进行中的请求，用来合并重复触发
  var lastStamp = 0;                     // 上次发起请求的时间，防止同一次会话重复打服务端

  function autoCheck() {
    // 同一会话里极短时间内重复触发（间隔 < 3 秒）复用上一次的结果，不重复请求
    if (pending) return pending;
    if (Date.now() - lastStamp < 3000 && lastStamp) return Promise.resolve(null);

    lastStamp = Date.now();
    pending = check().then(function (info) {
      pending = null;
      if (!info.ok || !info.hasUpdate) return info;   // 已是最新 / 拿不到数据：什么都不做
      if (!info.force && skipped(info)) return info;
      prompt(info);
      return info;
    }).catch(function (e) {
      pending = null;
      return { ok: false, reason: 'failed', error: e };   // 异常也保持静默
    });
    return pending;
  }

  /** 打开软件的入口：立即检查，不延迟、不等界面渲染完 */
  function startCheck() {
    return Promise.resolve(autoCheck());
  }

  /** 回到前台时再查一次（切后台超过 30 分钟才算重新「打开」） */
  function resumeCheck() {
    var last = +window.Store.get(LAST_KEY) || 0;
    if (Date.now() - last < 30 * 60 * 1000) return Promise.resolve(null);
    window.Store.set(LAST_KEY, Date.now());
    lastStamp = 0;                       // 放行本次请求（上面的 3 秒合并仅限同一时刻）
    return autoCheck();
  }

  window.Updater = {
    OWNER: OWNER, REPO: REPO,
    parse: parse, cmp: cmp, diffLevel: diffLevel,
    current: current, check: check, fromRelease: fromRelease, fromManifest: fromManifest,
    prompt: prompt, manualCheck: manualCheck, autoCheck: autoCheck,
    startCheck: startCheck, resumeCheck: resumeCheck,
    upToDateToast: upToDateToast, install: install, pinnedSha: pinnedSha, PINNED_SHA: PINNED_SHA
  };
})();
