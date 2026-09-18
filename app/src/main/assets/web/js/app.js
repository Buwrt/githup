/* ============================================================
 * app.js — 路由、主题、导航、通知轮询与启动流程
 * ============================================================ */
(function () {
  'use strict';
  var U = window.Util, UI = window.UI, P = window.Pages;

  var RESERVED = ['login', 'notifications', 'explore', 'search', 'settings', 'downloads', 'gists', 'gist', 'issues', 'pulls', 'orgs', 'topics', 'apps', 'sponsors', 'collections', 'trending', 'events', 'marketplace', 'about', 'profile'];

  /* 右上角「下载管理」入口的当前按钮实例（角标刷新用；不在该页时为 null） */
  var dlBtn = null;

  var App = {
    pageCache: Object.create(null),
    cacheGet: function (k) { return this.pageCache[k]; },
    cacheSet: function (k, v) { this.pageCache[k] = v; },
    clearPageCache: function () { this.pageCache = Object.create(null); },
    invalidate: function (prefix) {
      Object.keys(this.pageCache).forEach(function (k) {
        if (k.indexOf(prefix) === 0) delete App.pageCache[k];
      });
    },

    title: function (t, sub) {
      var el = document.getElementById('appbar-title');
      el.innerHTML = U.esc(t) + (sub ? ' <span class="muted" style="font-weight:400;font-size:12px">' + U.esc(sub) + '</span>' : '');
    },

    setActions: function (list) {
      var box = document.getElementById('appbar-actions');
      box.innerHTML = '';
      (list || []).forEach(function (a) {
        var b = document.createElement('button');
        b.className = 'icon-btn';
        b.innerHTML = window.icon(a.icon, 20);
        b.onclick = a.onClick;
        box.appendChild(b);
      });
      App.appendDownloadAction();
    },

    /**
     * 右上角常驻的「下载管理」入口。
     *
     * 必须挂在 setActions 末尾而不是让页面自己加：路由每次渲染都会先
     * setActions([]) 清空，页面自己的菜单又是异步挂上去的 —— 只有在这里
     * 兜底追加，才能保证任何页面（含异步渲染完的页面）右上角都有它。
     */
    appendDownloadAction: function () {
      if (!(window.NativeBridge && typeof window.NativeBridge.downloadStatus === 'function')) return;
      var box = document.getElementById('appbar-actions');
      if (!box) return;
      var h = (location.hash || '').replace(/^#/, '').split('?')[0];
      if (h === '/downloads') { dlBtn = null; return; }   // 已经在下载页，不必再给入口
      var b = document.createElement('button');
      b.className = 'icon-btn dl-action';
      b.setAttribute('aria-label', '下载管理');
      b.innerHTML = window.icon('download', 20) + '<i class="dl-badge" hidden></i>';
      b.onclick = function () { Router.go('/downloads'); };
      box.appendChild(b);
      dlBtn = b;
      App.refreshDownloadBadge();
    },

    /** 右上角下载入口的角标：还在跑的任务数（成功/失败的不算） */
    refreshDownloadBadge: function () {
      if (!dlBtn) return;
      var badge = dlBtn.querySelector('.dl-badge');
      if (!badge) return;
      var n = 0;
      try {
        var list = JSON.parse(window.NativeBridge.downloadStatus() || '[]') || [];
        n = list.filter(function (t) {
          return t.status !== 8 && t.status !== 16;   // 8=成功 16=失败
        }).length;
      } catch (e) { return; }
      if (n > 0) { badge.hidden = false; badge.textContent = n > 9 ? '9+' : String(n); }
      else badge.hidden = true;
    },

    applyTheme: function () {
      var s = window.Store.load();
      var t = s.theme || 'auto';
      if (t === 'auto') {
        t = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
      }
      document.documentElement.setAttribute('data-theme', t);
      var meta = document.querySelector('meta[name="theme-color"]');
      if (meta) meta.setAttribute('content', t === 'dark' ? '#010409' : '#ffffff');
      var l = document.getElementById('hljs-light'), d = document.getElementById('hljs-dark');
      if (l && d) { l.disabled = t === 'dark'; d.disabled = t !== 'dark'; }
      try {
        if (window.NativeBridge && NativeBridge.setStatusBar) {
          NativeBridge.setStatusBar(t === 'dark' ? '#010409' : '#ffffff');
        }
      } catch (e) {}
    },

    showBack: function (show) {
      var b = document.getElementById('btn-back');
      b.hidden = !show;
      if (show && !b.innerHTML) b.innerHTML = window.icon('arrow-left', 22);
      // 返回按钮与系统返回键走同一套逻辑，保证行为一致
      if (show) b.onclick = function () { App.handleBack(); };
    },

    /**
     * 统一的返回处理，返回 true 表示「已消费」（调用方不应再退出应用）。
     *
     * 优先级（从内到外，符合用户直觉）：
     *   1. 图片查看器（全屏覆盖）
     *   2. 打开的弹层 / 确认框 / 菜单（最新打开的最先关）
     *   3. 页面内的返回按钮（如搜索页回到上一层）
     *   4. 路由历史回退
     */
    handleBack: function () {
      // 1) 图片查看器
      var viewer = document.getElementById('viewer-root');
      if (viewer && viewer.classList.contains('show')) {
        if (window.UI && UI.closeViewer) { UI.closeViewer(); return true; }
        viewer.classList.remove('show');
        viewer.innerHTML = '';
        return true;
      }

      // 2) 弹层
      var sheet = document.getElementById('sheet-root');
      if (sheet && sheet.classList.contains('show')) {
        // 强制更新这类弹层标记为不可关闭：吞掉返回键，不给绕过的路径
        if (sheet.dataset.lock === '1') return true;
        if (window.UI && UI.closeSheet) UI.closeSheet();
        return true;
      }

      // 3) 路由历史回退（URL 变化驱动渲染）
      if (Router.canGoBack()) {
        history.back();
        return true;
      }
      return false;
    },

    setTab: function (name) {
      UI.$$('#tabbar .tab').forEach(function (t) { t.classList.toggle('active', t.getAttribute('data-tab') === name); });
    },

    updateBadge: function (n) {
      var b = document.getElementById('tab-badge');
      if (n === undefined) return;
      if (n > 0) { b.hidden = false; b.textContent = n > 99 ? '99+' : n; }
      else b.hidden = true;
    },

    refreshBadge: function () {
      if (!window.Session.isLogin) { this.updateBadge(0); return Promise.resolve(); }
      return window.API.get('/notifications', { per_page: 1, all: 'false' }).then(function (r) {
        var h = r.headers || {};
        var rem = h['x-ratelimit-remaining'];
        var link = r.link && r.link.last ? r.link.last : null;
        // 未读数用列表长度估算（未读视图）
        var n = (r.data || []).length;
        App.updateBadge(n > 0 ? n : 0);
      }).catch(function () {});
    }
  };
  window.App = App;

  /* ---------------- 路由解析 ---------------- */
  function parseHash(hash) {
    var raw = (hash || '').replace(/^#/, '');
    if (!raw || raw === '/') return { name: 'feed', ctx: {} };
    var qi = raw.indexOf('?');
    var query = {};
    if (qi >= 0) {
      raw.substring(qi + 1).split('&').forEach(function (kv) {
        if (!kv) return;
        var p = kv.split('=');
        query[decodeURIComponent(p[0])] = decodeURIComponent((p[1] || '').replace(/\+/g, ' '));
      });
      raw = raw.substring(0, qi);
    }
    var segs = raw.split('/').filter(function (s) { return s !== ''; }).map(function (s) {
      try { return decodeURIComponent(s); } catch (e) { return s; }
    });
    var first = (segs[0] || '').toLowerCase();

    if (segs.length === 1) {
      if (RESERVED.indexOf(first) < 0) return { name: 'user', ctx: { login: segs[0], query: query } };
      if (first === 'gist') return null;
    }
    switch (first) {
      case 'login': return { name: 'login', ctx: { query: query } };
      case 'notifications': return { name: 'notifications', ctx: { query: query } };
      case 'explore': return { name: 'explore', ctx: { query: query } };
      case 'search': return { name: 'search', ctx: { query: query } };
      case 'settings': return { name: 'settings', ctx: { query: query } };
      case 'downloads': return { name: 'downloads', ctx: { query: query } };
      case 'profile': return { name: 'profile', ctx: { query: query } };
      case 'gists': return { name: 'gists', ctx: { query: query } };
      case 'gist': return { name: 'gist', ctx: { id: segs[1], query: query } };
      case 'issues': return { name: 'issuesMine', ctx: { query: query } };
      case 'pulls': return { name: 'pullsMine', ctx: { query: query } };
    }

    if (segs.length >= 2) {
      var ctx = { owner: segs[0], repo: segs[1], query: query, tab: 'code' };
      if (segs.length === 2) return { name: 'repo', ctx: ctx };
      var sub = segs[2];
      if (sub === 'tree' || sub === 'blob') {
        ctx.kind = sub;
        ctx.ref = segs[3] || '';
        ctx.path = segs.slice(4).join('/');
        if (sub === 'blob' && !segs[4]) { ctx.kind = 'tree'; ctx.path = ''; }
        return { name: 'repo', ctx: ctx };
      }
      if (sub === 'issues' && segs[3]) return { name: 'issue', ctx: Object.assign(ctx, { tab: 'issues', number: segs[3] }) };
      if (sub === 'pull' && segs[3]) return { name: 'issue', ctx: Object.assign(ctx, { tab: 'pulls', number: segs[3], isPR: true }) };
      if (sub === 'actions' && segs[3]) return { name: 'run', ctx: Object.assign(ctx, { tab: 'actions', id: segs[3] }) };
      if (sub === 'releases' && segs[3]) return { name: 'release', ctx: Object.assign(ctx, { tab: 'releases', tag: segs[3] }) };
      if (sub === 'commit' && segs[3]) return { name: 'commit', ctx: Object.assign(ctx, { tab: 'commits', sha: segs[3] }) };
      var allow = ['issues', 'pulls', 'actions', 'releases', 'commits', 'contributors', 'branches', 'tags', 'settings', 'stargazers', 'watchers', 'forks'];
      if (allow.indexOf(sub) >= 0) { ctx.tab = sub; return { name: 'repo', ctx: ctx }; }
      return { name: 'repo', ctx: ctx };
    }
    return { name: 'feed', ctx: {} };
  }

  /* ---------------- 路由控制 ---------------- */
  // 标签根页面：这些是「顶层」，从它们再返回应当退出应用而不是继续回退
  var TAB_ROOTS = ['/notifications', '/explore', '/search', '/downloads', '/profile', '/login'];

  /**
   * 应用内路由深度。
   * 不从 history.length 推断（那个值包含应用外的历史），而是自己计数：
   * pushState 前进 +1，popstate / replace 回退 -1，下限 0。
   */
  var routeDepth = 0;

  /**
   * 统一的「向前导航」：写入历史栈并渲染。
   * 用 pushState 而不是 location.hash —— 后者在部分 WebView 下与
   * goBack() 配合不稳定（回退后 hash 与历史条目对不上，页面不刷新）。
   */
  function pushRoute(path) {
    var target = '#' + path;
    var base = location.pathname + location.search;
    // 已经在目标页：原地重渲染即可，不要再堆一条历史
    if (location.hash === target) { Router.render(); return; }

    /*
      标签根页面之间横向切换：仍然 push（这样能按返回回到上一个标签），
      但不增加「深度计数」—— 深度只用来判断「还能不能返回」，
      横向切标签属于同一层级，不该让用户为了退出应用而连按七八次返回。
      于是：可回退，但退到第一个标签页后就是栈底。
    */
    // 横向 = 目标是标签根页，且当前也在标签根页（同层级切换）
    var isLateral = Router.isRoot(path) && Router.isRoot(location.hash || '#/');
    if (!isLateral) routeDepth++;
    try {
      if (window.history && history.pushState) {
        history.pushState({ ghRoute: true, depth: routeDepth, lateral: isLateral },
            '', base + target);
        Router.render();
        return;
      }
    } catch (e) { /* 降级 */ }
    location.hash = target;
    setTimeout(function () {
      if (location.hash === target && Router.current !== target) Router.render();
    }, 30);
  }

  var Router = {
    current: null,
    /* 每个路由的滚动位置。返回时按路由还原，避免「点进详情再返回回到顶部」*/
    scrollMemo: Object.create(null),
    backNav: false,          // 本次 render 是不是「回退」触发的
    go: function (path) {
      path = path || '/';
      if (path.charAt(0) !== '/') path = '/' + path;
      var target = '#' + path;
      if (location.hash === target) { this.reload(); return; }
      /*
        注意：这里不能对标签根页面用 location.replace。
        replace 会「替换」当前历史条目而不是新增，导致从某个详情页点首页后
        再按返回键直接退出应用（回不到原来的详情页）。
        统一用 pushState：既产生历史条目，又能自定义状态。
      */
      pushRoute(path);
    },
    /** 替换当前历史条目（登录跳转、退出登录等「不该回退」的场景用） */
    replace: function (path) {
      path = path || '/';
      if (path.charAt(0) !== '/') path = '/' + path;
      var target = '#' + path;
      if (location.hash === target) { this.render(); return; }
      var base = location.pathname + location.search;
      if (window.history && history.replaceState) {
        // replace 不改变层级：登录成功等场景要「抹掉」上一页而不是堆叠
        history.replaceState({ ghRoute: true, replace: true, depth: routeDepth }, '', base + target);
        Router.render();
      } else {
        location.replace(target);
        setTimeout(function () { Router.render(); }, 30);
      }
    },
    reload: function () { this.render(); },
    /** 当前路由是否属于「标签根页面」（首页/通知/探索/搜索/我的/登录） */
    isRoot: function (path) {
      var p = (path || '').replace(/^#/, '').split('?')[0];
      return p === '' || p === '/' || TAB_ROOTS.indexOf(p) >= 0;
    },

    /**
     * 是否还有可回退的上一层。
     * 不用 history.length —— 它包含进入应用之前的外部历史，会导致在首页
     * 误判为「还能返回」，按了返回键却是退出应用。
     * 深度 > 0 表示有纵向层级；深度为 0 但当前不在起始页（横向切过标签）
     * 时也应该允许回退到起始页。
     */
    canGoBack: function () {
      if (routeDepth > 0) return true;
      var now = (location.hash || '#/').replace(/^#/, '').split('?')[0];
      return now !== '' && now !== '/' && this.isRoot(now);
    },
    render: function () {
      var hash = location.hash || '#/';
      // 离开上一页前记下滚动位置，回退时要还原（议题 #2）
      var prev = document.getElementById('view');
      if (prev && Router.current && Router.current !== hash) {
        Router.scrollMemo[Router.current] = prev.scrollTop;
      }
      Router.current = hash;
      var r = parseHash(hash);
      if (!r) { location.hash = '#/'; return; }
      var page = P[r.name] || P.feed;
      var host = document.getElementById('view');
      var restore = Router.backNav ? (Router.scrollMemo[hash] || 0) : 0;
      host.innerHTML = '';
      host.scrollTop = 0;
      App.setActions([]);
      document.getElementById('fab').hidden = true;

      var noTab = !!page.noTab;
      document.getElementById('tabbar').hidden = noTab;
      host.classList.toggle('no-tabbar', noTab);
      App.showBack(!noTab && !(page.tab) && hash !== '#/' && !isTabPath(hash));

      var title = typeof page.title === 'function' ? page.title(r.ctx) : (page.title || 'githup');
      var sub = typeof page.subtitle === 'function' ? page.subtitle(r.ctx) : page.subtitle;
      App.title(title, sub);
      App.setTab(page.tab || '');
      if (page.menu) {
        App.setActions([{
          icon: 'kebab-horizontal', onClick: function () {
            UI.menu('菜单', page.menu()).then(function (k) { if (k && page.onMenu) page.onMenu(k); });
          }
        }]);
      }

      UI.loading(true);
      var done = function () {
        UI.loading(false);
        if (restore) requestAnimationFrame(applyRestore);
      };
      /*
        还原滚动位置：页面内容可能是异步画出来的，所以先同步来一次
        （搜索结果这类有缓存、同步出内容的页面立刻就位），
        内容撑开后再来一次，避免高度不够被夹回 0。
      */
      function applyRestore() { host.scrollTop = restore; }
      try {
        var ret = page.render(r.ctx, host);
        if (restore) applyRestore();
        if (ret && ret.then) ret.then(done, done); else done();
      } catch (e) {
        done();
        host.innerHTML = UI.errorBox(e);
      }
      var path = hash.substring(1);
      var qi = path.indexOf('?');
      if (qi > 0) path = path.substring(0, qi);
      window.Store.set('lastPath', path);
    }
  };
  window.Router = Router;

  function isTabPath(hash) {
    var h = (hash || '').replace(/^#/, '').split('?')[0];
    return h === '/' || h === '/notifications' || h === '/explore' || h === '/search' || h === '/profile';
  }

  /* ---------------- 底部导航 ---------------- */
  UI.$$('#tabbar .tab').forEach(function (t) {
    t.onclick = function () {
      var name = t.getAttribute('data-tab');
      var map = { home: '/', notifications: '/notifications', explore: '/explore', search: '/search', profile: '/profile' };
      Router.go(map[name]);
    };
  });

  /* ---------------- 启动 ---------------- */
  /**
   * 清掉历史版本遗留在 localStorage 里的明文令牌。
   *
   * 早期版本把令牌用明文存在 gh_token / gh_saved_token 两个键里。
   * 现在虽然不写了，但老用户升级上来之后那两个键还在原地躺着 ——
   * 必须主动抹掉，否则修了等于没修。
   */
  function purgeLegacyToken() {
    try {
      ['gh_token', 'gh_saved_token'].forEach(function (k) {
        if (localStorage.getItem(k) !== null) localStorage.removeItem(k);
      });
    } catch (e) {}
  }

  function start() {
    purgeLegacyToken();
    window.iconFill();
    App.applyTheme();
    if (window.matchMedia) {
      var mq = window.matchMedia('(prefers-color-scheme: dark)');
      if (mq.addEventListener) mq.addEventListener('change', function () { if (window.Store.get('theme') === 'auto') App.applyTheme(); });
    }

    var token = window.Session.init();
    var boot = function () {
      document.getElementById('splash').remove();
      document.getElementById('app').hidden = false;
      var hash = location.hash;
      if (!hash || hash === '#' || hash === '#/') {
        var startTab = window.Store.get('startTab') || 'home';
        var map = { home: '/', notifications: '/notifications', explore: '/explore', profile: '/profile' };
        if (startTab !== 'home' && window.Session.isLogin) location.replace('#' + map[startTab]);
        else if (startTab !== 'home') location.replace('#/');
      }
      Router.render();
      App.refreshBadge();
      setInterval(function () { App.refreshBadge(); }, 120000);
      /* 右上角下载入口的角标：2 秒一刷（没入口时函数自己立刻返回，不打扰） */
      setInterval(function () { App.refreshDownloadBadge(); }, 2000);

      /*
        打开软件的瞬间就检查更新 —— 不等延时、不等界面渲染完。
          有新版本：第一位变化弹不可关闭的强制更新，第二、三位给可选按钮
          已是最新：什么都不做，跟没检查过一样
          断网或取不到：静默，不打扰
        每次打开都会真的去查一次，所以新版本随时能在下次打开时被发现。
      */
      try { if (window.Updater) window.Updater.startCheck(); } catch (e) { /* 检查失败不影响使用 */ }

      /*
        从后台切回前台时再查一次（离上次超过 30 分钟才算）。
        很多人是关屏再亮、切走再回来的，这时候也该有机会发现新版本。
      */
      try {
        document.addEventListener('visibilitychange', function () {
          if (!document.hidden && window.Updater) {
            try { window.Updater.resumeCheck(); } catch (e) { /* 忽略 */ }
          }
        });
      } catch (e) { /* 忽略 */ }
    };

    if (token) {
      window.API.me().then(function (r) {
        window.Session.user = r.data;
        boot();
      }).catch(function (e) {
        if (e.status === 401) {
          window.Session.clear();
          UI.toast('登录状态已失效，请重新登录');
        }
        boot();
      });
    } else boot();

    window.addEventListener('hashchange', function () {
      /*
        回退时 popstate 已经渲染过（并且还原了滚动位置），这里再渲染一次
        会把刚还原的位置冲掉 —— hash 没变就跳过。
      */
      if ((location.hash || '#/') === Router.current) return;
      Router.backNav = true;      // 单独由 hashchange 触发的，多半也是前进/后退
      Router.render();
      Router.backNav = false;
    });

    /*
      浏览器/系统返回键的回退入口。
      用 popstate 而不是只靠 hashchange：pushState 产生的条目不会触发
      hashchange，只有 popstate 能捕获回退，并用 state.depth 还原层级。
    */
    window.addEventListener('popstate', function (e) {
      var d = e && e.state && typeof e.state.depth === 'number' ? e.state.depth : null;
      if (d !== null) routeDepth = Math.max(0, d);
      else routeDepth = Math.max(0, routeDepth - 1);
      Router.backNav = true;
      Router.render();
      Router.backNav = false;
    });

    // 下拉刷新（顶部下拉手势）
    var view = document.getElementById('view');
    var startY = 0, pulling = false;
    view.addEventListener('touchstart', function (e) {
      if (view.scrollTop <= 0) { startY = e.touches[0].clientY; pulling = true; }
    }, { passive: true });
    view.addEventListener('touchmove', function (e) {
      if (!pulling) return;
      var dy = e.touches[0].clientY - startY;
      if (dy > 70 && view.scrollTop <= 0) { pulling = false; UI.haptic(); Router.reload(); }
    }, { passive: true });
    view.addEventListener('touchend', function () { pulling = false; }, { passive: true });

    // 兜底委托：任何带 data-go / data-p 的元素都能导航
    // 用标记位去重，已单独绑定 onclick 的元素不会重复触发
    document.addEventListener('click', function (e) {
      var t = e.target;
      if (!t || !t.closest) return;
      var hit = t.closest('[data-go],[data-p]');
      if (!hit || hit.__bound) return;
      var dest = hit.getAttribute('data-go') || hit.getAttribute('data-p');
      if (dest) { e.preventDefault(); window.Router.go(dest); }
    }, true);

    // 外链统一交给系统浏览器
    document.addEventListener('click', function (e) {
      var a = e.target && e.target.closest ? e.target.closest('a[target="_blank"]') : null;
      if (!a) return;
      e.preventDefault();
      var href = a.getAttribute('href') || '';
      if (/^(mailto:|tel:|https?:)/i.test(href)) {
        if (window.NativeBridge && NativeBridge.openExternal) NativeBridge.openExternal(href);
        else window.open(href, '_blank');
      }
    }, true);

    // 原生侧触发的刷新/返回
    window.AppOnResume = function () { App.refreshBadge(); };

    initKeyboardAware();
  }

  /**
   * 键盘感知：把键盘占用的高度写入 CSS 变量 --kb，并同步 --vvh（可视视口高度）。
   * 底部弹层据此抬到键盘之上，保证表单输入框始终可见。
   *
   * 关键点：这里不能依赖「窗口变小」来判断键盘，因为 WebView 在 adjustPan 下
   * 窗口尺寸不变；统一用 visualViewport 与 layout viewport 的高度差来算。
   */
  function initKeyboardAware() {
    var vv = window.visualViewport;
    var root = document.documentElement;
    var raf = 0;

    function measure() {
      var layoutH = window.innerHeight || root.clientHeight || 0;
      var vvH = (vv && vv.height) ? vv.height : layoutH;
      var kb = Math.max(0, Math.round(layoutH - vvH));
      // 小于一个键盘的最小高度时视为没弹出，避免地址栏收缩等噪声
      if (kb < 120) kb = 0;
      var prev = root.style.getPropertyValue('--kb');
      var next = kb + 'px';
      if (prev !== next) root.style.setProperty('--kb', next);
      root.style.setProperty('--vvh', Math.round(vvH) + 'px');
      root.classList.toggle('kb-open', kb > 0);

      // 键盘弹出后，把当前聚焦的输入框滚进可视区域
      if (kb > 0) {
        var ae = document.activeElement;
        if (ae && /^(INPUT|TEXTAREA|SELECT)$/.test(ae.tagName)) {
          setTimeout(function () {
            try { ae.scrollIntoView({ block: 'center', behavior: 'smooth' }); } catch (e) { ae.scrollIntoView(); }
          }, 60);
        }
        // 焦点不在输入框（例如在按钮上触发）时，把弹层里第一个被键盘遮住的控件滚出来
        ensureSheetVisible();
      }
    }

    /**
     * 弹层内容可能在视口之外（例如底部弹层里的靠下字段）。
     * 键盘弹出后，把第一个落在键盘线以下的表单控件滚进可视区。
     */
    function sheetBottomLimit() {
      return window.innerHeight - (parseInt(root.style.getPropertyValue('--kb'), 10) || 0);
    }

    function ensureSheetVisible() {
      setTimeout(function () {
        var sheetRoot = document.getElementById('sheet-root');
        if (!sheetRoot || !sheetRoot.classList.contains('show')) return;
        var body = sheetRoot.querySelector('.sheet-body');
        if (!body) return;
        var limit = sheetBottomLimit();
        var bodyRect = body.getBoundingClientRect();
        // 点击目标必须是「弹层内可见区域底部」与「键盘线」中的较小值
        var visibleBottom = Math.min(limit, bodyRect.bottom);
        var nodes = body.querySelectorAll('input, textarea, select, .upload-box');
        for (var i = 0; i < nodes.length; i++) {
          var r = nodes[i].getBoundingClientRect();
          if (r.height > 0 && r.bottom > visibleBottom - 8) {
            // 需要往下滚的距离 = 目标底边超出可见底部的部分 + 一点余量
            var delta = r.bottom - visibleBottom + 16;
            var max = Math.max(0, body.scrollHeight - body.clientHeight);
            body.scrollTop = Math.min(max, body.scrollTop + delta);
            // 滚动到底仍露不出来（内容比可视区还高很多）时，用块居中兜底
            if (nodes[i].getBoundingClientRect().bottom > visibleBottom) {
              try { nodes[i].scrollIntoView({ block: 'center' }); } catch (e) {}
            }
            return;
          }
        }
      }, 120);
    }

    function schedule() {
      if (raf) return;
      raf = requestAnimationFrame(function () { raf = 0; measure(); });
    }

    if (vv) {
      vv.addEventListener('resize', schedule);
      vv.addEventListener('scroll', schedule);
    }
    window.addEventListener('resize', schedule);
    window.addEventListener('orientationchange', schedule);

    // 输入框获得焦点时也要重新测量（部分机型键盘动画结束才更新尺寸）
    document.addEventListener('focusin', function () { schedule(); setTimeout(schedule, 260); }, true);

    // 弹层打开时重置一次，避免上一个页面的键盘状态残留
    window.addEventListener('hashchange', function () { root.style.setProperty('--kb', '0px'); });

    measure();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
