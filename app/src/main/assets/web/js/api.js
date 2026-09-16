/* ============================================================
 * api.js — 网络通道 + GitHub REST API 封装
 * 在 Android WebView 中通过 NativeBridge 发起请求（无跨域限制，
 * 由原生层统一附加 Authorization）；浏览器中自动降级为 fetch。
 * ============================================================ */
(function () {
  'use strict';

  // 允许通过 window.__GH_API_BASE__ 覆盖（仅用于本地调试/测试）
  var API_BASE = window.__GH_API_BASE__ || 'https://api.github.com';
  var ACCEPT = 'application/vnd.github+json';
  var X_VERSION = '2022-11-28';

  /* ---------- 原生桥 ---------- */
  var Native = {
    has: function () { return !!(window.NativeBridge && typeof window.NativeBridge.http === 'function'); },
    pending: Object.create(null),
    seq: 1,
    http: function (method, url, body, headers) {
      var self = this;
      if (!this.has()) return Promise.reject(new Error('no bridge'));
      return new Promise(function (resolve, reject) {
        var id = 'r' + (self.seq++);
        self.pending[id] = { resolve: resolve, reject: reject };
        try {
          window.NativeBridge.http(id, method, url, body || null, JSON.stringify(headers || {}));
        } catch (e) {
          delete self.pending[id];
          reject(e);
        }
      });
    },
    // 由原生层回调
    _cb: function (id, status, body, headers) {
      var p = this.pending[id];
      if (!p) return;
      delete this.pending[id];
      var res = { status: status, body: body, headers: {} };
      try { res.headers = JSON.parse(headers || '{}'); } catch (e) { res.headers = {}; }
      p.resolve(res);
    },
    getToken: function () {
      if (window.NativeBridge && typeof window.NativeBridge.getToken === 'function') {
        try { return window.NativeBridge.getToken() || ''; } catch (e) { return ''; }
      }
      try { return localStorage.getItem('gh_token') || ''; } catch (e) { return ''; }
    },
    setToken: function (t) {
      if (window.NativeBridge && typeof window.NativeBridge.setToken === 'function') {
        try { window.NativeBridge.setToken(t || ''); } catch (e) {}
      }
      try { t ? localStorage.setItem('gh_token', t) : localStorage.removeItem('gh_token'); } catch (e) {}
    },
    /** 在应用内置浏览器中打开（原生 WebView Activity）；无原生环境时降级为系统浏览器/新标签 */
    openInApp: function (url, title) {
      if (window.NativeBridge && typeof window.NativeBridge.openInApp === 'function') {
        try { window.NativeBridge.openInApp(url, title || ''); return; } catch (e) {}
      }
      if (window.NativeBridge && typeof window.NativeBridge.openExternal === 'function') {
        try { window.NativeBridge.openExternal(url); return; } catch (e) {}
      }
      window.open(url, '_blank');
    },

    /* ---------------- 文件能力（上传用） ---------------- */
    _pickCbs: Object.create(null),
    _readCbs: Object.create(null),
    _ksCbs: Object.create(null),

    /** 是否有原生文件选择能力 */
    canPick: function () {
      return !!(window.NativeBridge && typeof window.NativeBridge.pickFile === 'function');
    },

    /**
     * 打开文件选择器。
     * @param {string} accept MIME 类型，如 'application/vnd.android.package-archive'
     * @returns {Promise<{name,size,mime,uri}|null>} 用户取消时 resolve(null)
     */
    pickFile: function (accept) {
      var self = this;
      if (!this.canPick()) return Promise.reject(new Error('当前环境不支持选择文件'));
      return new Promise(function (resolve, reject) {
        var id = 'p' + (self.seq++);
        var timer = setTimeout(function () {
          delete self._pickCbs[id];
          reject(new Error('选择文件超时'));
        }, 180000);
        self._pickCbs[id] = function (meta, err) {
          clearTimeout(timer);
          if (err) reject(new Error(err));
          else resolve(meta);
        };
        try {
          window.NativeBridge.pickFile(id, accept || '*/*');
        } catch (e) {
          clearTimeout(timer);
          delete self._pickCbs[id];
          reject(e);
        }
      });
    },
    _pick: function (id, meta, err) {
      var cb = this._pickCbs[id];
      if (!cb) return;
      delete this._pickCbs[id];
      cb(meta, err);
    },

    /** 读取文件的 Base64 内容（用于仓库文件上传）。 */
    readFileBase64: function (uri, maxBytes) {
      var self = this;
      if (!(window.NativeBridge && typeof window.NativeBridge.readFileBase64 === 'function')) {
        return Promise.reject(new Error('当前环境不支持读取文件'));
      }
      return new Promise(function (resolve, reject) {
        var id = 'f' + (self.seq++);
        self._readCbs[id] = function (b64, err) {
          if (err) reject(new Error(err)); else resolve(b64);
        };
        try {
          window.NativeBridge.readFileBase64(id, uri, maxBytes || 0);
        } catch (e) {
          delete self._readCbs[id];
          reject(e);
        }
      });
    },
    _read: function (id, b64, err) {
      var cb = this._readCbs[id];
      if (!cb) return;
      delete this._readCbs[id];
      cb(b64, err);
    },

    /**
     * 在应用内生成签名密钥。
     * 输入一串英文字母和数字（口令），直接得到可用于签名的 keystore（Base64）。
     * 同一串口令永远得到同一把钥匙 —— 这就是「使用同一个签名」的实现方式。
     */
    makeKeystore: function (seed, alias, pass) {
      var self = this;
      if (!(window.NativeBridge && typeof window.NativeBridge.makeKeystore === 'function')) {
        return Promise.reject(new Error('当前版本不支持在应用内生成签名'));
      }
      return new Promise(function (resolve, reject) {
        var id = 'k' + (self.seq++);
        var timer = setTimeout(function () {
          delete self._ksCbs[id];
          reject(new Error('生成签名超时，请重试'));
        }, 120000);
        self._ksCbs[id] = function (b64, err) {
          clearTimeout(timer);
          if (err) reject(new Error(err)); else resolve(b64);
        };
        try {
          window.NativeBridge.makeKeystore(id, seed, alias || 'githup', pass, pass);
        } catch (e) {
          clearTimeout(timer);
          delete self._ksCbs[id];
          reject(e);
        }
      });
    },
    _ks: function (id, b64, err) {
      var cb = this._ksCbs[id];
      if (!cb) return;
      delete this._ksCbs[id];
      cb(b64, err);
    },

    /**
     * 二进制上传到指定 URL（Release 附件用）。
     * 走原生读取与发送，避免 JS 侧承载大文件。
     */
    uploadBinary: function (url, uri, headers) {
      var self = this;
      if (!(window.NativeBridge && typeof window.NativeBridge.uploadBinary === 'function')) {
        return Promise.reject(new Error('当前环境不支持二进制上传'));
      }
      return new Promise(function (resolve, reject) {
        var id = 'u' + (self.seq++);
        self.pending[id] = { resolve: resolve, reject: reject };
        try {
          window.NativeBridge.uploadBinary(id, url, uri, JSON.stringify(headers || {}));
        } catch (e) {
          delete self.pending[id];
          reject(e);
        }
      });
    },

    /**
     * 下载文件到系统下载目录。
     * 带 Authorization 的下载（Release 资产、Actions 构建产物）必须用这个方法，
     * 否则 GitHub 会返回 403。无认证需求时 headers 传 null 即可。
     */
    download: function (url, filename, headers) {
      var NB = window.NativeBridge;
      if (!(NB && typeof NB.downloadWithHeaders === 'function')) {
        // 降级：无原生能力时用浏览器打开
        window.open(url, '_blank');
        return false;
      }
      NB.downloadWithHeaders(url, filename || 'download',
        headers ? JSON.stringify(headers) : null);
      return true;
    },

    /**
     * 下载构建产物并自动解压出 APK 安装。
     * 官网只提供 ZIP 下载，解压与安装要用户自己在手机上完成；这里一步到位。
     * 低版本原生没有该方法时，退回普通下载，不会点不出反应。
     */
    installApk: function (url, filename, headers) {
      var NB = window.NativeBridge;
      if (!(NB && typeof NB.installApk === 'function')) {
        return this.download(url, filename, headers);
      }
      NB.installApk(url, filename || 'artifact.zip',
        headers ? JSON.stringify(headers) : null);
      return true;
    },

    /** GitHub 下载用的认证头（未登录时返回 null） */
    authHeaders: function () {
      var t = this.getToken();
      if (!t) return null;
      return {
        Authorization: 'Bearer ' + t,
        Accept: '*/*',
        'X-GitHub-Api-Version': '2022-11-28'
      };
    },

    /**
     * 应用版本号（原生 BuildConfig.VERSION_NAME）；拿不到原生环境时用内置常量。
     * 读不到具体值时宁可返回空串，也不要编一个 0.0.0 —— 假版本号会被
     * 更新检测当成「大版本升级」而弹强制更新。
     */
    APP_VERSION: '1.1.1',
    appVersion: function () {
      try {
        if (window.NativeBridge && typeof window.NativeBridge.appVersion === 'function') {
          var v = window.NativeBridge.appVersion();
          if (v) return String(v).trim();
        }
      } catch (e) {}
      return this.APP_VERSION;
    }
  };
  window.Native = Native;

  /* ---------- 设置 / 本地存储 ---------- */
  var SETTINGS_KEY = 'gh_settings';
  var defaults = {
    theme: 'auto',            // auto | light | dark
    density: 'comfortable',
    codeFont: 13,
    startTab: 'home',
    markdownZoom: false,
    lastUser: ''
  };
  var Store = {
    settings: null,
    load: function () {
      if (this.settings) return this.settings;
      var s = {};
      for (var k in defaults) s[k] = defaults[k];
      var raw = null;
      if (window.NativeBridge && typeof window.NativeBridge.getPref === 'function') {
        try { raw = window.NativeBridge.getPref(SETTINGS_KEY); } catch (e) {}
      }
      if (!raw) { try { raw = localStorage.getItem(SETTINGS_KEY); } catch (e) {} }
      if (raw) { try { var o = JSON.parse(raw); for (var j in o) s[j] = o[j]; } catch (e) {} }
      this.settings = s;
      return s;
    },
    get: function (k) { return this.load()[k]; },
    set: function (k, v) {
      var s = this.load(); s[k] = v;
      var raw = JSON.stringify(s);
      if (window.NativeBridge && typeof window.NativeBridge.setPref === 'function') {
        try { window.NativeBridge.setPref(SETTINGS_KEY, raw); } catch (e) {}
      }
      try { localStorage.setItem(SETTINGS_KEY, raw); } catch (e) {}
      return v;
    },
    // 通用缓存（历史记录等）
    getJSON: function (k, def) {
      var raw = null;
      if (window.NativeBridge && typeof window.NativeBridge.getPref === 'function') {
        try { raw = window.NativeBridge.getPref(k); } catch (e) {}
      }
      if (!raw) { try { raw = localStorage.getItem(k); } catch (e) {} }
      if (!raw) return def;
      try { return JSON.parse(raw); } catch (e) { return def; }
    },
    setJSON: function (k, v) {
      var raw = JSON.stringify(v);
      if (window.NativeBridge && typeof window.NativeBridge.setPref === 'function') {
        try { window.NativeBridge.setPref(k, raw); } catch (e) {}
      }
      try { localStorage.setItem(k, raw); } catch (e) {}
    }
  };
  window.Store = Store;

  /* ---------- 会话 ----------
   * 两个独立概念：
   *  - token       当前会话令牌，参与请求鉴权；退出登录时清空
   *  - savedToken  记住的令牌，持久保存（localStorage: gh_saved_token），
   *                供登录页回填；只有「忘记此令牌」才清除
   */
  var SAVED_KEY = 'gh_saved_token';
  var Session = {
    token: '',
    user: null,
    savedToken: '',
    init: function () {
      this.token = Native.getToken();
      try { this.savedToken = localStorage.getItem(SAVED_KEY) || this.token || ''; } catch (e) { this.savedToken = this.token; }
      if (!this.savedToken) this.savedToken = this.token || '';
      return this.token;
    },
    setToken: function (t) {
      this.token = t || '';
      this.user = null;
      Native.setToken(this.token);
      if (this.token) {
        this.savedToken = this.token;
        try { localStorage.setItem(SAVED_KEY, this.token); } catch (e) {}
      }
    },
    /** 退出登录：只清当前会话，记住的令牌保留，供下次一键回填 */
    clear: function () {
      this.token = '';
      this.user = null;
      Native.setToken('');
    },
    /** 彻底忘记令牌（用户主动清除时调用） */
    forget: function () {
      this.token = '';
      this.user = null;
      this.savedToken = '';
      Native.setToken('');
      try { localStorage.removeItem(SAVED_KEY); } catch (e) {}
    },
    get isLogin() { return !!this.token; }
  };
  window.Session = Session;

  /* ---------- HTTP ---------- */
  function buildUrl(path, params) {
    var url = /^https?:/.test(path) ? path : API_BASE + path;
    if (params) {
      var q = [];
      for (var k in params) {
        var v = params[k];
        if (v === undefined || v === null || v === '') continue;
        q.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
      }
      if (q.length) url += (url.indexOf('?') >= 0 ? '&' : '?') + q.join('&');
    }
    return url;
  }

  function parseLink(header) {
    var out = {};
    if (!header) return out;
    header.split(',').forEach(function (part) {
      var m = part.match(/<([^>]+)>;\s*rel="?(\w+)"?/);
      if (m) out[m[2]] = m[1];
    });
    return out;
  }

  function parseJSON(res) {
    if (!res.body) return null;
    try { return JSON.parse(res.body); } catch (e) { return null; }
  }

  function makeError(res, data) {
    var msg = (data && data.message) || ('HTTP ' + res.status);
    var e = new Error(msg);
    e.status = res.status;
    e.data = data;
    e.rateLimited = res.status === 403 && (!data || /rate limit/i.test(msg) || (res.headers && res.headers['x-ratelimit-remaining'] === '0'));
    if (res.status === 401) e.unauthorized = true;
    if (res.status === 404) e.notFound = true;
    return e;
  }

  var cache = Object.create(null);
  var inflight = Object.create(null);

  function req(method, path, opts) {
    opts = opts || {};
    var url = buildUrl(path, opts.params);
    var headers = {
      'Accept': opts.accept || ACCEPT,
      'X-GitHub-Api-Version': X_VERSION,
      'User-Agent': 'githup/1.0'
    };
    if (Session.token && !opts.anonymous) headers['Authorization'] = 'Bearer ' + Session.token;
    if (opts.headers) for (var h in opts.headers) headers[h] = opts.headers[h];

    var body = null;
    if (opts.body !== undefined && opts.body !== null) {
      body = typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body);
      headers['Content-Type'] = 'application/json';
    }

    var isGet = method === 'GET';
    var ckey = method + ' ' + url;
    if (isGet && opts.cache && cache[ckey] && Date.now() - cache[ckey].t < (opts.cache || 30000)) {
      return Promise.resolve(cache[ckey].v);
    }
    if (isGet && inflight[ckey] && opts.dedupe !== false) return inflight[ckey];

    var p;
    if (Native.has()) {
      p = Native.http(method, url, body, headers).then(function (res) {
        var data = parseJSON(res);
        if (res.status >= 400) throw makeError(res, data);
        var out = { data: data, status: res.status, link: parseLink(res.headers.link || res.headers.Link), headers: res.headers };
        if (isGet && opts.cache) cache[ckey] = { t: Date.now(), v: out };
        return out;
      });
    } else {
      var init = { method: method, headers: headers, mode: 'cors', credentials: 'omit' };
      if (body) init.body = body;
      p = fetch(url, init).then(function (r) {
        return r.text().then(function (txt) {
          var res = { status: r.status, body: txt, headers: { link: r.headers.get('Link'), 'x-ratelimit-remaining': r.headers.get('X-RateLimit-Remaining') } };
          var data = parseJSON(res);
          if (r.status >= 400) throw makeError(res, data);
          var out = { data: data, status: r.status, link: parseLink(res.headers.link), headers: res.headers };
          if (isGet && opts.cache) cache[ckey] = { t: Date.now(), v: out };
          return out;
        });
      });
    }
    if (isGet) {
      inflight[ckey] = p;
      var clear = function () { delete inflight[ckey]; };
      p.then(clear, clear);
    }
    return p;
  }

  var API = {
    base: API_BASE,
    get: function (p, params, opts) { return req('GET', p, Object.assign({ params: params }, opts)); },
    post: function (p, body, opts) { return req('POST', p, Object.assign({ body: body }, opts)); },
    patch: function (p, body, opts) { return req('PATCH', p, Object.assign({ body: body }, opts)); },
    put: function (p, body, opts) { return req('PUT', p, Object.assign({ body: body }, opts)); },
    del: function (p, body, opts) { return req('DELETE', p, Object.assign({ body: body === undefined ? {} : body }, opts)); },

    /** 自动翻页拉取，返回合并后的数组 */
    paged: function (path, params, maxPages, opts) {
      maxPages = maxPages || 1;
      params = Object.assign({}, params, { per_page: params && params.per_page || 30 });
      var all = [], last = null, page = 1;
      function next() {
        return req('GET', path, Object.assign({ params: Object.assign({}, params, { page: page }) }, opts))
          .then(function (res) {
            var d = res.data;
            if (Array.isArray(d)) all = all.concat(d); else { all.push(d); return { data: all, done: true, link: res.link }; }
            last = res;
            if (res.link && res.link.next && page < maxPages) { page++; return next(); }
            return { data: all, done: !res.link || !res.link.next, link: res.link };
          });
      }
      return next();
    },

    /** 纯文本（raw / diff / 日志） */
    text: function (path, accept, opts) {
      return req('GET', path, Object.assign({ accept: accept || 'application/vnd.github.raw' }, opts))
        .then(function (res) { return res.data && res.data.raw !== undefined ? res.data.raw : null; });
    },

    clearCache: function () { cache = Object.create(null); },

    /* ---- 常用业务端点 ---- */
    me: function () { return this.get('/user', null, { cache: 60000 }); },
    rateLimit: function () { return this.get('/rate_limit', null, { cache: 5000 }); },

    /**
     * 应用版本号（原生 BuildConfig.VERSION_NAME）。
     * 失败时返回空串 —— 绝不能返回 '0.0.0' 之类的假版本号，
     * 否则更新检测会把「读不到版本」误判成「从 0.0.0 大版本升级」而强制更新。
     */
    appVersion: function () {
      return window.Native ? window.Native.appVersion() : '';
    }
  };

  window.API = API;

  /* ---------- 通用小工具（供全局使用） ---------- */
  window.Util = {
    esc: function (s) {
      return String(s === undefined || s === null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    },
    num: function (n) {
      if (n === undefined || n === null) return '0';
      n = +n;
      if (n >= 1000000) return (n / 1000000).toFixed(1).replace(/\.0$/, '') + 'm';
      if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
      return String(n);
    },
    timeAgo: function (iso) {
      if (!iso) return '';
      var t = Date.parse(iso);
      if (isNaN(t)) return '';
      var s = Math.floor((Date.now() - t) / 1000);
      if (s < 45) return '刚刚';
      if (s < 3600) return Math.floor(s / 60) + ' 分钟前';
      if (s < 86400) return Math.floor(s / 3600) + ' 小时前';
      if (s < 2592000) return Math.floor(s / 86400) + ' 天前';
      if (s < 31536000) return Math.floor(s / 2592000) + ' 个月前';
      return Math.floor(s / 31536000) + ' 年前';
    },
    date: function (iso) {
      var d = new Date(iso);
      if (isNaN(d)) return '';
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    },
    bytes: function (n) {
      if (n === undefined || n === null) return '';
      var u = ['B', 'KB', 'MB', 'GB'], i = 0;
      n = +n;
      while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
      return (i === 0 ? n : n.toFixed(1)) + ' ' + u[i];
    },
    /** 标签文字颜色自适应 */
    labelStyle: function (color) {
      color = (color || 'ededed').replace('#', '');
      var r = parseInt(color.substring(0, 2), 16) || 0;
      var g = parseInt(color.substring(2, 4), 16) || 0;
      var b = parseInt(color.substring(4, 6), 16) || 0;
      var lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
      var isDark = document.documentElement.getAttribute('data-theme') === 'dark';
      if (isDark) {
        return 'background:#' + color + '33;color:rgb(' + Math.min(r + 90, 235) + ',' + Math.min(g + 90, 235) + ',' + Math.min(b + 90, 235) + ');border-color:#' + color + '66';
      }
      return 'background:#' + color + ';color:' + (lum > 0.6 ? '#1f2328' : '#ffffff');
    },
    langColor: function (lang) {
      var M = {
        JavaScript: '#f1e05a', TypeScript: '#3178c6', Python: '#3572A5', Java: '#b07219', Kotlin: '#A97BFF',
        Go: '#00ADD8', Rust: '#dea584', 'C++': '#f34b7d', C: '#555555', 'C#': '#178600', Ruby: '#701516',
        PHP: '#4F5D95', Swift: '#F05138', Dart: '#00B4AB', HTML: '#e34c26', CSS: '#563d7c', SCSS: '#c6538c',
        Vue: '#41b883', Shell: '#89e051', Lua: '#000080', Scala: '#c22d40', Perl: '#0298c3', R: '#198CE7',
        'Objective-C': '#438eff', Dockerfile: '#384d54', Makefile: '#427819', 'Jupyter Notebook': '#DA5B0B',
        Elixir: '#6e4a7e', Haskell: '#5e5086', Julia: '#a270ba', Zig: '#ec915c', Vue3: '#41b883',
        'Vim Script': '#199f4b', PowerShell: '#012456', Assembly: '#6E4C13', Nix: '#7e7eff'
      };
      return M[lang] || '#8b949e';
    },
    debounce: function (fn, ms) {
      var t; return function () { var a = arguments, s = this; clearTimeout(t); t = setTimeout(function () { fn.apply(s, a); }, ms || 250); };
    },
    decodeBase64: function (b64) {
      try {
        b64 = (b64 || '').replace(/\s/g, '');
        var bin = atob(b64);
        var bytes = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new TextDecoder('utf-8').decode(bytes);
      } catch (e) { return ''; }
    }
  };
})();
