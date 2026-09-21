/* ============================================================
 * translate.js — 给 githup 加一个「翻译本页」
 *
 * 为什么需要它：
 *   GitHub 网页版能靠浏览器扩展 / 油猴脚本翻译，但 App 里的 WebView 装不了
 *   扩展，也没有注入脚本的入口。既然前端就是自己的，就把翻译做进前端本身。
 *
 * 工作方式：
 *   1. 收集当前页面里「值得翻译」的文本节点（跳过代码、路径、commit sha、
 *      @提及、#编号、版本号、已经是中文的内容…）
 *   2. 分批送到免费翻译引擎：微软 Edge → 有道 → DeepL → Google → MyMemory，
 *      哪个能用用哪个，也可以在菜单里手动指定
 *   3. 只改文本节点的 nodeValue，不动 DOM 结构 —— 所以点击、长按、下拉刷新
 *      这些事件一个都不会被破坏
 *   4. 再点一次原样还原（原文挂在节点上，不重建页面、不重新请求接口）
 *
 * 隐私：
 *   请求走 Native.http 原生通道。桥接层不会自动附加 GitHub Token（token 是
 *   前端显式拼进 headers 的，见 JsBridge.http），所以翻译请求不带任何凭据。
 *   但「被翻译的文本本身」会发给所选的翻译服务商 —— 这是翻译的前提，避免不了。
 *
 * 用法：在 index.html 里引入本文件即可，不需要改 app.js。
 *   有 #appbar-actions 时（App 内）：自动往右上角塞一个图标按钮
 *   没有时（浏览器 Demo）：退化成右下角悬浮按钮
 * ============================================================ */
(function () {
  'use strict';

  /* ================= 常量 ================= */
  /* 目标语言。以前写死 'zh-Hans'，整页只能往中文翻。
   * 现在可以在菜单里切换（见 pickTarget），默认仍是简体中文，
   * 老用户升级后行为跟以前完全一致。
   * 各引擎会把它换成自己的格式：有道 zh-CHS、Google zh-CN、DeepL ZH… */
  var TARGETS = [
    { key: 'zh-Hans', label: '简体中文', short: '中文' },
    { key: 'en', label: 'English（英文）', short: '英文' }
  ];
  var TARGET_DEFAULT = 'zh-Hans';
  var KEY_TARGET = 'gh_tr_target';
  function normalizeTarget(v) {
    for (var i = 0; i < TARGETS.length; i++) { if (TARGETS[i].key === v) return TARGETS[i].key; }
    return TARGET_DEFAULT;
  }
  var TO = normalizeTarget(prefGet(KEY_TARGET, TARGET_DEFAULT));
  var FROM = '';                      // 源语言，空 = 自动识别（交给引擎自己判断）
  var MAX_CHARS = 5000;               // 单批总字符上限（微软匿名端点保守值）
  var MAX_ITEMS = 40;                 // 单批最大段数
  var CACHE_MAX = 600;                // 本地译文缓存条数上限
  var JSONP_TIMEOUT = 15000;
  var PROBE_TIMEOUT = 5000;           // 单个引擎探测超时
  var CONCURRENCY = 8;                // 逐条引擎的并发请求数
  /* 同时进行的批次数。以前这个值定义了却没用上，组并发是写死的 3 ——
   * 两边不一致，改常量的人以为自己调了并发，其实一点没变。现在接上。 */
  var BATCH_PARALLEL = 3;             // 同时进行的批次数

  var KEY_ENGINE = 'gh_tr_engine';
  var KEY_CUSTOM = 'gh_tr_custom';
  var KEY_CACHE = 'gh_tr_cache';
  var KEY_AUTO = 'gh_tr_auto';   // 总开关。默认关闭：装上不自动翻，
                                 // 用户亲手点开按钮才算同意翻译

  /* ================= 存储（App 用 Store，浏览器用 localStorage） ================= */
  function prefGet(k, def) {
    try {
      if (window.Store && typeof window.Store.getJSON === 'function') {
        var v = window.Store.getJSON(k, undefined);
        if (v !== undefined && v !== null) return v;
      }
    } catch (e) {}
    try {
      var raw = localStorage.getItem(k);
      return raw ? JSON.parse(raw) : def;
    } catch (e) { return def; }
  }

  function prefSet(k, v) {
    /* Store.setJSON 内部已经是「桥梁 + localStorage」双写了，这里再补一次
     * localStorage.setItem 等于把整份数据序列化两遍、落盘两遍。
     * 单条设置（几字节）无所谓，但译文缓存几百条时这一倍是实打实的。
     * 只有 Store 这条路走不通时才退回 localStorage。 */
    var wrote = false;
    try {
      if (window.Store && typeof window.Store.setJSON === 'function') {
        window.Store.setJSON(k, v);
        wrote = true;
      }
    } catch (e) {}
    if (wrote) return;
    try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {}
  }

  /* ================= 小工具 ================= */
  function hash(s) {
    var h = 5381, i = s.length;
    while (i) h = (h * 33) ^ s.charCodeAt(--i);
    return (h >>> 0).toString(36);
  }

  function norm(s) {
    return String(s === undefined || s === null ? '' : s).replace(/\s+/g, ' ').trim();
  }

  // 同一条提示 3 秒内只弹一次：自动补翻、页面定时刷新相对时间这些路径
  // 可能短时间内反复走到同一个提示，不节流的话会叠着弹个没完。
  var _lastToast = '', _lastToastAt = 0;
  function toast(msg) {
    var now = Date.now();
    if (msg === _lastToast && now - _lastToastAt < 3000) return;
    _lastToast = msg; _lastToastAt = now;
    if (window.UI && window.UI.toast) window.UI.toast(msg);
    else console.log('[translate] ' + msg);
  }

  function icon(name, size) {
    if (typeof window.icon === 'function') {
      try { var s = window.icon(name, size || 20); if (s) return s; } catch (e) {}
    }
    // 没有图标库时（浏览器 Demo）用文字兜底
    if (name === 'globe') return '译';
    if (name === 'x') return '原';
    return '';
  }

  /**
   * 并发跑任务，但最多同时跑 limit 个。
   * 逐条翻译的引擎（有道 / MyMemory / 设备端）原来是一条一条串着发的，
   * 72 段就是 72 次往返 —— 慢的根源就在这儿。改成 6 路并发后能快好几倍。
   * 失败的项填 null，由调用方兜底成原文，绝不让某一句卡住整批。
   */
  /**
   * abort 是「换页中止」的开关：一旦它为真，就不再起新任务，剩下的位置填 null
   * （调用方会兜成原文）。没有它的时候换页只是把 seq 加了 1，已经在飞的
   * 逐条请求一个都停不下来 —— 探索页一屏上百段，那些请求会继续把原生网络
   * 线程和有道的节流队列占满，新页面只能排在后面等。
   */
  function mapLimit(items, limit, fn, abort) {
    var out = new Array(items.length);
    var i = 0, active = 0;
    return new Promise(function (resolve) {
      if (!items.length) return resolve(out);
      function next() {
        while (active < limit && i < items.length) {
          if (abort && abort()) break;
          (function (idx) {
            active++;
            Promise.resolve()
              .then(function () { return fn(items[idx], idx); })
              .then(function (r) { out[idx] = r; }, function (e) {
                out[idx] = null;
                /* 单段失败在这里被吞掉的话，「引擎不可用」提示就带不出原因
                 * （真机上见过：有道全挂，提示却空着括号）。记下第一个错误。 */
                if (e && typeof state !== 'undefined' && state && !state.lastErr) {
                  state.lastErr = e.message || String(e);
                }
              })
              .then(function () { active--; next(); });
          })(i++);
        }
        /* 原来这里还要求 i >= items.length，中止时 i 到不了终点，
         * promise 就永远不 resolve —— 换页后新的一轮会被旧链挂住。 */
        if (active === 0) resolve(out);
      }
      next();
    });
  }

  /* ================= 网络 =================
   * App 内：走 Native.http（原生 Socket 实现，没有跨域限制，也不会自动带 token）
   * 浏览器：退回 fetch；再不行还能用 JSONP（script 注入，天然无视 CORS）
   */
  function hasBridge() {
    return !!(window.Native && typeof window.Native.http === 'function' &&
      window.Native.has && window.Native.has());
  }

  /* 网络请求超时：黑洞路由（连不上也不拒绝）会让请求挂到天荒地老，
   * 没有这层的话一个坏引擎就能把整页翻译卡住。默认 15 秒，可在
   * localStorage 里放 gh_tr_timeout 调整（毫秒）。 */
  var REQ_TIMEOUT = prefGet('gh_tr_timeout', 15000) | 0 || 15000;

  /* 原生通道：这里**必须传对象，不能自己 stringify**。
   *
   * 链路是两层，很容易搞混：
   *   translate.js ──▶ window.Native.http(method, url, body, headers)   ← api.js 的 JS 封装
   *                    它内部会 JSON.stringify(headers)，再调
   *                    ──▶ NativeBridge.http(id, method, url, body, headersJson)  ← Java
   *                        Java 侧签名是 String headersJson，做 new JSONObject(headersJson)。
   *
   * 以前在这里先 JSON.stringify 了一次，于是 Java 拿到的是「JSON 字符串的字符串」，
   * new JSONObject 直接抛异常 → 回调 status 0 → 原生通道每次都失败，
   * 只能退回 fetch 兜底。表现就是：只有服务端愿意给 CORS 头的引擎（有道 / MyMemory）
   * 能用，Google / DeepL 全灭，而且每个请求都要先失败一次再重试 —— 又慢又不全。
   * 所以传对象，让 api.js 去 stringify，只 stringify 一次。 */
  /* 原生通道默认带个浏览器 UA：Http.java 在没有 UA 时会写 HubMobile/1.0，
   * 有道 / DeepL 这类对非浏览器 UA 不太友好，容易直接拒。 */
  var DEFAULT_UA = 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 ' +
    '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
  function nativeHttp(method, url, body, headers) {
    var h = { 'User-Agent': DEFAULT_UA };
    if (headers) { for (var k in headers) { if (Object.prototype.hasOwnProperty.call(headers, k)) h[k] = headers[k]; } }
    return window.Native.http(method, url, body || null, h)
      .then(function (res) {
        if (!res || !res.status || res.status >= 400) {
          throw new Error('HTTP ' + (res ? res.status : 0));
        }
        return res.body || '';
      });
  }
  function fetchHttp(method, url, body, headers) {
    var init = { method: method, mode: 'cors', credentials: 'omit', headers: headers || {} };
    if (body) init.body = body;
    return fetch(url, init).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.text();
    });
  }
  function request(method, url, body, headers) {
    // 原生通道优先（不受同源策略限制），它挂了就退回 fetch 再试一次
    var run = hasBridge()
      ? nativeHttp(method, url, body, headers).catch(function (e) {
        console.warn('[translate] 原生通道失败，退回 fetch', e && e.message);
        return fetchHttp(method, url, body, headers);
      })
      : fetchHttp(method, url, body, headers);
    return Promise.race([run, new Promise(function (_, rej) {
      setTimeout(function () { rej(new Error('请求超时')); }, REQ_TIMEOUT);
    })]);
  }

  var jsonpSeq = 0;
  /** JSONP：给浏览器环境留的后路（Google gtx 支持 callback） */
  function jsonp(url, param) {
    return new Promise(function (resolve, reject) {
      var name = '__tr_jsonp_' + (++jsonpSeq);
      var s = document.createElement('script');
      var timer = setTimeout(function () { cleanup(); reject(new Error('JSONP 超时')); }, JSONP_TIMEOUT);
      function cleanup() {
        clearTimeout(timer);
        try { delete window[name]; } catch (e) { window[name] = undefined; }
        if (s.parentNode) s.parentNode.removeChild(s);
      }
      window[name] = function (data) { cleanup(); resolve(data); };
      s.onerror = function () { cleanup(); reject(new Error('JSONP 加载失败')); };
      s.src = url + (url.indexOf('?') >= 0 ? '&' : '?') + (param || 'callback') + '=' + name;
      document.head.appendChild(s);
    });
  }

  /* ================= 引擎 =================
   * 统一契约：translate(texts: string[]) => Promise<string[]>
   * 返回的数组必须与入参一一对应；某一段没译出来就返回原文，绝不错位。
   */

  var ENGINES = {};

  /* --- 0. 设备端翻译（浏览器 / WebView 内置，离线、文本不出设备） ---
   * 新版 Chromium 内置了 window.Translator（设备端翻译 API）。能不能用要看系统
   * 有没有下载对应的语言包，用不上就静默跳过，不打扰用户。
   * 注意：这个全局对象是浏览器原生的，所以本模块对外一律用 window.GhTranslator，
   * 绝不占用 Translator 这个名字。 */
  function onDeviceReady() {
    try {
      var T = window.Translator;
      return !!(T && typeof T.create === 'function');
    } catch (e) { return false; }
  }
  ENGINES.ondevice = {
    label: '设备端翻译（离线，系统内置）',
    batch: false,
    translate: function (texts, opts) {
      var abort = opts && opts.abort;
      var T = window.Translator;
      if (!T || typeof T.create !== 'function') return Promise.reject(new Error('不支持设备端翻译'));
      // 设备端翻译不支持 auto，源语言必须给死；绝大多数 GitHub 内容是英文
      var src = (FROM || 'en').split('-')[0];
      var tgt = TO === 'zh-Hans' ? 'zh' : TO.split('-')[0];
      // create/ready/translate 在部分 WebView 上会永久挂起（availability 同款毛病），
      // 手动选中这个引擎时没有任何探测保护，不超时就直接把 busy 卡死 —— 全链路 race。
      var withTimeout = function (pr) {
        return Promise.race([Promise.resolve(pr), new Promise(function (_, rej) {
          setTimeout(function () { rej(new Error('设备端翻译无响应')); }, REQ_TIMEOUT);
        })]);
      };
      return withTimeout(T.create({ sourceLanguage: src, targetLanguage: tgt }))
        .then(function (tr) {
          if (tr && tr.ready) return withTimeout(tr.ready).then(function () { return tr; });
          return tr;
        }).then(function (tr) {
          return mapLimit(texts, CONCURRENCY, function (t) {
            if (abort && abort()) return Promise.resolve(null);
            return withTimeout(tr.translate(t)).then(function (r) { return norm(r) || t; });
          }, abort).then(function (out) {
            return out.map(function (r, i) { return r || texts[i]; });
          });
        });
    }
  };

  /* --- 1. 微软 Edge 免费翻译 —— 已停用 ---
   * 微软已经关掉了 Edge 浏览器翻译的公开令牌接口：
   * https://edge.microsoft.com/translate/auth 现在直接返回 404，
   * 拿不到 JWT 就调不动 api-edge 翻译接口 —— 所以真机上选中它是「HTTP 404」，
   * 这不是代码写错了，是官方把免费门关了（同类工具如 Read Frog、pyVideoTrans
   * 也在同一时间报一样的 404）。
   * 实现留着、但从自动探测和手动选择里摘掉，免得你选中它只看到 404。
   * 哪天微软重新开放，把下面这行 retired 改成 false 就回来了。 */
  var edgeJwt = { v: '', t: 0 };
  function edgeAuth() {
    if (edgeJwt.v && Date.now() - edgeJwt.t < 8 * 60 * 1000) return Promise.resolve(edgeJwt.v);
    // JWT 有有效期（约 10 分钟），缓存 8 分钟，过期静默重取
    return request('GET', 'https://edge.microsoft.com/translate/auth', null, {})
      .then(function (t) {
        t = String(t || '').trim();
        if (!t) throw new Error('取不到 Edge 令牌');
        edgeJwt = { v: t, t: Date.now() };
        return t;
      });
  }
  ENGINES.edge = {
    label: '微软 Edge（免费）',
    batch: true,
    retired: true,                                   // 见上方说明：官方接口已 404
    retiredWhy: '已关闭免费接口，不再可用',
    translate: function (texts) {
      return edgeAuth().then(function (jwt) {
        var url = 'https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&to=' +
          encodeURIComponent(TO) + (FROM ? '&from=' + encodeURIComponent(FROM) : '');
        var body = JSON.stringify(texts.map(function (t) { return { Text: t }; }));
        return request('POST', url, body, {
          'Authorization': 'Bearer ' + jwt,
          'Content-Type': 'application/json'
        }).then(function (s) {
          var arr = JSON.parse(s);
          if (!Array.isArray(arr) || arr.length !== texts.length) throw new Error('返回结构不符');
          return arr.map(function (it, i) {
            var t = it && it.translations && it.translations[0] && it.translations[0].text;
            return t || texts[i];
          });
        });
      });
    }
  };

  /* --- 2. Google 免费翻译（gtx 端点，海外可用；用换行拼批，返回再拆） --- */
  function googleRaw(text, useJsonp) {
    var url = 'https://translate.googleapis.com/translate_a/single?client=gtx&sl=' +
      (FROM || 'auto') + '&tl=' + encodeURIComponent(TO === 'zh-Hans' ? 'zh-CN' : TO) +
      '&dt=t&ie=UTF-8&oe=UTF-8&q=' + encodeURIComponent(text);
    var p = useJsonp ? jsonp(url, 'callback') : request('GET', url, null, {}).then(function (s) { return JSON.parse(s); });
    return p.then(function (data) {
      // 结构：[[["译文","原文",...],...], null, "en", ...]
      var seg = data && data[0];
      var out = '';
      if (Array.isArray(seg)) {
        for (var i = 0; i < seg.length; i++) {
          if (seg[i] && typeof seg[i][0] === 'string') out += seg[i][0];
        }
      }
      if (!out) throw new Error('空译文');
      return out;
    });
  }
  ENGINES.google = {
    label: 'Google（免费）',
    batch: true,
    /* 用换行把一批拼成一次请求：换行是翻译引擎最容易保留的分隔符。
     * 拆回来行数对不上时（引擎偶尔会合并/拆分行），整批退回逐条重译，
     * 宁可慢一点也不让译文错位。 */
    translate: function (texts, opts) {
      var abort = opts && opts.abort, onPartial = opts && opts.onPartial;
      if (abort && abort()) return Promise.resolve(texts.slice());
      var joined = texts.join('\n');
      var useJsonp = !hasBridge() && !!document.createElement;
      return googleRaw(joined, useJsonp).then(function (out) {
        var lines = String(out).split('\n');
        if (lines.length === texts.length) {
          var r = lines.map(function (l, i) { return norm(l) || texts[i]; });
          if (onPartial && !(abort && abort())) { try { onPartial(0, r); } catch (e) {} }
          return r;
        }
        return ENGINES.google.oneByOne(texts, opts);
      }).catch(function () { return ENGINES.google.oneByOne(texts, opts); });
    },
    oneByOne: function (texts, opts) {
      var abort = opts && opts.abort;
      return mapLimit(texts, CONCURRENCY, function (t) {
        if (abort && abort()) return Promise.resolve(null);
        return googleRaw(t, !hasBridge()).then(function (r) { return norm(r) || t; });
      }, abort).then(function (out) {
        return out.map(function (r, i) { return r || texts[i]; });
      });
    }
  };

  /* --- 3. 有道翻译（aidemo 演示接口：国内直连、无需 key） ---
   * 速度的关键在这里：aidemo 对换行拼批**原样保留换行**（实测进几行出几行），
   * 所以能一次带十几段走一个请求。
   *
   * 单条请求的两个硬上限：实测 16 行仍成功、20 行报 103（内容过长），
   * 行数取 12 留余量；字符上限 800 会在行数之前先兜住长句。
   *
   * 失败的 chunk **绝不退回逐条**（那会让频率雪崩），而是对半拆小再试；
   * 拆到单行还失败就放弃，保持原文交给重试循环。
   *
   * ================= 限流模型（真机外实测，2026-09）=================
   * 同样 13 个「8 行拼批」请求，四种发法对着测：
   *
   *   一次性并发 13 个       →   261ms   13/13 全成功
   *   并发 4、波间 150ms     →  1108ms   12/13
   *   并发 2、波间 300ms     →  2629ms   12/13
   *   串行、间隔 300ms（旧）→  4639ms    6/13 ← 7 次被限流
   *
   * 25 个批次（≈200 段一页）一次性并发：308ms，24/25 成功。
   *
   * 结论反直觉但很稳定：**有道不怕并发，怕的是「持续不断地来」**。
   * 短时间集中发完最安全；把请求摊开慢慢发反而最容易撞 411。
   *
   * 旧实现恰好是最吃亏的那种写法：全局一个 `youdaoNextAt` 时间戳，
   * 每个请求都必须和上一个间隔 300~800ms。它同时踩了两条：
   *   1) 慢 —— 40 段一页实际网络只要 5×51ms，节流却要收 2.3 秒；
   *   2) 撞限流 —— 请求被摊到好几秒上，13 批里 7 批被拒，
   *      那 7 批只能等补翻，用户看到的就是「特别特别慢，还翻不全」。
   * 更糟的是撞了 411 之后自适应还会把间隔放宽到 1500ms，
   * 于是更慢、摊得更长、更容易撞 —— 一个恶性循环。
   *
   * 现在改成：**并发发出（全局一个名额池）+ 撞限流才歇一下**。
   * 并发数按实测反馈自适应：连续成功就加大，撞一次就减半并冷却。 */
  var YOUDAO_BATCH_LINES = 12;        // 单条拼批请求的最大行数（实测 16 行 OK、20 行 103）
  var YOUDAO_BATCH_CHARS = 800;       // 单条拼批请求的字符上限

  var YOUDAO_PAR_MIN = 2;             // 并发下限（撞限流时退到这里）
  var YOUDAO_PAR_MAX = 12;            // 并发上限
  var YOUDAO_COOL_MAX = 1500;         // 撞限流后的冷却上限
  var youdaoPar = 6;                  // 当前允许的并发请求数
  var youdaoCool = 0;                 // 当前冷却时长
  var youdaoNextAt = 0;               // 冷却截止时刻（在此之前先别发）
  var youdaoOkRun = 0;                // 连续成功计数，攒够就试着加大并发
  var youdaoInFlight = 0;             // 当前在飞的有道请求数
  var youdaoQueue = [];               // 等名额的请求
  /** 换页时清掉冷却：新页面不该接着上一页的惩罚 */
  function youdaoResetThrottle() { youdaoNextAt = 0; }
  function youdaoRelease() {
    youdaoInFlight--;
    /* 名额是全局共享的：组并发（BATCH_PARALLEL）叠上来也不会突破上限，
     * 对半拆分多出来的请求同样走这个池子。 */
    while (youdaoQueue.length && youdaoInFlight < youdaoPar) {
      youdaoInFlight++;
      youdaoQueue.shift()();
    }
  }
  /** 申请一个并发名额；拿到之后还要等过冷却期（撞过限流才有） */
  function youdaoAcquire() {
    if (youdaoInFlight < youdaoPar) { youdaoInFlight++; return Promise.resolve(); }
    return new Promise(function (res) { youdaoQueue.push(res); });
  }
  function youdaoWaitCool() {
    var w = youdaoNextAt - Date.now();
    if (w <= 0) return Promise.resolve();
    return new Promise(function (res) { setTimeout(res, w); });
  }
  function youdaoRequest(q, abort) {
    if (abort && abort()) return Promise.reject(new Error('已放弃（换页）'));
    return youdaoAcquire()
      .then(youdaoWaitCool)
      .then(function () {
        /* 拿到名额、也等过冷却之后再问一次：请求可能是换页**之前**排进来的 */
        if (abort && abort()) throw new Error('已放弃（换页）');
        return request('POST', 'https://aidemo.youdao.com/trans',
          'q=' + encodeURIComponent(q) +
          '&from=' + (FROM || 'auto') +
          '&to=' + (TO === 'zh-Hans' ? 'zh-CHS' : TO),
          { 'Content-Type': 'application/x-www-form-urlencoded' });
      })
      .then(function (s) {
        var d = JSON.parse(s);
        if (d && d.errorCode && String(d.errorCode) !== '0') {
          var code = String(d.errorCode);
          /* 411 = 请求频率过快；429 是同类的限流。
           * 旧实现靠「让每个请求之间隔得更久」来处理，实测证明那是反的：
           * 摊得越长越容易撞。这里改成「降并发 + 冷却一会儿」——
           * 少发、快发完、然后安静，比一直慢慢发更不容易被判成异常。
           * 103（内容过长）靠拆小解决，不在这里加码。 */
          if (code === '411' || code === '429') {
            youdaoPar = Math.max(YOUDAO_PAR_MIN, Math.floor(youdaoPar / 2));
            youdaoCool = Math.min(YOUDAO_COOL_MAX, Math.max(600, youdaoCool * 2 + 200));
            youdaoNextAt = Date.now() + youdaoCool;
            youdaoOkRun = 0;
          }
          throw new Error('有道错误 ' + code + (d.msg ? ' ' + d.msg : ''));
        }
        var v = d && d.translation && d.translation[0];
        if (!v || !String(v).trim()) throw new Error('空译文');
        // 顺顺利利拿到译文：说明当前并发是安全的，攒够 6 次就再大胆一点
        if (++youdaoOkRun >= 6) {
          youdaoOkRun = 0;
          youdaoPar = Math.min(YOUDAO_PAR_MAX, youdaoPar + 2);
          youdaoCool = Math.max(0, youdaoCool - 200);
        }
        return String(v);
      })
      .then(function (v) { youdaoRelease(); return v; },
            function (e) { youdaoRelease(); throw e; });
  }
  function youdaoOne(text, abort) {
    return youdaoRequest(text, abort).then(norm);
  }
  /** 剥掉首尾空行：有道偶尔在译文前后各多给一个换行（真机抓到过）。
   * 这种差异是无害的——行还在、顺序还在，只是多了两个空串。
   * 以前拿它当「行数不符」处理，整批会一路对半拆到放弃，最后原文上屏，
   * 用户看到的就是「这一段死活翻不出来」。 */
  function trimBlankEdges(arr) {
    var s = 0, e = arr.length;
    while (s < e && !String(arr[s]).trim()) s++;
    while (e > s && !String(arr[e - 1]).trim()) e--;
    return arr.slice(s, e);
  }
  /** 413？不是，411 —— 限流。跟「内容太长」不是一回事：
   *  411 是频率问题，把 chunk 拆小只会让请求更多、更挤，
   *  正确做法是等冷却过去再原样重试。只有行数对不上 / 103 才该拆。 */
  function isLimited(err) {
    return !!err && /有道错误\s*(411|429)/.test(String(err.message || err));
  }
  /** 翻一个 chunk：行数对不上或请求失败就对半拆小再试，别让译文错位。
   *  撞上限流则先等冷却再原样重试一次（retried 防重复），仍不行才拆。
   *  abort 为真时立刻收手（返回等长的空位），一个请求都不再发。 */
  function youdaoChunk(chunk, depth, abort, retried) {
    var giveUp = function () { return Promise.resolve(chunk.map(function () { return null; })); };
    if (abort && abort()) return giveUp();
    if (chunk.length === 1) {
      /* 必须返回**数组**：调用方按下标往结果里填，返回字符串会被当成
       * 字符序列逐字塞进去（探测那个单条分支就是这样翻车的一整轮都判成
       * 「引擎不可用」）。以前用 acc.concat(...) 恰好把这个差异吞掉了。 */
      return youdaoOne(chunk[0], abort).then(function (v) { return [v]; },
        function (err) {
          if (!retried && isLimited(err)) {
            return youdaoWaitCool().then(function () { return youdaoChunk(chunk, depth, abort, true); });
          }
          return [chunk[0]];    // 放弃：保持原文等重试
        });
    }
    var split = function () {
      if (abort && abort()) return giveUp();
      if (depth >= 2) return Promise.resolve(chunk.slice());               // 放弃：保持原文等重试
      var mid = Math.ceil(chunk.length / 2);
      return Promise.all([
        youdaoChunk(chunk.slice(0, mid), depth + 1, abort),
        youdaoChunk(chunk.slice(mid), depth + 1, abort)
      ]).then(function (p) { return p[0].concat(p[1]); });
    };
    return youdaoRequest(chunk.join('\n'), abort).then(function (out) {
      var lines = out.split('\n');
      if (lines.length !== chunk.length) {
        // 先假设只是首尾多了空行，剥掉再比一次；真对不上才拆
        var t = trimBlankEdges(lines);
        if (t.length !== chunk.length) return split();
        return t.map(function (l, i) { return norm(l) || chunk[i]; });
      }
      return lines.map(function (l, i) { return norm(l) || chunk[i]; });
    }, function (err) {
      if (!retried && isLimited(err)) {
        /* 限流：拆小是反的。等冷却过完再原样发一次 —— 冷却时长本身
         * 已经在 youdaoRequest 里按「撞一次翻一倍」调过了。 */
        return youdaoWaitCool().then(function () {
          if (abort && abort()) return giveUp();
          return youdaoChunk(chunk, depth, abort, true);
        });
      }
      return split();
    });
  }
  ENGINES.youdao = {
    label: '有道翻译（免费，国内直连）',
    batch: true,
    resetThrottle: function () { youdaoResetThrottle(); },
    translate: function (texts, opts) {
      /* 按行数 + 字符数双上限切片。**不再串行发** —— 全部一起排队，
       * 实际并发由 youdaoAcquire 的全局名额池统一控制（池子是跨组共享的，
       * 所以组并发叠上来、或对半拆分多出请求，都不会突破上限）。 */
      opts = opts || {};
      var abort = opts.abort, onPartial = opts.onPartial;
      var chunks = [], cur = [], len = 0;
      texts.forEach(function (t) {
        if (cur.length && (cur.length >= YOUDAO_BATCH_LINES ||
            len + t.length + 1 > YOUDAO_BATCH_CHARS)) {
          chunks.push(cur); cur = []; len = 0;
        }
        cur.push(t); len += t.length + 1;
      });
      if (cur.length) chunks.push(cur);

      var starts = [], base = 0;
      chunks.forEach(function (c) { starts.push(base); base += c.length; });
      var out = new Array(texts.length);
      function runOne(idx) {
        if (abort && abort()) {
          // 中止：后面的 chunk 一个都不发，位置用空位补齐（长度不能变）
          for (var k = 0; k < chunks[idx].length; k++) out[starts[idx] + k] = null;
          return Promise.resolve();
        }
        return youdaoChunk(chunks[idx], 0, abort).then(function (arr) {
          for (var k = 0; k < arr.length; k++) out[starts[idx] + k] = arr[k];
          /* 流式上屏：这个 chunk 一译完就先写进节点，不等其他 chunk。 */
          if (onPartial && !(abort && abort())) {
            try { onPartial(starts[idx], arr.slice()); } catch (e) {}
          }
        });
      }
      var idxs = chunks.map(function (_, i) { return i; });
      return mapLimit(idxs, Math.max(1, YOUDAO_PAR_MAX), runOne, abort).then(function () {
        return out.map(function (r, i) { return r || texts[i]; });
      });
    }
  };

  /* --- 4. DeepL（网页版 jsonrpc 接口：免费无需 key，质量最好） ---
   * 没有官方免费 key，走的是网页版同款接口；公共 IP 偶尔被限流（429），
   * 失败的段落兜底回原文，不影响整页。对频率敏感，并发压低到 3。 */
  var deeplId = Math.floor(Date.now() / 1000) * 1000;
  function deeplOne(text) {
    deeplId++;
    var body = JSON.stringify({
      jsonrpc: '2.0',
      method: 'LMT_handle_texts',
      params: {
        splitting: 'newlines',
        lang: {
          target_lang: TO === 'zh-Hans' ? 'ZH' : String(TO).toUpperCase(),
          source_lang_user_selected: (FROM || 'auto')
        },
        texts: [{ text: text, requestAlternatives: 0 }]
      },
      id: deeplId
    });
    return request('POST', 'https://www2.deepl.com/jsonrpc', body,
      { 'Content-Type': 'application/json' }).then(function (s) {
        var d = JSON.parse(s);
        if (d && d.error) throw new Error('DeepL ' + (d.error.code || '') + ' ' + (d.error.message || ''));
        var v = norm(d && d.result && d.result.texts && d.result.texts[0] && d.result.texts[0].text);
        if (!v) throw new Error('空译文');
        return v;
      });
  }
  ENGINES.deepl = {
    label: 'DeepL（免费，质量最佳）',
    batch: false,
    translate: function (texts, opts) {
      var abort = opts && opts.abort;
      return mapLimit(texts, 3, function (t) {
        if (abort && abort()) return Promise.resolve(null);
        return deeplOne(t);
      }, abort).then(function (out) { return out.map(function (r, i) { return r || texts[i]; }); });
    }
  };

  /* --- 5. MyMemory（兜底，全球可达，匿名有日配额，单条限 ~500 字节） --- */
  ENGINES.mymemory = {
    label: 'MyMemory（兜底，有日限额）',
    batch: false,
    translate: function (texts, opts) {
      var abort = opts && opts.abort;
      return mapLimit(texts, CONCURRENCY, function (t) {
        if (abort && abort()) return Promise.resolve(null);
        // 单条上限约 500 字节，超了就不浪费一次请求
        if (encodeURIComponent(t).length > 480) return Promise.resolve(null);
        // 以前这里把目标写死成 zh-CN —— 切到「翻成英文」时它还在往中文翻。
        // MyMemory 不像其他引擎那样认 auto，源语言靠 writing-script 猜一个给它。
        var url = 'https://api.mymemory.translated.net/get?q=' + encodeURIComponent(t) +
          '&langpair=' + encodeURIComponent((guessSource(t) || 'en') + '|' +
            (TO === 'zh-Hans' ? 'zh-CN' : TO));
        return request('GET', url, null, {}).then(function (s) {
          var d = JSON.parse(s);
          var r = d && d.responseData && d.responseData.translatedText;
          // 配额用完时它会把警告当成译文返回，这种要当失败处理
          if (!r || /MYMEMORY WARNING/i.test(r)) throw new Error('无配额');
          return norm(r);
        });
      }, abort).then(function (out) { return out.map(function (r, i) { return r || texts[i]; }); });
    }
  };

  /* --- 6. 自定义接口（逃生舱：自建 / DeepLX / 公司内网都行）
   * 协议：POST JSON {"q":["..."],"from":"auto","to":"zh-Hans"}
   *      返回 {"translations":["..."]} 或直接是字符串数组 */
  ENGINES.custom = {
    label: '自定义接口',
    batch: true,
    needUrl: true,
    translate: function (texts, opts) {
      var abort = opts && opts.abort;
      if (abort && abort()) return Promise.resolve(texts.slice());
      var url = prefGet(KEY_CUSTOM, '');
      if (!url) return Promise.reject(new Error('还没填自定义接口地址'));
      return request('POST', url, JSON.stringify({ q: texts, from: FROM || 'auto', to: TO }),
        { 'Content-Type': 'application/json' }).then(function (s) {
          var d = JSON.parse(s);
          var arr = Array.isArray(d) ? d : (d.translations || d.data || d.result);
          if (!Array.isArray(arr) || arr.length !== texts.length) throw new Error('返回结构不符');
          return arr.map(function (x, i) { return (typeof x === 'string' ? norm(x) : '') || texts[i]; });
        });
    }
  };

  /* 探测顺序：设备端（离线不出设备）→ 微软 → 有道（国内直连最稳）→
   * DeepL（质量最佳但可能限流）→ Google（需海外网络）→ MyMemory（兜底）
   * 哪个先探测成功用哪个，后面的不再试。 */
  /* 自动选择的尝试顺序，按真机反馈定的原则：
   * 1) 有道放第一：国内网络直连最稳
   * 2) 免费无限额的优先；MyMemory 有每日匿名配额，永远垫底
   * 3) 设备端翻译离线无限额，但依赖系统语言包，能不能用看机器
   * 4) DeepL / Google 质量好，但国内网络大概率不可达（不是代码问题）
   * 5) 微软 Edge 已从列表移除：官方关闭了免费令牌接口（auth 返回 404），
   *    留着只会让每次探测白等一次失败。ENGINES.edge 的实现仍在，可随时恢复。 */
  var ORDER = ['youdao', 'ondevice', 'deepl', 'google', 'mymemory'];
  var SHORT = { ondevice: '设备端', edge: '微软', youdao: '有道', deepl: 'DeepL',
                google: 'Google', mymemory: 'MyMemory', custom: '自定义' };

  /* ================= 引擎选择 / 探测 ================= */
  function currentEngine() {
    return prefGet(KEY_ENGINE, 'auto') || 'auto';
  }

  /** 用一句短文本试引擎，能译出来才算可用（8 秒没结果就当不可用） */
  function probe(name) {
    var e = ENGINES[name];
    if (!e) return Promise.resolve(false);
    if (e.needUrl && !prefGet(KEY_CUSTOM, '')) return Promise.resolve(false);
    if (name === 'ondevice') return probeOnDevice();
    return realProbe(e);
  }

  function realProbe(e) {
    var timeout = new Promise(function (_, rej) {
      setTimeout(function () { rej(new Error('探测超时')); }, PROBE_TIMEOUT);
    });
    return Promise.race([
      e.translate(['Hello, world!']).then(function (r) {
        var t = r && r[0];
        /* 探测这一发不该占用限流队列：它只为了确认「这个引擎能用」，
         * 却会把有道那个 800ms 的节流阀往后推一格 —— 于是每次新会话的
         * 第一批真实译文都要白等 800ms（实测首个译文从 996ms 降到 ~200ms）。
         * 探测成功后把队列清零，好钢用在真要翻的那批上。 */
        if (e && e.resetThrottle) { try { e.resetThrottle(); } catch (err) {} }
        return !!(t && t !== 'Hello, world!' && /[一-龥]/.test(t));
      }, function () { return false; }),
      timeout
    ]).catch(function () { return false; });
  }

  /** 设备端翻译先问 availability：语言包没装就是 unavailable，
   *  'downloadable' 也先放弃 —— 真去下载会卡住首次翻译，得不偿失。
   *  实测部分 WebView 里 availability() 会永久挂起（8 秒都不响应），
   *  不加超时的话整个自动选择会被它卡死 —— 必须race 一个超时。 */
  function probeOnDevice() {
    if (!onDeviceReady()) return Promise.resolve(false);
    var av;
    try {
      av = Promise.resolve(window.Translator.availability({ sourceLanguage: 'en', targetLanguage: 'zh' }));
    } catch (e) { return Promise.resolve(false); }
    var timeout = new Promise(function (_, rej) {
      setTimeout(function () { rej(new Error('探测超时')); }, PROBE_TIMEOUT);
    });
    return Promise.race([
      av.then(function (a) { return a === 'available' ? realProbe(ENGINES.ondevice) : false; },
              function () { return false; }),
      timeout
    ]).catch(function () { return false; });
  }

  var probing = null;
  /**
   * 选本轮要用的引擎：
   * - 手动指定的引擎，只要这一轮还没试过 → 永远先给它机会（用户的选择
   *   就是选择；挂了自然有降级兜底。不能因为 5 分钟前挂过就替用户做主）
   * - 否则（auto / 手动的这轮已经失败过）在剩余候选里并行探测，谁快用谁
   * - 全都没戏 → null（调用方提示所有引擎不可用）
   */
  function ensureEngine(tried) {
    tried = tried || [];
    var cur = currentEngine();
    /* 手动选的引擎如果已经停用（微软 Edge），不能因为「用户选过」就硬着头皮用：
     * 用户当年选它是因为它能用，现在它不能用了，替用户换掉才是尊重他的本意。
     * 提示一次，之后按自动选择走。 */
    if (cur !== 'auto' && ENGINES[cur] && ENGINES[cur].retired) {
      if (!ensureEngine._told) {
        ensureEngine._told = {};
      }
      if (!ensureEngine._told[cur]) {
        ensureEngine._told[cur] = true;
        toast('「' + (SHORT[cur] || cur) + '」' + (ENGINES[cur].retiredWhy || '已不可用') +
          '，已自动改用其他引擎');
      }
      cur = 'auto';
    }
    if (cur !== 'auto' && ENGINES[cur] && tried.indexOf(cur) < 0 &&
        (!ENGINES[cur].needUrl || prefGet(KEY_CUSTOM, ''))) {
      return Promise.resolve(cur);
    }
    var candidates = ORDER.filter(function (k) {
      return tried.indexOf(k) < 0 && !!ENGINES[k] && !ENGINES[k].retired && !isBadNow(k) &&
        (!ENGINES[k].needUrl || prefGet(KEY_CUSTOM, ''));
    });
    if (!candidates.length) return Promise.resolve(null);
    if (probing) return probing;
    var memo = prefGet('gh_tr_pick', '');
    if (memo && ENGINES[memo] && candidates.indexOf(memo) >= 0 && !isBadNow(memo)) {
      return Promise.resolve(memo);
    }
    /* 刚经历过一次「全军覆没」就进入冷却：2 分钟内不再全量探测，直接拿
     * 第一个候选硬上（失败有重试循环兜底）。探测是要真发网络请求的，
     * Google/DeepL 这类连不上的家伙一挂就是一整个连接超时——每换一页
     * 都来这么一轮，页面自己的数据请求就全堵在后面排队（骨架屏转圈）。 */
    if (Date.now() < probeCoolUntil) {
      return Promise.resolve(candidates[0] || null);
    }
    probing = probeFastest(candidates);
    return probing.then(function (r) {
      probing = null;
      if (!r) probeCoolUntil = Date.now() + 2 * 60 * 1000;
      return r;
    }, function (e) { probing = null; throw e; });
  }

  var probeCoolUntil = 0;

  /**
   * 同时向候选引擎发探测，谁先成功就用谁。
   * 原来是挨个串行试：前面几个不通的话，光等待超时就把首次翻译拖成几十秒。
   * 并行之后总耗时 ≈ 最快那个引擎的响应时间 —— 顺带还自动选出了最快的。
   * 低优先级引擎稍微延后一点再发，避免浪费请求。
   * 全部失败返回 null（调用方负责提示），不再硬塞一个默认引擎——
   * 以前全挂时兜底 edge，真机上就是「微软 HTTP 404」的来源。
   */
  function probeFastest(candidates) {
    /* 拉黑名单里的不再探测：探测它们只会白占一个网络线程一整个连接超时 */
    candidates = (candidates || ORDER.slice()).filter(function (k) { return !probeBlocked(k); });
    return new Promise(function (resolve) {
      var settled = false, pending = candidates.length;
      function settle(v) { if (!settled) { settled = true; resolve(v); } }
      /* 总兜底：任何引擎的探测都可能挂起（真机 WebView 上 availability 就会），
       * 到点还没人选，就认输返回 null —— 绝不让自动选择卡死翻译。 */
      var overall = setTimeout(function () { settle(null); }, PROBE_TIMEOUT + 2500);
      if (!pending) { clearTimeout(overall); return settle(null); }
      candidates.forEach(function (name, i) {
        var delay = i === 0 ? 0 : i * 200;
        setTimeout(function () {
          if (settled) return;
          probe(name).then(function (ok) {
            if (settled) return;
            if (ok) { clearTimeout(overall); prefSet('gh_tr_pick', name); clearBad(name); settle(name); return; }
            /* 探测失败也要记账：连不上的引擎（Google/DeepL 在国内）一探测
             * 就把原生网络线程挂满一个连接超时，不记账的话每换一页都要
             * 重来一轮 —— 页面数据请求全被堵在后面排队。 */
            probeFailed(name);
            if (--pending === 0) { clearTimeout(overall); settle(null); }
          }, function () {
            if (settled) return;
            probeFailed(name);
            if (--pending === 0) { clearTimeout(overall); settle(null); }
          });
        }, delay);
      });
    });
  }

  /* ================= 译文缓存 ================= */
  var cache = prefGet(KEY_CACHE, {}) || {};
  /* 缓存版本：历史上失败兜底曾把"原文"当译文写进缓存（v1 的 bug），
   * 命中原文的缓存会让页面永远停在英文。升版本号触发一次全清，老用户
   * 升级后即自愈，不用手动清缓存。 */
  var CACHE_VER = 2;
  if (prefGet('gh_tr_cache_ver', 1) !== CACHE_VER) {
    cache = {};                       // 旧版本缓存一律作废
    prefSet(KEY_CACHE, cache);
    prefSet('gh_tr_cache_ver', CACHE_VER);
  }
  function cacheGet(k) { return cache[k]; }

  /* ------------------------------------------------------------------
   * 落盘去抖（这里是「翻译越来越慢」的头号元凶）
   *
   * 老写法每译出一段就 prefSet 一次整份缓存——一轮几十段就是几十次：
   *   · Object.keys(整份缓存)      —— 全表扫描，只为数个数
   *   · JSON.stringify(整份缓存)   —— 几百 KB 反复序列化
   *   · 跨桥 setPref + 原生落盘    —— 每次一次 SharedPreferences.apply
   *   · localStorage.setItem       —— 再写一遍
   * 缓存从空攒到上限的过程中单次成本线性上涨，恰好就是用户感觉到的
   * 「用得越久、翻译越慢」。
   *
   * 现在：内存里立刻生效（同一轮后面的批次该命中照样命中），
   * 落盘合并到一轮结束后的那一次。
   * ------------------------------------------------------------------ */
  var cacheCount = 0;
  var cacheDirty = false;
  var cacheFlushTimer = 0;
  (function countCache() {
    // 只在启动时数一次，之后用 cacheCount 增量维护，不再反复 Object.keys
    for (var kk in cache) { if (Object.prototype.hasOwnProperty.call(cache, kk)) cacheCount++; }
  })();

  function flushCache() {
    if (cacheFlushTimer) { clearTimeout(cacheFlushTimer); cacheFlushTimer = 0; }
    if (!cacheDirty) return;
    cacheDirty = false;
    if (cacheCount > CACHE_MAX) {
      // 简易淘汰：清掉最早写入的一批，不做严格 LRU，够用
      var keys = Object.keys(cache);
      var drop = keys.slice(0, Math.floor(CACHE_MAX / 3));
      drop.forEach(function (x) { delete cache[x]; });
      cacheCount -= drop.length;
    }
    prefSet(KEY_CACHE, cache);
  }

  function scheduleCacheFlush() {
    if (cacheFlushTimer) return;
    cacheFlushTimer = setTimeout(function () {
      cacheFlushTimer = 0;
      flushCache();
    }, 400);
  }

  function cacheSet(k, v) {
    if (cache[k] === undefined) cacheCount++;
    cache[k] = v;
    cacheDirty = true;
    scheduleCacheFlush();
  }
  /* 这几个时机必须立刻落盘，不能等去抖计时器：
   * App 被切后台、页面被回收、用户手动还原原文/关总开关，晚一步就丢这一轮的译文。 */
  function installCacheFlushHooks() {
    var now = function () { try { flushCache(); } catch (e) {} };
    if (window.addEventListener) {
      window.addEventListener('pagehide', now);
      window.addEventListener('beforeunload', now);
      try {
        if (document.addEventListener) {
          document.addEventListener('visibilitychange', function () {
            if (document.hidden) now();
          });
        }
      } catch (e) {}
    }
  }

  /* ================= 文本收集 ================= */
  var SKIP_SEL = 'pre, code, svg, script, style, textarea, noscript, [data-no-translate], .no-translate, .hljs, .tr-skip';

  function inSkip(node) {
    var p = node.parentElement;
    if (!p) return true;
    if (p.closest) { try { return !!p.closest(SKIP_SEL); } catch (e) {} }
    // 老 WebView 没有 closest 时手动向上找
    while (p) {
      if (p.nodeName === 'PRE' || p.nodeName === 'CODE' || p.nodeName === 'SVG' ||
          p.nodeName === 'SCRIPT' || p.nodeName === 'STYLE' || p.nodeName === 'TEXTAREA') return true;
      if (p.getAttribute && p.getAttribute('data-no-translate') !== null) return true;
      p = p.parentElement;
    }
    return false;
  }

  var RE_URL = /^(https?:\/\/|www\.|[\w.+-]+@[\w-]+\.[\w.]+)/i;
  var RE_PATH = /^[\w.\-]+([\/:][\w.\-]+)+$/;              // app/src/main、owner/repo
  var RE_SHA = /^[0-9a-f]{7,40}$/i;
  var RE_NUM = /^[\d\s.,:+*/=<>%#&|~^$@!?'"()\[\]{}\-_\\]+$/;
  var RE_REF = /^[@#][\w/-]+$/;                             // @someone、#123
  var RE_VER = /^v?\d+(\.\d+)*[a-z]?$/i;                    // v1.1.2
  // 相对时间：10 minutes ago / 2 hours ago / just now。
  // 探索页有个定时器隔一会儿就把这些刷一遍，不跳过的话每次刷新都会
  // 被当成「新内容」重新收进来翻译一遍。
  var RE_AGO = /^(\d+\s+(seconds?|s|minutes?|m|hours?|h|days?|d|weeks?|w|months?|years?|y)\s*ago|just now|now|yesterday|last (hour|day|week|month|year))$/i;

  /* ------------------------------------------------------------------
   * 按 Unicode 区段判断一段文本「主要用什么文字书写」
   *
   * 老逻辑只有一句 if (!/[A-Za-z]/.test(s)) return false ——
   * 于是日文、韩文、俄文、阿拉伯文、泰文、希腊文、希伯来文…
   * 全因为没有拉丁字母而被判定「不值得翻译」，从头到尾没进过翻译流程。
   * 这就是「只能翻英文」的真正瓶颈，跟引擎没关系：引擎那边一直是 auto 检测。
   *
   * 现在改成看「书写系统」而不是看「有没有 ABC」：
   *   目标是中文 → 非汉字的都翻
   *   目标是英文 → 非拉丁字母的都翻
   * 各引擎自身支持上百种语言，这里放行之后它们就能干活了。
   * ------------------------------------------------------------------ */
  /* 区间表而不是正则：这一段会被每个候选节点调用一次，探索页一屏几百个、
   * 滚起来上千个候选。用正则就得给每个字符跑十来个状态机 —— 实测
   * 600 行的页面一轮要 49ms；改成 charCodeAt + 数值比较后回落到 12ms。 */
  var SCRIPTS = [
    { id: 'cjk', ranges: [[0x3400, 0x4dbf], [0x4e00, 0x9fff], [0xf900, 0xfaff], [0x3005, 0x3007]] },  // 汉字
    { id: 'kana', ranges: [[0x3040, 0x30ff], [0x31f0, 0x31ff], [0xff66, 0xff9f]] },                   // 假名（含半角）
    { id: 'hangul', ranges: [[0x1100, 0x11ff], [0x3130, 0x318f], [0xac00, 0xd7af]] },                 // 韩文
    { id: 'cyrillic', ranges: [[0x0400, 0x04ff], [0x0500, 0x052f]] },                                 // 俄文等
    { id: 'arabic', ranges: [[0x0600, 0x06ff], [0x0750, 0x077f], [0xfb50, 0xfdff]] },
    { id: 'hebrew', ranges: [[0x0590, 0x05ff]] },
    { id: 'thai', ranges: [[0x0e00, 0x0e7f]] },
    { id: 'greek', ranges: [[0x0370, 0x03ff], [0x1f00, 0x1fff]] },
    { id: 'devanagari', ranges: [[0x0900, 0x097f], [0xa8e0, 0xa8ff]] },                              // 印地语/梵文
    { id: 'latin', ranges: [[0x41, 0x5a], [0x61, 0x7a], [0xc0, 0x24f], [0x1e00, 0x1eff], [0xff21, 0xff3a], [0xff41, 0xff5a]] }
  ];
  /* 目标语言对应的书写系统：达到这个占比就认为「已经目标语言了」。
   * 0.35 沿用原来判断中英文的经验阈值。 */
  var TARGET_SCRIPT = { 'zh-Hans': 'cjk', 'en': 'latin' };
  var OWN_RATIO = 0.35;

  /** 一个字符属于哪种书写系统（不属于任何文字系统则返回 ''） */
  function scriptOfChar(cc) {
    /* ASCII 快路径：GitHub 上绝大部分文本是英文，这条分支几乎包圆了，
     * 连下面的表都不用查。 */
    if (cc < 0x80) return ((cc >= 0x41 && cc <= 0x5a) || (cc >= 0x61 && cc <= 0x7a)) ? 'latin' : '';
    for (var i = 0; i < SCRIPTS.length; i++) {
      var rs = SCRIPTS[i].ranges;
      for (var j = 0; j < rs.length; j++) {
        if (cc >= rs[j][0] && cc <= rs[j][1]) return SCRIPTS[i].id;
      }
    }
    return '';
  }

  /** 扫一遍，算出每种书写系统各占多少字符（非文字字符不计入分母） */
  function scriptMixed(s) {
    var counts = {}, letters = 0, i, k, id;
    for (i = 0; i < SCRIPTS.length; i++) counts[SCRIPTS[i].id] = 0;
    for (i = 0; i < s.length; i++) {
      id = scriptOfChar(s.charCodeAt(i));
      if (id) { counts[id]++; letters++; }
    }
    return { counts: counts, letters: letters };
  }

  /** 这段文本主要是哪种书写系统（没有可识别文字则返回 ''） */
  function detectScript(s) {
    var m = scriptMixed(s);
    if (!m.letters) return '';
    var best = '', bestN = 0;
    for (var k in m.counts) {
      if (Object.prototype.hasOwnProperty.call(m.counts, k) && m.counts[k] > bestN) {
        bestN = m.counts[k]; best = k;
      }
    }
    return best;
  }

  /** 给不支持 auto 的引擎（MyMemory）猜一个源语言代码 */
  var SCRIPT_LANG = { cjk: 'zh', kana: 'ja', hangul: 'ko', cyrillic: 'ru',
                      arabic: 'ar', hebrew: 'he', thai: 'th', greek: 'el',
                      devanagari: 'hi', latin: 'en' };
  function guessSource(s) { return SCRIPT_LANG[detectScript(s)] || ''; }

  /**
   * 这段文本是否已经「用目标语言写着」—— 是就不用翻了。
   *
   * 最麻烦的是【日文】：它大量借用汉字，「リポジトリの活動に関する説明」
   * 一句里汉字段落能占到三成七，光看汉字占比会被判成中文而跳过。
   * 所以判据不能只看占比，得先看有没有「决定性特征字符」：
   * 只要出现假名，这段文字就是日文，跟汉字占多少没关系。
   */
  /**
   * 目标 = 英文时的补充判据。
   *
   * 法文 / 德文 / 西班牙文 / 葡萄牙文 / 越南文 / 土耳其文… 用的也是拉丁字母，
   * 光看书写系统跟英文一模一样，分不出来。但它们普遍带附加符号
   * （é ü ñ ç ş ộ ư），正经英文很少这么写。
   * 所以「带符号的拉丁字母占比异常高」就当它不是英文，翻。
   *
   * 门槛按实测样本定在 8%：法语、波兰语、越南语一般在 8~30%，
   * 德语 / 西班牙语的重音太稀疏（2~3%）够不着——抱歉，这两种真分不出来。
   * 8% 能让 Björn(3.8%)、Pokémon(3.8%) 这类散在英文里的外来词稳稳留在原地。
   * 真翻错了也只是多翻一句，而漏翻会让整篇法语 README 停在原地，后者更亏。
   */
  var RE_ACCENTED = /[\u00c0-\u00ff\u0100-\u017f\u1e00-\u1eff]/;
  function heavyAccent(s) {
    if (!RE_ACCENTED.test(s)) return false;                 // 一个符号都没有：快路径，不必数
    var lat = (s.match(/[A-Za-z\u00c0-\u00ff\u0100-\u017f\u1e00-\u1eff]/g) || []).length;
    if (!lat) return false;
    var acc = (s.match(/[\u00c0-\u00ff\u0100-\u017f\u1e00-\u1eff]/g) || []).length;
    return acc / lat > 0.08;
  }

  /**
   * 有没有「成词的另一种文字」：连续 2 个以上不属于目标书写系统的字母。
   *
   * 光看占比判不出混排：『中文里掺 English 词』里汉字照样过半，按占比就是
   * 「已经是中文」，可用户想翻的恰恰是夹在里面的那几个英文单词；
   * 『英文里掺中文』同理。所以占比过半之后还要再扫一遍，看有没有成段的外文。
   *
   * 门槛取 2 是为了放过零散的单个字母（"……只有一个 a ……"），
   * 那种真的是母语文本，翻了没意义。数字和标点不算字母，会打断连续段。
   */
  function hasForeignWord(s, want) {
    var run = 0;
    for (var i = 0; i < s.length; i++) {
      var id = scriptOfChar(s.charCodeAt(i));
      if (id && id !== want) {
        run++;
        if (run >= 2) return true;
      } else {
        run = 0;
      }
    }
    return false;
  }

  function isTargetLanguage(s, alreadyCounted) {
    var want = TARGET_SCRIPT[TO] || 'cjk';
    var m = alreadyCounted || scriptMixed(s);   // 调用方算过了就别再扫一遍
    if (!m.letters) return true;                        // 没有可识别文字：翻也无意义
    if (want === 'cjk' && m.counts.kana > 0) return false;   // 含假名 = 日文，要翻
    if (want === 'latin' && heavyAccent(s)) return false;    // 一堆附加符号 = 多半是欧陆语言
    /* 明显不是目标语言（占比不过半）：直接翻，不必再扫一遍。
     * 英文页面配「翻成中文」走的就是这条快路径，几乎不额外花钱。 */
    if ((m.counts[want] || 0) / m.letters <= OWN_RATIO) return false;
    /* 占比过半了，但里面可能还夹着成词的另一种文字——那种也是要翻的。
     * 「中文里掺英文」「英文里掺中文」都属于这一类，以前会被直接跳过。 */
    return !hasForeignWord(s, want);
  }
  /* 编程语言名：探索页的语言标签整段就是 "TypeScript"，送去翻会变成「打印稿」
   * 这种笑话，还白耗一次请求。整段等于语言名的一律跳过。 */
  var RE_LANG = /^(actionscript|ada|assembly|bash|c|c\+\+|c#|clojure|cmake|cobol|coffee(script)?|crystal|css|d|dart|dockerfile|elixir|erlang|f#|fortran|go|gradle|groovy|haskell|html|java|javascript|julia|jupyter(\s?notebook)?|kotlin|lua|matlab|nim|nix|objective-c|ocaml|pascal|perl|php|powershell|prolog|python|r|racket|ruby|rust|scala|scheme|shell|smalltalk|solidity|sql|svelte|swift|tcl|typescript|vb\.?net|vue|vue\.js|zig)$/i;

  /** 判断一段文本值不值得送去翻译 */
  function needTranslate(raw) {
    var s = norm(raw);
    if (s.length < 2) return false;
    /* 没有任何可识别文字（纯数字、符号、emoji）：翻了也没意义。
     * 以前这里是 if (!/[A-Za-z]/) —— 把日韩俄阿泰一起挡在门外了。
     * 一次扫描的结果两个判断共用，别扫两遍。 */
    var m = scriptMixed(s);
    if (!m.letters) return false;
    /* 已经写着目标语言：不用翻。
     * 以前只判断中文占比；现在按当前目标的书写系统判断，
     * 所以「翻成中文」和「翻成英文」两种情况都能正确跳过母语文本。 */
    if (isTargetLanguage(s, m)) return false;
    if (RE_URL.test(s) || RE_PATH.test(s) || RE_SHA.test(s) || RE_NUM.test(s) ||
        RE_REF.test(s) || RE_VER.test(s) || RE_AGO.test(s)) return false;
    /* 语言名单拎出来：RE_LANG 是几十个分支的大正则，而语言名最长也就
     * "jupyter notebook"（16 字符）这一档。先按长度剪一刀，长句根本不进去——
     * 探索页一屏几百个候选，这里省下来的是最贵的一笔。 */
    if (s.length <= 20 && RE_LANG.test(s)) return false;
    /* 这里原本还有一句 if (/^\W+$/) return false —— 本意是「纯标点就不翻」。
     * 但 JS 的 \W 是 [^A-Za-z0-9_]，中文、日文、俄文全都被算成 \W，
     * 于是这一句会把所有非拉丁文本当成「纯符号」直接跳过。
     * 以前它前面有一道 if (!/[A-Za-z]/) 的闸门，非拉丁文本根本走不到这儿，
     * 所以一直相安无事；闸门拆掉之后它就露出獠牙了。
     * 现在「有没有可识别文字」已经由 detectScript 负责，这一句纯属多余且有害。 */
    return true;
  }

  /**
   * 收集文本节点，并标出「现在屏幕上看得见」的那些。
   * 标出来是为了让首屏先翻、先上屏 —— 用户盯着屏幕的那几段一秒不到就变中文，
   * 屏幕外的交给后台慢慢翻，等滚到了早就译好了，感觉不到等待。
   */
  function collect(root, near) {
    var out = [];
    var vh = (window.innerHeight || document.documentElement.clientHeight || 800);
    /* 滚动驱动：只收「附近」的段——视口上下各多看 600px，滚过去时刚好译好。
     * 不再后台翻整页：整页几十段并发是有道限流的元凶，而且用户滚过去时
     * 经常看到的还是没翻的。near=false 才收全页（现在没有调用方用了）。 */
    var pad = near === false ? 150 : 600;
    /* 同一父元素只量一次位置：getBoundingClientRect 每次都可能强制浏览器
     * 重新排版，探索页一个条目里七八个文本节点共用同一个父元素，
     * 不缓存的话一轮 collect 就是几百次强制布局 —— 页面渲染被翻译卡住。 */
    var rects = new Map();
    /* 同一个父元素下的判定结果，按父元素缓存两份：
     *   1) getBoundingClientRect（上面那条注释说的：避免反复强制重排）
     *   2) inSkip —— 它内部是 p.closest(SKIP_SEL)，每次都要把那串选择器
     *      解析一遍。一个条目里七八个文本节点共用一个父元素，等于同一件事
     *      干七八遍。探索页滚到几百条时这一项自己就能吃掉几十毫秒。 */
    var skips = new Map();
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
    var n;
    while ((n = walker.nextNode())) {
      if (!n.nodeValue || !n.nodeValue.trim()) continue;
      if (n.__tr_done) continue;
      var p = n.parentElement;
      var skipped = skips.get(p);
      if (skipped === undefined) { skipped = inSkip(n); skips.set(p, skipped); }
      if (skipped) continue;
      if (!needTranslate(n.nodeValue)) continue;
      if (p && p.getBoundingClientRect) {
        var r = rects.get(p);
        if (!r) { r = p.getBoundingClientRect(); rects.set(p, r); }
        if (!(r.bottom > -pad && r.top < vh + pad)) continue;   // 不在视野附近：留给滚动触发
        out.push({ node: n, text: norm(n.nodeValue), visible: true, top: r.top });
      }
    }
    /* 离视线越近的越先翻：从上屏顺序上保证「你正盯着的这几段最先变中文」，
     * 屏幕边缘的随后跟上。探测/拼批都要一两个来回，顺序就是速度。 */
    out.sort(function (a, b) {
      return Math.abs(a.top - vh / 2) - Math.abs(b.top - vh / 2);
    });
    return out;
  }

  /* ------------------------------------------------------------------
   * 「有没有新内容要翻」的问询，短时间内只需要一个答案
   *
   * 换页/补翻一共有三条链在各自轮询：watchView 900ms、catchUp 1200ms、
   * retryLoop 4000ms，再加上滚动的 watchScroll。它们互不知情，经常挤在
   * 同一两百毫秒里把整棵子树各扫一遍——同一个结果算三四遍。
   *
   * 这里给这种「只是问问」的调用加一个很短的结果复用窗：
   *   · 真正要开翻时（translatePage）会先作废旧答案再重扫，拿的一定是最新 DOM
   *   · 最坏情况只是「晚 250ms 发现新内容」，而下一轮轮询本来就 900ms 起步
   * 单次扫描在探索页上千节点时要几十毫秒，合并掉重复的那几次是纯赚。
   * ------------------------------------------------------------------ */
  var COLLECT_TTL = 250;
  var _cCache = null, _cAt = 0;
  function invalidateCollect() { _cCache = null; _cAt = 0; }
  function peekCollect() {
    var now = Date.now();
    if (_cCache && now - _cAt < COLLECT_TTL) return _cCache;
    _cCache = collect(root(), true);
    _cAt = now;
    return _cCache;
  }

  function batch(items) {
    var groups = [], cur = [], len = 0, vis = false;
    items.forEach(function (it) {
      if ((len + it.text.length > MAX_CHARS || cur.length >= MAX_ITEMS) && cur.length) {
        groups.push({ items: cur, visible: vis }); cur = []; len = 0; vis = false;
      }
      cur.push(it); len += it.text.length;
      if (it.visible) vis = true;
    });
    if (cur.length) groups.push({ items: cur, visible: vis });
    return groups;
  }

  /* ================= 翻译流程 ================= */
  var state = {
    busy: false,
    done: false,          // 当前页是否已翻译
    nodes: [],            // 本页被改过的节点，用于还原
    seq: 0,               // 切页自增，丢弃上一页的迟到结果
    engine: '',           // 上次实际使用的引擎
    ms: 0                 // 上次翻译耗时（毫秒）
  };

  function root() {
    return document.getElementById('view') || document.body;
  }

  /** 一批失败时把任务对半拆开重试：批量超限（微软/Google 都有长度限制）时很管用
   *  opts = { abort, onPartial }：abort 为真不再往下拆也不再发请求；
   *  onPartial 由引擎在译出一部分时回调（下标是相对本批 texts 的）。 */
  function translateBatch(engine, texts, depth, opts) {
    opts = opts || {};
    if (opts.abort && opts.abort()) return Promise.resolve(texts.slice());
    return engine.translate(texts, opts).then(function (out) {
      if (!out || out.length !== texts.length) throw new Error('返回条数不符');
      return out;
    }).catch(function (e) {
      if (texts.length <= 1 || depth >= 3) throw e;
      if (opts.abort && opts.abort()) throw e;
      var mid = Math.ceil(texts.length / 2);
      /* 拆两半时各自的下标要平移：右半边的 onPartial(start) 是相对自己那半的，
       * 回到本批要加上 mid，上屏才不会写错节点。 */
      var wrap = function (base) {
        var sub = { abort: opts.abort };
        if (opts.onPartial) {
          sub.onPartial = function (start, arr) { opts.onPartial(base + start, arr); };
        }
        return sub;
      };
      return Promise.all([
        translateBatch(engine, texts.slice(0, mid), depth + 1, wrap(0)),
        translateBatch(engine, texts.slice(mid), depth + 1, wrap(mid))
      ]).then(function (p) { return p[0].concat(p[1]); });
    });
  }

  /* 引擎「临时不可用」记忆：整轮全败的引擎记 5 分钟，期间自动选择直接跳过它，
   * 免得用户手动选了个必挂的引擎后，每次换页都要先撞一次 404/超时再降级。 */
  var badUntil = {};
  function isBadNow(name) { return (badUntil[name] || 0) > Date.now(); }
  function markBad(name, ms) {
    if (name) badUntil[name] = Date.now() + (ms || 5 * 60 * 1000);
  }
  function clearBad(name) { delete badUntil[name]; }
  /* 探测黑名单（独立于 badUntil）：探测失败的引擎 10 分钟内不再参与探测。
   * 为什么不直接用 markBad？真翻译的降级链路里还是该给它们机会——
   * 万一网络刚好恢复；但探测是每换一页都可能跑的，连不上的引擎
   * 一探测就挂满一个连接超时，把原生网络线程占死。 */
  var probeFailUntil = {};
  function probeBlocked(name) { return (probeFailUntil[name] || 0) > Date.now(); }
  function probeFailed(name) { if (name) probeFailUntil[name] = Date.now() + 10 * 60 * 1000; }

  function translatePage(silent, tried) {
    tried = tried || [];
    if (state.busy) { if (!silent) toast('正在翻译，稍等一下'); return Promise.resolve(); }
    invalidateCollect();                     // 真要开翻了：丢掉「只是问问」留下的旧答案
    var nodes = collect(root(), true);
    if (!nodes.length) { if (!silent) toast('这一页没有需要翻译的英文'); return Promise.resolve(); }

    var mySeq = ++state.seq;
    var t0 = Date.now();
    state.busy = true;
    state.okCount = 0;              // 实际写出译文的段数，用来判断引擎是不是整体不可用
    state.lastErr = '';             // 首个批次错误（HTTP 403/超时之类），提示里带上便于自查
    setBusy(true);
    if (!silent) toast('正在翻译 ' + nodes.length + ' 段…');
    /* 看门狗：手动选中一个会挂起的引擎时（个别 WebView 的系统翻译就是这样），
     * 兜底把 busy 释放掉，别让整个翻译功能陪葬 —— 卡死过一次要重启 App 才能救。 */
    var watchdog = setTimeout(function () {
      if (mySeq !== state.seq || !state.busy) return;
      state.busy = false; setBusy(false);
      toast('翻译卡住了，当前引擎可能不可用，建议长按图标换回自动选择');
    }, prefGet('gh_tr_busy_timeout', 45000) | 0 || 45000);

    var engName = '';   // 引擎名存到外层：完成提示在外层 then 里，直接写 name
                        // 会命中全局 window.name（空字符串），提示就变成「」了
    return ensureEngine(tried).then(function (name) {
      if (!name) throw new Error('所有引擎都不可用');
      engName = name;
      var engine = ENGINES[name];
      if (!engine) throw new Error('没有可用引擎');
      state.engine = name;
      // 滚动驱动：collect 已经按「视口 ±600px」筛过了，这里全部组直接翻，
      // 不再有「首屏 + 后台整页」之分——后台整页翻是有道限流的元凶，
      // 而且用户滚过去时经常看到的还是没翻的英文。
      var groups = batch(nodes);

      /* 换页中止开关：这一轮属于 mySeq，页面一换（seq 变了）就为真。
       * 引擎的每个请求/每个 chunk 之前都会问它一次，为真就收手。 */
      var staled = function () { return mySeq !== state.seq; };

      function runGroup(g) {
        if (staled()) return Promise.resolve();
        // 先查缓存，命中的不用发请求。
        // 命中值与原文相同 = 旧版 bug 留下的坏缓存（失败兜底时写进去的），
        // 当 miss 处理重新翻 —— 已污染的缓存能自愈。
        var texts = g.items.map(function (it) { return it.text; });
        var results = new Array(texts.length);
        var miss = [];
        texts.forEach(function (t, k) {
          /* 缓存 key 里带上目标语言：同一段英文翻成中文和翻成英文是两个结果，
           * 不带上 TO 的话切语言之后会命中另一种语言的旧译文。
           * 老 key（没有 TO 段）自然失效、逐步被淘汰，不影响正确性。 */
          var c = cacheGet(name + '|' + TO + '|' + hash(t));
          if (c && c !== t) results[k] = c; else miss.push(k);
        });
        if (!miss.length) { apply(g.items, results, name); return Promise.resolve(); }
        var payload = miss.map(function (k) { return texts[k]; });

        /** 把一段译文写进结果、进缓存，并返回「这一项对应的节点」用于上屏 */
        function commit(j, v) {
          var src = payload[j];
          var s = norm(v);
          /* 只有真译文才上屏、才进缓存。失败兜底回来的原文绝不能缓存——
           * 缓存住原文 = 这段永远不会再翻，页面从此钉死在英文。 */
          if (!s || s === src) return null;
          var k = miss[j];
          results[k] = s;
          cacheSet(name + '|' + TO + '|' + hash(src), s);
          return g.items[k];
        }

        /* 流式上屏：引擎每译出一部分就先写进对应节点，不用等整批结束。
         * 之前 40 段一组要跑完 5 个往返才一次性上屏，用户盯着的空白时间
         * = 整组耗时；现在第一个 chunk 回来就有字，后面的陆续补上。 */
        var opts = {
          abort: staled,
          onPartial: function (start, arr) {
            if (staled()) return;
            var items = [], out = [];
            for (var j = 0; j < arr.length; j++) {
              var it = commit(start + j, arr[j]);
              if (it) { items.push(it); out.push(norm(arr[j])); }
            }
            if (items.length) apply(items, out, name);
          }
        };

        return translateBatch(engine, payload, 0, opts).then(function (out) {
          if (staled()) return;
          miss.forEach(function (k, j) { commit(j, out[j]); });
          // 兜底：把流式没覆盖到的（例如不支持 onPartial 的引擎）统一上屏。
          // apply 内部会跳过已经翻过的节点，不会重复计数。
          apply(g.items, results, name);
        }, function (err) {
          console.warn('[translate] 批次失败', err);
          if (!state.lastErr && err) state.lastErr = err.message || String(err);
        });
      }

      return mapLimit(groups, BATCH_PARALLEL, runGroup);
    }).then(function () {
      if (mySeq !== state.seq) return Promise.resolve();
      clearTimeout(watchdog);
      state.busy = false; setBusy(false);
      state.ms = Date.now() - t0;
      if (state.okCount) {
        clearBad(engName);                    // 这轮成功：摘掉「临时不可用」帽子
        state.done = true;
        if (prefGet(KEY_AUTO, false)) {
          catchUp(1);                         // 异步加载出来的内容再捞一遍
          retryLoop(mySeq, 0);                // 有没翻出来的段（限流兜底）就进重试循环
        }
        // 只有用户亲手点的那次才弹完成提示；自动补翻（换页、列表刷新带出来的
        // 新内容）静默完成——探索页每隔一会儿自己刷新一次时间戳，不静默的话
        // 「已翻译 58 段」会一遍遍往外蹦。
        if (!silent) {
          var how = state.ms < 100 ? '缓存秒出' : (state.ms / 1000).toFixed(1) + ' 秒';
          toast('已翻译 ' + state.okCount + ' 段 · ' + how + ' · ' +
            (SHORT[engName] || engName || '缓存'));
        }
        return Promise.resolve();
      }
      /* ===== 一段都没翻成：自动降级，别把用户晾在英文页上 =====
       * 记住这个引擎挂了（5 分钟内自动选择跳过它），然后按 ORDER 换下一个
       * 还没试过的引擎接着翻本轮。全试完才认输。真机上手动选微软（404）
       * 或设备端（不支持）就是这个场景。 */
      markBad(engName);
      var triedNow = tried.concat([engName]);
      var candidates = ORDER.filter(function (k) {
        return triedNow.indexOf(k) < 0 && !!ENGINES[k] && !isBadNow(k) &&
          (!ENGINES[k].needUrl || prefGet(KEY_CUSTOM, ''));
      });
      if (!candidates.length) {
        if (!silent) toast('所有翻译引擎都不可用，已保持原文（可长按图标换引擎或稍后再试）');
        /* 全军覆没也要排队重试：真机上常见的是「抖一下」——弱网、限流、
         * 系统刚唤醒。用户滑到这里看到的是英文，不重试他就得手动再滑一次
         * 才可能翻出来。等 4 秒再试，最多 3 轮，试不出来就收工。 */
        if (prefGet(KEY_AUTO, false)) retryLoop(mySeq, 1);
        return Promise.resolve();
      }
      if (!silent) toast('「' + (SHORT[engName] || engName) + '」不可用' +
        (state.lastErr ? '（' + state.lastErr + '）' : '') + '，自动换用其他引擎…');
      return translatePage(true, triedNow).then(function () {
        // 降级成功且是用户亲手点的这轮：提一句谁接了班
        if (!silent && state.okCount && state.engine !== triedNow[0]) {
          toast('已改用「' + (SHORT[state.engine] || state.engine) + '」翻译');
        }
      });
    }).catch(function (e) {
      if (mySeq !== state.seq) return Promise.resolve();
      clearTimeout(watchdog);
      state.busy = false; setBusy(false);
      if (!silent) toast('翻译失败：' + (e && e.message ? e.message : e));
      else if (typeof console !== 'undefined') console.log('[translate] 翻译失败', e && e.message);
      // 异常退出同样排队重试（理由同上：抖一下不该让页面停在英文）
      if (prefGet(KEY_AUTO, false)) retryLoop(mySeq, 1);
      return Promise.resolve();
    });
  }

  /**
   * 失败段重试循环：翻译完成后页面上还有没翻出来的英文段（限流/抖动/懒加载
   * 晚到），隔一会儿再捞一轮（探索页会持续加载新条目，几轮常常不够用——
   * 表现就是列表后半截停在英文）。collect 只收没翻的段，翻成功的命中缓存，
   * 不会重复请求；换页（seq 变）或关掉总开关就停。
   *
   * 间隔原来是固定 4 秒 × 5 轮：一个「抖一下」的失败也要等满 4 秒才补，
   * 5 轮就是 20 秒，用户早就划走了。改成指数退避 —— 第一轮 1.2 秒（抖一下
   * 的情况基本就靠它补上），往后逐步拉长到 12 秒（真限流才需要等那么久），
   * 总时长差不多，但「看得到的变化」来得更早。
   */
  var RETRY_DELAYS = [1200, 2500, 5000, 8000, 12000];
  function retryLoop(mySeq, round) {
    if (round >= RETRY_DELAYS.length) return;
    setTimeout(function () {
      if (mySeq !== state.seq) return;                     // 换页了
      if (!prefGet(KEY_AUTO, false)) return;               // 关了
      if (state.busy) { retryLoop(mySeq, round); return; } // 还在翻：等它
      if (!peekCollect().length) return;                   // 没有剩余段了，收工
      translatePage(true).then(function () {
        retryLoop(mySeq, round + 1);
      });
    }, RETRY_DELAYS[round]);
  }

  function apply(group, results, engineName) {
    group.forEach(function (it, i) {
      var t = results[i];
      if (!t || !t.trim() || t === it.text) return;
      var n = it.node;
      /* 已经翻过的节点直接跳过：流式上屏会先写一遍，整批收尾再写一遍，
       * 不挡住就会把 okCount 数成两倍、state.nodes 里塞进重复节点。 */
      if (n.__tr_done) return;
      if (n.__tr_orig === undefined) n.__tr_orig = n.nodeValue;
      n.nodeValue = t;
      n.__tr_done = true;
      state.nodes.push(n);
      state.okCount = (state.okCount || 0) + 1;
    });
  }

  function restorePage(silently) {
    state.seq++;                      // 作废进行中的批次
    state.busy = false;
    setBusy(false);
    var n = 0;
    state.nodes.forEach(function (node) {
      if (node.__tr_orig !== undefined) {
        node.nodeValue = node.__tr_orig;
        delete node.__tr_orig;
        n++;
      }
      delete node.__tr_done;
    });
    state.nodes = [];
    state.done = false;
    // 切目标语言时内部会先还原一次，那是流程的一部分，不该弹「已还原原文」
    if (n && !silently) toast('已还原原文');
  }

  /**
   * 按钮是「总开关」，不是「翻这一页」。
   *   开着：当前页翻，换页后新页面也接着翻，直到你关掉它
   *   关掉：当前页还原，之后换页不再翻
   * 图标亮着就表示开关开着。
   */
  function toggle() {
    if (prefGet(KEY_AUTO, false)) {
      // 关闭不等翻译结束：restorePage 会作废进行中的批次并释放 busy。
      // 翻译途中也能点 —— 按钮在 busy 时不再屏蔽点击（见 translate.css），
      // 否则想叫停只能干等，就是那个「暂停不了」的问题。
      var wasBusy = state.busy;
      prefSet(KEY_AUTO, false);
      restorePage();
      setOn(false);
      toast(wasBusy ? '已停止翻译，已翻的内容已还原' : '翻译已关闭');
    } else {
      if (state.busy) { toast('正在翻译，稍等一下'); return; }
      prefSet(KEY_AUTO, true);
      setOn(true);
      translatePage();
    }
  }

  /* ================= 界面 ================= */
  var btn = null;

  function setBusy(on) {
    if (btn) btn.classList.toggle('busy', !!on);
  }

  /** 按钮外观只跟总开关走：亮 = 开着，跟某一页翻没翻过无关 */
  function setOn(on) {
    if (!btn) return;
    btn.classList.toggle('on', !!on);
    btn.innerHTML = icon('globe', 20);
    btn.setAttribute('aria-label', on ? '关闭翻译' : '开启翻译');
    btn.title = on ? '翻译已开启（点一下关闭）' : '开启翻译';
  }

  function mount() {
    var box = document.getElementById('appbar-actions');
    if (box) {
      if (btn && btn.parentNode === box) return;
      btn = document.createElement('button');
      btn.id = 'tr-btn';
      btn.className = 'icon-btn tr-btn';
      box.appendChild(btn);
    } else if (!btn) {
      btn = document.createElement('button');
      btn.id = 'tr-fab';
      btn.className = 'tr-btn tr-fab';
      document.body.appendChild(btn);
    }
    setOn(prefGet(KEY_AUTO, false));
    bind(btn);
    watchScroll();
  }

  /* 滚动即翻：滚到哪里，翻到哪里。
   * 用户滚动列表/文章时，视野附近（±600px）出现新的英文段就立刻补翻，
   * 防抖 250ms 避免滚动过程里连环触发。
   * 两个关键点（都是真机上「滚到一半不翻了」的元凶）：
   * 1. scroll 事件**不冒泡**——挂 window 上只能收到根滚动，App 列表要是在
   *    内部滚动容器里滚，事件根本到不了。用 document 捕获阶段监听，
   *    页面里任何一个容器滚动都逃不掉。
   * 2. 上一轮还没翻完时不能直接放弃：探索页一屏几十段，上一轮要跑一两秒，
   *    用户先滚一段停下（触发一轮）再往下滚，第二轮正好撞上 busy——
   *    放弃的话这段就永远停在英文（截图里 21~24 号没翻就是这个）。
   *    busy 时排队等它，翻完立刻补。 */
  function watchScroll() {
    if (watchScroll._done) return;
    watchScroll._done = true;
    var t = 0;
    var kick = function () {
      if (!prefGet(KEY_AUTO, false)) return;      // 开关关了：不翻也不空转
      if (state.busy) { t = setTimeout(kick, 900); return; }   // 等上一轮，别放弃
      if (peekCollect().length) translatePage(true);           // 滚动触发
    };
    document.addEventListener('scroll', function () {
      clearTimeout(t);
      t = setTimeout(kick, 250);
    }, { capture: true, passive: true });
  }

  /** App 每次换页都会重建右上角按钮区，用观察器自动补挂 */
  function watchAppbar() {
    var box = document.getElementById('appbar-actions');
    if (!box || !window.MutationObserver) return;
    var mo = new MutationObserver(function () {
      if (!document.getElementById('tr-btn')) {
        btn = null;
        mount();
      }
    });
    mo.observe(box, { childList: true });
  }

  function bind(el) {
    var timer = null, longPressed = false, moved = false, sx = 0, sy = 0;

    el.addEventListener('click', function (e) {
      e.preventDefault();
      if (longPressed) { longPressed = false; return; }
      if (window.UI && UI.haptic) UI.haptic();
      toggle();
    });

    // 长按 / 右键 → 打开菜单（切换引擎、自动翻译开关、清缓存…）
    el.addEventListener('touchstart', function (e) {
      moved = false;
      sx = (e.touches[0] || {}).clientX || 0;
      sy = (e.touches[0] || {}).clientY || 0;
      timer = setTimeout(function () { longPressed = true; openMenu(); }, 520);
    }, { passive: true });
    el.addEventListener('touchmove', function (e) {
      var t = e.touches[0] || {};
      if (Math.abs((t.clientX || 0) - sx) > 10 || Math.abs((t.clientY || 0) - sy) > 10) {
        moved = true; clearTimeout(timer);
      }
    }, { passive: true });
    el.addEventListener('touchend', function () { clearTimeout(timer); if (moved) longPressed = false; });
    el.addEventListener('contextmenu', function (e) { e.preventDefault(); openMenu(); });
  }

  /* ---------------- 目标语言切换 ---------------- */
  function targetLabel(k) {
    for (var i = 0; i < TARGETS.length; i++) { if (TARGETS[i].key === k) return TARGETS[i]; }
    return TARGETS[0];
  }

  function setTarget(k) {
    k = normalizeTarget(k);
    if (k === TO) return false;
    TO = k;
    prefSet(KEY_TARGET, k);
    /* 必须先还原再重翻：翻过的节点挂着 __tr_done，collect 会跳过它们，
     * 不还原的话「换了 target 但页面纹丝不动」，像是没生效。 */
    restorePage(true);
    invalidateCollect();
    return true;
  }

  function applyTarget(k) {
    k = normalizeTarget(k);
    var t = targetLabel(k);
    if (!setTarget(k)) { toast('已经在翻译成' + t.short + '了'); return; }
    toast('已切换：翻译成' + t.short);
    setTimeout(function () { translatePage(false); }, 80);   // 让用户立刻看到效果
  }

  function pickTarget() {
    var items = TARGETS.map(function (t) { return { key: t.key, label: t.label, icon: 'globe' }; });
    if (window.UI && UI.choose) {
      UI.choose('翻译成哪种语言', items, TO, function (k) { if (k) applyTarget(k); });
    } else {
      var v = window.prompt ? window.prompt('目标语言：\n1. 简体中文\n2. English') : null;
      if (v) applyTarget(/^\s*(2|en)\s*$/i.test(v) ? 'en' : 'zh-Hans');
    }
  }

  function openMenu() {
    var cur = currentEngine();
    var items = [
      // 放在第一位：彻底消除等待的办法就是让它后台先翻，翻完你正好看到
      { key: 'auto', label: '打开页面时自动翻译', value: prefGet(KEY_AUTO, false) ? '已开启' : '关', icon: 'zap' },
      { key: 'again', label: '重新翻译本页', icon: 'sync',
        value: state.ms ? (state.ms / 1000).toFixed(1) + ' 秒' : '' },
      { key: 'restore', label: '关闭翻译并还原', icon: 'history' },
      '-',
      // 目标语言开关：日/俄/韩/阿等语言也都是翻到这里选的那一种
      { key: 'lang', label: '翻译成', icon: 'globe', value: targetLabel(TO).label },
      // 显示实际在跑哪个引擎：手动选的和实际跑的是同一个时就不要重复念两遍
      { key: 'eng', label: '翻译引擎', icon: 'globe',
        value: cur === 'auto'
          ? '自动' + (state.engine ? ' · ' + (ENGINES[state.engine] ? ENGINES[state.engine].label : state.engine) : '')
          : (ENGINES[cur] ? ENGINES[cur].label : cur) +
            (state.engine && state.engine !== cur
              ? ' · 上次 ' + (ENGINES[state.engine] ? ENGINES[state.engine].label : state.engine)
              : '') },
      { key: 'clear', label: '清空译文缓存', icon: 'trash' },
      '-',
      { key: 'about', label: '关于翻译', icon: 'info' }
    ];
    if (window.UI && UI.menu) {
      UI.menu('翻译', items).then(function (k) { if (k) onMenu(k); });
    } else {
      // Demo（没有 UI 组件）里的极简兜底
      var map = items.filter(function (x) { return x.key; }).map(function (x, i) { return (i + 1) + '. ' + x.label; });
      var v = prompt(map.join('\n'));
      if (v) onMenu(items[+v - 1] ? items[+v - 1].key : null);
    }
  }

  function onMenu(k) {
    if (k === 'again') { restorePage(); setTimeout(translatePage, 60); }
    else if (k === 'restore') { if (prefGet(KEY_AUTO, false)) toggle(); else restorePage(); }
    else if (k === 'lang') pickTarget();
    else if (k === 'eng') pickEngine();
    else if (k === 'auto') {
      prefSet(KEY_AUTO, !prefGet(KEY_AUTO, false));
      toast(prefGet(KEY_AUTO, false) ? '已开启：进页面自动翻译' : '已关闭自动翻译');
    } else if (k === 'clear') {
      cache = {}; prefSet(KEY_CACHE, cache);
      toast('译文缓存已清空');
    } else if (k === 'about') about();
  }

  /** 切引擎后立刻用新引擎把当前页重翻一遍 —— 不然页面纹丝不动，
   *  用户根本不知道换没换上，也不知道新引擎到底通不通。
   *  注意别只看 state.nodes：看门狗从挂起引擎里救回来时 nodes 是空的，
   *  但页面上躺着一屏英文，同样需要翻。 */
  function applyEngine(k, tip) {
    prefSet(KEY_ENGINE, k);
    toast(tip);
    if (!prefGet(KEY_AUTO, false)) return;
    if (state.nodes.length || state.done || state.busy) restorePage();
    if (collect(root()).length) translatePage(false);   // 用户主动换引擎，给完整反馈
  }

  function pickEngine() {
    var items = [{ key: 'auto', label: '自动选择（推荐）', icon: 'zap' }]
      .concat(ORDER.map(function (k) { return { key: k, label: ENGINES[k].label, icon: 'globe' }; }))
      .concat([{ key: 'custom', label: '自定义接口…', icon: 'server' }]);
    var cur = currentEngine();
    if (window.UI && UI.choose) {
      UI.choose('翻译引擎', items, cur, function (k) {
        if (k === 'custom') {
          var url = prefGet(KEY_CUSTOM, '');
          if (window.UI && UI.prompt) {
            UI.prompt('自定义接口地址', {
              desc: 'POST JSON {"q":["文本"],"to":"' + TO + '"}，返回 {"translations":["译文"]}',
              value: url, placeholder: 'https://你的接口/translate'
            }).then(function (v) {
              if (v) applyEngine('custom', '已保存，马上用新接口重翻本页');
            });
          }
          return;
        }
        applyEngine(k, k === 'auto' ? '已设为自动选择，马上重翻本页' : '已切换到 ' + ENGINES[k].label + '，马上重翻本页');
      });
    } else {
      var v = prompt('引擎：auto/youdao/ondevice/deepl/google/mymemory/custom');
      if (v) applyEngine(v.trim(), '已切换');
    }
  }

  function about() {
    var msg = '翻译本页由 translate.js 提供。\n\n' +
      '• 只翻译页面上的英文正文，代码块、路径、commit sha、@提及会自动跳过\n' +
      '• 译文有本地缓存，同样的内容不会重复请求\n' +
      '• 请求不携带你的 GitHub 令牌，但文本会发给所选翻译服务商\n' +
      '• 免费引擎里，国内网络实测稳定可用的只有「有道」和「MyMemory」；\n' +
      '  DeepL / Google 需要能访问海外网络，「微软 Edge」官方已关闭免费接口\n\n' +
      '点按钮翻译 / 还原，长按按钮打开设置。';
    if (window.UI && UI.sheet) {
      UI.sheet({ title: '关于翻译', body: '<div style="font-size:14px;line-height:1.7;white-space:pre-wrap">' +
        String(msg).replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; }) + '</div>' });
    } else alert(msg);
  }

  /* ================= 启动 ================= */
  function resetState() {
    /* seq 自增会把上一页还在飞的批次作废，避免旧译文写到新页面上。
     * 注意「作废」不只是丢结果：translatePage 给每个引擎都传了 abort 回调
     * （见 runGroup），seq 一变，旧链上还没发出的请求就一个都不发了，
     * 有道那条全局节流队列也在这里清零 —— 新页面不必排在旧页面的时间槽后面。 */
    /* 落盘挪到下一个宏任务：整份缓存（上限 600 条）序列化 + 跨桥写入是同步的，
     * 放在跳转这一帧里就是「点进去先顿一下」。丢失风险由 pagehide /
     * visibilitychange 那两个钩子兜着（见 installCacheFlushHooks）。 */
    setTimeout(function () { try { flushCache(); } catch (e) {} }, 0);
    youdaoResetThrottle();
    state.seq++;
    state.nodes = [];
    state.done = false;
    state.busy = false;
    setBusy(false);
  }

  function onRouteChange() {
    resetState();
    if (prefGet(KEY_AUTO, false)) autoTranslate(2);
  }

  /**
   * 自动翻译：刚换页时内容往往还没渲染完（Router.render 是异步的），
   * 所以要等一下再收集；一次没收着就再等一轮，最多三轮。
   * 注意 busy 时是「等」不是「放弃」：真机上引擎慢（有道单条接口），
   * 上一页的后台批次能跑好几秒，换页瞬间大概率还在 busy——
   * 直接放弃的话新页就永远不翻了（真机上报过：换页不翻译）。
   *
   * 首轮等待从 800ms 降到 300ms：800 是「怕内容没渲染完」拍出来的，
   * 但重试轮次本来就在后面接着，晚一点发现新内容并不丢东西，
   * 而快一点能实打实省下用户盯着英文的半秒。后续轮次 500ms 一拍。
   */
  var AUTO_FIRST_DELAY = 300;
  var AUTO_NEXT_DELAY = 500;
  function autoTranslate(tries) {
    var mySeq = state.seq;
    var delay = tries >= 2 ? AUTO_FIRST_DELAY : AUTO_NEXT_DELAY;
    setTimeout(function () {
      if (mySeq !== state.seq) return;                    // 又换页了，本轮作废
      if (!prefGet(KEY_AUTO, false)) return;              // 排队期间被关掉了：别再翻
      if (state.busy) { autoTranslate(tries); return; }   // 上一页还在翻：等它
      if (state.done) return;                             // 这一页已经翻过了
      if (peekCollect().length) translatePage(true);    // 静默：自动模式下不弹提示打扰
      else if (tries > 0) autoTranslate(tries - 1);
    }, delay);
  }

  /**
   * 翻完再回头捞一遍。
   * githup 的页面多是异步渲染的（先骨架屏，数据到了才填内容），第一次翻的时候
   * 后半截往往还没出来。collect 会跳过已翻译的节点，所以重跑只会补翻新增的部分。
   */
  function catchUp(tries) {
    var mySeq = state.seq;
    setTimeout(function () {
      if (mySeq !== state.seq) return;
      if (state.busy) { catchUp(tries); return; }   // 还在翻：等，别放弃
      if (!prefGet(KEY_AUTO, false)) return;
      if (peekCollect().length) translatePage(true);
      else if (tries > 0) catchUp(tries - 1);
    }, 1200);
  }

  /**
   * 这个 App 的换页走 history.pushState —— 它既不触发 hashchange 也不触发
   * popstate（后者只在后退时响）。只监听这两个事件的话，往前跳的每一次换页
   * 都会漏掉：翻译状态不重置、自动翻译也不跑。所以直接把 pushState 包一层。
   */
  function patchHistory() {
    if (!window.history) return;
    ['pushState', 'replaceState'].forEach(function (m) {
      var orig = history[m];
      if (typeof orig !== 'function' || orig.__trPatched) return;
      var wrapped = function () {
        var r = orig.apply(history, arguments);
        try { onRouteChange(); } catch (e) {}
        return r;
      };
      wrapped.__trPatched = true;
      history[m] = wrapped;
    });
  }

  /** 兜底：万一有别的地方绕过 pushState 直接换了内容，也能跟上 */
  function watchView() {
    var view = document.getElementById('view');
    if (!view || !window.MutationObserver) return;
    var t = 0;
    var tick = function () {
      // 正在翻时等一轮再查（而不是放弃）：列表数据慢到达时经常撞上 busy
      if (state.busy) { t = setTimeout(tick, 900); return; }
      // 已翻译的节点全都不在文档里了，说明整页被换掉了
      var gone = state.nodes.length && !state.nodes.some(function (n) {
        return document.contains(n);
      });
      if (gone) { onRouteChange(); return; }
      // 开关开着时，后加载出来的内容（README、展开的评论）也要补上
      if (prefGet(KEY_AUTO, false) && peekCollect().length) translatePage(true);
    };
    new MutationObserver(function () {
      clearTimeout(t);
      t = setTimeout(tick, 900);
    }).observe(view, { childList: true, subtree: true });
  }

  function init() {
    installCacheFlushHooks();
    mount();
    watchAppbar();
    /* 这两行是换页继续翻译的命根子，之前忘了装（真机上换页就断）：
     * 真 App 的路由是 history.pushState 的 path 型——hashchange 和 popstate
     * 都不响应，必须把 pushState/replaceState 包一层；watchView 再用
     * MutationObserver 兜住绕过路由直接换 DOM 的情况。 */
    patchHistory();
    watchView();
    window.addEventListener('hashchange', onRouteChange);
    window.addEventListener('popstate', onRouteChange);
    if (prefGet(KEY_AUTO, false)) autoTranslate(2);
    // 供调试 / 控制台调用。
    // 名字必须是 GhTranslator：window.Translator 是新版 Chromium 自带的
    // 设备端翻译 API，占用它会在真机上跟系统打架。
    window.GhTranslator = {
      translate: translatePage,
      restore: restorePage,
      toggle: toggle,
      menu: openMenu,
      engines: ENGINES,
      setEngine: applyEngine,
      _state: function () {
        return { busy: state.busy, seq: state.seq, done: state.done,
                 nodes: state.nodes.length, okCount: state.okCount || 0,
                 engine: state.engine, cur: currentEngine(), target: TO };
      },
      targets: TARGETS,
      setTarget: applyTarget,
      _needTranslate: function (s) { return needTranslate(s); },
      _detectScript: function (s) { return detectScript(s); },
      probe: function () {
        return Promise.all(ORDER.map(function (k) {
          return probe(k).then(function (ok) { return { engine: k, ok: ok, label: ENGINES[k].label }; });
        }));
      }
    };
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
