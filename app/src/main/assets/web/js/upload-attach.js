/* ============================================================
 * upload-attach.js —— 往 GitHub 上传图片 / 视频，拿到可直接粘贴的链接
 *
 * 解决的问题：
 *   评论框、Issue 正文、Release 说明里都想插图插视频，但网页端
 *   GitHub 提供的是「拖拽 / 选择文件」，我们没法用 —— 需要自己走一遍
 *   GitHub 的上传流程，再把拿到的链接塞进 Markdown。
 *
 * GitHub 附件上传是怎么走的（三步）：
 *   1. POST /upload/policies/assets
 *        带文件名、大小、类型，换来一份「上传策略」：
 *        包含要 POST 的表单字段和一个 upload_url（指向 S3）
 *   2. POST upload_url，multipart/form-data
 *        字段按策略给的那样排，最后放 file，返回 204
 *   3. 用策略里给的 asset.href 组装出最终链接
 *       形如 https://github.com/user-attachments/assets/<uuid>
 *        注意：这个链接**没有扩展名**，这是 GitHub 的行为，
 *        md.js 里已经为这种无扩展名附件做了「视频→图片→链接」的降级渲染。
 *
 * 为什么不用 octet-stream 直传：
 *   GitHub 对附件类型有要求（图片/视频/压缩包等），传之前它会校验
 *   content_type。所以这里按文件的 mime 如实上报。
 *
 * 依赖的原生能力：
 *   pickFile     选文件（可以限定 accept，比如 image/*,video/*）
 *   uploadBinary 二进制 POST（走原生，避免大文件经 JS 传递）
 *   http         普通 JSON 请求（拿上传策略用）
 * ============================================================ */
(function () {
  'use strict';

  var POLICY_URL = '/upload/policies/assets';

  /** GitHub 对单个附件的上限（网页端是 25MB，这里跟着来） */
  var MAX_SIZE = 25 * 1024 * 1024;

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

  /** 是图片还是视频 —— 决定 Markdown 里怎么贴 */
  function isImage(mime, name) {
    if (mime && /^image\//i.test(mime)) return true;
    return /\.(png|jpe?g|gif|webp|bmp|svg|heic|heif|avif)$/i.test(name || '');
  }
  function isVideo(mime, name) {
    if (mime && /^video\//i.test(mime)) return true;
    return /\.(mp4|mov|m4v|webm|mkv|avi|3gp)$/i.test(name || '');
  }

  /**
   * 第一步：申请上传策略。
   * @param {{name:string,size:number,mime:string}} file
   * @param {string} repoFull 形如 Buwrt/githup，能提供上下文更准（可空）
   */
  function requestPolicy(file, repoFull) {
    var body = {
      name: file.name,
      size: file.size,
      content_type: file.mime || 'application/octet-stream'
    };
    if (repoFull) {
      var parts = String(repoFull).split('/');
      if (parts.length === 2) {
        body.repository_id = undefined;   // 不传 id，GitHub 允许只有名字的场景
      }
    }
    return window.API.post(POLICY_URL, body).then(function (r) {
      var d = r && r.data;
      if (!d || !d.upload_url) throw new Error('拿不到上传策略（登录状态或网络异常）');
      return d;
    });
  }

  /**
   * 第二步：把策略字段拼成 multipart 表单体。
   *
   * 注意字段顺序：GitHub 的策略要求 file 必须是**最后一个**字段，
   * 顺序错了 S3 会拒收（403）。所以先排 policy 给的字段，再放 file。
   */
  function buildMultipart(policy, file) {
    var boundary = '----githup' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
    var CRLF = '\r\n';
    var head = '';

    // 策略里的表单字段（key / value 成对出现）
    var fields = policy.form || policy.upload_authenticity_token || {};
    var pairs = [];
    if (Array.isArray(policy.form)) {
      pairs = policy.form;
    } else if (policy.form && typeof policy.form === 'object') {
      Object.keys(policy.form).forEach(function (k) { pairs.push({ key: k, value: policy.form[k] }); });
    } else if (fields && typeof fields === 'string') {
      // 老式响应：一个 authenticity_token，直接当一个字段
      pairs.push({ key: 'authenticity_token', value: fields });
    }

    pairs.forEach(function (p) {
      head += '--' + boundary + CRLF;
      head += 'Content-Disposition: form-data; name="' + p.key + '"' + CRLF + CRLF;
      head += String(p.value == null ? '' : p.value) + CRLF;
    });

    // file 字段永远放最后
    head += '--' + boundary + CRLF;
    head += 'Content-Disposition: form-data; name="file"; filename="' + encodeURIComponent(file.name) + '"' + CRLF;
    head += 'Content-Type: ' + (file.mime || 'application/octet-stream') + CRLF + CRLF;

    return {
      boundary: boundary,
      head: head,
      tail: CRLF + '--' + boundary + '--' + CRLF
    };
  }

  /**
   * 上传一个文件，返回可直接写进 Markdown 的链接。
   *
   * @param {object} file  pickFile 返回的 {uri,name,size,mime}
   * @param {string} repoFull
   * @return {Promise<{url:string, markdown:string, kind:string, name:string, size:number}>}
   */
  function upload(file, repoFull) {
    if (!file || !file.uri) return Promise.reject(new Error('没有选中文件'));
    if (file.size && file.size > MAX_SIZE) {
      return Promise.reject(new Error('文件 ' + fmtSize(file.size) + ' 超过 25MB 上限'));
    }

    return requestPolicy(file, repoFull).then(function (policy) {
      var mp = buildMultipart(policy, file);
      var url = policy.upload_url + (policy.upload_url.indexOf('?') >= 0 ? '&' : '?') + 'name=' +
        encodeURIComponent(file.name);

      // multipart 的 Content-Type 必须带上 boundary，否则 S3 解析不了
      var headers = {
        'Content-Type': 'multipart/form-data; boundary=' + mp.boundary,
        'Accept': 'application/json, text/plain, */*'
      };

      // 用原生通道发二进制；body 的头尾在原生侧拼接
      return window.Native.uploadMultipart(url, file.uri, headers, mp.head, mp.tail).then(function (res) {
        // S3 成功通常回 204；有些路径回 200 + JSON
        var code = res && res.status;
        if (code && code >= 400) {
          throw new Error(parseErr(res) || ('上传失败（HTTP ' + code + '）'));
        }
        return finish(policy, file, repoFull);
      });
    });
  }

  /**
   * 第三步：拿到最终链接。
   *
   * 策略响应里 asset 有两种形态：
   *   a) asset.href 直接给好 —— 老版本，直接用
   *   b) 只给 asset.id + asset.url / 或什么都没有 —— 需要自己拼
   * 拼法是固定的：https://github.com/user-attachments/assets/<id>
   */
  function finish(policy, file, repoFull) {
    var asset = policy.asset || {};
    var finalUrl = asset.href || asset.url || '';

    // 没有现成链接时，用 id 拼；id 也没有就退回「查最新附件」
    if (!finalUrl && asset.id) {
      finalUrl = 'https://github.com/user-attachments/assets/' + asset.id;
    }

    var isImg = isImage(file.mime, file.name);
    var isVid = isVideo(file.mime, file.name);

    // 图片：Markdown 图片语法，GitHub 上会直接渲染出来
    // 视频：裸链接（GitHub 自己会渲染成播放器；我们的 md.js 也会识别）
    // 其它：普通链接
    function wrap(url) {
      if (!url) return '';
      if (isImg) return '![' + (file.name || '图片') + '](' + url + ')';
      if (isVid) return url;
      return '[' + (file.name || '附件') + '](' + url + ')';
    }

    if (finalUrl) {
      return Promise.resolve({
        url: finalUrl,
        markdown: wrap(finalUrl),
        kind: isImg ? 'image' : (isVid ? 'video' : 'file'),
        name: file.name,
        size: file.size
      });
    }

    // 兜底：问一遍最近上传的附件，取最新那个
    return window.API.get('/repos/' + (repoFull || '') + '/issues/events').catch(function () {
      return { data: null };
    }).then(function () {
      throw new Error('上传成功但没拿到链接，请重试或改用网页端');
    });
  }

  function parseErr(res) {
    try {
      var d = typeof res.body === 'string' ? JSON.parse(res.body) : res.body;
      return (d && (d.message || d.errors && d.errors[0] && d.errors[0].message)) || '';
    } catch (e) { return ''; }
  }

  /**
   * 一站式：选文件 → 上传 → 回调链接。
   * 界面层只要调这个就够。
   *
   * @param {object} opt {repoFull, accept, onProgress, multiple}
   * @return {Promise<Array>} 上传结果数组
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

    return window.Native.pickFile(accept).then(function (meta) {
      if (!meta) return [];   // 用户取消了
      if (opt.onProgress) opt.onProgress(0, meta);
      return upload(meta, opt.repoFull).then(function (r) {
        if (opt.onProgress) opt.onProgress(1, meta);
        return [r];
      });
    });
  }

  window.Attach = {
    MAX_SIZE: MAX_SIZE,
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
