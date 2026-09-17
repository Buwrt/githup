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
      /* 以前外链图片（https://…）被包进 <a target="_blank"> 且不带 data-zoom：
       * 内置查看器只认 [data-zoom]，而 WebView 又没开多窗口、也没实现
       * onCreateWindow —— 点下去既不放大也不跳转，看起来就是「图片点不了」。
       * 现在不管内外链一律打上 data-zoom，点击走内置查看器。 */
      return '<img class="md-img" src="' + U.esc(href) + '" alt="' + U.esc(text || '') +
        '" loading="lazy" data-zoom="1">';
    },
    /* 原始 HTML：以前一律 return ''，于是 GitHub 上传的视频
     * （issue / README 里的 <video src="https://github.com/user-attachments/…">）
     * 在应用里连个影子都没有 —— 这就是「看不了视频」。
     * 现在放行一小撮只影响排版和媒体的标签，其余照旧丢掉；
     * 后面还有 DOMPurify 兜底，script / iframe / 事件属性留不下来。 */
    html: function (a) {
      var h = (a && typeof a === 'object') ? (a.text || a.raw) : a;
      if (!h) return '';
      var names = h.match(/<\/?([a-zA-Z][a-zA-Z0-9-]*)/g);
      if (!names) return '';   // 纯注释之类，没有标签
      for (var i = 0; i < names.length; i++) {
        if (!HTML_OK.test(names[i].replace(/^<\/?/, ''))) return '';
      }
      return h;
    }
  };

  /* 允许出现在原始 HTML 里的标签名。凡是影响行为的一律不在表内：
   * script / iframe / style / form / input / object / embed … */
  var HTML_OK = /^(?:video|source|img|picture|figure|figcaption|br|hr|p|div|span|a|b|i|u|s|em|strong|del|ins|sub|sup|kbd|code|pre|blockquote|ul|ol|li|table|thead|tbody|tfoot|tr|th|td|h[1-6]|details|summary|center)$/i;

  /* GitHub 上传的视频 / 图片附件：粘贴进来时是一行裸链接，
   * 光有 <a> 点开只会跳浏览器（视频还得下载），所以就地还原成播放器。 */
  var VIDEO_EXT = /\.(?:mp4|m4v|mov|webm|ogv|ogg|mkv)(?:[?#]|$)/i;
  var IMAGE_EXT = /\.(?:png|jpe?g|gif|webp|bmp|svg|avif)(?:[?#]|$)/i;

  function isVideo(u) { return VIDEO_EXT.test(u || ''); }
  function isImage(u) { return IMAGE_EXT.test(u || ''); }

  /* GitHub 网页端上传的附件是**没有扩展名**的（拖个视频进 issue，
   * 贴出来就是 github.com/user-attachments/assets/<uuid> 这么一行），
   * 从 URL 上看不出是视频还是图片 —— 所以乐观当视频渲染，
   * 加载失败（多半是张截图）自动降级成图片，再不行退回成链接。
   * 降级链绑在 MD.mount 里，见 probeMedia()。 */
  var ATTACH_RE = /^https?:\/\/(?:www\.)?github\.com\/user-attachments\/[a-z]+\/[0-9a-zA-Z-]{6,}/i;

  function videoTag(u, cls) {
    return '<video class="md-video' + (cls ? ' ' + cls : '') + '" src="' + U.esc(u) +
      '" controls preload="metadata" playsinline webkit-playsinline></video>';
  }

  function imgTag(u, alt) {
    return '<img class="md-img" src="' + U.esc(u) + '" alt="' + U.esc(alt || '') +
      '" loading="lazy" data-zoom="1">';
  }

  /* 无扩展名附件的降级链：视频加载失败 → 当图片试 → 再失败给个能点的链接。
   * GitHub 上传的截图和视频长得一模一样（都没有扩展名），
   * 唯一可靠的区别就是「让 <video> 自己去拉 metadata，拉不动就换 <img>」。 */
  function probeMedia(v) {
    if (v.getAttribute('data-probed')) return;
    v.setAttribute('data-probed', '1');
    var url = v.getAttribute('src');
    if (!url) return;
    var stepped = false;
    v.addEventListener('error', function () {
      if (stepped) return;
      stepped = true;
      var img = new Image();
      img.onload = function () {
        var el = document.createElement('img');
        el.className = 'md-img';
        el.src = url;
        el.alt = '';
        el.setAttribute('data-zoom', '1');
        el.onclick = function () { window.UI.viewImage(url); };
        if (v.parentNode) v.parentNode.replaceChild(el, v);
      };
      img.onerror = function () {
        var a = document.createElement('a');
        a.className = 'md-attach-link';
        a.href = url;
        a.target = '_blank';
        a.rel = 'noopener';
        a.textContent = '📎 打开附件';
        if (v.parentNode) v.parentNode.replaceChild(a, v);
      };
      img.src = url;
    });
  }

  function mdLink(href, text) {
    if (!href) return U.esc(text || '');
    if (isVideo(href)) return videoTag(href, 'md-probe');      // 裸的视频链接 → 直接内嵌播放器
    if (ATTACH_RE.test(href)) return videoTag(href, 'md-probe'); // 无扩展名的上传附件 → 乐观当视频，失败自动降级
    if (isImage(href)) return imgTag(href, text);               // 裸的图片链接 → 就地显示，可点开
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
        /* SHA 自动链接：只认「前面不是 URL 成分」的裸 SHA。
         * 以前用 \b 边界，结果 GitHub 上传附件的 UUID（user-attachments/assets/b68c927-b888-…）
         * 恰好全是十六进制字符，URL 当场被撕成「半个链接 + 一个假 commit」——
         * 后面视频识别拿到的已经不是完整链接了，怎么看不了视频都找不到原因。
         * 所以前面是 / - . = & % > 或字母数字的一律不碰（那些都是 URL / 词的内部）。 */
        src = src.replace(/(^|[^\/\-.=&%>\w])([0-9a-f]{7,40})\b/g, function (m, pre, sha) {
          if (/^\d+$/.test(sha)) return m;
          return pre + '<a class="mono" href="#/' + repo + '/commit/' + sha + '">' + sha.substring(0, 7) + '</a>';
        });
      }
    return src;
  }

  var MD = {
    /** 渲染为受信任的 HTML */    render: function (src, ctx) {
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
          /* controls / playsinline / preload 这些不是 DOMPurify 的默认属性，
           * 不在这里放行的话，视频标签会被扒成光秃秃的 <video src>，按了没反应。 */
          ADD_ATTR: ['target', 'data-zoom', 'data-lang', 'class', 'align', 'colspan', 'rowspan',
            'open', 'controls', 'playsinline', 'webkit-playsinline', 'preload', 'poster',
            'loop', 'muted', 'width', 'height', 'loading'],
          FORBID_TAGS: ['style', 'script', 'iframe', 'form', 'input', 'object', 'embed'],
          FORBID_ATTR: ['onerror', 'onload', 'onclick']
        });
        // 补 controls：GitHub 贴进来的 <video> 未必自带 controls，
        // 没这个属性视频就是一块不会动的黑砖 —— 用户只会说「视频放不了」。
        html = html.replace(/<video\b(?![^>]*\bcontrols\b)([^>]*)>/g,
          '<video controls playsinline preload="metadata"$1>');
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
      /* 所有图片都能点开看（不再区分内外链）；加载失败的给它一个可见的边框，
       * 免得只剩一个空白位置，让人以为是应用坏了。 */
      window.UI.$$('img', container).forEach(function (img) {
        img.onclick = function () { window.UI.viewImage(img.src); };
        img.addEventListener('error', function () { img.classList.add('img-broken'); });
        if (img.complete && img.naturalWidth === 0 && img.getAttribute('src')) {
          img.classList.add('img-broken');
        }
      });
      /* 无扩展名的 GitHub 上传附件：乐观当视频渲染，这里负责失败后的降级链
       * 视频 → 图片 → 链接。没有这条链，截图类附件会留一块按不动的黑砖。 */
      window.UI.$$('video.md-probe', container).forEach(probeMedia);
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
