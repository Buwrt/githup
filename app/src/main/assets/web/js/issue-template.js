/* ============================================================
 * issue-template.js — 读仓库的 .github/ISSUE_TEMPLATE，给「新建议题」加分类
 *
 * 议题 #36：网页端开新议题会先让选开发者预设的模板，App 里直接是一张白表。
 * 这里补的是「分类 + 预填」：列出仓库配置好的模板，选一个就把标题 / 正文
 * 骨架 / 标签带进表单。**不**复刻 GitHub Issue Forms 的必填校验、下拉、
 * checkbox 等完整交互 —— 那等于把网页端整套表单引擎搬进 WebView，
 * 维护成本与回归面都远大于收益。
 *
 * 支持三种形态：
 *   1. .github/ISSUE_TEMPLATE/*.md      —— 带 YAML front matter 的老式模板
 *   2. .github/ISSUE_TEMPLATE/*.yml     —— Issue Forms（只取字段标签搭骨架）
 *   3. .github/ISSUE_TEMPLATE.md        —— 仓库根目录下的单文件模板
 * 外加 config.yml 的 blank_issues_enabled 与 contact_links。
 *
 * 零回归：仓库没配模板时整个区域隐藏，表单和以前一模一样；
 * 任何一步失败（404 / 权限 / 解析异常）都静默退化，绝不让开议题卡住。
 *
 * YAML 用极轻量的只读解析：只取顶层少量标量和一个块列表，不引第三方库。
 * 解析不出来就给空骨架，不猜、不报错。
 * ============================================================ */
(function () {
  'use strict';

  var DIR = '.github/ISSUE_TEMPLATE';
  var MAX_FILES = 8;          // 一次最多读 8 个模板，别为开个议题打十几个请求
  var CACHE_MS = 5 * 60 * 1000;

  function esc(s) { return window.Util ? window.Util.esc(s) : String(s == null ? '' : s); }

  function b64(s) {
    try {
      if (window.Util && window.Util.decodeBase64) return window.Util.decodeBase64(s || '');
      var bin = atob(String(s || '').replace(/\s/g, ''));
      var bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return new TextDecoder('utf-8').decode(bytes);
    } catch (e) { return ''; }
  }

  function unquote(v) {
    v = String(v == null ? '' : v).trim();
    if (v.length > 1 && ((v[0] === '"' && v[v.length - 1] === '"') ||
      (v[0] === "'" && v[v.length - 1] === "'"))) v = v.slice(1, -1);
    if (v === '[]' || v === 'null' || v === '~') return '';
    return v;
  }

  /** 取顶层 key 的标量值；找不到返回空串 */
  function scalar(y, key) {
    var m = new RegExp('^\\s*' + key + '\\s*:\\s*(.*?)\\s*$', 'm').exec(y);
    return m ? unquote(m[1]) : '';
  }

  /** labels 两种写法：`labels: a, b` 和块列表 `- a` */
  function listVal(y, key) {
    var m = new RegExp('^\\s*' + key + '\\s*:\\s*(.*?)\\s*$', 'm').exec(y);
    if (!m) return [];
    var inline = m[1].trim();
    if (inline && inline !== '[]') {
      if (inline.charAt(0) === '[') inline = inline.slice(1, -1);
      return inline.split(',')
        .map(function (s) { return unquote(s); })
        .filter(function (s) { return !!s; });
    }
    var lines = String(y).split(/\r?\n/), out = [], inBlock = false, base = 0;
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      if (new RegExp('^\\s*' + key + '\\s*:\\s*$').test(l)) {
        inBlock = true; base = (l.match(/^\s*/) || [''])[0].length; continue;
      }
      if (!inBlock) continue;
      var mm = /^\s*-\s+(.+?)\s*$/.exec(l);
      if (mm) { out.push(unquote(mm[1])); continue; }
      if (l.trim() && (l.match(/^\s*/) || [''])[0].length <= base) inBlock = false;
    }
    return out;
  }

  /** 老式 .md 模板：YAML front matter + 正文 */
  function parseMd(name, text) {
    var m = /^\uFEFF?---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n?([\s\S]*)$/.exec(text);
    var fm = m ? m[1] : '', body = m ? m[2] : text;
    return {
      kind: 'md',
      name: scalar(fm, 'name') || name.replace(/\.md$/i, ''),
      about: scalar(fm, 'about') || scalar(fm, 'description') || '',
      title: scalar(fm, 'title') || '',
      labels: listVal(fm, 'labels'),
      body: String(body || '').trim()
    };
  }

  /**
   * Issue Forms (.yml)：把 body 里每个字段的 label / 说明拼成 Markdown 骨架。
   * 只做「看得懂该填什么」，不做必填校验与控件复刻。
   */
  function parseForm(name, text) {
    var o = {
      kind: 'form',
      name: scalar(text, 'name') || name.replace(/\.ya?ml$/i, ''),
      about: scalar(text, 'description') || scalar(text, 'about') || '',
      title: scalar(text, 'title') || '',
      labels: listVal(text, 'labels'),
      body: ''
    };
    var sec = [];
    var blocks = String(text).split(/^\s*-\s+type\s*:/m).slice(1);
    blocks.forEach(function (b) {
      var label = scalar(b, 'label');
      if (!label) {
        // markdown 类型字段：value 本身就是一段说明，原样保留
        var v = scalar(b, 'value');
        if (v) sec.push(v);
        return;
      }
      var desc = scalar(b, 'description') || scalar(b, 'placeholder') || '';
      sec.push('## ' + label + (desc ? '\n\n' + desc : '') + '\n\n');
    });
    o.body = sec.join('\n').trim();
    return o;
  }

  function parseConfig(text) {
    var blank = scalar(text, 'blank_issues_enabled');
    var contacts = [];
    var m = /contact_links\s*:\s*([\s\S]*)$/.exec(text);
    if (m) {
      String(m[1]).split(/^\s*-\s+/m).filter(function (s) { return s.trim(); })
        .forEach(function (it) {
          var n = scalar(it, 'name'), u = scalar(it, 'url');
          if (!n) {
            var first = /^\s*name\s*:\s*(.+?)\s*$/m.exec(it);
            if (first) n = unquote(first[1]);
          }
          if (!u) {
            var fu = /^\s*url\s*:\s*(.+?)\s*$/m.exec(it);
            if (fu) u = unquote(fu[1]);
          }
          if (n && u) contacts.push({ name: n, url: u, about: scalar(it, 'about') });
        });
    }
    return { kind: 'config', blank: blank !== 'false', contacts: contacts };
  }

  function fetchText(repoFull, path) {
    return window.API.get('/repos/' + repoFull + '/contents/' + path,
      null, { cache: CACHE_MS, noLog: true }).then(function (r) {
        return b64(r && r.data && r.data.content);
      });
  }

  /** 返回 {templates:[], blank:bool, contacts:[]} —— 永远不会 reject */
  function load(repoFull) {
    var base = '/repos/' + repoFull + '/contents/';
    var empty = { templates: [], blank: true, contacts: [] };
    return window.API.get(base + DIR, null, { cache: CACHE_MS, noLog: true })
      .then(function (r) {
        var arr = (r && r.data && Array.isArray(r.data)) ? r.data : [];
        var files = arr.filter(function (f) {
          return f && f.type === 'file' && /\.(md|ya?ml)$/i.test(f.name) && f.name !== 'config.yml';
        }).slice(0, MAX_FILES);
        var cfg = arr.filter(function (f) { return f && f.name === 'config.yml'; })[0];
        var jobs = files.map(function (f) {
          return fetchText(repoFull, DIR + '/' + encodeURIComponent(f.name))
            .then(function (txt) {
              return /\.ya?ml$/i.test(f.name) ? parseForm(f.name, txt) : parseMd(f.name, txt);
            })
            .catch(function () { return null; });
        });
        if (cfg) {
          jobs.push(fetchText(repoFull, DIR + '/config.yml')
            .then(parseConfig).catch(function () { return null; }));
        }
        return Promise.all(jobs).then(function (res) {
          var templates = res.filter(function (x) { return x && (x.kind === 'md' || x.kind === 'form'); });
          var conf = res.filter(function (x) { return x && x.kind === 'config'; })[0];
          return {
            templates: templates,
            blank: conf ? conf.blank : true,
            contacts: conf ? conf.contacts : []
          };
        });
      })
      .catch(function () {
        // 没有目录：试旧式的单文件模板；再没有就当仓库没配
        return fetchText(repoFull, '.github/ISSUE_TEMPLATE.md')
          .then(function (txt) {
            return { templates: [parseMd('ISSUE_TEMPLATE.md', txt)], blank: true, contacts: [] };
          })
          .catch(function () { return empty; });
      });
  }

  /** 把分类卡片画进 host；onPick(t|null) 由调用方接管填表 */
  function render(host, data, onPick) {
    if (!host) return;
    var tpls = (data && data.templates) || [], contacts = (data && data.contacts) || [];
    if (!tpls.length && !contacts.length) { host.hidden = true; return; }
    host.hidden = false;
    var card = 'display:block;width:100%;text-align:left;margin:6px 0;padding:10px 12px;' +
      'border:1px solid var(--border,#30363d);border-radius:10px;background:var(--card,#161b22);' +
      'color:var(--text,#e6edf3);cursor:pointer';
    var sub = 'display:block;font-size:12px;opacity:.72;margin-top:3px;line-height:1.45';
    var html = '<label>' + window.I18n.t('议题分类') + '</label>' +
      '<div class="muted tiny" style="margin-bottom:6px">' +
      window.I18n.t('仓库维护者预设了议题模板，选一个会自动填好标题、正文和标签') + '</div>';
    tpls.forEach(function (t, i) {
      html += '<button type="button" class="itpl-card" data-i="' + i + '" style="' + card + '">' +
        '<strong>' + esc(t.name) + '</strong>' +
        (t.about ? '<span style="' + sub + '">' + esc(t.about) + '</span>' : '') +
        (t.labels && t.labels.length
          ? '<span style="' + sub + '">' + esc(t.labels.join(' · ')) + '</span>' : '') +
        '</button>';
    });
    if (data.blank !== false) {
      html += '<button type="button" class="itpl-card" data-blank="1" style="' + card + '">' +
        '<strong>' + window.I18n.t('空白议题') + '</strong>' +
        '<span style="' + sub + '">' + window.I18n.t('不使用模板，自己从零写') + '</span></button>';
    }
    contacts.forEach(function (c) {
      html += '<button type="button" class="itpl-card" data-contact="' + esc(c.url) + '" style="' + card + '">' +
        '<strong>' + esc(c.name) + ' ↗</strong>' +
        (c.about ? '<span style="' + sub + '">' + esc(c.about) + '</span>' : '') + '</button>';
    });
    host.innerHTML = html;
    Array.prototype.forEach.call(host.querySelectorAll('.itpl-card'), function (b) {
      b.onclick = function () {
        Array.prototype.forEach.call(host.querySelectorAll('.itpl-card'), function (x) {
          x.style.outline = '';
        });
        b.style.outline = '2px solid var(--accent,#58a6ff)';
        if (b.hasAttribute('data-contact')) {
          var u = b.getAttribute('data-contact');
          if (window.openWeb) window.openWeb(u, cName(b));
          else if (window.NativeBridge && NativeBridge.openExternal) NativeBridge.openExternal(u);
          return;
        }
        var i = b.getAttribute('data-i');
        onPick(i === null ? null : tpls[+i]);
      };
      function cName(x) { var s = x.querySelector('strong'); return s ? s.textContent : ''; }
    });
  }

  window.IssueTemplates = { load: load, render: render };
})();
