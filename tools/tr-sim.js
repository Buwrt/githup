#!/usr/bin/env node
/* ============================================================
 * tr-sim.js —— 翻译「慢不慢」的对照实验台
 *
 * 为什么要有它：
 *   「翻译慢」这种事，靠读代码只能猜。改之前 40 段英文多久出第一个中文、
 *   换页之后还有多少请求在白跑，都是能测出来的量，测了才知道改得对不对。
 *
 * 它是怎么测的：
 *   用 jsdom 起一个假页面（几十段英文文本节点），把 translate.js 塞进去真跑；
 *   网络层整个换成假的：请求一来就记账，120ms 后按「进几行出几行」回一份译文
 *   （有道 aidemo 端点的真实行为，实测单条 ~120ms）。
 *   于是「引擎快慢」这个变量被固定住，剩下的差异就全是翻译模块自己的。
 *
 * 四种场景：
 *   full     一口气翻完，量「首个译文上屏」和「全部上屏」
 *   switch   翻到一半换页，量「换页后还发出多少请求」（僵尸链）
 *   regress  回归：段数会不会被数成两倍、缓存还命中吗、还原干净吗
 *   throttle 前两拍返回 411，看节流间隔会不会自动放宽
 *
 * 依赖 jsdom（只在本工具用，不进 APK）：
 *   npm i jsdom
 *
 * 用法：
 *   node tools/tr-sim.js <translate.js 路径> <标签> <场景>
 *   例：node tools/tr-sim.js app/src/main/assets/web/js/translate.js 改后 full
 *
 * 想看改前改后的差别，别手跑两次，用 tools/run-tr-sim.sh ——
 * 它会把「改动前那一版」从 git 里取出来，四种场景各跑一遍并排打出来。
 * ============================================================ */
'use strict';

var fs = require('fs');
var path = require('path');

var SRC = process.argv[2];
var LABEL = process.argv[3] || '当前';
var MODE = process.argv[4] || 'full';

var SEG = Number(process.env.TR_SIM_SEG || (MODE === 'big' ? 200 : 40));   // 页面上的英文段数
var RTT = Number(process.env.TR_SIM_RTT || 120);  // 假引擎的往返耗时（ms）
var SWITCH_AT = Number(process.env.TR_SIM_SWITCH || 1500);

var JSDOM;
try {
  JSDOM = require('jsdom').JSDOM;
} catch (e) {
  console.error('缺少 jsdom，先装一下：  npm i jsdom');
  process.exit(2);
}

var dom = new JSDOM(
  '<!doctype html><html><body><div id="appbar-actions"></div><div id="view"></div></body></html>',
  { runScripts: 'outside-only', pretendToBeVisual: true });
var w = dom.window;
Object.defineProperty(w, 'innerHeight', { value: 800 });

/* ---------- 假网络：记账 + 按规则回包 ---------- */
var reqCount = 0;
var timeline = [];
var T0 = Date.now();
var fail411 = {};              // 第几次请求返回 411（throttle 场景用）
var inflight = 0, maxInflight = 0;
function nowMs() { return Date.now() - T0; }

w.NativeBridge = {
  http: function (id, method, url, body) {
    reqCount++;
    inflight++; if (inflight > maxInflight) maxInflight = inflight;
    timeline.push(nowMs());
    var q = decodeURIComponent(String(body || '').replace(/^q=/, '').split('&')[0]);
    var lines = q.split('\n');
    if (process.env.TR_SIM_VERBOSE) {
      console.log('        [req#' + reqCount + ' ' + nowMs() + 'ms] ' + url.slice(0, 60) +
        ' | 入 ' + lines.length + ' 行 | ' + JSON.stringify(q.slice(0, 30)));
    }
    var payload;
    if (fail411[reqCount]) {
      payload = { errorCode: '411', msg: '请求频率过快' };
    } else {
      payload = {
        errorCode: '0',
        translation: [lines.map(function (l, i) { return '【译' + (i + 1) + '】' + l.slice(0, 10); }).join('\n')]
      };
    }
    setTimeout(function () {
      inflight--;
      w.Native._cb(id, 200, JSON.stringify(payload), '{}');
    }, RTT);
  }
};
w.Native = {
  has: function () { return true; },
  pending: {},
  _cb: function (id, status, body) {
    var p = w.Native.pending[id];
    if (p) { delete w.Native.pending[id]; p(status, body); }
  },
  http: function (method, url, body, headers) {
    var self = this;
    return new Promise(function (resolve) {
      var id = String(Math.random()).slice(2);
      self.pending[id] = function (s, b) { resolve({ status: s, body: b }); };
      w.NativeBridge.http(id, method, url, body, JSON.stringify(headers || {}));
    });
  }
};
w.UI = { toast: function (m) { if (process.env.TR_SIM_VERBOSE) console.log('        [toast] ' + m); } };
w.Store = { getJSON: function (k, d) { return d; }, setJSON: function () {}, get: function () { return null; }, set: function () {} };

/* ---------- 假页面 ---------- */
var view = w.document.getElementById('view');
function buildPage(prefix) {
  view.innerHTML = '';
  /* 段间距要随段数收敛：collect 只收「视口 ±600px」里的段，
   * 固定 18px 的话 200 段会排到 3600px 外，大半根本进不了翻译，
   * 于是「200 段翻完」这种场景永远测不出来。 */
  var step = Math.min(18, 1200 / SEG);
  for (var i = 0; i < SEG; i++) {
    var d = w.document.createElement('div');
    d.textContent = prefix + ' repository description number ' + i + ' english text';
    (function (top) {
      d.getBoundingClientRect = function () { return { top: top, bottom: top + 18 }; };
    })(100 + i * step);
    view.appendChild(d);
  }
}
buildPage('Explore');

/* ---------- 把 translate.js 装进去 ---------- */
w.eval(fs.readFileSync(path.resolve(SRC), 'utf8'));
if (typeof w.GhTranslator === 'undefined') {
  w.document.dispatchEvent(new w.Event('DOMContentLoaded'));
}
// 固定用有道：探测顺序本身不该混进测量里
w.GhTranslator.setEngine('youdao');

function doneCount() {
  var n = 0;
  view.querySelectorAll('div').forEach(function (d) {
    if (d.firstChild && /^【译/.test(d.firstChild.nodeValue)) n++;
  });
  return n;
}
function gaps() {
  var g = [];
  for (var i = 1; i < timeline.length; i++) {
    var d = timeline[i] - timeline[i - 1] - RTT;
    // 并发时相邻请求的间隔会小于一个往返，打印成负数反而看不懂
    g.push(d < 0 ? '并发' : d + 'ms');
  }
  return g;
}
function tl() {
  return timeline.map(function (t, i) { return t + 'ms(第' + (i + 1) + '次)'; }).join(' → ');
}

var marks = {};

/* 总超时必须有：撞上限流的旧实现会把一半段落留在英文，
 * 「全部上屏」永远等不到 —— 没有这道闸，脚本就挂在那儿不返回了。 */
var CAP = Number(process.env.TR_SIM_CAP || 20000);
function run(full) {
  return new Promise(function (resolve) {
    var poll = setInterval(function () {
      var d = doneCount();
      if (d >= 1 && !marks.first) marks.first = nowMs();
      if (d >= SEG && !marks.all) { marks.all = nowMs(); clearInterval(poll); resolve(); }
    }, 15);
    setTimeout(function () { w.GhTranslator.translate(true); }, 60);
    if (!full) setTimeout(function () { clearInterval(poll); resolve(); }, 9000);
    setTimeout(function () {
      if (!marks.all) { marks.gave = true; clearInterval(poll); resolve(); }
    }, CAP);
  });
}

function allTxt() {
  return marks.all ? (marks.all + 'ms') : ('未完成（只上了 ' + doneCount() + '/' + SEG + ' 段）');
}

if (MODE === 'full' || MODE === 'big') {
  run(true).then(function () {
    console.log('[' + LABEL + '] 首个译文上屏 ' + (marks.first || '未') + 'ms ｜ 全部 ' + SEG +
      ' 段上屏 ' + allTxt() + ' ｜ 请求 ' + reqCount + ' 次 ｜ 峰值并发 ' + maxInflight);
    console.log('        ' + tl().slice(0, 400));
    process.exit(0);
  });
} else if (MODE === 'switch') {
  setTimeout(function () {
    var before = reqCount;
    buildPage('RepoPage');
    w.dispatchEvent(new w.Event('hashchange'));      // 换页
    setTimeout(function () {
      console.log('[' + LABEL + '] 换页时已发 ' + before + ' 次；换页后又发出 ' + (reqCount - before) + ' 次');
      console.log('        ' + tl());
      process.exit(0);
    }, 2500);
  }, SWITCH_AT);
  setTimeout(function () { w.GhTranslator.translate(true); }, 60);
} else if (MODE === 'regress') {
  run(true).then(function () {
    var st = w.GhTranslator._state();
    var after1 = reqCount;
    console.log('[' + LABEL + '] 第一轮 okCount = ' + st.okCount + '（应等于 ' + SEG +
      '，翻倍说明流式上屏和收尾重复计数了）');
    return new Promise(function (res) { w.GhTranslator.translate(true).then(res); }).then(function () {
      console.log('        第二轮新增请求 ' + (reqCount - after1) + ' 次（全命中缓存应为 0）');
      w.GhTranslator.restore();
      var back = 0;
      view.querySelectorAll('div').forEach(function (d) {
        if (d.firstChild && !/^【译/.test(d.firstChild.nodeValue)) back++;
      });
      console.log('        还原后回到英文的段数 = ' + back + '（应等于 ' + SEG + '）');
      process.exit(0);
    });
  });
} else if (MODE === 'throttle') {
  /* 并发突发时前几拍撞上限流：重点不是间隔变成多少，而是
   * 「降并发 + 冷却」之后还能不能翻完 —— 卡死或一路 411 到底都算失败。 */
  for (var i = 2; i <= 7; i++) fail411[i] = true;
  run(true).then(function () {
    console.log('[' + LABEL + '] 前 6 拍撞 411：全部 ' + SEG + ' 段上屏 ' +
      allTxt() + ' ｜ 请求 ' + reqCount + ' 次');
    console.log('        相邻请求间隔（扣掉 ' + RTT + 'ms 往返）：' + gaps().slice(0, 12).join(', '));
    process.exit(0);
  });
} else {
  console.error('未知场景：' + MODE + '（可选 full / big / switch / regress / throttle）');
  process.exit(2);
}
