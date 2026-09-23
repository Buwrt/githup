/* ============================================================
 * upload-attach.js —— 往 GitHub 上传图片 / 视频，拿到可直接粘贴的链接
 *
 * 解决的问题：
 *   评论框、Issue 正文、Release 说明里都想插图插视频，但网页端
 *   GitHub 提供的是「拖拽 / 选择文件」，我们没法用 —— 需要自己走一遍
 *   GitHub 的上传流程，再把拿到的链接塞进 Markdown。
 *
 * 现在走的是哪条路（一步到位）：
 *   POST https://uploads.github.com/user-attachments/assets
 *        ?name=<文件名>&content_type=<mime>&repository_id=<仓库数字 id>
 *   Authorization: Bearer <token>，请求体就是文件的原始字节。
 *   成功回 201 + {"url": "https://github.com/user-attachments/assets/<uuid>"}
 *
 *   这正是网页端拖拽上传用的那个接口，所以上传出来的附件会**继承仓库的
 *   可见性**（私有仓库里的图，外人打不开），比传到公共图床安全。
 *
 * 为什么不再用「先取策略再传 S3」的老三步：
 *   老流程第一步要打 https://api.github.com/upload/policies/assets，
 *   而 /upload/... 是 github.com（网页）的路由，api.github.com 上根本没有，
 *   服务端直接回 404 —— 界面上就只剩一句莫名其妙的「上传失败：Not Found」。
 *   老流程现在只作为兜底保留（万一新接口哪天被改掉），而且把域名改成了
 *   正确的 github.com。
 *
 * 关于 repository_id：
 *   必须是**数字 id**（GET /repos/{full} 的 .id）。用 GraphQL 那种
 *   MDEwOlJlcG9zaXRvcnkx… 的节点 id 会 404。拿不到就先不带这个参数上传。
 *
 * 依赖的原生能力：
 *   pickFile     选文件（可以限定 accept，比如 image/*,video/*）
 *   uploadRaw    流式二进制 POST（不把文件读进内存，适合十几 MB 的视频）
 *   http         普通 JSON 请求（查仓库 id 用）
 * ============================================================ */
(function () {
  'use strict';

  /* 一步直传的端点 */
  var UPLOAD_URL = 'https://uploads.github.com/user-attachments/assets';

  /* 兜底用：网页端同款的「上传策略」接口。
   * 注意是 github.com —— 写 api.github.com 会 404（就是之前那个 Not Found）。 */
  var LEGACY_POLICY_URL = 'https://github.com/upload/policies/assets';

  /**
   * GitHub 附件大小上限。
   * 图片 / GIF / 普通文件 10MB；视频免费计划 10MB、付费计划 100MB，
   * 所以视频先按 100MB 放行，超了让服务端自己说话（会带具体原因）。
   */
  var LIMIT_IMAGE = 10 * 1024 * 1024;
  var LIMIT_VIDEO = 100 * 1024 * 1024;
  var LIMIT_FILE = 10 * 1024 * 1024;

  /** 判断能不能选文件（浏览器环境没有原生桥，就退化成提示） */
  function canUpload() {
    return !!(window.Native && window.Native.canPick && window.Native.canPick());
  }

  /** 人类可读的大小 */
  function fmtSize(n) {
    if (!n && n !== 0) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(1) + ' MB';
  }

  /** 是图片还是视频 —— 决定 Markdown 里怎么贴、按哪个上限卡 */
  function isImage(mime, name) {
    if (mime && /^image\//i.test(mime)) return true;
    return /\.(png|jpe?g|gif|webp|bmp|svg|heic|heif|avif)$/i.test(name || '');
  }
  function isVideo(mime, name) {
    if (mime && /^video\//i.test(mime)) return true;
    return /\.(mp4|mov|m4v|webm|mkv|avi|3gp)$/i.test(name || '');
  }

  function kindOf(file) {
    return isImage(file.mime, file.name) ? 'image' : (isVideo(file.mime, file.name) ? 'video' : 'file');
  }

  /** 把上传结果包成界面要的形状（图片用 ![]()，视频裸链，其它普通链接） */
  function pack(url, file) {
    var kind = kindOf(file);
    var md;
    if (kind === 'image') md = '![' + (file.name || '图片') + '](' + url + ')';
    else if (kind === 'video') md = url;      // GitHub 会自己渲染成播放器
    else md = '[' + (file.name || '附件') + '](' + url + ')';
    return { url: url, markdown: md, kind: kind, name: file.name, size: file.size };
  }

  /** 从 GitHub 的报错体里抠一句人话 */
  function parseErr(res) {
    try {
      var d = typeof res.body === 'string' ? JSON.parse(res.body) : res.body;
      var m = (d && (d.message || (d.errors && d.errors[0] && d.errors[0].message))) || '';
      if (m) return m;
    } catch (e) { /* 不是 JSON，走下面 */ }
    // S3 那类返回的是 XML，抠一下 <Message>...</Message>
    try {
      var mm = String(res && res.body || '').match(/<Message>([^<]+)<\/Message>/);
      if (mm) return mm[1];
    } catch (e2) { /* 忽略 */ }
    return '';
  }

  /** 把常见状态码翻成中文，别让用户对着 422 发呆 */
  function httpHint(code, msg) {
    if (code === 401) return '登录状态已失效，请重新登录';
    if (code === 403) return '没有该仓库的权限，或已触发速率限制';
    if (code === 404) return '上传接口不可用（404），可能是仓库 id 失效或仓库不可见';
    if (code === 422) return msg || '文件类型或大小不被接受（422）';
    return msg || ('上传失败（HTTP ' + code + '）');
  }

  /* ---------- 仓库数字 id ---------- */
  var _repoIds = Object.create(null);

  /**
   * 拿仓库的**数字** id（不是 GraphQL 的 node id）。
   * 拿不到就返回 null —— 调用方照样能传，只是附件不挂到具体仓库上。
   */
  function repoId(full) {
    if (!full || full.indexOf('/') < 0) return Promise.resolve(null);
    if (_repoIds[full] !== undefined) return Promise.resolve(_repoIds[full]);
    return window.API.get('/repos/' + full, null, { cache: 3600000 }).then(function (r) {
      var id = r && r.data && r.data.id;
      _repoIds[full] = id || null;
      return _repoIds[full];
    }).catch(function () {
      _repoIds[full] = null;
      return null;
    });
  }

  /**
   * 一步直传：POST uploads.github.com/user-attachments/assets
   * 请求体就是文件本身，走原生流式发送，十几 MB 的视频也不会撑爆内存。
   */
  function uploadDirect(file, repoFull) {
    return repoId(repoFull).then(function (rid) {
      var q = 'name=' + encodeURIComponent(file.name || 'file') +
        '&content_type=' + encodeURIComponent(file.mime || 'application/octet-stream');
      if (rid) q += '&repository_id=' + encodeURIComponent(rid);
      var url = UPLOAD_URL + '?' + q;

      var headers = {
        'Authorization': 'Bearer ' + ((window.Session && window.Session.token) || ''),
        'Accept': 'application/json',
        'Content-Type': file.mime || 'application/octet-stream',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'githup/1.0'
      };

      // 有专用流式通道就用；老版本应用没有 uploadRaw 时，
      // 退成「头尾为空的 multipart」—— 效果等价于裸体 POST。
      var p;
      if (window.Native && window.Native.uploadRaw) {
        p = window.Native.uploadRaw(url, file.uri, headers);
      } else {
        p = window.Native.uploadMultipart(url, file.uri, headers, '', '');
      }

      return p.then(function (res) {
        var code = (res && res.status) || 0;
        if (!code || code >= 400) {
          var e = new Error(httpHint(code, parseErr(res)));
          e.status = code;
          throw e;
        }
        var data = null;
        try { data = res.body ? JSON.parse(res.body) : null; } catch (e2) { data = null; }
        // 成功体形如 {"url":"https://github.com/user-attachments/assets/<uuid>"}
        // 也见过套一层 asset 的，一并兜住
        var u = data && (data.url || data.href ||
          (data.asset && (data.asset.href || data.asset.url)));
        if (!u) {
          var e3 = new Error('上传成功但没拿到链接，请重试');
          e3.status = code;
          throw e3;
        }
        return pack(u, file);
      });
    });
  }

  /**
   * 老三步（兜底）：取策略 → 传 S3 → 拼链接。
   * 只在直传接口整个不存在（404/405/410/501）时才试，平时用不到。
   */
  function requestPolicy(file) {
    var body = {
      name: file.name,
      size: file.size,
      content_type: file.mime || 'application/octet-stream'
    };
    return window.API.post(LEGACY_POLICY_URL, body).then(function (r) {
      var d = r && r.data;
      if (!d || !d.upload_url) throw new Error('拿不到上传策略（登录状态或网络异常）');
      return d;
    });
  }

  /** 把策略字段拼成 multipart 表单体；file 必须是最后一个字段，否则 S3 拒收 */
  function buildMultipart(policy, file) {
    var boundary = '----githup' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
    var CRLF = '\r\n';
    var head = '';

    var fields = policy.form || policy.upload_authenticity_token || {};
    var pairs = [];
    if (Array.isArray(policy.form)) {
      pairs = policy.form;
    } else if (policy.form && typeof policy.form === 'object') {
      Object.keys(policy.form).forEach(function (k) { pairs.push({ key: k, value: policy.form[k] }); });
    } else if (fields && typeof fields === 'string') {
      pairs.push({ key: 'authenticity_token', value: fields });
    }

    pairs.forEach(function (p) {
      head += '--' + boundary + CRLF;
      head += 'Content-Disposition: form-data; name="' + p.key + '"' + CRLF + CRLF;
      head += String(p.value == null ? '' : p.value) + CRLF;
    });

    head += '--' + boundary + CRLF;
    head += 'Content-Disposition: form-data; name="file"; filename="' + encodeURIComponent(file.name) + '"' + CRLF;
    head += 'Content-Type: ' + (file.mime || 'application/octet-stream') + CRLF + CRLF;

    return {
      boundary: boundary,
      head: head,
      tail: CRLF + '--' + boundary + '--' + CRLF
    };
  }

  /** 老流程里「拿到最终链接」这一步 */
  function legacyFinish(policy, file) {
    var asset = policy.asset || {};
    var finalUrl = asset.href || asset.url || '';
    if (!finalUrl && asset.id) finalUrl = 'https://github.com/user-attachments/assets/' + asset.id;
    if (!finalUrl) throw new Error('上传成功但没拿到链接，请重试或改用网页端');
    return pack(finalUrl, file);
  }

  function uploadLegacy(file) {
    return requestPolicy(file).then(function (policy) {
      var mp = buildMultipart(policy, file);
      var url = policy.upload_url + (policy.upload_url.indexOf('?') >= 0 ? '&' : '?') +
        'name=' + encodeURIComponent(file.name);
      var headers = {
        'Content-Type': 'multipart/form-data; boundary=' + mp.boundary,
        'Accept': 'application/json, text/plain, */*'
      };
      return window.Native.uploadMultipart(url, file.uri, headers, mp.head, mp.tail).then(function (res) {
        var code = res && res.status;
        if (!code || code >= 400) {
          var e = new Error(parseErr(res) || ('上传失败（HTTP ' + code + '）'));
          e.status = code || 0;
          throw e;
        }
        return legacyFinish(policy, file);
      });
    });
  }

  /**
   * 上传一个文件，返回可直接写进 Markdown 的链接。
   *
   * @param {object} file  pickFile 返回的 {uri,name,size,mime}
   * @param {string} repoFull 形如 Buwrt/githup
   * @return {Promise<{url:string, markdown:string, kind:string, name:string, size:number}>}
   */
  function upload(file, repoFull) {
    if (!file || !file.uri) return Promise.reject(new Error('没有选中文件'));

    var kind = kindOf(file);
    var limit = kind === 'image' ? LIMIT_IMAGE : (kind === 'video' ? LIMIT_VIDEO : LIMIT_FILE);
    if (file.size && file.size > limit) {
      return Promise.reject(new Error('文件 ' + fmtSize(file.size) + ' 超过上限（' + fmtSize(limit) + '）'));
    }
    if (window.Native && !window.Native.uploadRaw && !window.Native.uploadMultipart) {
      return Promise.reject(new Error('当前版本不支持附件上传'));
    }

    return uploadDirect(file, repoFull).catch(function (e) {
      var s = e && e.status;
      // 只有「接口本身不存在/不支持」时才换老路走一遍
      if (s === 404 || s === 405 || s === 410 || s === 501) return uploadLegacy(file);
      throw e;
    });
  }

  /**
   * 一站式：选文件 → 上传 → 回调链接。
   * 界面层只要调这个就够。
   *
   * 支持一次选多个：逐个上传（串行，避免同时开好几条大流量连接），
   * 全部完成后一起返回 —— 界面上表现为「按钮一直转，转完把链接都插进去」。
   *
   * @param {object} opt {repoFull, accept, onProgress, multiple}
   * @return {Promise<Array>} 上传结果数组（取消时为空数组）
   */
  function pickAndUpload(opt) {
    opt = opt || {};
    if (!window.Session || !window.Session.isLogin) {
      return Promise.reject(new Error('请先登录'));
    }
    if (!canUpload()) {
      return Promise.reject(new Error('当前环境不支持选择文件'));
    }

    var accept = opt.accept || 'image/*,video/*';
    // 多选走 pickFiles；只有明确单选时才用 pickFile
    var picker = (opt.multiple === false) ? window.Native.pickFile(accept).then(function (m) {
      return m ? [m] : [];
    }) : window.Native.pickFiles(accept);

    return picker.then(function (list) {
      if (!list || !list.length) return [];   // 用户取消了

      var out = [];
      var failed = [];
      // 串行上传：大文件并发会把连接和内存一起挤爆
      var chain = Promise.resolve();
      list.forEach(function (meta, idx) {
        chain = chain.then(function () {
          if (opt.onProgress) opt.onProgress(idx / list.length, meta);
          return upload(meta, opt.repoFull).then(function (r) {
            out.push(r);
            if (opt.onProgress) opt.onProgress((idx + 1) / list.length, meta);
          }).catch(function (e) {
            // 一个失败不拖累其它：记下来继续传下一个
            failed.push({ name: meta.name, message: e && e.message ? e.message : '上传失败' });
          });
        });
      });

      return chain.then(function () {
        if (!out.length && failed.length) {
          throw new Error(failed.length === 1
            ? failed[0].message
            : (failed.length + ' 个文件上传失败：' + failed[0].message));
        }
        // 部分成功时把失败的一并告知，界面层决定怎么提示
        out.failed = failed;
        return out;
      });
    });
  }

  window.Attach = {
    MAX_SIZE: LIMIT_FILE,
    LIMIT_IMAGE: LIMIT_IMAGE,
    LIMIT_VIDEO: LIMIT_VIDEO,
    canUpload: canUpload,
    isImage: isImage,
    isVideo: isVideo,
    fmtSize: fmtSize,
    upload: upload,
    pickAndUpload: pickAndUpload,
    requestPolicy: requestPolicy,
    buildMultipart: buildMultipart
  };
})();
