/* ============================================================
 * onboarding.js — 新手指导（游戏式高亮引导）
 * ------------------------------------------------------------
 * 设计要点：
 *
 * 1. 「挖洞」遮罩
 *    不用 SVG mask、也不用 clip-path —— 一个透明的 div 配一圈
 *    `box-shadow: 0 0 0 9999px` 就是遮罩，洞里能看到真实界面。
 *    老 WebView 也认 box-shadow，clip-path 在部分 ROM 上会锯齿。
 *
 * 2. 每一步自己声明在哪一页、高亮谁
 *    跨页的步骤会先 Router.go(route)，再轮询等目标元素出现。
 *    页面是异步渲染的（骨架同步、内容异步），所以必须轮询，
 *    直接 querySelector 会拿到 null。
 *
 * 3. 拿不到目标就退化成居中弹窗
 *    任何一个环节出问题（接口挂了、页面结构变了、选择器写错），
 *    都不能把用户卡在遮罩里。超时 2.5 秒直接当「没有目标」继续走。
 *
 * 4. 随时可跳过
 *    右上角常驻「跳过」，系统返回键也等同跳过（在 App.handleBack 里挂的）。
 *    跳过和看完都会记状态，下次不再自动弹；设置里可以手动重看。
 * ============================================================ */
(function () {
  'use strict';

  var KEY = 'onboarded';      // Store 里的键
  var VERSION = 1;            // 以后改引导内容就 +1，老用户也会重新看到

  /* ---------------- 步骤表 ----------------
     route   这一步要先跳到哪个页面（不填 = 停在当前页）
     target  要高亮的选择器（不填 = 居中弹窗，没有洞）
     round   true 强制用圆形洞（图标按钮这类） */
  var STEPS = [
    {
      title: '欢迎使用 githup',
      body: '把 GitHub 装进口袋：看动态、收通知、搜仓库、逛趋势，都在这一个 App 里。' +
            '<br>下面用 10 步带你把主要界面走一遍，<b>随时可以点右上角「跳过」</b>。'
    },
    {
      title: '顶栏：你在哪',
      body: '左边是当前页面的标题。进入二级页面时，最左边会多出一个返回箭头。' +
            '<br>这一栏永远停在屏幕顶部，内容滚动时它不动。',
      target: '#appbar'
    },
    {
      title: '右上角：菜单和下载',
      body: '三个点的按钮是当前页的<b>菜单</b>（比如通知页的「全部标为已读」）。' +
            '<br>旁边的收纳图标是<b>下载管理</b>，正在下载的 Release 包在这里看进度。',
      target: '#appbar-actions'
    },
    {
      title: '底栏：五个主入口',
      body: '<b>首页</b>看关注的动态，<b>通知</b>收 GitHub 消息，<b>探索</b>逛趋势榜，' +
            '<b>搜索</b>找仓库/用户/代码，<b>我的</b>是你的主页和设置。' +
            '<br>在这枚胶囊上左右拖动能跟手切换。',
      target: '#tabbar'
    },
    {
      title: '搜索：从关键词开始',
      body: '输入关键词，点右边的<b>搜索</b>（或按回车）。' +
            '<br>框里的 × 是一键清空；下方的<b>排序</b>和<b>筛选</b>可以按语言、Star 数再收窄结果。',
      route: '/search',
      target: '.search-bar'
    },
    {
      title: '搜索：先选类型再搜',
      body: '这几个标签决定你搜的是<b>仓库</b>、<b>用户</b>、<b>代码</b>还是<b>议题</b>。' +
            '<br>先选类型再搜，命中率会高很多。',
      route: '/search',
      target: '#tabs'
    },
    {
      title: '探索：时间范围',
      body: '趋势榜的时间范围在这里切：<b>今日 / 本周 / 本月</b>。' +
            '<br>它筛的是「这段时间内新创建的仓库」，所以和 Star 总数榜不是一回事。',
      route: '/explore',
      target: '.seg-wrap'
    },
    {
      title: '探索：按语言筛选',
      body: '只想看你用的语言？点这里的语言标签，趋势榜立刻只剩那一种。' +
            '<br>这排标签可以左右滑，语言挺多的。',
      route: '/explore',
      target: '#langs'
    },
    {
      title: '设置：界面风格你说了算',
      body: '这个开关是<b>液态玻璃底栏</b>：开启时底栏是悬浮的磨砂胶囊（默认开），' +
            '关掉就回到朴素样式。<b>机型较老、觉得卡</b>的时候可以关掉它。',
      route: '/settings',
      target: '[data-s="glass"]'
    },
    {
      title: '就这些，开始用吧',
      body: '想再看一遍这段引导，随时来 <b>设置 → 新手指导</b>。' +
            '<br>登录后还能收通知、评论 Issue、Star 仓库——去「我的」页面登录即可。'
    }
  ];

  var S = { on: false, i: 0, startHash: '', waitTimer: null, lastSel: '' };
  var D = null;   // DOM 引用，ensureDom() 里建

  /* ---------------- DOM ---------------- */
  function ensureDom() {
    if (D) return D;
    var root = document.createElement('div');
    root.id = 'ob-root';
    /* 翻译脚本会遍历文本节点往外发请求，引导文案本来就是中文，
       标上 data-no-translate 免得白跑一趟（translate.js 的 SKIP_SEL 认这个）。 */
    root.setAttribute('data-no-translate', '1');
    root.innerHTML =
      '<div id="ob-hole"></div>' +
      '<div id="ob-ring"></div>' +
      '<button id="ob-skip" type="button">跳过</button>' +
      '<div id="ob-tip">' +
        '<div id="ob-step"><span id="ob-step-text"></span><span id="ob-step-num"></span></div>' +
        '<div id="ob-progress"><i></i></div>' +
        '<div id="ob-title"></div>' +
        '<div id="ob-body"></div>' +
        '<div id="ob-actions">' +
          '<button class="ob-btn" id="ob-prev" type="button" hidden>上一步</button>' +
          '<button class="ob-btn" id="ob-next" type="button">下一步</button>' +
        '</div>' +
      '</div>';
    document.body.appendChild(root);

    D = {
      root: root,
      hole: root.querySelector('#ob-hole'),
      ring: root.querySelector('#ob-ring'),
      tip: root.querySelector('#ob-tip'),
      title: root.querySelector('#ob-title'),
      body: root.querySelector('#ob-body'),
      num: root.querySelector('#ob-step-num'),
      bar: root.querySelector('#ob-progress > i'),
      prev: root.querySelector('#ob-prev'),
      next: root.querySelector('#ob-next'),
      skip: root.querySelector('#ob-skip')
    };

    D.next.onclick = function () { go(S.i + 1); };
    D.prev.onclick = function () { if (S.i > 0) go(S.i - 1); };
    D.skip.onclick = function () { quit(); };
    /* 点到遮罩不做任何事：引导期间误触代价太大（会跳到别的页面），
       宁可让人多点一次「下一步」也不要把它退掉。 */
    root.addEventListener('click', function (e) { e.stopPropagation(); });
    window.addEventListener('resize', function () { if (S.on) locate(); });
    window.addEventListener('orientationchange', function () {
      if (S.on) setTimeout(locate, 260);
    });
    return D;
  }

  /* ---------------- 轮询等元素 ----------------
     页面骨架是同步渲染的，但有的块要等接口回来才有，所以轮询。
     超时就返回 null —— 调用方会退化成居中弹窗，绝不把人卡住。 */
  function waitFor(sel, cb) {
    clearTimeout(S.waitTimer);
    if (!sel) return cb(null);
    var t0 = Date.now();
    (function poll() {
      var node = null;
      try { node = document.querySelector(sel); } catch (e) { node = null; }
      if (node) return cb(node);
      if (Date.now() - t0 > 2500) return cb(null);
      S.waitTimer = setTimeout(poll, 80);
    })();
  }

  /* ---------------- 定位洞和气泡 ---------------- */
  function locate() {
    if (!D) return;
    var step = STEPS[S.i];
    var node = step.target ? document.querySelector(step.target) : null;
    var r0 = node ? node.getBoundingClientRect() : null;

    /* 没有目标，或者目标小到看不见（比如右上角还没有任何按钮时
       #appbar-actions 是空的）—— 都退化成居中弹窗。
       给一个 8px 的门槛：高亮一个 2px 的空壳没有任何意义。 */
    if (!node || !r0 || r0.width < 8 || r0.height < 8) {
      D.root.classList.add('no-target');
      /* ⚠️ 必须把内联样式清掉。
         上一步写过 left/top/width/height，内联的优先级高于
         `#ob-root.no-target #ob-tip { left:50%; top:50% }` 那条规则，
         不清的话居中那两步的气泡会跑到屏幕外面去（欢迎页实测 x=-156）。 */
      D.tip.style.left = '';
      D.tip.style.top = '';
      [D.hole, D.ring].forEach(function (n) {
        n.style.left = ''; n.style.top = '';
        n.style.width = ''; n.style.height = '';
      });
      return;
    }
    D.root.classList.remove('no-target');

    /* 目标在可滚动区域里且不在视口内时，先滚进来再量。
       fixed 的顶栏/底栏调这个没有副作用。 */
    try {
      var vh0 = window.innerHeight;
      if (r0.top < 0 || r0.bottom > vh0) {
        node.scrollIntoView({ block: 'center', inline: 'nearest' });
      }
    } catch (e) {}

    var vw = window.innerWidth, vh2 = window.innerHeight;
    var r = node.getBoundingClientRect();
    var pad = 6;
    var x = Math.max(0, r.left - pad);
    var y = Math.max(0, r.top - pad);
    var w = Math.min(Math.max(24, r.width + pad * 2), vw - x);
    var h = Math.min(Math.max(24, r.height + pad * 2), vh2 - y);

    var round = !!step.round || (w <= 56 && h <= 56);
    [D.hole, D.ring].forEach(function (n) {
      n.classList.toggle('round', round);
      n.style.left = x + 'px';
      n.style.top = y + 'px';
      n.style.width = w + 'px';
      n.style.height = h + 'px';
    });

    /* 气泡：优先放洞下方，放不下就放上方，再不行就居中。
       顶部要留出「跳过」按钮那条（安全区 + 10 + 36 + 8）。 */
    var tipW = D.tip.offsetWidth, tipH = D.tip.offsetHeight;
    var gap = 12;
    var minTop = (parseFloat(getComputedStyle(document.documentElement)
      .getPropertyValue('--safe-t')) || 0) + 56;
    var top;
    if (vh2 - (y + h) >= tipH + gap + 12) top = y + h + gap;
    else if (y >= tipH + gap + 12) top = y - tipH - gap;
    else top = Math.max(minTop, Math.min(vh2 - tipH - 12, (vh2 - tipH) / 2));
    if (top < minTop) top = minTop;

    var left = x + w / 2 - tipW / 2;
    left = Math.max(12, Math.min(vw - tipW - 12, left));
    D.tip.style.left = left + 'px';
    D.tip.style.top = Math.max(minTop, Math.min(top, vh2 - tipH - 12)) + 'px';
  }

  /* ---------------- 走一步 ---------------- */
  function go(i) {
    if (i >= STEPS.length) return finish();
    S.i = i;
    var step = STEPS[i];

    if (step.route && window.Router) {
      var here = (location.hash || '#/').replace(/^#/, '').split('?')[0];
      if (here !== step.route) window.Router.go(step.route);
    }

    D.title.textContent = step.title;
    D.body.innerHTML = step.body;          // 内容是本文件里的常量，没有外部输入
    D.num.textContent = (i + 1) + ' / ' + STEPS.length;
    D.bar.style.width = ((i + 1) / STEPS.length * 100) + '%';
    D.prev.hidden = (i === 0);
    D.next.textContent = (i === STEPS.length - 1) ? '完成' : '下一步';
    /* 重播一次气泡的进入动画：CSS animation 挂在元素上，只改内容不会重新播，
       强制回流一下才有效果（每步都有一点「冒出来」的感觉）。 */
    try {
      D.tip.style.animation = 'none';
      void D.tip.offsetWidth;
      D.tip.style.animation = '';
    } catch (e) {}

    waitFor(step.target || '', function () {
      if (!S.on) return;                   // 等待期间被关掉了
      locate();
    });
  }

  /* ---------------- 开始 / 结束 ---------------- */
  function start(opts) {
    opts = opts || {};
    if (S.on) return;                      // 已经在跑，不重复启动
    ensureDom();
    S.on = true;
    S.i = 0;
    S.startHash = (location.hash || '#/');
    D.root.classList.add('show');
    go(0);
    /* 手动从设置里进来时给个提示，让人知道随时能退 */
    if (opts.manual && window.UI && UI.toast) UI.toast('点右上角「跳过」可随时退出引导');
  }

  function teardown() {
    S.on = false;
    clearTimeout(S.waitTimer);
    if (D) {
      D.root.classList.remove('show', 'no-target');
      [D.hole, D.ring].forEach(function (n) {
        n.style.width = '0px'; n.style.height = '0px';
      });
    }
  }

  /** 看完 / 跳过，都算「已经看过」，下次不再自动弹 */
  function finish() {
    markDone();
    var back = S.startHash && S.startHash !== '#' ? S.startHash : '#/';
    teardown();
    if (window.Router) {
      try { window.Router.replace(back.replace(/^#/, '')); } catch (e) { location.hash = back; }
    }
  }

  /** 返回键 / 点跳过：等同看完，也要回到进引导前的页面 */
  function quit() {
    markDone();
    var back = S.startHash && S.startHash !== '#' ? S.startHash : '#/';
    teardown();
    if (window.Router) {
      try { window.Router.replace(back.replace(/^#/, '')); } catch (e) { location.hash = back; }
    }
  }

  function markDone() {
    try { if (window.Store) window.Store.set(KEY, VERSION); } catch (e) {}
  }

  /* ---------------- 对外 ---------------- */
  window.Onboarding = {
    VERSION: VERSION,
    steps: STEPS,
    start: start,
    quit: quit,
    isActive: function () { return S.on; },
    isDone: function () {
      try { return window.Store ? (window.Store.get(KEY) || 0) >= VERSION : true; }
      catch (e) { return true; }
    },
    /** 首次启动自动弹：只看 Store，看过就不再打扰 */
    autoStart: function () {
      try { if (this.isDone()) return false; } catch (e) { return false; }
      var self = this;
      /* 等首屏画完再上遮罩，否则会盖在一个还没渲染好的页面上 */
      setTimeout(function () { if (!S.on) self.start(); }, 400);
      return true;
    },
    /** 重新看一遍（设置里的入口）：清掉状态再走一遍 */
    restart: function () {
      try { if (window.Store) window.Store.set(KEY, 0); } catch (e) {}
      start({ manual: true });
    }
  };
})();
