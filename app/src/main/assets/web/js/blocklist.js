/**
 * blocklist.js —— 远程访问名单（按 GitHub 数字 ID 比对）
 *
 * ── 为什么按 ID 而不是用户名 ──
 * 用户名随时能改，改一次旧的判定就失效了。数字 ID 从注册那刻定死，
 * 改昵称、改头像、改用户名都不变。要比对就得比这个。
 * 名单里也只写 ID —— 用户名会变，且没必要知道是谁。
 *
 * ── 为什么名单放远程 ──
 * 想加谁减谁不用重新发版，改那份文本就行。
 *
 * ── 判定方向：拿不到就拦（fail-closed）──
 * 上一版是「拿不到就放行」，结果被绕过去了：
 *   · 登录时断网 → 身份都拿不到 → 直接放行
 *   · 退出登录再断网 → 连判定都不做 → 直接放行
 *   · 只把名单地址屏蔽掉（hosts / 代理 / DNS）→ 名单拉不到 → 放行
 * 三种都只要掐掉一次请求就进去了，名单等于没有。
 *
 * 现在反过来：无法确认「这个人不在名单里」时，一律拦。
 *
 * ── 但 fail-closed 有个致命副作用：名单源一挂，全体用户被挡 ──
 * 所以配一份**本地缓存**兜底：
 *   · 拉成功过一次 → 缓存落盘，之后拉不到就用缓存判
 *   · 每次启动仍会尝试刷新，联网就拿到最新的
 *   · 解封某人后，需要他联网一次才会看到新名单（断网期间维持旧判定）
 * 缓存不过期，是故意的：过期就等于给「一直断网」留了一条绕过的路。
 *
 * ── 还记住「上次判定过的 ID」──
 * 断网时 me() 拿不到身份，但本机多半刚用某个账号跑过。
 * 记住它，断网就按它判 —— 堵掉「退出登录 + 断网」这条绕过路径。
 *
 * ── 名单格式 ──
 * 一行一个数字 ID，空行和 # 开头的行忽略。
 */
window.Blocklist = (function () {
  'use strict';

  /*
    名单地址。

    不做明文：不是加密（反编译 dex 照样能翻出来，客户端改不了这一点），
    只是别让它躺在那儿一眼被看见。
  */
  var URL = atob(
    'aHR0cHM6Ly9naXN0LmdpdGh1YnVzZXJjb250ZW50LmNvbS9CdXdydC82ZDQw' +
    'MDFmODM0NjE2NjAxZWE1N2U3YmNjMjAyMzRhOS9yYXcvcG9saWN5LnR4dA=='
  );

  /*
    内置基线名单。

    远程那份是主源，但有个空档：全新安装 + 从未联网成功过 → 既没有
    缓存也没有记住的 ID，远程又拉不到 —— 这时候什么都判不了，只能放行。
    「删除重装 + 断网」走的就是这条空档。

    基线把这条空档填上：它不依赖网络，装完就在。
    解封时远程名单里删掉即可 —— 合并是取并集，但远程成功拉到时以
    远程为准（见 merge），所以远程删了、联网一次就真的解封。
  */
  var BASE = parse(
    atob('NzAyMTQ5NzA=')
  );

  /* 拿名单最多等这么久。启动路径上不该被一个旁支请求拖住。 */
  var TIMEOUT_MS = 8000;

  var CACHE_KEY = 'bl_cache';   // 上次拉到的名单原文
  var UID_KEY = 'bl_uid';       // 上次判定过的账号 ID

  function parse(text) {
    var out = [];
    String(text || '').split(/\r?\n/).forEach(function (ln) {
      var t = ln.trim();
      if (!t || t.charAt(0) === '#') return;
      if (/^\d+$/.test(t)) out.push(Number(t));
    });
    return out;
  }

  var loaded = null;   // 本次会话已拿到的名单；null 表示还没拉
  var hit = false;     // 这个账号命中了没有
  var verdict = 0;     // 0=还没判 1=放行 2=命中

  /* ── 本地存储 ──
     走原生 prefs 而不是 localStorage：清 WebView 数据会连 localStorage
     一起清掉，缓存没了就只剩「拦」这一条路（正常用户被误伤）。 */
  function prefGet(k) {
    try {
      if (window.NativeBridge && typeof window.NativeBridge.getPref === 'function') {
        return window.NativeBridge.getPref(k) || '';
      }
    } catch (e) {}
    return '';
  }
  function prefSet(k, v) {
    try {
      if (window.NativeBridge && typeof window.NativeBridge.setPref === 'function') {
        window.NativeBridge.setPref(k, v || '');
      }
    } catch (e) {}
  }

  /** 合并：远程优先，缺了就补基线和缓存 */
  function merge(ids) {
    var out = [];
    (ids || []).concat(BASE).forEach(function (n) {
      if (out.indexOf(n) < 0) out.push(n);
    });
    return out;
  }

  /**
   * 拉名单。
   *
   * @returns {Promise<Array|null>} 拉不到且没有缓存时返回 null ——
   *          null 是「无法判定」，跟 []（名单为空、放行）是两回事，
   *          别混成一个：混了就等于把 fail-closed 又开回 fail-open。
   */
  function load() {
    if (loaded) return Promise.resolve(loaded);

    var headers = { 'Accept': 'text/plain', 'User-Agent': 'githup/1.0' };

    var p;
    if (window.Native && typeof window.Native.http === 'function') {
      p = window.Native.http('GET', URL, '', headers).then(function (r) {
        if (!r || r.status !== 200 || !r.body) return null;
        /* 拉到了就存一份，下次断网有得用 */
        prefSet(CACHE_KEY, String(r.body));
        return parse(r.body);
      });
    } else {
      p = Promise.resolve(null);
    }

    var timer = new Promise(function (res) { setTimeout(function () { res(null); }, TIMEOUT_MS); });

    return Promise.race([p, timer]).then(function (ids) {
      if (ids && ids.length) { loaded = merge(ids); return loaded; }
      /* 拉不到（或拉到空的）→ 退回缓存。
         拉到「空名单」也走缓存分支：一份空名单多半是源被改坏了，
         拿它去放行等于给别人送了一条路。 */
      var cached = prefGet(CACHE_KEY);
      if (cached) {
        var c = parse(cached);
        if (c.length) { loaded = merge(c); return loaded; }
      }
      /* 远程拉到了（哪怕是空的）就以远程为准 —— 解封靠这一步 */
      loaded = (ids && ids.length) ? merge(ids) : (ids ? ids : null);
      if (loaded && !loaded.length && BASE.length) loaded = BASE.slice();
      return loaded;
    }).catch(function () {
      var cached = prefGet(CACHE_KEY);
      loaded = cached ? parse(cached) : null;
      if (!loaded || !loaded.length) loaded = BASE.length ? BASE.slice() : null;
      else loaded = merge(loaded);
      return loaded;
    });
  }

  /** 记住这次判定的是谁，供断网时用 */
  function rememberUid(uid) {
    if (uid) prefSet(UID_KEY, String(uid));
  }
  function lastUid() {
    var v = prefGet(UID_KEY);
    var n = Number(v);
    return (v && !isNaN(n) && n) ? n : 0;
  }

  return {
    /**
     * 判定这个账号能不能用。
     *
     * @param {number|string} uid    当前账号数字 ID；拿不到就传 0/空，
     *                               会用上次记住的那个判
     * @param {boolean} strict       true = 连身份都拿不到时按「拦」处理
     *                               （有令牌却验不出是谁时应该传 true）
     * @returns {Promise<number>} 1=放行 2=命中
     */
    check: function (uid, strict) {
      var n = Number(uid) || 0;
      if (!n) n = lastUid();          // 断网 / 未登录：用记住的那个

      if (!n) {
        /*
          完全不知道是谁。

          以前这里 strict=false 就放行，于是「删除重装 + 断网」直接进得去 ——
          重装后既没有记住的 ID 也没有缓存名单，判定根本没东西可比。

          现在：本机只要有任一「曾经用过的痕迹」（令牌 / 缓存名单），
          就说明这台机器上确实有过账号，只是现在验不出来 —— 拦。
          真正的全新安装（什么痕迹都没有）才放行。
        */
        var traced = !!strict || !!prefGet(CACHE_KEY);
        verdict = traced ? 3 : 1;      // 3 = 无法确认，也要拦（见 lockView）
        return Promise.resolve(verdict);
      }
      rememberUid(n);

      return load().then(function (ids) {
        if (!ids) { verdict = 3; return verdict; }   // 连基线都没有 → 无法确认
        hit = ids.indexOf(n) >= 0;
        verdict = hit ? 2 : 1;
        return verdict;
      }).catch(function () {
        verdict = 3;
        return verdict;
      });
    },

    /** 判定过了没有：0=还没判 1=放行 2=命中。render 据此决定要不要拦。 */
    state: function () { return verdict; },

    /**
     * 换账号时清掉结论。
     * 只清内存里的判定，不清 UID_KEY —— 那个要在断网时当兜底用。
     */
    reset: function () { hit = false; verdict = 0; loaded = null; },

    /** 上次判定过的 ID（排查用） */
    lastUid: lastUid
  };
})();
