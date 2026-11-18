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
 * 阶段 AJ 增补（议题 #36 的第二轮）：
 *   · 仓库没配模板时给出「问题反馈 / 功能建议 / 提问」三个通用分类，
 *     不再整个区域隐藏 —— 否则用户打开自己这个没放模板的仓库，
 *     看到的还是截图里那张白表，等于没修。
 *   · Issue Forms 骨架补上「（必填）」标记与下拉/复选项，更接近官网。
 *   · 单文件模板除 .github/ 外再认根目录与 docs/。
 * 零回归：任何一步失败（404 / 权限 / 解析异常）都静默退化，绝不让开议题卡住。
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
    } catch (e) {
    try{window.__diagJs&&window.__diagJs("issue-template.js",e)}catch(__diagIgnored){} return ''; }
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
      var required = /^\s*required\s*:\s*true\s*$/m.test(b);
      /* 下拉 / 复选把选项列成待勾选清单，骨架更接近官网那张表单 */
      var opts = [], inOpts = false, lines = String(b).split(/\r?\n/);
      for (var i = 0; i < lines.length; i++) {
        var l = lines[i];
        if (/^\s*options\s*:/.test(l)) { inOpts = true; continue; }
        if (!inOpts) continue;
        if (/^\s*(validations|attributes|body)\s*:/.test(l)) { inOpts = false; continue; }
        var mm = /^\s*-\s+(.+?)\s*$/.exec(l);
        if (!mm) continue;
        var val = unquote(mm[1]);
        var lm = /^\s*label\s*:\s*(.+?)\s*$/.exec(val);
        if (lm) val = unquote(lm[1]);
        if (val) opts.push('- [ ] ' + val);
      }
      sec.push('## ' + label + (required ? window.I18n.t('（必填）') : '') +
        (desc ? '\n\n' + desc : '') +
        (opts.length ? '\n\n' + opts.join('\n') : '') + '\n\n');
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

  /* 通用分类：仓库没配模板时的兜底。
     议题 #36 的截图对比的是「官方网页开新议题有分类」，而 Buwrt/githup
     自己并没有 .github/ISSUE_TEMPLATE/ —— 只认仓库文件的话，用户装上新版
     在自家仓库里仍然看不到任何变化。这三个分类不需要仓库做任何配置。 */
  var BUILTIN = [
    { kind: 'md', name: '问题反馈', about: '可复现的异常 / Bug', title: '[Bug] ', labels: ['bug'],
      body: '## 问题描述\n\n\n## 复现步骤\n1. \n2. \n\n## 期望结果\n\n\n## 实际结果\n\n\n## 环境\n- 软件版本：\n- 设备 / 系统：\n' },
    { kind: 'md', name: '功能建议', about: '新想法或改进', title: '[Feature] ', labels: ['enhancement'],
      body: '## 想解决什么问题\n\n\n## 期望的效果\n\n\n## 备选方案（可选）\n\n' },
    { kind: 'md', name: '提问 / 讨论', about: '使用上的疑问', title: '[Question] ', labels: ['question'],
      body: '## 问题\n\n\n## 已经尝试过\n\n' }
  ];
  function builtinData() {
    return { templates: BUILTIN.slice(), blank: true, contacts: [], builtin: true };
  }

  /** 返回 {templates:[], blank:bool, contacts:[]} —— 永远不会 reject */
  function load(repoFull) {
    var base = '/repos/' + repoFull + '/contents/';
    var empty = builtinData();
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
          /* 目录存在但没有可用模板（比如只有 config.yml）→ 给通用分类 */
          if (!templates.length && !(conf && conf.contacts && conf.contacts.length)) {
            var bd = builtinData();
            if (conf) { bd.blank = conf.blank; bd.contacts = conf.contacts; }
            return bd;
          }
          return {
            templates: templates,
            blank: conf ? conf.blank : true,
            contacts: conf ? conf.contacts : [],
            builtin: false
          };
        });
      })
      .catch(function () {
        // 没有目录：按官方约定依次试三个单文件模板位置；都没有就给通用分类
        var paths = ['.github/ISSUE_TEMPLATE.md', 'ISSUE_TEMPLATE.md', 'docs/ISSUE_TEMPLATE.md'];
        return paths.reduce(function (chain, p) {
          return chain.then(function (found) {
            if (found) return found;
            return fetchText(repoFull, p).then(function (txt) {
              return txt && txt.trim()
                ? { templates: [parseMd(p.split('/').pop(), txt)], blank: true, contacts: [], builtin: false }
                : null;
            }).catch(function () { return null; });
          });
        }, Promise.resolve(null)).then(function (found) { return found || empty; });
      });
  }

  /** 把分类卡片画进 host；onPick(t|null) 由调用方接管填表
   *
   * 议题 #36 后续：分类改成「默认合拢的一行按钮 + 点一下向下展开」。
   * 之前整块卡片直接铺在最上面，把标题/正文挤下去，小屏上要滚很久才
   * 看到输入框。现在只占一行，展开后仍是原来的卡片列表，选完保持展开
   * 方便连点切换（用户明确要求过「换分类要撤掉上一次」）。
   *
   * 只改渲染层：onPick / applyTpl / undoOwn / 标签去重 / 预览样式一律不动。 */
  function render(host, data, onPick) {
    if (!host) return null;
    var tpls = (data && data.templates) || [], contacts = (data && data.contacts) || [];
    if (!tpls.length && !contacts.length) { host.hidden = true; return null; }
    host.hidden = false;

    var card = 'display:block;width:100%;text-align:left;margin:6px 0;padding:10px 12px;' +
      'border:1px solid var(--border,#30363d);border-radius:10px;background:var(--card,#161b22);' +
      'color:var(--text,#e6edf3);cursor:pointer';
    var sub = 'display:block;font-size:12px;opacity:.72;margin-top:3px;line-height:1.45';

    var inner = '<div class="muted tiny" style="margin-bottom:6px">' +
      window.I18n.t(data && data.builtin
        ? '本仓库未配置议题模板，可先选一个通用分类；选了会自动带出标题与正文骨架'
        : '仓库维护者预设了议题模板，选一个会自动填好标题、正文和标签') + '</div>';
    tpls.forEach(function (t, i) {
      inner += '<button type="button" class="itpl-card" data-i="' + i + '" style="' + card + '">' +
        '<strong>' + esc(t.name) + '</strong>' +
        (t.about ? '<span style="' + sub + '">' + esc(t.about) + '</span>' : '') +
        (t.labels && t.labels.length
          ? '<span style="' + sub + '">' + esc(t.labels.join(' · ')) + '</span>' : '') +
        '</button>';
    });
    if (data.blank !== false) {
      inner += '<button type="button" class="itpl-card" data-blank="1" style="' + card + '">' +
        '<strong>' + window.I18n.t('空白议题') + '</strong>' +
        '<span style="' + sub + '">' + window.I18n.t('不使用模板，自己从零写') + '</span></button>';
    }
    contacts.forEach(function (c) {
      inner += '<button type="button" class="itpl-card" data-contact="' + esc(c.url) + '" style="' + card + '">' +
        '<strong>' + esc(c.name) + ' ↗</strong>' +
        (c.about ? '<span style="' + sub + '">' + esc(c.about) + '</span>' : '') + '</button>';
    });

    /* 折叠头：整行可点。默认合拢（列表 display:none），箭头与 aria 同步。
       用 class 而不是 id —— 新建议题/PR 弹层可能先后存在，别撞 ID。 */
    host.innerHTML =
      '<button type="button" class="itpl-toggle btn" aria-expanded="false" style="' +
        'display:flex;align-items:center;gap:8px;width:100%;text-align:left;' +
        'padding:10px 12px;border:1px solid var(--border,#30363d);border-radius:10px;' +
        'background:var(--card,#161b22);color:var(--text,#e6edf3);cursor:pointer">' +
        '<span style="flex:0 0 auto;font-weight:600">' + window.I18n.t('议题分类') + '</span>' +
        '<span class="itpl-active muted tiny" style="flex:1;min-width:0;overflow:hidden;' +
          'text-overflow:ellipsis;white-space:nowrap;opacity:.72;font-size:12px"></span>' +
        '<span class="itpl-arrow" style="flex:0 0 auto;font-size:14px;opacity:.8">▸</span>' +
      '</button>' +
      '<div class="itpl-list" style="display:none;padding-top:8px">' + inner + '</div>';

    var toggle = host.querySelector('.itpl-toggle');
    var list = host.querySelector('.itpl-list');
    var activeEl = host.querySelector('.itpl-active');
    var arrowEl = host.querySelector('.itpl-arrow');
    if (!toggle || !list) return null;

    var expanded = false;
    function setExpanded(on) {
      expanded = !!on;
      /* 直接切 display，不依赖 hidden 属性 —— .field 的 CSS 可能覆盖它 */
      list.style.display = expanded ? 'block' : 'none';
      arrowEl.textContent = expanded ? '▾' : '▸';
      toggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    }
    function setActive(name, auto) {
      activeEl.textContent = name
        ? (auto ? window.I18n.t('已自动选择：') : window.I18n.t('已选：')) + name
        : window.I18n.t('点击展开选择');
    }
    toggle.onclick = function () { setExpanded(!expanded); };

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
          return;   // 外链不算「选中分类」，头部状态不动
        }
        var i = b.getAttribute('data-i');
        var picked = i === null ? null : tpls[+i];
        setActive(picked ? picked.name : window.I18n.t('空白议题'), false);
        /* 选中后保持展开：用户会连点切换分类，收起来反而多点一次 */
        onPick(picked);
      };
      function cName(x) { var st = x.querySelector('strong'); return st ? st.textContent : ''; }
    });

    /* 仓库关掉「空白议题」时，调用方会自动套第一个模板；这里把头部
       同步成那个名字，否则合拢状态下用户不知道已经选了哪个。
       条件与 page-repo.js 的 tplRequired 完全一致。 */
    setActive(data && data.blank === false && tpls.length ? tpls[0].name : '',
      data && data.blank === false && tpls.length);
    setExpanded(false);   // 默认合拢

    return { setActive: setActive, expand: function () { setExpanded(true); },
             collapse: function () { setExpanded(false); } };
  }

  window.IssueTemplates = { load: load, render: render };
})();
