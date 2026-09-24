/* ============================================================
 * ui.js — 通用界面组件：提示、弹层、骨架屏、复用片段
 * ============================================================ */
(function () {
  'use strict';
  var U = window.Util;

  var UI = {
    /* ---------- 基础 ---------- */
    $: function (sel, root) { return (root || document).querySelector(sel); },
    $$: function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); },
    el: function (html) { var d = document.createElement('div'); d.innerHTML = html.trim(); return d.firstElementChild; },

    go: function (path) { window.Router.go(path); },

    haptic: function () {
      try { window.NativeBridge.haptic(); } catch (e) {}
    },

    /**
     * 长按绑定：手指或鼠标按住约 500ms 触发 fn，中途移动（滚动）即取消。
     * 返回一个 wasLong() —— 这次按压是不是长按，供调用方在随后的
     * click 里把长按松手带出来的那次误触吞掉。
     *
     * 三个容易被漏掉的细节：
     *  · touchstart 用 passive 且不 preventDefault —— 在这里一拦，
     *    WebView 连后续的 click 都不合成了，条目本身点不动；
     *  · 触发后要在 touchend / contextmenu 里 preventDefault —— 不然
     *    Android 长按默认的文本选择 / 系统菜单会跟着冒出来；
     *  · 绑定的元素顺手关掉 user-select —— 长按选字和长按手势出在同
     *    一根手指上，选了字就没有干净的长按了。
     */
    bindLongPress: function (el, fn, ms) {
      var hold = ms || 500, timer = null, fired = false;
      var start = function () {
        fired = false;
        timer = setTimeout(function () { fired = true; try { fn(); } catch (e) {} }, hold);
      };
      var cancel = function () { if (timer) { clearTimeout(timer); timer = null; } };
      try {
        el.style.webkitUserSelect = 'none';
        el.style.userSelect = 'none';
      } catch (e) {}
      el.addEventListener('touchstart', start, { passive: true });
      el.addEventListener('touchmove', cancel, { passive: true });
      el.addEventListener('touchend', function (e) { cancel(); if (fired) e.preventDefault(); });
      el.addEventListener('touchcancel', cancel);
      /* 桌面 / 模拟器上的鼠标长按（按住不放）也认 */
      el.addEventListener('mousedown', start);
      el.addEventListener('mouseup', cancel);
      el.addEventListener('mouseleave', cancel);
      /* Android 的长按会带一个 contextmenu 事件（文本选择 / 系统菜单的入口），
       * 在动作按钮上没有值得保留的右键菜单，一律压掉。 */
      el.addEventListener('contextmenu', function (e) { e.preventDefault(); });
      return function () { return fired; };
    },

    /* ---------- 轻提示 ---------- */
    // 轻提示一律贴在底部正中间，两三秒自动消失；#toast-root 是 pointer-events:none，
    // 所以它冒出来的时候照常能点页面上的东西，不打断操作。
    toast: function (msg, ms) {
      var root = document.getElementById('toast-root');
      var t = document.createElement('div');
      t.className = 'toast';
      t.textContent = msg;
      root.appendChild(t);
      setTimeout(function () {
        t.style.transition = 'opacity .2s'; t.style.opacity = '0';
        setTimeout(function () { t.remove(); }, 220);
      }, ms || 2400);
    },

    /**
     * 带绿勾的轻提示，用于「您已是最新版本」这类确认反馈。
     * 行为跟 toast 完全一致（底部居中、自动消失、不挡操作），只是多一个对勾更醒目。
     */
    toastOk: function (msg, ms) {
      var root = document.getElementById('toast-root');
      var t = document.createElement('div');
      t.className = 'toast toast-ok';
      var ic = document.createElement('span');
      ic.className = 'toast-ico';
      ic.innerHTML = window.icon('check-circle-fill', 16);
      t.appendChild(ic);
      t.appendChild(document.createTextNode(msg));
      root.appendChild(t);
      setTimeout(function () {
        t.style.transition = 'opacity .2s'; t.style.opacity = '0';
        setTimeout(function () { t.remove(); }, 220);
      }, ms || 2600);
    },

    /* ---------- 加载指示 ---------- */
    loading: function (on) {
      document.getElementById('progress').hidden = !on;
    },

    /* ---------- 复制 ---------- */
    copy: function (text, tip) {
      var done = function () { UI.toast(tip || '已复制'); };
      if (window.NativeBridge && typeof window.NativeBridge.copy === 'function') {
        try { window.NativeBridge.copy(text); done(); return; } catch (e) {}
      }
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, function () { UI.fallbackCopy(text, done); });
      } else UI.fallbackCopy(text, done);
    },
    fallbackCopy: function (text, done) {
      var ta = document.createElement('textarea');
      ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); done(); } catch (e) { UI.toast('复制失败'); }
      ta.remove();
    },

    /* ---------- 底部弹层 ---------- */
    sheet: function (opt) {
      var root = document.getElementById('sheet-root');
      var full = opt.full ? ' full' : '';
      // dismissible:false —— 用于「强制更新」这类必须做出选择的弹层：
      // 不渲染关闭按钮，点遮罩与 Esc 都无效，只能点下面的按钮。
      var lock = opt.dismissible === false;
      var html = '<div class="sheet-mask"' + (lock ? '' : ' data-close="1"') + '></div>' +
        '<div class="sheet' + full + '" role="dialog">' +
        (opt.title !== undefined ? '<div class="sheet-head">' + (opt.icon ? '<span>' + window.icon(opt.icon, 20) + '</span>' : '') +
          '<h3>' + U.esc(opt.title) + '</h3>' + (lock ? '' : '<button class="icon-btn" data-close="1" aria-label="关闭">' + window.icon('x', 18) + '</button>') + '</div>' : '') +
        '<div class="sheet-body">' + (opt.body || '') + '</div>' +
        (opt.foot ? '<div class="sheet-foot">' + opt.foot + '</div>' : '') +
        '</div>';
      root.innerHTML = html;
      root.classList.add('show');
      var close = function () {
        root.classList.remove('show');
        root.innerHTML = '';
        document.removeEventListener('keydown', onKey);
        if (opt.onClose) opt.onClose();
      };
      var onKey = function (e) { if (!lock && e.key === 'Escape') close(); };
      document.addEventListener('keydown', onKey);
      root.onclick = function (e) {
        // 用 closest 向上找：✕ 按钮内部是 <svg>/<path>，
        // 手指点到图标上时 e.target 是 svg 而非 button，裸 getAttribute 会失效
        var el = e.target && e.target.closest ? e.target.closest('[data-close]') : null;
        if (!el) return;
        // 只处理本弹层内的关闭元素，避免误伤
        if (!root.contains(el)) return;
        e.stopPropagation();
        close();
      };
      root._close = close;
      root.dataset.lock = lock ? '1' : '';
      if (opt.onMount) opt.onMount(root.querySelector('.sheet-body'), close);
      return close;
    },
    closeSheet: function () {
      var root = document.getElementById('sheet-root');
      // 不可关闭的弹层（如强制更新）不响应统一关闭，避免被返回键等旁路绕过
      if (root && root.dataset.lock === '1') return;
      if (root._close) root._close();
    },

    confirm: function (title, msg, okText, danger) {
      return new Promise(function (resolve) {
        var decided = false;
        UI.sheet({
          title: title,
          body: '<div style="font-size:14px;color:var(--fg-muted);line-height:1.6">' + U.esc(msg || '') + '</div>',
          foot: '<button class="btn" data-no>取消</button><button class="btn ' + (danger ? 'danger' : 'primary') + '" data-yes>' + U.esc(okText || '确定') + '</button>',
          onMount: function (body, close) {
            var root = document.getElementById('sheet-root');
            root.querySelector('[data-yes]').onclick = function () { decided = true; close(); resolve(true); };
            root.querySelector('[data-no]').onclick = function () { decided = true; close(); resolve(false); };
          },
          // 只有用户未做选择就关闭（点遮罩 / Esc）才回 false，
          // 避免 close() 触发的 onClose 抢先 resolve(false) 而吞掉「确定」
          onClose: function () { if (!decided) { decided = true; resolve(false); } }
        });
      });
    },

    /** 选项列表弹层 */
    menu: function (title, items, opts) {
      return new Promise(function (resolve) {
        var decided = false;
        var body = items.map(function (it, i) {
          if (it === '-') return '<div class="sep-line"></div>';
          return '<button class="opt" data-i="' + i + '"' +
            (it.disabled ? ' disabled style="opacity:.45"' : '') + '>' +
            (it.icon ? '<span class="opt-ico">' + window.icon(it.icon, 18) + '</span>' : '') +
            '<span>' + U.esc(it.label) + '</span>' +
            (it.value ? '<span class="muted tiny" style="margin-left:auto">' + U.esc(it.value) + '</span>' : '') +
            '</button>';
        }).join('');
        var root = document.getElementById('sheet-root');
        UI.sheet({
          title: title, body: body,
          onMount: function (b, close) {
            UI.$$('.opt', root).forEach(function (btn) {
              btn.onclick = function () {
                var i = +btn.getAttribute('data-i');
                if (items[i].disabled) return;   // 置灰项点了没反应，等同不存在
                decided = true;
                close();
                resolve(items[i].key !== undefined ? items[i].key : i);
              };
            });
          },
          onClose: function () { if (!decided) { decided = true; resolve(null); } }
        });
      });
    },

    /** 单选设置。第 5 个参数 onLong（可选）：某条被长按时的回调。
     *  长按不关弹层 —— 「点是选它，长按是去别处办事」的场景里，
     *  用户从浏览器回来还得能接着点。 */
    choose: function (title, items, current, onChange, onLong) {
      var body = items.map(function (it) {
        return '<button class="opt' + (it.key === current ? ' on' : '') + '" data-k="' + U.esc(it.key) + '">' +
          '<span class="opt-ico">' + window.icon(it.icon || 'dot', 18) + '</span><span>' + U.esc(it.label) + '</span>' +
          (it.key === current ? '<span class="opt-check">' + window.icon('check', 18) + '</span>' : '') + '</button>';
      }).join('');
      var root = document.getElementById('sheet-root');
      UI.sheet({
        title: title, body: body,
        onMount: function () {
          UI.$$('.opt', root).forEach(function (btn) {
            var wasLong = null;
            if (onLong) {
              wasLong = UI.bindLongPress(btn, function () {
                onLong(btn.getAttribute('data-k'));
              });
            }
            btn.onclick = function () {
              if (wasLong && wasLong()) return;   // 长按松手带出来的 click：吞掉
              var k = btn.getAttribute('data-k');
              UI.closeSheet();
              if (onChange) onChange(k);
            };
          });
        }
      });
    },

    prompt: function (title, opt) {
      opt = opt || {};
      return new Promise(function (resolve) {
        var decided = false;
        var body =
          (opt.desc ? '<p class="muted tiny" style="margin:0 0 10px">' + U.esc(opt.desc) + '</p>' : '') +
          '<div class="field"><input class="input" id="pv" type="' + (opt.type || 'text') + '" value="' + U.esc(opt.value || '') + '" placeholder="' + U.esc(opt.placeholder || '') + '"' + (opt.multiline ? '' : '') + '></div>';
        UI.sheet({
          title: title, body: body,
          foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>' + U.esc(opt.ok || '确定') + '</button>',
          onMount: function (b, close) {
            var root = document.getElementById('sheet-root');
            var input = root.querySelector('#pv');
            setTimeout(function () { input.focus(); }, 120);
            root.querySelector('[data-yes]').onclick = function () { var v = input.value; decided = true; close(); resolve(v); };
            root.querySelector('[data-no]').onclick = function () { decided = true; close(); resolve(null); };
          },
          onClose: function () { if (!decided) { decided = true; resolve(null); } }
        });
      });
    },

    /* ---------- 片段 ---------- */
    avatar: function (login, url, size, square) {
      var s = size || 32;
      var cls = 'avatar av-' + s + (square ? ' sq' : '');
      return '<img class="' + cls + '" width="' + s + '" height="' + s + '" loading="lazy" src="' + U.esc(url || '') + '" alt="' + U.esc(login || '') + '" onerror="this.style.visibility=\'hidden\'">';
    },

    skeleton: function (n) {
      var s = '';
      for (var i = 0; i < (n || 5); i++) {
        s += '<div class="skel skel-row w80"></div><div class="skel skel-row w40"></div>';
        s += '<div style="height:8px"></div>';
      }
      return '<div class="card flat" style="border:0">' + s + '</div>';
    },

    empty: function (iconName, title, desc) {
      return '<div class="empty">' + window.icon(iconName || 'inbox', 40) +
        '<div class="t">' + U.esc(title || '空空如也') + '</div>' +
        (desc ? '<div class="d">' + U.esc(desc) + '</div>' : '') + '</div>';
    },

    stateBadge: function (it, isPR) {
      if (isPR) {
        if (it.merged) return '<span class="state merged">' + window.icon('git-merge', 14) + ' 已合并</span>';
        if (it.draft) return '<span class="state draft">' + window.icon('git-pull-request-draft', 14) + ' 草稿</span>';
        if (it.state === 'closed') return '<span class="state closed">' + window.icon('git-pull-request', 14) + ' 已关闭</span>';
        return '<span class="state open">' + window.icon('git-pull-request', 14) + ' 待合并</span>';
      }
      if (it.state === 'closed' || it.state_reason) {
        return '<span class="state ' + (it.state_reason === 'completed' ? 'merged' : 'closed') + '">' +
          window.icon(it.state_reason === 'completed' ? 'issue-closed' : 'skip', 14) +
          (it.state_reason === 'not_planned' ? ' 未计划' : ' 已关闭') + '</span>';
      }
      return '<span class="state open">' + window.icon('issue-opened', 14) + ' 待处理</span>';
    },

    langBar: function (langs) {
      if (!langs || !Object.keys(langs).length) return '';
      var total = 0, k;
      for (k in langs) total += langs[k];
      if (!total) return '';
      var keys = Object.keys(langs).sort(function (a, b) { return langs[b] - langs[a]; });
      var bar = keys.map(function (name) {
        var pct = (langs[name] / total * 100).toFixed(2);
        return '<span style="width:' + pct + '%;background:' + U.langColor(name) + ';display:block;height:100%"></span>';
      }).join('');
      var legend = keys.slice(0, 6).map(function (name) {
        return '<span><i style="background:' + U.langColor(name) + '"></i>' + U.esc(name) + ' ' + (langs[name] / total * 100).toFixed(1) + '%</span>';
      }).join('');
      return '<div class="lang-bar">' + bar + '</div><div class="lang-legend">' + legend + '</div>';
    },

    /** 数字统计行 */
    stats: function (items) {
      return '<div class="row-meta">' + items.filter(Boolean).map(function (it) {
        return '<span>' + (it.icon ? window.icon(it.icon, 13) : '') + U.esc(it.text) + '</span>';
      }).join('') + '</div>';
    },

    /** 分段控件 */
    seg: function (id, items, current) {
      return '<div class="seg" id="' + id + '">' + items.map(function (it) {
        return '<button data-v="' + U.esc(it.key) + '" class="' + (it.key === current ? 'active' : '') + '">' + U.esc(it.label) + '</button>';
      }).join('') + '</div>';
    },

    /* ---------- 图片查看 ---------- */
    viewImage: function (src) {
      var root = document.getElementById('viewer-root');
      root.innerHTML = '<img src="' + U.esc(src) + '" alt="">';
      root.classList.add('show');
      root.onclick = function () { UI.closeViewer(); };
    },

    /** 关闭图片查看器；返回是否真的有关闭动作（供返回键判断） */
    closeViewer: function () {
      var root = document.getElementById('viewer-root');
      if (!root || !root.classList.contains('show')) return false;
      root.classList.remove('show');
      root.innerHTML = '';
      return true;
    },

    /**
     * 一块内容被原地重画之后，知会一下翻译。
     *
     * 列表/搜索结果这类内容是直接在原容器上 innerHTML 重画的，不走 Router ——
     * 没有 pushState、也没有 hashchange，翻译那边判断「页面换了」的两个信号
     * 一个都不会响，只能靠 MutationObserver 的兜底链去发现改动，
     * 那一拍的等待是直接叠在网络往返后面的。
     *
     * 详见 translate.js 里 refresh() 的说明。这里放 UI 是为了让每个页面都能
     * 用同一句话，不用各自复制一份；翻译模块没起来时什么也不做。
     */
    noticeRefresh: function (box) {
      try {
        if (box && window.GhTranslator && window.GhTranslator.refresh) {
          window.GhTranslator.refresh(box);
        }
      } catch (e) { /* 不许挡住页面自己的渲染 */ }
    },

    /** 当前是否有弹层打开（菜单 / 确认框 / 表单弹层等） */
    hasSheet: function () {
      var root = document.getElementById('sheet-root');
      return !!(root && root.classList.contains('show'));
    }
  };

  window.UI = UI;
})();
