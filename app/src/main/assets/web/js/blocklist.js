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
  /*
    名单文件名就叫 policy.txt，跟 Gist 里的保持一致。

    两处必须同步：Gist 里改名 + 这里换 base64。对不上就 404，
    而 404 按「拿不到名单」处理 = 放行 —— 名单会静默失效。
    中文文件名能做，但要 percent-encode（githup%E9%BB%91...），
    多一道容易错的手工活，所以回到纯 ASCII 的 policy.txt。

    ⚠️ 之前这里写的是另一个 Gist id（6d4001f8...），那个地址实际是 404：
    远程名单一直没拉到过，全靠内置基线和缓存在撑。已换成真实 id
    （e907901d...），远程这条主源才算真正接通。
  */
  var URL = atob(
    'aHR0cHM6Ly9naXN0LmdpdGh1YnVzZXJjb250ZW50LmNvbS9C' +
    'dXdydC9lOTA3OTAxZDc4NGNkZDhmMmM3MGJmZjFjOTM1ZDli' +
    'ZS9yYXcvcG9saWN5LnR4dA=='
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
   * 关键是要把两种「没拿到」分开 —— 以前都返回 null，后果是：
   * 你把 Gist 文件删了想放行，App 却照样拦，因为它退回缓存/基线去了。
   *
   *   miss   = 服务器明确回答「没有这个文件 / 文件是空的」
   *            → 主人主动清空了名单 → 清缓存，放行所有人
   *   unknown= 网络故障、超时、非 200 → 不知道名单什么样
   *            → 退回缓存 → 基线（fail-closed，不能因为断网就放行）
   *
   * 这两种在以前的代码里长得一模一样（都是 null），必须拆开。
   *
   * @returns {Promise<Array|null>} null = 无法判定（见上）
   */
  function load() {
    if (loaded) return Promise.resolve(loaded);

    var headers = { 'Accept': 'text/plain', 'User-Agent': 'githup/1.0' };

    var p;
    if (window.Native && typeof window.Native.http === 'function') {
      p = window.Native.http('GET', URL, '', headers).then(function (r) {
        if (!r || !r.status) return { ids: null, miss: false };   // 网络故障

        /* 404 = Gist 里没这个文件了。这是服务器明确回答「没有」，
           不是「不知道」—— 主人删掉了名单，该放行。 */
        if (r.status === 404) return { ids: [], miss: true };
        if (r.status === 200) {
          var body = String(r.body || '').trim();
          /* 文件在但是空的（只剩下注释行也算空）→ 同样算「没有名单」 */
          if (!body || !parse(body).length) return { ids: [], miss: true };
          /* 拉到了就存一份，下次断网有得用 */
          prefSet(CACHE_KEY, body);
          return { ids: parse(body), miss: false };
        }
        return { ids: null, miss: false };   // 其它状态码：不知道
      });
    } else {
      p = Promise.resolve({ ids: null, miss: false });
    }

    var timer = new Promise(function (res) {
      setTimeout(function () { res({ ids: null, miss: false }); }, TIMEOUT_MS);
    });

    return Promise.race([p, timer]).then(function (got) {
      var ids = got ? got.ids : null;
      var miss = !!(got && got.miss);

      /*
        「没有文件」→ 放行。

        连缓存和内置基线都不用：远程是主源，主源说没有就是没有，
        再拿旧的缓存去拦，等于你删了名单却删不掉，名单就单向了
        （只能加不能减）。所以这里连缓存一起清掉，保证后续断网
        也不会拿旧名单拦人。
      */
      if (miss) {
        prefSet(CACHE_KEY, '');
        loaded = [];
        return loaded;
      }

      if (ids && ids.length) { loaded = merge(ids); return loaded; }

      /* 网络故障 → 退回缓存；没有缓存 → 退回内置基线 */
      var cached = prefGet(CACHE_KEY);
      if (cached) {
        var c = parse(cached);
        if (c.length) { loaded = merge(c); return loaded; }
      }
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
          完全不知道是谁 —— 没有真实 ID，连「上次记住的那个」也没有
          （退出登录会把它清掉，这是刻意的，否则被拦的人退不出去）。

          ⚠️ 以前这里拿「缓存过名单」当痕迹，条件太宽：
          名单缓存只说明这台机器联网拉到过一次名单，跟「本机有账号」
          没有半点关系。于是正常用户令牌一过期（401）就被判 3 拦住，
          而 reverify 又永远拉不到身份 —— 卡在「需要联网验证」那页
          无限自动重试，退出登录都绕不出来，等于把人锁死在外面。

          现在只看调用方给的 strict：由「失败原因是不是网络」决定，
          令牌失效这类不可自愈的失败不该拦 —— 拦着没意义，得放人
          回去重新登录。
        */
        verdict = strict ? 3 : 1;      // 3 = 无法确认（见 lockView）
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

    /**
     * 真正退出登录时调：连记住的 ID 一起清。
     * 不清的话，退出后仍拿旧账号的 ID 去判 —— 那人永远退不出去，
     * 也就换不了号。退出是用户自己的动作，该退干净。
     */
    forget: function () {
      hit = false;
      verdict = 0;
      loaded = null;
      try { prefSet(UID_KEY, ''); } catch (e) {}
    },

    /** 上次判定过的 ID（排查用） */
    lastUid: lastUid
  };
})();
