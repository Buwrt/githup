/* ============================================================
 * md.js — Markdown 渲染（GFM），代码高亮、站内链接转换、XSS 过滤
 * ============================================================ */
(function () {
  'use strict';
  var U = window.Util;

  // 当前渲染上下文（仓库全名），用于把相对链接/短 SHA 转成站内路由
  window.MDContext = { repo: null, user: null };

  var renderer = {
    code: function (a, b) {
      var code = (a && typeof a === 'object') ? a.text : a;
      var lang = (a && typeof a === 'object') ? a.lang : b;
      var html;
      try {
        if (lang && window.hljs && hljs.getLanguage(lang)) {
          html = hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
        } else if (window.hljs) {
          html = hljs.highlightAuto(code).value;
        } else html = U.esc(code);
      } catch (e) { html = U.esc(code); }
      var lines = code.split('\n').length;
      var gutter = '';
      if (lines > 1 && lines < 400) {
        for (var i = 1; i <= lines; i++) gutter += '<div>' + i + '</div>';
      }
      return '<pre class="md-code"' + (lang ? ' data-lang="' + U.esc(lang) + '"' : '') + '>' +
        '<div class="code-lines">' +
        (gutter ? '<div class="gutter">' + gutter + '</div>' : '') +
        '<code class="hljs language-' + U.esc(lang || 'text') + '">' + html + '</code></div></pre>';
    },
    link: function (a, b, c) {
      var href = (a && typeof a === 'object') ? a.href : a;
      var text = (a && typeof a === 'object') ? a.text : c;
      return mdLink(href, text);
    },
    image: function (a, b, c) {
      var href = (a && typeof a === 'object') ? a.href : a;
      var text = (a && typeof a === 'object') ? a.text : c;
      if (/^https?:/.test(href)) {
        return '<a href="' + U.esc(href) + '" target="_blank" rel="noopener"><img src="' + U.esc(href) + '" alt="' + U.esc(text || '') + '" loading="lazy"></a>';
      }
      return '<img src="' + U.esc(href) + '" alt="' + U.esc(text || '') + '" loading="lazy" data-zoom="1">';
    },
    html: function (a) {
      var h = (a && typeof a === 'object') ? (a.text || a.raw) : a;
      // 丢掉原始 HTML（WebView 内渲染无法保证安全），只保留注释/换行语义
      return '';
    }
  };

  function mdLink(href, text) {
    if (!href) return U.esc(text || '');
    var gh = href.match(/^https?:\/\/(?:www\.)?github\.com\/(.+)$/i);
    if (gh) {
      var p = gh[1].replace(/#.*$/, '');
      return '<a href="#/' + U.esc(p) + '">' + U.esc(text || href) + '</a>';
    }
    if (/^https?:/i.test(href)) {
      return '<a href="' + U.esc(href) + '" target="_blank" rel="noopener">' + U.esc(text || href) + '</a>';
    }
    if (/^#/.test(href)) return '<a href="' + U.esc(href) + '">' + U.esc(text || href) + '</a>';
    // 站内相对路径
    if (window.MDContext.repo && /^[^\/]/.test(href)) {
      return '<a href="#/' + U.esc(window.MDContext.repo) + '/blob/HEAD/' + U.esc(href) + '">' + U.esc(text || href) + '</a>';
    }
    return '<a href="' + U.esc(href) + '">' + U.esc(text || href) + '</a>';
  }

  function inlineExtras(src) {
    var repo = window.MDContext.repo;
    src = src.replace(/(^|[^\w@/])@([a-zA-Z0-9](?:[a-zA-Z0-9]|-(?=[a-zA-Z0-9])){0,38})/g,
      function (m, pre, name) {
        if (/^(?:action|actions|apps|auth|blog|business|collections|contact|customer|docs|edu|enterprise|events|explore|features|gist|github|help|issues|jobs|login|marketplace|mirrors|notifications|orgs|pages|pricing|pulls|readme|security|settings|shop|showcases|sponsors|stars|status|topics|trending|users|watching)$/i.test(name)) return m;
        return pre + '<a class="mention" href="#/' + name + '">@' + name + '</a>';
      });
    if (repo) {
      src = src.replace(/(^|[^\w&])#(\d{1,7})\b/g,
        function (m, pre, n) { return pre + '<a href="#/' + repo + '/issues/' + n + '">#' + n + '</a>'; });
      src = src.replace(/\b([0-9a-f]{7,40})\b/g, function (m, sha) {
        if (/^\d+$/.test(sha)) return m;
        return '<a class="mono" href="#/' + repo + '/commit/' + sha + '">' + sha.substring(0, 7) + '</a>';
      });
    }
    return src;
  }

  var MD = {
    /** 渲染为受信任的 HTML */
    render: function (src, ctx) {
      if (!src) return '';
      if (ctx) window.MDContext.repo = ctx.repo || null;
      var prev = window.MDContext.repo;
      try {
        // 代码块内容不参与 @/# 自动链接
        var parts = String(src).split(/```/);
        var out = parts.map(function (p, i) { return i % 2 ? p : inlineExtras(p); }).join('```');

        // 渲染器只在模块加载时注册一次（见文件末尾）。
        // 每次渲染都调用 marked.use 会让覆盖层层累积，
        // 长会话下内存和耗时持续上涨，还可能重复处理。
        var html = marked.parse(out);

        html = DOMPurify.sanitize(html, {
          ADD_ATTR: ['target', 'data-zoom', 'data-lang', 'class', 'align', 'colspan', 'rowspan', 'open'],
          FORBID_TAGS: ['style', 'script', 'iframe', 'form', 'input', 'object', 'embed'],
          FORBID_ATTR: ['onerror', 'onload', 'onclick']
        });
        return html;
      } catch (e) {
        return '<pre>' + U.esc(src) + '</pre>';
      } finally {
        window.MDContext.repo = prev;
      }
    },

    /** 渲染并把结果写入容器，同时绑定图片点击查看 */
    mount: function (container, src, ctx) {
      container.innerHTML = this.render(src, ctx) || '<p class="muted">（无内容）</p>';
      container.classList.add('md');
      window.UI.$$('img[data-zoom]', container).forEach(function (img) {
        img.onclick = function () { window.UI.viewImage(img.src); };
      });
      window.UI.$$('.md a', container).forEach(function (a) {
        a.onclick = function (e) {
          var href = a.getAttribute('href') || '';
          if (href.charAt(0) === '#') { e.preventDefault(); window.Router.go(href.substring(1)); }
        };
      });
    },

    /** 纯文本摘要（列表用） */
    excerpt: function (src, n) {
      if (!src) return '';
      var s = String(src).replace(/```[\s\S]*?```/g, ' ').replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
        .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/[#>*_`~|-]/g, ' ').replace(/\s+/g, ' ').trim();
      return s.length > (n || 140) ? s.substring(0, n || 140) + '…' : s;
    }
  };

  window.MD = MD;

  /**
   * 渲染器与全局选项只在这里注册一次。
   *
   * 放到每次 render() 里调 marked.use 的话，覆盖会一层层叠加：
   * 同一个渲染器被重复挂载，渲染越来越慢、内存只涨不降，长会话下很要命。
   * 注册时机放在模块加载时，MD.render() 里只管 parse。
   */
  try {
    marked.setOptions({ gfm: true, breaks: true, headerIds: false, mangle: false });
    marked.use({ renderer: renderer });
  } catch (e) {
    // marked 出了问题也不该让整个模块挂掉，render() 里有兜底
  }
})();
