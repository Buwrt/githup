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
 * ── 一条硬规则：拿不到名单就放行 ──
 * 地址挂了、网络不通、内容坏了、超时 —— 任何一种都当成「名单为空」。
 * 绝不能反过来：一次网络故障把全体用户挡在门外是灾难级的，
 * 漏放某一个人只是小损失。两者不对等，所以永远偏向放行。
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

  /* 拿名单最多等这么久。启动路径上不该被一个旁支请求拖住。 */
  var TIMEOUT_MS = 8000;

  var loaded = null;   // 本次会话已拿到的名单，避免重复请求
  var hit = false;     // 这个账号命中了没有
  var verdict = 0;     // 0=还没判 1=放行 2=命中

  /**
   * 拉名单 → 取出里面的 ids 数组。
   * 任何异常都 resolve 成空数组（放行），不往上抛。
   */
  function load() {
    if (loaded) return Promise.resolve(loaded);

    var headers = {
      'Accept': 'text/plain',
      'User-Agent': 'githup/1.0'
    };

    /* 走原生通道：页面源是本地的，直接 fetch 是跨域，会被拦掉。
     * 没有原生环境（浏览器里跑着玩）就放弃 —— 拿不到就放行。 */
    var p;
    if (window.Native && typeof window.Native.http === 'function') {
      p = window.Native.http('GET', URL, '', headers)
        .then(function (r) {
          if (!r || r.status !== 200 || !r.body) return [];
          var out = [];
          String(r.body).split(/\r?\n/).forEach(function (ln) {
            var t = ln.trim();
            if (!t || t.charAt(0) === '#') return;
            if (/^\d+$/.test(t)) out.push(Number(t));
          });
          return out;
        });
    } else {
      p = Promise.resolve([]);
    }

    /* 超时兜底：宁可放行，也不能让启动卡在这上面。 */
    var timer = new Promise(function (res) { setTimeout(function () { res([]); }, TIMEOUT_MS); });

    return Promise.race([p, timer]).then(function (ids) {
      loaded = ids;
      return ids;
    }).catch(function () {
      loaded = [];
      return [];
    });
  }

  return {
    /**
     * 这个数字 ID 在不在名单里。
     * @param {number|string} uid  GitHub 账号数字 ID
     * @returns {Promise<boolean>} 只在确定命中时返回 true
     */
    check: function (uid) {
      var n = Number(uid);
      if (!n || isNaN(n)) return Promise.resolve(false);
      return load().then(function (ids) {
        hit = ids.indexOf(n) >= 0;
        return hit;
      }).catch(function () { hit = false; return false; });
    },

    /** 判定过了没有：0=还没判 1=放行 2=命中。render 据此决定要不要拦。 */
    state: function () { return verdict; },

    /** 命中后铺一层全屏页，应用不再可用 */
    show: function () {
      var host = document.getElementById('app');
      var splash = document.getElementById('splash');
      if (splash) splash.remove();
      if (!host) return;
      host.hidden = false;
      host.innerHTML =
        '<div class="page" style="display:flex;align-items:center;justify-content:center;min-height:60vh">' +
        '<div class="card" style="padding:24px;text-align:center;max-width:320px">' +
        '<div style="margin-bottom:12px">' + (window.icon ? window.icon('alert', 28) : '') + '</div>' +
        '<div style="font-size:15px;font-weight:600;margin-bottom:8px">无法使用本应用</div>' +
        '<div class="muted" style="font-size:13px;line-height:1.6">' +
        '当前 GitHub 账号已被限制使用 githup。' +
        '</div></div></div>';
    },

    /** 名单地址（方便排查） */
    url: URL
  };
})();
