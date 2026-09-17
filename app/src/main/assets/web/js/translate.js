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
  var TO = 'zh-Hans';                 // 目标语言
  var FROM = '';                      // 源语言，空 = 自动识别
  var MAX_CHARS = 5000;               // 单批总字符上限（微软匿名端点保守值）
  var MAX_ITEMS = 40;                 // 单批最大段数
  var CACHE_MAX = 600;                // 本地译文缓存条数上限
  var JSONP_TIMEOUT = 15000;
  var PROBE_TIMEOUT = 5000;           // 单个引擎探测超时
  var CONCURRENCY = 8;                // 逐条引擎的并发请求数
  var BATCH_PARALLEL = 2;             // 同时进行的批次数

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
    try {
      if (window.Store && typeof window.Store.setJSON === 'function') window.Store.setJSON(k, v);
    } catch (e) {}
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
  function mapLimit(items, limit, fn) {
    var out = new Array(items.length);
    var i = 0, active = 0;
    return new Promise(function (resolve) {
      if (!items.length) return resolve(out);
      function next() {
        while (active < limit && i < items.length) {
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
        if (active === 0 && i >= items.length) resolve(out);
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

  /* 原生通道：注意 headersJson 参数是 **字符串**（JsBridge.http 的签名是
   * String headersJson，Java 侧 new JSONObject(headersJson)）。
   * 以前直接把 JS 对象塞进去，WebView 桥接时塞不进 String 参数，结果
   * headers 被整体丢弃 —— 于是所有依赖 Content-Type / Authorization
   * 的引擎（有道、DeepL、微软批量）在真机上全部失败，只有不需要 header
   * 的 MyMemory 能活。必须 JSON.stringify。 */
  /* 原生通道默认带个浏览器 UA：Http.java 在没有 UA 时会写 HubMobile/1.0，
   * 有道 / DeepL 这类对非浏览器 UA 不太友好，容易直接拒。 */
  var DEFAULT_UA = 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 ' +
    '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
  function nativeHttp(method, url, body, headers) {
    var h = { 'User-Agent': DEFAULT_UA };
    if (headers) { for (var k in headers) { if (Object.prototype.hasOwnProperty.call(headers, k)) h[k] = headers[k]; } }
    return window.Native.http(method, url, body || null, JSON.stringify(h))
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
    translate: function (texts) {
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
            return withTimeout(tr.translate(t)).then(function (r) { return norm(r) || t; });
          }).then(function (out) {
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
    translate: function (texts) {
      var joined = texts.join('\n');
      var useJsonp = !hasBridge() && !!document.createElement;
      return googleRaw(joined, useJsonp).then(function (out) {
        var lines = String(out).split('\n');
        if (lines.length === texts.length) {
          return lines.map(function (l, i) { return norm(l) || texts[i]; });
        }
        return ENGINES.google.oneByOne(texts);
      }).catch(function () { return ENGINES.google.oneByOne(texts); });
    },
    oneByOne: function (texts) {
      return mapLimit(texts, CONCURRENCY, function (t) {
        return googleRaw(t, !hasBridge()).then(function (r) { return norm(r) || t; });
      }).then(function (out) {
        return out.map(function (r, i) { return r || texts[i]; });
      });
    }
  };

  /* --- 3. 有道翻译（aidemo 演示接口：国内直连、无需 key） ---
   * 速度的关键在这里：aidemo 对换行拼批**原样保留换行**（实测进几行出几行）。
   * 原来每段一个请求 = 每段一次完整 TCP+TLS 握手（Http.java 不复用连接），
   * 而且真机实测：一屏 30 段连发必然撞上 411「请求频率过快」，一半段落
   * 直接被拒——这就是「翻得慢、还翻一半就停」的元凶。
   * 拼批 + 节流之后：一屏 3 条请求、间隔 800ms，实测 24 段 2.9 秒全翻完。
   * 实测出的两个硬上限（超了报 103 内容异常）：单条 ≤8 行且 ≤800 字符。
   * 失败的 chunk **绝不退回逐条**（那会让频率雪崩，十几条连发全 411），
   * 而是对半拆小再试；拆到单行还失败就放弃，保持原文交给重试循环，
   * 4 秒后频率窗口早过了，自然补上。 */
  var YOUDAO_BATCH_LINES = 8;         // 单条拼批请求的最大行数（实测 17 行会被 103 拒）
  var YOUDAO_BATCH_CHARS = 800;       // 单条拼批请求的字符上限
  var YOUDAO_MIN_GAP = 800;           // 相邻两次请求的最小间隔（411「请求频率过快」的保险）
  var youdaoNextAt = 0;               // 下一次允许发请求的时间戳（全局节流）
  function youdaoRequest(q) {
    var wait = youdaoNextAt - Date.now();
    if (wait < 0) wait = 0;
    youdaoNextAt = Date.now() + wait + YOUDAO_MIN_GAP;
    return new Promise(function (res) { setTimeout(res, wait); }).then(function () {
      return request('POST', 'https://aidemo.youdao.com/trans',
        'q=' + encodeURIComponent(q) +
        '&from=' + (FROM || 'auto') +
        '&to=' + (TO === 'zh-Hans' ? 'zh-CHS' : TO),
        { 'Content-Type': 'application/x-www-form-urlencoded' }).then(function (s) {
          var d = JSON.parse(s);
          if (d && d.errorCode && String(d.errorCode) !== '0') {
            throw new Error('有道错误 ' + d.errorCode + (d.msg ? ' ' + d.msg : ''));
          }
          var v = d && d.translation && d.translation[0];
          if (!v || !String(v).trim()) throw new Error('空译文');
          return String(v);
        });
    });
  }
  function youdaoOne(text) {
    return youdaoRequest(text).then(norm);
  }
  /** 翻一个 chunk：行数对不上或请求失败就对半拆小再试，别让译文错位 */
  function youdaoChunk(chunk, depth) {
    if (chunk.length === 1) {
      return youdaoOne(chunk[0]).catch(function () { return chunk[0]; });  // 放弃：保持原文等重试
    }
    var split = function () {
      if (depth >= 2) return Promise.resolve(chunk.slice());               // 放弃：保持原文等重试
      var mid = Math.ceil(chunk.length / 2);
      return Promise.all([
        youdaoChunk(chunk.slice(0, mid), depth + 1),
        youdaoChunk(chunk.slice(mid), depth + 1)
      ]).then(function (p) { return p[0].concat(p[1]); });
    };
    return youdaoRequest(chunk.join('\n')).then(function (out) {
      var lines = out.split('\n');
      if (lines.length !== chunk.length) return split();
      return lines.map(function (l, i) { return norm(l) || chunk[i]; });
    }, split);
  }
  ENGINES.youdao = {
    label: '有道翻译（免费，国内直连）',
    batch: true,
    translate: function (texts) {
      /* 按行数 + 字符数双上限切片，串行发（节流阀已经把节奏排好了） */
      var chunks = [], cur = [], len = 0;
      texts.forEach(function (t) {
        if (cur.length && (cur.length >= YOUDAO_BATCH_LINES ||
            len + t.length + 1 > YOUDAO_BATCH_CHARS)) {
          chunks.push(cur); cur = []; len = 0;
        }
        cur.push(t); len += t.length + 1;
      });
      if (cur.length) chunks.push(cur);
      var p = Promise.resolve([]);
      chunks.forEach(function (chunk) {
        p = p.then(function (acc) {
          return youdaoChunk(chunk, 0).then(function (out) { return acc.concat(out); });
        });
      });
      return p.then(function (out) {
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
    translate: function (texts) {
      return mapLimit(texts, 3, function (t) {
        return deeplOne(t);
      }).then(function (out) { return out.map(function (r, i) { return r || texts[i]; }); });
    }
  };

  /* --- 5. MyMemory（兜底，全球可达，匿名有日配额，单条限 ~500 字节） --- */
  ENGINES.mymemory = {
    label: 'MyMemory（兜底，有日限额）',
    batch: false,
    translate: function (texts) {
      return mapLimit(texts, CONCURRENCY, function (t) {
        // 单条上限约 500 字节，超了就不浪费一次请求
        if (encodeURIComponent(t).length > 480) return Promise.resolve(null);
        var url = 'https://api.mymemory.translated.net/get?q=' + encodeURIComponent(t) +
          '&langpair=' + encodeURIComponent((FROM || 'en') + '|zh-CN');
        return request('GET', url, null, {}).then(function (s) {
          var d = JSON.parse(s);
          var r = d && d.responseData && d.responseData.translatedText;
          // 配额用完时它会把警告当成译文返回，这种要当失败处理
          if (!r || /MYMEMORY WARNING/i.test(r)) throw new Error('无配额');
          return norm(r);
        });
      }).then(function (out) { return out.map(function (r, i) { return r || texts[i]; }); });
    }
  };

  /* --- 6. 自定义接口（逃生舱：自建 / DeepLX / 公司内网都行）
   * 协议：POST JSON {"q":["..."],"from":"auto","to":"zh-Hans"}
   *      返回 {"translations":["..."]} 或直接是字符串数组 */
  ENGINES.custom = {
    label: '自定义接口',
    batch: true,
    needUrl: true,
    translate: function (texts) {
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
    candidates = candidates || ORDER.slice();
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
             * 就把原生网络线程挂满一个连接超时，不记 10 分钟的话每换一页
             * 都要重来一轮 —— 页面数据请求全被堵在后面。 */
            markBad(name, 10 * 60 * 1000);
            if (--pending === 0) { clearTimeout(overall); settle(null); }
          }, function () {
            if (settled) return;
            markBad(name, 10 * 60 * 1000);
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
  function cacheSet(k, v) {
    var keys = Object.keys(cache);
    if (keys.length > CACHE_MAX) {
      // 简易淘汰：清掉最早写入的一批，不做严格 LRU，够用
      keys.slice(0, Math.floor(CACHE_MAX / 3)).forEach(function (x) { delete cache[x]; });
    }
    cache[k] = v;
    prefSet(KEY_CACHE, cache);
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
  /* 编程语言名：探索页的语言标签整段就是 "TypeScript"，送去翻会变成「打印稿」
   * 这种笑话，还白耗一次请求。整段等于语言名的一律跳过。 */
  var RE_LANG = /^(actionscript|ada|assembly|bash|c|c\+\+|c#|clojure|cmake|cobol|coffee(script)?|crystal|css|d|dart|dockerfile|elixir|erlang|f#|fortran|go|gradle|groovy|haskell|html|java|javascript|julia|jupyter(\s?notebook)?|kotlin|lua|matlab|nim|nix|objective-c|ocaml|pascal|perl|php|powershell|prolog|python|r|racket|ruby|rust|scala|scheme|shell|smalltalk|solidity|sql|svelte|swift|tcl|typescript|vb\.?net|vue|vue\.js|zig)$/i;

  /** 判断一段文本值不值得送去翻译 */
  function needTranslate(raw) {
    var s = norm(raw);
    if (s.length < 2) return false;
    if (!/[A-Za-z]/.test(s)) return false;                            // 没有拉丁字母（中文/数字/符号）
    if (/[一-龥]/.test(s)) {
      var cn = (s.match(/[一-龥]/g) || []).length;
      if (cn / s.length > 0.35) return false;                         // 已经以中文为主
    }
    if (RE_URL.test(s) || RE_PATH.test(s) || RE_SHA.test(s) || RE_NUM.test(s) ||
        RE_REF.test(s) || RE_VER.test(s) || RE_AGO.test(s) || RE_LANG.test(s)) return false;
    if (/^\W+$/.test(s)) return false;
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
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
    var n;
    while ((n = walker.nextNode())) {
      if (!n.nodeValue || !n.nodeValue.trim()) continue;
      if (n.__tr_done) continue;
      if (inSkip(n)) continue;
      if (!needTranslate(n.nodeValue)) continue;
      var p = n.parentElement;
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

  /** 一批失败时把任务对半拆开重试：批量超限（微软/Google 都有长度限制）时很管用 */
  function translateBatch(engine, texts, depth) {
    return engine.translate(texts).then(function (out) {
      if (!out || out.length !== texts.length) throw new Error('返回条数不符');
      return out;
    }).catch(function (e) {
      if (texts.length <= 1 || depth >= 3) throw e;
      var mid = Math.ceil(texts.length / 2);
      return Promise.all([
        translateBatch(engine, texts.slice(0, mid), depth + 1),
        translateBatch(engine, texts.slice(mid), depth + 1)
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

  function translatePage(silent, tried) {
    tried = tried || [];
    if (state.busy) { if (!silent) toast('正在翻译，稍等一下'); return Promise.resolve(); }
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

      function runGroup(g) {
        if (mySeq !== state.seq) return Promise.resolve();
        // 先查缓存，命中的不用发请求。
        // 命中值与原文相同 = 旧版 bug 留下的坏缓存（失败兜底时写进去的），
        // 当 miss 处理重新翻 —— 已污染的缓存能自愈。
        var texts = g.items.map(function (it) { return it.text; });
        var results = new Array(texts.length);
        var miss = [];
        texts.forEach(function (t, k) {
          var c = cacheGet(name + '|' + hash(t));
          if (c && c !== t) results[k] = c; else miss.push(k);
        });
        if (!miss.length) { apply(g.items, results, name); return Promise.resolve(); }
        var payload = miss.map(function (k) { return texts[k]; });
        return translateBatch(engine, payload, 0).then(function (out) {
          miss.forEach(function (k, j) {
            var v = norm(out[j]);
            /* 只有真译文才上屏、才进缓存。失败兜底回来的原文绝不能缓存——
             * 缓存住原文 = 这段永远不会再翻，页面从此钉死在英文。 */
            if (v && v !== payload[j]) {
              results[k] = v;
              cacheSet(name + '|' + hash(payload[j]), v);
            }
            // v 为空或等于原文：不写结果，apply 会跳过，保持原文等待下次重试
          });
          if (mySeq === state.seq) apply(g.items, results, name);
        }, function (err) {
          console.warn('[translate] 批次失败', err);
          if (!state.lastErr && err) state.lastErr = err.message || String(err);
        });
      }

      return mapLimit(groups, 3, runGroup);
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
   * 晚到），隔 4 秒再捞一轮，最多 5 轮（探索页会持续加载新条目，3 轮常常
   * 不够用——表现就是列表后半截停在英文）。collect 只收没翻的段，翻成功的
   * 命中缓存，不会重复请求；换页（seq 变）或关掉总开关就停。
   */
  function retryLoop(mySeq, round) {
    if (round >= 5) return;
    setTimeout(function () {
      if (mySeq !== state.seq) return;                     // 换页了
      if (!prefGet(KEY_AUTO, false)) return;               // 关了
      if (state.busy) { retryLoop(mySeq, round); return; } // 还在翻：等它
      if (!collect(root()).length) return;                 // 没有剩余段了，收工
      translatePage(true).then(function () {
        retryLoop(mySeq, round + 1);
      });
    }, 4000);
  }

  function apply(group, results, engineName) {
    group.forEach(function (it, i) {
      var t = results[i];
      if (!t || !t.trim() || t === it.text) return;
      var n = it.node;
      if (n.__tr_orig === undefined) n.__tr_orig = n.nodeValue;
      n.nodeValue = t;
      n.__tr_done = true;
      state.nodes.push(n);
      state.okCount = (state.okCount || 0) + 1;
    });
  }

  function restorePage() {
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
    if (n) toast('已还原原文');
  }

  /**
   * 按钮是「总开关」，不是「翻这一页」。
   *   开着：当前页翻，换页后新页面也接着翻，直到你关掉它
   *   关掉：当前页还原，之后换页不再翻
   * 图标亮着就表示开关开着。
   */
  function toggle() {
    if (prefGet(KEY_AUTO, false)) {
      // 关闭不等翻译结束：restorePage 会作废进行中的批次并释放 busy
      prefSet(KEY_AUTO, false);
      restorePage();
      setOn(false);
      toast('翻译已关闭');
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
      if (collect(root(), true).length) translatePage(true);
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

  function openMenu() {
    var cur = currentEngine();
    var items = [
      // 放在第一位：彻底消除等待的办法就是让它后台先翻，翻完你正好看到
      { key: 'auto', label: '打开页面时自动翻译', value: prefGet(KEY_AUTO, false) ? '已开启' : '关', icon: 'zap' },
      { key: 'again', label: '重新翻译本页', icon: 'sync',
        value: state.ms ? (state.ms / 1000).toFixed(1) + ' 秒' : '' },
      { key: 'restore', label: '关闭翻译并还原', icon: 'history' },
      '-',
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
    // seq 自增会把上一页还在飞的批次作废，避免旧译文写到新页面上
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
   */
  function autoTranslate(tries) {
    var mySeq = state.seq;
    setTimeout(function () {
      if (mySeq !== state.seq) return;                    // 又换页了，本轮作废
      if (!prefGet(KEY_AUTO, false)) return;              // 排队期间被关掉了：别再翻
      if (state.busy) { autoTranslate(tries); return; }   // 上一页还在翻：等它
      if (state.done) return;                             // 这一页已经翻过了
      if (collect(root()).length) translatePage(true);    // 静默：自动模式下不弹提示打扰
      else if (tries > 0) autoTranslate(tries - 1);
    }, 800);
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
      if (collect(root()).length) translatePage(true);
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
      if (prefGet(KEY_AUTO, false) && collect(root()).length) translatePage(true);
    };
    new MutationObserver(function () {
      clearTimeout(t);
      t = setTimeout(tick, 900);
    }).observe(view, { childList: true, subtree: true });
  }

  function init() {
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
                 engine: state.engine, cur: currentEngine() };
      },
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
