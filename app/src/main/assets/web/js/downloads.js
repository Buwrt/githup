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
    extraEl.textContent = failed ? '失败' : (paused ? '等待网络' : '');
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
