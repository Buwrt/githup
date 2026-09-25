/* ============================================================
 * md.js — Markdown 渲染（GFM），代码高亮、站内链接转换、XSS 过滤
 * ============================================================ */
(function () {
  'use strict';
  var U = window.Util;

  // 当前渲染上下文（仓库全名 / 分支 / 文件在仓库里的路径），
  // 用于把相对链接、短 SHA、以及**相对图片地址**还原成能访问的绝对地址
  window.MDContext = { repo: null, ref: null, path: null, user: null };

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
      return '<img class="md-img" src="' + U.esc(resolveImgUrl(href)) + '" alt="' + U.esc(text || '') +
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

  /* ============================================================
   * 图片地址补全 —— 解决「README 里的图片看不了」
   *
   * README 里的图片几乎都写成相对路径（![图](docs/img/a.png)）。
   * 网页上浏览器会拿当前页地址去补；App 里页面是 file:///android_asset/web/index.html，
   * 补出来是 file:///android_asset/web/docs/img/a.png —— 本地压根没这个文件，
   * 于是图片全部裂开，只剩一个空白框。
   *
   * 这里按 GitHub 的规则自己补全成 raw 地址：
   *   相对路径        → raw.githubusercontent.com/{repo}/{ref}/{README 所在目录}/{路径}
   *   /开头           → 当仓库根目录算
   *   github.com/…/blob/…/x.png → raw.githubusercontent.com（网页版那种写法
   *                     直接当 src 拉到的是 HTML 页面，同样是裂图）
   *   其它绝对地址     → 原样不动
   * ============================================================ */
  function joinPath(basePath, rel) {
    var dir = String(basePath || '').replace(/\\/g, '/');
    var cut = dir.lastIndexOf('/');
    dir = cut >= 0 ? dir.substring(0, cut + 1) : '';   // 只留目录，去掉文件名
    var parts = (dir ? dir.split('/') : []).concat(String(rel || '').split('/'));
    var out = [];
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i];
      if (p === '' || p === '.') continue;
      if (p === '..') { out.pop(); continue; }
      out.push(p);
    }
    return out.join('/');
  }

  function rawUrl(repo, ref, path) {
    var q = '';
    var i = String(path).search(/[?#]/);
    if (i >= 0) { q = path.substring(i); path = path.substring(0, i); }
    return 'https://raw.githubusercontent.com/' + repo + '/' + (ref || 'HEAD') + '/' +
      String(path).split('/').map(encodeURIComponent).join('/') + q;
  }

  function resolveImgUrl(href) {
    var h = String(href == null ? '' : href).trim();
    if (!h) return h;
    if (/^(?:data|blob):/i.test(h)) return h;
    if (/^\/\//.test(h)) return 'https:' + h;                 // 协议相对地址
    var m = h.match(/^https?:\/\/(?:www\.)?github\.com\/([^\/]+)\/([^\/]+)\/(?:blob|raw)\/([^\/]+)\/(.+)$/i);
    if (m) return rawUrl(m[1] + '/' + m[2], m[3], m[4]);
    if (/^[a-z][a-z0-9+.-]*:/i.test(h)) return h;             // 其它绝对地址原样
    if (h.charAt(0) === '#') return h;                        // 页内锚点，不是图片
    var ctx = window.MDContext;
    if (!ctx || !ctx.repo) return h;                          // 没有上下文就别乱补
    // 以 / 开头 = 相对仓库根（GitHub 网页版就是这个语义），否则相对 README 所在目录
    return rawUrl(ctx.repo, ctx.ref,
      h.charAt(0) === '/' ? joinPath('', h) : joinPath(ctx.path, h));
  }

  /* ============================================================
   * 图片走原生通道 —— 光补全地址还不够
   *
   * raw.githubusercontent.com 在不少网络下直连是不通的（api.github.com 反而通，
   * 因为 App 内的列表/文本走的是原生网络栈）。所以补全出 raw 地址之后，
   * 加载也交给原生：拉回 base64 转成 data URI 塞回 <img>。
   * 拉不到（超时 / 404 / 太大）就维持原样，交给 img-broken 兜底。
   * 没有原生桥（浏览器 Demo）时保持直连不动。
   * ============================================================ */
  var fetchQueue = [], fetching = 0, FETCH_CONCURRENCY = 4;
  /* 同一个 URL 只真正拉一次。
   * README 里常常好几处引用同一张图（正文一张、表格里再列一次地址），
   * 不去重就是同一张几百 KB 的图下三四遍 —— 流量和等待都是白搭。 */
  var fetched = Object.create(null);   // url -> data URI（已完成）
  var inflight = Object.create(null);  // url -> 等待同一份结果的 job 列表（在路上）

  function sniffMime(b64) {
    /* base64 前缀就是文件头魔数的编码，认这几种最常见的就够了 */
    if (/^iVBORw0KGgo/.test(b64)) return 'image/png';         /* \x89PNG */
    if (/^\/9j\//.test(b64))      return 'image/jpeg';        /* FFD8FF */
    if (/^R0lGOD/.test(b64))      return 'image/gif';         /* GIF8   */
    if (/^UklGR/.test(b64))       return 'image/webp';        /* RIFF   */
    return '';
  }

  function mimeOf(headers, url) {
    try {
      var ct = (headers && (headers['content-type'] || headers['Content-Type'])) || '';
      ct = String(ct).split(';')[0].trim();
      if (/^image\//i.test(ct)) return ct;
    } catch (e) {}
    var m = (String(url).match(/\.([a-z0-9]+)(?:[?#]|$)/i) || [])[1] || '';
    return { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif',
             webp: 'image/webp', svg: 'image/svg+xml', bmp: 'image/bmp', avif: 'image/avif',
             ico: 'image/x-icon' }[m.toLowerCase()] || 'application/octet-stream';
  }

  /* 只给 GitHub 自家域名带令牌 —— 把令牌发给第三方图床等于把仓库写权限交出去 */
  function headersFor(url) {
    if (!/^https?:\/\/(?:[^\/]*\.)?(?:githubusercontent\.com|github\.com|github\.io)\//i.test(url))
      return null;
    var t = (window.API && typeof window.API.getToken === 'function') ? window.API.getToken() : '';
    return t ? { Authorization: 'Bearer ' + t, Accept: '*/*' } : null;
  }

  function pumpFetch() {
    while (fetching < FETCH_CONCURRENCY && fetchQueue.length) {
      var job = fetchQueue.shift();
      /* 已经拉到过 —— 直接把现成的贴上，不再走一趟网络 */
      if (fetched[job.url]) {
        job.img.src = fetched[job.url];
        job.img.classList.remove('img-broken');
        continue;
      }
      /* 同一张图正在路上 —— 挂到它的队列上等结果。
       * 少了这一步，README 里三处引用同一张 417KB 的图就会同时发出三个请求
       * （并发 4 条，谁也不知道对方在拉同一个 URL）。 */
      if (inflight[job.url]) { inflight[job.url].push(job); continue; }

      var group = inflight[job.url] = [job];
      fetching++;
      (function (url, jobs) {
        window.Native.httpB64(url, headersFor(url)).then(function (res) {
          try {
            if (res && res.status === 200 && res.body) {
              var mime = sniffMime(res.body) || mimeOf(res.headers, url);
              var uri = 'data:' + mime + ';base64,' + res.body;
              fetched[url] = uri;
              jobs.forEach(function (j) {
                if (!j.img.isConnected) return;
                j.img.src = uri;
                j.img.classList.remove('img-broken');
              });
            }
          } catch (e) {}
          delete inflight[url];
          fetching--;
          pumpFetch();
        }).catch(function () { delete inflight[url]; fetching--; pumpFetch(); });
      })(job.url, group);
    }
  }

  function queueNativeFetch(img) {
    var url = img.getAttribute('src') || '';
    if (!/^https?:/i.test(url)) return;                       // data:/相对地址不处理
    if (img.getAttribute('data-nf')) return;                  // 别重复排队
    img.setAttribute('data-nf', '1');
    fetchQueue.push({ img: img, url: url });
    pumpFetch();
  }

  /* ============================================================
   * 图片的「快车道」开没开？
   *
   * 开了的意思是：App 会在 WebView 伸手取图的时候把字节直接接过去
   * （Java 侧 ImageProxy + shouldInterceptRequest，返回时就已是解好的原始字节）。
   * 那条路没有 Base64 那一层 33% 的体积膨胀，不来回过 Binder，也不必
   * 等整张到齐 —— 浏览器天生的 HTTP 缓存和渐进解码它都有。
   * 所以**开了反倒什么都不必做**：<img src> 保持原样交给 WebView 自己拉，
   * 就是最快的一条路。
   *
   * 没开（浏览器演示模式、或者这个方法压根不在）就退回老的 base64 通道：
   * 慢是慢点，图照样出来。
   * ============================================================ */
  function proxyReady() {
    try {
      return !!(window.NativeBridge
        && typeof window.NativeBridge.imageProxyReady === 'function'
        && window.NativeBridge.imageProxyReady());
    } catch (e) { return false; }
  }

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
    return '<img class="md-img" src="' + U.esc(resolveImgUrl(u)) + '" alt="' + U.esc(alt || '') +
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
    /* 裸的图片链接 → 就地显示，可点开。
     *
     * 只有「没有标题」或「标题就是 URL 本身」时才算裸链接 —— 那正是 GitHub
     * 网页端的做法。带标题的标准链接 [标题](url) 一律按链接渲染：
     * 以前不分青红皂白，只要 href 指向图片就渲染成 <img>，于是 README 里
     * [`docs/tips.png`](…/blob/main/docs/tips.png) 这种「反引号路径 + 跳转链接」
     * 也被画成了图 ——— 一张 417KB 的图在同一个 README 里被请求了 5 次，
     * 官网却只显示两条链接。 */
    if (isImage(href) && (!text || text === href)) return imgTag(href, text);
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
      var prev = { repo: window.MDContext.repo, ref: window.MDContext.ref, path: window.MDContext.path };
      if (ctx) {
        window.MDContext.repo = ctx.repo || null;
        window.MDContext.ref = ctx.ref || null;
        window.MDContext.path = ctx.path || null;
      }
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
        window.MDContext.repo = prev.repo;
        window.MDContext.ref = prev.ref;
        window.MDContext.path = prev.path;
      }
    },

    /** 渲染并把结果写入容器，同时绑定图片点击查看 */
    mount: function (container, src, ctx) {
      container.innerHTML = this.render(src, ctx) || '<p class="muted">（无内容）</p>';
      container.classList.add('md');
      /* README 里内联写的 <img src="a.png"> 走的是原始 HTML 那条路，
       * 不经过上面的 image 渲染器，相对地址得在这儿再补一遍。
       * render() 结束后上下文已经还原了，所以先临时挂回去。 */
      var prevR = window.MDContext.repo, prevF = window.MDContext.ref, prevP = window.MDContext.path;
      if (ctx) {
        window.MDContext.repo = ctx.repo || null;
        window.MDContext.ref = ctx.ref || null;
        window.MDContext.path = ctx.path || null;
      }
      /* 所有图片都能点开看（不再区分内外链）；加载失败的给它一个可见的边框，
       * 免得只剩一个空白位置，让人以为是应用坏了。 */
      /* 图片加载是懒加载的：滑到眼前才发请求 —— 那份「每次滑到这儿都要等一下」
       * 就是这么来的。这里有 App 替我们先把前几张偷偷下好，滑到时读的是本地文件。
       * 只取前几张：一份 README 可能有几十张图，全预习等于替用户把他不会滑到的
       * 部分也买了单。 */
      var preload = [];
      /* 这一次渲染要不要走快车道：整份 README 统一判断一次，
       * 免得一半图走这条路、一半图走那条路，出问题对不上账。 */
      var proxyOn = proxyReady();
      window.UI.$$('img', container).forEach(function (img) {
        var s = img.getAttribute('src');
        if (s) {
          var fixed = resolveImgUrl(s);
          if (fixed && fixed !== s) img.setAttribute('src', fixed);
        }
        img.onclick = function () { window.UI.viewImage(img.src); };
        img.addEventListener('error', function () {
          img.classList.add('img-broken');
          /* 快车道没接住 —— 私有附件、404、网络抽风都有可能。
           * 这时候退回老的 base64 通道再试一次（那条路自带 Authorization），
           * 失败也不过是维持现在的裂图状态。 */
          if (proxyOn) queueNativeFetch(img);
        });
        if (img.complete && img.naturalWidth === 0 && img.getAttribute('src')) {
          img.classList.add('img-broken');
        }
        if (proxyOn) {
          var u = img.getAttribute('src') || '';
          if (/^https?:/i.test(u) && preload.length < 8) preload.push(u);
        } else if (window.Native && typeof window.Native.httpB64 === 'function') {
          // 快车道没开着：外链图片一律走原生通道拉（WebView 直连 raw 常常不通）
          queueNativeFetch(img);
        }
      });
      if (proxyOn && preload.length) {
        try { window.NativeBridge.prefetchImages(JSON.stringify(preload)); } catch (e) {}
      }
      window.MDContext.repo = prevR; window.MDContext.ref = prevF; window.MDContext.path = prevP;
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
