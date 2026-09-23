/* ============================================================
 * md.js — Markdown 渲染（GFM），代码高亮、站内链接转换、XSS 过滤
 * ============================================================ */
(function () {
  'use strict';
  var U = window.Util;

  // 当前渲染上下文（仓库全名 / 分支 / 文件在仓库里的路径），
  // 用于把相对链接、短 SHA、以及**相对图片地址**还原成能访问的绝对地址
  window.MDContext = { repo: null, ref: null, path: null, user: null };

  /* ============================================================
   * 记忆中的「当前仓库」—— 给没传上下文的调用点兜底
   *
   * MD.mount(容器, 正文, ctx) 的第三个参数是相对地址补全的全部依据：
   * 没有它，resolveImgUrl 走到最后只能把地址原样返回，而那个相对地址
   * 是相对**页面**（file:///android_asset/web/index.html）的，包内当然
   * 没有这个文件 —— 图片就只剩一个裂图标。
   *
   * 问题是调用点实在太多，每一处都传 ctx 是靠不住的：Star 列表、提交详情、
   * Release 说明、Actions 卡片、我的主页简介……漏掉任何一处，那里的图就裂。
   * 所以在这里记一笔「最近一次渲染是在哪个仓库」，谁没传就借它用。
   *
   * 只在仓库页里记，且仅当这次渲染确实带了 repo —— 绝不会凭空造出一个
   * 仓库名来。切到别的仓库会立刻改写，不存在串味。
   * ============================================================ */
  var lastRepo = null;
  var lastRef = null;

  function noteRepo(ctx) {
    if (ctx && ctx.repo) {
      lastRepo = ctx.repo;
      lastRef = ctx.ref || null;
    }
  }

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

  /* ============================================================
   * 这张图 App 里本来就有 —— 那就别上网了
   *
   * README 里有一张图写成 `app/src/main/assets/web/img/tips.png`
   * （仓库内路径），而那正是 App 自己资源文件夹的相对路径。
   * 照字面补成 raw 地址的话，用户每翻一次 README 就要走一趟 GitHub：
   * 400 多 KB、国内网络动辄好几秒 —— 屏幕上就是「图半天不出来」，
   * 而包里明明躺着同一个文件（逐字节一致）。
   *
   * 所以补全之前先认一下：这个路径在 App 里存在吗？
   * 存在就用本地那份（同目录、直接从 file: 读，不联网、不占并发）。
   * 认的名单是写死的 —— 只有确实同时存在于仓库和包内的图才在里面，
   * 免得把仓库里别的图片也错误地换成本地文件。
   * ============================================================ */
  /* 包内 web/ 目录下已有的图（相对 web/ 的路径）。
   * 只有确实两边都存在的才列在这里 —— 写全路径是故意的：
   * 换成「按前缀放行」就等于承认「assets 下的任何文件都能当图片读」，
   * 那条口子不该开（防护链清单也在 assets 里）。 */
  var LOCAL_IMAGES = {
    /* 仓库内路径（README 里怎么写就怎么列）→ 包内相对 web/ 的路径 */
    'app/src/main/assets/web/img/tips.png': 'img/tips.png'
  };

  function localImageFor(path) {
    var p = String(path || '').replace(/\\/g, '/').replace(/^\/+/, '').split(/[?#]/)[0];
    var hit = LOCAL_IMAGES[p];
    return hit || null;
  }

  function resolveImgUrl(href) {
    var h = String(href == null ? '' : href).trim();
    if (!h) return h;
    if (/^(?:data|blob):/i.test(h)) return h;
    if (/^\/\//.test(h)) return 'https:' + h;                 // 协议相对地址

    /* 仓库里那份图，App 里正好也有同一张 —— 直接用本地的（见 localImageFor）。
     * 这一步要排在「补成 raw 地址」之前，否则补完就再也认不出来了。 */
    var local = localImageFor(h);
    if (local) return local;

    var m = h.match(/^https?:\/\/(?:www\.)?github\.com\/([^\/]+)\/([^\/]+)\/(?:blob|raw)\/([^\/]+)\/(.+)$/i);
    if (m) {
      var hit = localImageFor(m[4]);
      if (hit) return hit;
      return rawUrl(m[1] + '/' + m[2], m[3], m[4]);
    }
    /* 直链形式的同一张图（README 里就写着 raw 地址那种）也一样处理 */
    var rm = h.match(/^https?:\/\/raw\.githubusercontent\.com\/([^\/]+)\/([^\/]+)\/[^\/]+\/(.+)$/i);
    if (rm) {
      var rl = localImageFor(rm[3]);
      if (rl) return rl;
    }
    if (/^[a-z][a-z0-9+.-]*:/i.test(h)) return h;             // 其它绝对地址原样
    if (h.charAt(0) === '#') return h;                        // 页内锚点，不是图片
    /* 走到这儿说明是相对地址（README / 议题正文里最常见的那种）。
     * 先看这次渲染的上下文，没有再借「上一个仓库」—— 见 lastRepo 的说明。
     * 两者都没有时才原样返回（比如还没进过任何仓库页）。 */
    var ctx = window.MDContext;
    var repo = (ctx && ctx.repo) || lastRepo;
    if (!repo) return h;
    var ref = (ctx && ctx.repo && ctx.ref) || lastRef;
    var base = (ctx && ctx.repo && ctx.path) || '';
    // 以 / 开头 = 相对仓库根（GitHub 网页版就是这个语义），否则相对正文所在目录
    return rawUrl(repo, ref,
      h.charAt(0) === '/' ? joinPath('', h) : joinPath(base, h));
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
  /* 一条正文里十几张图是常态，四条并发得排好几轮。原生通道是流式的、
   * 单张也就几百 KB，放宽到 8 条既能把排队摊平，又不至于把连接池挤干。 */
  var fetchQueue = [], fetching = 0, FETCH_CONCURRENCY = 8;

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

  function isGitHubHost(url) {
    return /^https?:\/\/(?:[^\/]*\.)?(?:githubusercontent\.com|github\.com|github\.io)\//i.test(url);
  }

  /* 只给 GitHub 自家域名带令牌 —— 把令牌发给第三方图床等于把仓库写权限交出去 */
  function headersFor(url) {
    if (!isGitHubHost(url)) return null;
    var t = (window.API && typeof window.API.getToken === 'function') ? window.API.getToken() : '';
    return t ? { Authorization: 'Bearer ' + t, Accept: '*/*' } : null;
  }

  /* ============================================================
   * 「图不见了」的根在这儿 —— 取图这件事以前只做过一次，失败就放弃
   *
   * GitHub 附件的地址是这样一行：
   *     https://github.com/user-attachments/assets/<uuid>
   * 它不是图片本体，而是一次 302：
   *     → https://private-user-images.githubusercontent.com/…?jwt=…
   *
   * 难处在于两跳要的身份互相打架：私有仓库的附件不带令牌压根不认，
   * 而带签名的 CDN 地址见到 Authorization 又常常直接回 400
   * （「只允许一种鉴权方式」）。只试一次、说完就走，
   * 用户看到的就是「上传成功了，图呢？」
   *
   * 于是把「能拿到这张图的办法」排成一队，挨个试：
   *   1. 本次会话刚传过的图 → 直接读本地文件（图本来就在手机里，
   *      没必要再绕一圈 GitHub 的 CDN —— 国内的网络连不到那一跳很常见）
   *   2. 带令牌取（私有仓库的附件要靠它）
   *   3. 不带令牌再取一次（应对上面说的那种「只认签名不认令牌」的 CDN）
   * 非 GitHub 地址只有第 3 档，行为和以前一致。
   * ============================================================ */
  var LOCAL_MAX = 12 * 1024 * 1024;   // 本地直读的上限，超过就放弃这条路

  function attemptsFor(url) {
    var out = [];
    var local = (window.Attach && typeof window.Attach.localFor === 'function')
      ? window.Attach.localFor(url) : null;
    if (local) out.push({ local: local });
    var withAuth = headersFor(url);
    if (withAuth) out.push(withAuth);
    out.push({ Accept: '*/*' });
    return out;
  }

  function dataUri(body, headers, url) {
    return 'data:' + (sniffMime(body) || mimeOf(headers, url)) + ';base64,' + body;
  }

  function isImageBytes(body) { return !!sniffMime(body); }

  /**
   * 顺着 attemptsFor 排好的队一路试下去，谁先交出字节就用谁。
   * strict=true 时还要求字节确实是张图 —— 裸附件链接那个场景要靠这点区分图 / 视频。
   * alive 返回 false 就收手（容器已经被重画了，填数据也没人要）。
   */
  function pullImage(url, list, strict, i, alive, cb) {
    if (i >= list.length || (alive && !alive())) { cb(null); return; }
    var a = list[i];
    var p = a.local ? window.Native.readFileBase64(a.local, LOCAL_MAX)
      : window.Native.httpB64(url, a);
    p.then(function (res) {
      var body = a.local ? res : (res && res.status === 200 ? res.body : '');
      var heads = a.local ? {} : ((res && res.headers) || {});
      if (body && body.length > 32 && (!strict || isImageBytes(body))) {
        cb({ body: body, headers: heads });
        return;
      }
      pullImage(url, list, strict, i + 1, alive, cb);
    }).catch(function () { pullImage(url, list, strict, i + 1, alive, cb); });
  }

  function applyBytes(img, got, url) {
    img.src = dataUri(got.body, got.headers, url);
    img.classList.remove('img-broken', 'img-retry');
    img.removeAttribute('data-retry');
    img.removeAttribute('title');
    img.onclick = function () { window.UI.viewImage(img.src); };
  }

  /* 所有取法都拿不到，就别留一块让人以为 App 坏了的空白：给个能点的提示。 */
  function markFailed(job) {
    var img = job.img;
    img.classList.add('img-broken', 'img-retry');
    img.setAttribute('data-retry', '1');
    img.title = '图片没拉回来，点一下重试';
    img.onclick = function (e) {
      if (e) { e.preventDefault(); e.stopPropagation(); }
      img.classList.remove('img-retry');
      img.removeAttribute('data-retry');
      img.removeAttribute('title');
      runFetchJob(job, function () {});
      return false;
    };
  }

  /** 一次结算的计数归并 —— 不管重试了多少次，一个 job 只占用一格并发 */
  function makeFinish() {
    var done = false;
    return function () {
      if (done) return;
      done = true;
      fetching = fetching > 0 ? fetching - 1 : 0;
      pumpFetch();
    };
  }

  function runFetchJob(job, finish) {
    var attempts = job.attempts || (job.attempts = attemptsFor(job.url));
    var alive = function () { return !!job.img.isConnected; };
    pullImage(job.url, attempts, false, 0, alive, function (got) {
      if (got && job.img.isConnected) applyBytes(job.img, got, job.url);
      else if (job.img.isConnected) markFailed(job);
      finish();
    });
  }

  function pumpFetch() {
    while (fetching < FETCH_CONCURRENCY && fetchQueue.length) {
      var job = fetchQueue.shift();
      fetching++;
      runFetchJob(job, makeFinish());
    }
  }

  function queueNativeFetch(img) {
    var url = img.getAttribute('src') || '';
    if (!/^https?:/i.test(url)) return;                       // data:/相对地址不处理
    /* 去重是按**地址**来的，不是按元素。
     *
     * 以前只打一个 data-nf 标志位就算去过重了，可偏偏同一张图在补全相对
     * 地址的前后是两个不同的地址：mount() 里第一轮拿原地址（file:// 相对
     * 路径）必然失败、排过一次队；补成 raw 地址之后 error 再触发时，
     * 标志位还挂着，兜底就这么被自己挡回去了。
     * 所以记的是「上次为哪个地址排过队」——地址变了就值得再试一次，
     * 同一地址重复失败则老老实实不排队。 */
    if (img.getAttribute('data-nf') === url) return;
    img.setAttribute('data-nf', url);
    fetchQueue.push({ img: img, url: url });
    pumpFetch();
  }

  /* ============================================================
   * 加载失败才走原生通道 —— 别再每张图都排队
   *
   * 以前这里是无条件的：每张 <img> 都塞进 fetchQueue 走一遍
   * Native.httpB64（base64 → 跨 JS 桥 → data URI）。
   * 于是同一张图被下载了两遍 —— WebView 自己拉一次，原生通道再拉一次，
   * 第二遍还要膨胀 33% 再整块字符串过桥，并发却只有 4。
   * README 里十来张图排三轮队，用户看到的就是「图片半天才出来」。
   *
   * 现在 WebView 的图片请求本身就由原生接管了（WebImageProxy 那一层，
   * 字节直接交给渲染器，还有两级缓存），这张网只在**真的失败**时才撒，
   * 作为第二道保险 —— 接管那条路没覆盖到的场景（比如某些机型上
   * 拦截没生效）依然有兜底，正常情况下则一次多余的下载都不会发生。
   * ============================================================ */
  function onImgError(img) {
    img.classList.add('img-broken');
    if (!(window.Native && typeof window.Native.httpB64 === 'function')) return;
    queueNativeFetch(img);
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
    /* decoding="async"：解码放到后台，别占着主线程 —— 图一多，光解码就能
     * 让滚动卡出好几帧，看着同样像「加载慢」。 */
    return '<img class="md-img" src="' + U.esc(resolveImgUrl(u)) + '" alt="' + U.esc(alt || '') +
      '" loading="lazy" decoding="async" data-zoom="1">';
  }

  /* 无扩展名附件的降级链：<video> 自己都拉不动 → 用原生通道把字节取回来
   * 看它到底是图还是别的 → 是图就地显示，不是就退回一个能点的链接。
   *
   * 这一步以前写的是 `new Image()`：那是让 WebView 再去拉一次同样的地址，
   * 而这整条机制之所以存在，前提恰恰就是「WebView 直连 GitHub 附件拉不动」。
   * 把降级交给刚刚失败过的同一条网络路径，等于什么都没做 —— 用户看到的
   * 就是「我传了图，图不显示」。改走原生通道之后这一步才有意义。 */
  function probeMedia(v) {
    if (v.getAttribute('data-probed')) return;
    v.setAttribute('data-probed', '1');
    var url = v.getAttribute('src');
    if (!url) return;
    var stepped = false;

    function swap(el) { if (v.parentNode) v.parentNode.replaceChild(el, v); }

    function asLink() {
      var a = document.createElement('a');
      a.className = 'md-attach-link';
      a.href = url;
      a.target = '_blank';
      a.rel = 'noopener';
      a.textContent = '📎 打开附件';
      swap(a);
    }

    function asImage(src) {
      var el = document.createElement('img');
      el.className = 'md-img';
      el.src = src;
      el.alt = '';
      el.setAttribute('data-zoom', '1');
      el.onclick = function () { window.UI.viewImage(src); };
      swap(el);
    }

    function stepImage() {
      if (window.Native && typeof window.Native.httpB64 === 'function') {
        pullImage(url, attemptsFor(url), true, 0, null, function (got) {
          if (got) asImage(dataUri(got.body, got.headers, url));
          else asLink();
        });
        return;
      }
      var probe = new Image();               // 浏览器 Demo：没有原生桥，退回原先的猜测
      probe.onload = function () { asImage(url); };
      probe.onerror = asLink;
      probe.src = url;
    }

    function go() {
      if (stepped) return;
      stepped = true;
      stepImage();
    }

    v.addEventListener('error', go);
    /* 有些 WebView 版本的 <video> 取不到源时不发 error，只把 networkState
     * 停在 NETWORK_NO_SOURCE。光等 error 会把这类环境漏掉，所以再盯一眼状态。
     * 预检_metadata 还没回来（networkState=2 LOADING）时不打扰 —— 那只是慢。 */
    var ticks = 0;
    var timer = setInterval(function () {
      if (stepped || ++ticks > 10) { clearInterval(timer); return; }
      if (v.networkState === 3) { clearInterval(timer); go(); }
      if (v.parentNode && v.readyState >= 1) clearInterval(timer);
    }, 1000);
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
      noteRepo(ctx);
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
      noteRepo(ctx);
      /* README 里内联写的 <img src="a.png"> 走的是原始 HTML 那条路，
       * 不经过上面的 image 渲染器，相对地址得在这儿再补一遍。
       * render() 结束后上下文已经还原了，所以先临时挂回去。
       *
       * ctx 没传（调用方太多，总会有漏的）时把「上一个仓库」也挂上 ——
       * 不然这一轮 resolveImgUrl 认不出相对地址，图片就直接裂了。 */
      var prevR = window.MDContext.repo, prevF = window.MDContext.ref, prevP = window.MDContext.path;
      if (ctx) {
        window.MDContext.repo = ctx.repo || null;
        window.MDContext.ref = ctx.ref || null;
        window.MDContext.path = ctx.path || null;
      } else if (lastRepo) {
        window.MDContext.repo = lastRepo;
        window.MDContext.ref = lastRef;
        window.MDContext.path = null;
      }
      /* 所有图片都能点开看（不再区分内外链）；加载失败的给它一个可见的边框，
       * 免得只剩一个空白位置，让人以为是应用坏了。 */
      window.UI.$$('img', container).forEach(function (img) {
        var s = img.getAttribute('src');
        if (s) {
          var fixed = resolveImgUrl(s);
          if (fixed && fixed !== s) img.setAttribute('src', fixed);
        }
        img.onclick = function () { window.UI.viewImage(img.src); };
        img.addEventListener('error', function () { onImgError(img); });
        if (img.complete && img.naturalWidth === 0 && img.getAttribute('src')) {
          onImgError(img);
        }
      });
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
