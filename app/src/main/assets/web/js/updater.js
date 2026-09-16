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
 * 更新源是本仓库（Buwrt/githup）的 Release，
 * 取其最新一个正式版（跳过 draft 与 prerelease）的 .apk 附件，
 * 交给原生层的 installApk 下载并拉起安装器。
 * ============================================================ */
(function () {
  'use strict';

  var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); };

  var OWNER = 'Buwrt';
  var REPO = 'githup';
  var SKIP_KEY = 'updSkipVersion';   // 用户主动跳过的新版本号
  var LAST_KEY = 'updLastCheck';     // 上次静默检查的时间戳

  var LEVEL_TEXT = {
    major: '重要更新，安装后才能继续使用',
    minor: '功能更新，可以稍后再装',
    patch: '修复更新，可以稍后再装'
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
   * @return null（无需更新）| 'major'（强制）| 'minor' | 'patch'
   */
  function diffLevel(cur, next) {
    if (cmp(next, cur) <= 0) return null;   // 没更新 / 服务端版本更旧：不打扰
    var a = parse(cur), b = parse(next);
    if (b.major > a.major) return 'major';
    if (b.minor > a.minor) return 'minor';
    return 'patch';
  }

  /* ---------- 数据来源 ---------- */

  function current() {
    try { return window.API && window.API.appVersion ? window.API.appVersion() : '0.0.0'; }
    catch (e) { return '0.0.0'; }
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

  /**
   * 拉取最新 Release 并比对。
   * 返回对象里 ok=false 表示没能拿到可用结果（网络失败 / 还没有发布任何版本）。
   */
  function check() {
    var cur = current();
    var path = '/repos/' + OWNER + '/' + REPO + '/releases/latest';
    return window.API.get(path).then(function (r) {
      if (!r || r.status >= 400 || !r.data) {
        return { ok: false, current: cur, reason: r && r.status === 404 ? 'no-release' : 'failed' };
      }
      var d = r.data;
      if (d.draft || d.prerelease) return { ok: false, current: cur, reason: 'no-release' };
      var latest = String(d.tag_name || d.name || '').replace(/^[Vv]/, '');
      var level = diffLevel(cur, latest);
      return {
        ok: true, current: cur, latest: latest,
        level: level, hasUpdate: !!level, force: level === 'major',
        asset: pickApk(d.assets), name: d.name || latest,
        notes: d.body || '', url: d.html_url || '',
        published: d.published_at || '',
        size: d.assets && d.assets.length ? fmtSize((pickApk(d.assets) || {}).size) : ''
      };
    }).catch(function (e) {
      return { ok: false, current: cur, reason: 'failed', error: e };
    });
  }

  /* ---------- 下载安装 ---------- */

  /** 下载 APK 并拉起系统安装器；浏览器环境（无原生桥）退化为打开 Release 页面 */
  function install(info) {
    var a = info && info.asset;
    if (!a) {
      if (window.UI) window.UI.toast('这个版本没有附带 APK，已打开下载页');
      if (info && info.url) openOut(info.url);
      return false;
    }
    if (window.NativeBridge && typeof NativeBridge.installApk === 'function') {
      try {
        NativeBridge.installApk(a.browser_download_url, a.name,
          JSON.stringify({ Accept: 'application/vnd.android.package-archive' }));
        UI.toast('正在下载 ' + a.name);
        return true;
      } catch (e) { /* 落到下面打开网页 */ }
    }
    openOut(info.url || (a && a.browser_download_url));
    return true;
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
    var head = force ? '必须更新才能继续使用' : '发现新版本 ' + info.latest;

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
          window.Store.set(SKIP_KEY, info.latest);
          UI.toast('已跳过 ' + info.latest + '，发布大版本时仍会提醒');
          close();
          if (opt.onDone) opt.onDone('skip');
        };
      }
    });
  }

  /** 手动检查（设置页入口）：无论有没有更新都要给出反馈 */
  function manualCheck() {
    var UI = window.UI;
    UI.loading(true);
    return check().then(function (info) {
      UI.loading(false);
      if (!info.ok) {
        UI.toast(info.reason === 'no-release' ? '还没有发布正式版本' : '检查更新失败，请稍后再试');
        return info;
      }
      if (!info.hasUpdate) {
        UI.sheet({
          title: '已是最新版本',
          icon: 'check-circle-fill',
          body: '<div class="center" style="padding:10px 0 4px">' +
            '<div style="display:flex;justify-content:center;color:var(--success)">' + window.icon('check-circle-fill', 40) + '</div>' +
            '<div style="font-size:17px;font-weight:600;margin-top:10px">' + esc(info.current) + '</div>' +
            '<div class="muted tiny" style="margin-top:4px">当前已经是 ' + esc(OWNER + '/' + REPO) + ' 的最新正式版本</div>' +
            '</div>',
          foot: '<button class="btn" data-close="1">好的</button>'
        });
        return info;
      }
      prompt(info);
      return info;
    });
  }

  /**
   * 启动时的静默检查。
   * 6 小时内不重复打扰；被跳过的版本不再提示，但强制更新永远会拦。
   */
  function autoCheck() {
    var last = +window.Store.get(LAST_KEY) || 0;
    if (Date.now() - last < 6 * 3600 * 1000) return Promise.resolve(null);
    window.Store.set(LAST_KEY, Date.now());
    return check().then(function (info) {
      if (!info.ok || !info.hasUpdate) return info;
      if (!info.force && window.Store.get(SKIP_KEY) === info.latest) return info;
      prompt(info);
      return info;
    });
  }

  window.Updater = {
    OWNER: OWNER, REPO: REPO,
    parse: parse, cmp: cmp, diffLevel: diffLevel,
    current: current, check: check, prompt: prompt,
    manualCheck: manualCheck, autoCheck: autoCheck, install: install
  };
})();
