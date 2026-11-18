/* 全层错误日志 · 前端统一上报入口（必须最先加载）
 * 业务代码里自己 catch 吞掉的异常，window.onerror 收不到。
 * 构建期会给所有 catch 插一行 window.__diagJs(...)，统一走这里，
 * 带布尔锁防止 logError 自身再抛时无限递归。 */
(function () {
  var busy = false;
  window.__diagJs = function (file, err) {
    if (busy) return;
    busy = true;
    try {
      var msg = err && err.message ? String(err.message)
              : (err != null ? String(err) : '未知异常');
      if (msg.length > 300) msg = msg.slice(0, 300) + '…';
      var st = '';
      try {
        if (err && err.stack) st = String(err.stack).split('\n').slice(0, 6).join(' | ');
      } catch (_) { }
      try {
        if (window.API && typeof window.API.logError === 'function') {
          window.API.logError('〔前端〕脚本捕获 · ' + file + '：' + msg, st);
        }
      } catch (_) { }
    } finally { busy = false; }
  };
})();
