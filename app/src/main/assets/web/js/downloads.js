/* ============================================================
 * downloads.js — App 内的下载进度条
 *
 * 下载走系统 DownloadManager，进度只在通知栏里；App 里点完「下载」
 * 就没下文了，用户不知道在传、传了多少。这里轮询原生的
 * downloadStatus()，在底栏上方画一条进度，全部结束自动收起。
 *
 * 只报「还没收到完成广播」的任务（原生层在完成时会把任务从表里
 * 移走），所以列表天然就是进行中的那些，不用前端自己算差集。
 * ============================================================ */
(function () {
  'use strict';

  if (!(window.NativeBridge && typeof window.NativeBridge.downloadStatus === 'function'))
    return;   // 浏览器 Demo / 低版本原生没有这个方法，不出进度条

  /* DownloadManager 的状态常量 */
  var STATUS_PAUSED = 4, STATUS_SUCCESSFUL = 8, STATUS_FAILED = 16;

  var bar = null, fill = null, nameEl = null, extraEl = null, pctEl = null;
  var timer = null;
  /* 算速度用的采样点：换道后任务 id 会变，那时必须重新起算 */
  var lastId = null, lastSofar = -1, lastTs = 0, speedTxt = '';

  function fmtSpeed(bps) {
    if (bps >= 1024 * 1024) return (bps / 1048576).toFixed(1) + ' MB/s';
    if (bps >= 1024) return Math.round(bps / 1024) + ' KB/s';
    return Math.round(bps) + ' B/s';
  }

  function ensureBar() {
    if (bar) return;
    bar = document.createElement('div');
    bar.id = 'dl-bar';
    bar.innerHTML =
      '<div class="dl-row">' +
        '<span class="dl-ico">↓</span>' +
        '<span class="dl-name"></span>' +
        '<span class="dl-extra"></span>' +
        '<span class="dl-pct"></span>' +
      '</div>' +
      '<div class="dl-track"><div class="dl-fill"></div></div>';
    (document.getElementById('app') || document.body).appendChild(bar);
    fill = bar.querySelector('.dl-fill');
    nameEl = bar.querySelector('.dl-name');
    extraEl = bar.querySelector('.dl-extra');
    pctEl = bar.querySelector('.dl-pct');
  }

  function hide() { if (bar) bar.classList.remove('show'); }

  function tick() {
    var list;
    try { list = JSON.parse(window.NativeBridge.downloadStatus() || '[]'); }
    catch (e) { return; }
    if (!list || !list.length) { hide(); return; }
    /* 终态（成功/失败）的任务**绝不显示** —— 以前完成广播偶尔丢一次，
     * 100% 的条就会每 800ms 弹出来一次，永远收不了场。
     * 原生层现在会把终态任务就地收尾（主防线），这里再滤一道兜底。 */
    list = list.filter(function (t) {
      return t.status !== STATUS_SUCCESSFUL && t.status !== STATUS_FAILED;
    });
    if (!list.length) { hide(); return; }
    ensureBar();

    var t = list[0];
    var total = Number(t.total) || 0;
    var failed = t.status === STATUS_FAILED;
    var paused = t.status === STATUS_PAUSED;
    var done = t.status === STATUS_SUCCESSFUL;
    var pct = null;
    if (total > 0) pct = Math.max(0, Math.min(100, Math.round((Number(t.sofar) || 0) * 100 / total)));

    var name = String(t.name || '下载中');
    if (list.length > 1) name += ' 等 ' + list.length + ' 个';
    nameEl.textContent = name;
    nameEl.title = name;

    /* 速度：两次采样做差。原生层发现太慢会自动换道（任务 id 跟着变），
     * 所以这里顺便把「走的哪条通道」显示出来 —— 用户能看到它在自救，
     * 比干等着一个不动的进度条强得多。 */
    var sofar = Number(t.sofar) || 0, now = Date.now();
    if (t.id !== lastId) { lastId = t.id; lastSofar = -1; lastTs = 0; speedTxt = ''; }
    if (lastTs && lastSofar >= 0 && now - lastTs > 400 && sofar >= lastSofar) {
      speedTxt = fmtSpeed((sofar - lastSofar) * 1000 / (now - lastTs));
    }
    lastSofar = sofar; lastTs = now;

    var tail = failed ? '失败' : (paused ? '等待网络'
      : ((t.ch ? t.ch : '') + (speedTxt ? ' · ' + speedTxt : '')));
    extraEl.textContent = tail;
    extraEl.classList.toggle('dl-err', failed);
    pctEl.textContent = failed ? '✕' : (pct == null ? '…' : pct + '%');

    fill.classList.toggle('indeterminate', pct == null && !failed);
    fill.classList.toggle('failed', failed);
    fill.style.width = failed ? '100%' : (pct == null ? '30%' : pct + '%');

    bar.classList.add('show');

    /* 完成广播一到原生就会把任务移走，这里只是兜底别停在 100% */
    if (done && list.length === 1) setTimeout(hide, 1200);
  }

  function start() {
    if (timer) return;
    timer = setInterval(tick, 800);
    tick();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
