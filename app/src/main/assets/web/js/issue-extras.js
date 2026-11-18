/* ============================================================
 * issue-extras.js — 议题能力对齐 GitHub 官方
 *
 * 官方 Issues 里那些「REST/GraphQL 能稳定落地、但详情页原先没有」的能力
 * 集中放这里，避免把 3000 行的 page-detail.js 再撑大一圈：
 *
 *   · 编辑议题标题/正文、关闭原因（completed / not_planned）
 *   · 里程碑关联、指派人多选、标签批量设置
 *   · 订阅/取消订阅、锁定/解锁（带 lock_reason）
 *   · 置顶/取消置顶（GraphQL，无 REST 端点）
 *   · 转移议题、复制议题、从评论新建议题
 *   · 子议题（sub-issues）与议题依赖（blocked by / blocking）
 *   · 表情回应 Reactions（正文 + 每条评论，8 种官方表情）
 *   · 任务清单勾选回填正文、@ 提及协作者
 *   · 侧栏状态卡：状态原因 / 锁定 / 订阅 / 置顶 / 父子 / 依赖
 *
 * 设计口径：每个新端点都**独立 catch**。GitHub 的子议题、依赖、transfer
 * 在部分仓库/旧令牌上会返回 404 或 422 —— 那只让对应那一块隐藏，
 * 绝不能把整张详情页拖成报错框。所有写操作失败都给一句人话原因。
 * ============================================================ */
(function () {
  'use strict';
  var U = window.Util, UI = window.UI;
  var X = (window.IssueExtras = {});

  /* Reactions 的 preview 头：GA 后普通 +json 也能用，带上兼容更老的服务端 */
  var RACC = 'application/vnd.github.squirrel-girl-preview+json';
  var EMOJI = [
    { k: '+1', e: '👍', n: '赞' }, { k: '-1', e: '👎', n: '不赞' },
    { k: 'laugh', e: '😄', n: '哈哈' }, { k: 'confused', e: '😕', n: '疑惑' },
    { k: 'heart', e: '❤️', n: '喜欢' }, { k: 'hooray', e: '🎉', n: '庆祝' },
    { k: 'rocket', e: '🚀', n: '火箭' }, { k: 'eyes', e: '👀', n: '关注' }
  ];
  var LOCK_REASONS = [
    { k: 'off-topic', n: '偏离主题' }, { k: 'too heated', n: '讨论过热' },
    { k: 'resolved', n: '已解决' }, { k: 'spam', n: '垃圾信息' }
  ];

  function t(s) { return window.I18n.t(s); }
  function esc(s) { return U.esc(String(s == null ? '' : s)); }
  function toast(s) { try { UI.toast(t(s)); } catch (e) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",e)}catch(__diagIgnored){} } }
  function reload() { try { window.Router.reload(); } catch (e) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",e)}catch(__diagIgnored){} } }
  function login() { return !!(window.Session && window.Session.isLogin); }
  function me() { return (window.Session && window.Session.user && window.Session.user.login) || ''; }

  /* 统一包装：静默失败用于「探测型」请求（新 API 没开放时返回 404 是正常的） */
  function req(method, path, body, opts) {
    var A = window.API;
    var fn = method === 'GET' ? A.get : method === 'POST' ? A.post
      : method === 'PATCH' ? A.patch : method === 'PUT' ? A.put : A.del;
    var o = Object.assign({ noLog: true }, opts || {});
    return fn.call(A, path, method === 'GET' ? (body || null) : (body || {}), o);
  }
  function quiet(p) { return p.catch(function () { return { data: null }; }); }

  function fail(e, what) {
    var m = (e && e.message) || '';
    if (e && e.status === 403) m = t('没有权限（需要仓库写权限或更高权限令牌）');
    else if (e && e.status === 404) m = t('该仓库/接口暂不支持此操作');
    else if (e && e.status === 422) m = t('参数不被接受，检查输入或仓库设置');
    toast(what + t('失败：') + m);
  }

  function done(msg) { toast(msg); reload(); }

  /* ============================================================
   * 一、菜单项与分发
   * ============================================================ */
  X.menuItems = function (full, n, it) {
    if (!login()) return [];
    var out = [
      { icon: 'pencil', label: t('编辑标题与正文'), key: 'ie:edit' },
      { icon: 'milestone', label: t('设置里程碑'), key: 'ie:ms' },
      { icon: 'people', label: t('管理指派人'), key: 'ie:assign' },
      { icon: 'tag', label: t('批量设置标签'), key: 'ie:labels' },
      '-',
      { icon: 'issue-closed', label: t('关闭为…（完成 / 非计划）'), key: 'ie:close' },
      { icon: 'bell', label: t('订阅 / 取消订阅'), key: 'ie:sub' },
      { icon: 'lock', label: t('锁定 / 解锁会话'), key: 'ie:lock' },
      { icon: 'pin', label: t('置顶 / 取消置顶'), key: 'ie:pin' },
      '-',
      { icon: 'git-branch', label: t('子议题与依赖'), key: 'ie:rels' },
      { icon: 'link-external', label: t('转移到其它仓库'), key: 'ie:transfer' },
      { icon: 'copy', label: t('复制此议题'), key: 'ie:clone' }
    ];
    return out;
  };

  X.dispatch = function (key, full, n, it) {
    if (!key || key.indexOf('ie:') !== 0) return false;
    var a = key.slice(3);
    if (a === 'edit') editIssue(full, n, it);
    else if (a === 'ms') setMilestone(full, n, it);
    else if (a === 'assign') setAssignees(full, n, it);
    else if (a === 'labels') setLabels(full, n, it);
    else if (a === 'close') closeAs(full, n, it);
    else if (a === 'sub') toggleSubscribe(full, n);
    else if (a === 'lock') toggleLock(full, n, it);
    else if (a === 'pin') togglePin(full, n, it);
    else if (a === 'rels') relationsSheet(full, n, it);
    else if (a === 'transfer') transferIssue(full, n);
    else if (a === 'clone') cloneIssue(full, n);
    return true;
  };

  /* ---------- 编辑标题 / 正文 ---------- */
  function editIssue(full, n, it) {
    var root = document.getElementById('sheet-root');
    UI.sheet({
      title: t('编辑议题'), full: true,
      body: '<div class="field"><label>' + t('标题') + '</label>' +
        '<input class="input" id="ie-t" value="' + esc(it.title) + '"></div>' +
        '<div class="field"><label>' + t('正文（Markdown）') + '</label>' +
        '<textarea class="textarea" id="ie-b" style="min-height:220px">' + esc(it.body || '') + '</textarea></div>',
      foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>保存</button>',
      onMount: function () {
        root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
        root.querySelector('[data-yes]').onclick = function () {
          var ti = root.querySelector('#ie-t').value.trim();
          var bo = root.querySelector('#ie-b').value;
          if (!ti) return toast('标题不能为空');
          UI.loading(true);
          req('PATCH', '/repos/' + full + '/issues/' + n, { title: ti, body: bo })
            .then(function () { UI.loading(false); UI.closeSheet(); done('议题已更新'); })
            .catch(function (e) { UI.loading(false); fail(e, t('编辑')); });
        };
      }
    });
  }

  /* ---------- 里程碑 ---------- */
  function setMilestone(full, n, it) {
    req('GET', '/repos/' + full + '/milestones', { state: 'all', per_page: 100 }).then(function (r) {
      var ms = r.data || [];
      var items = [{ icon: 'circle-slash', label: t('不设置里程碑'), key: '__none' }];
      ms.forEach(function (m) {
        items.push({ icon: m.state === 'open' ? 'milestone' : 'issue-closed',
          label: m.title + (m.state === 'closed' ? t('（已关闭）') : ''), key: String(m.number) });
      });
      items.push('-');
      items.push({ icon: 'gear', label: t('管理里程碑…'), key: '__manage' });
      UI.menu(t('设置里程碑'), items).then(function (k) {
        if (!k) return;
        if (k === '__manage') return window.Router.go('/' + full + '/milestones');
        var v = k === '__none' ? null : +k;
        UI.loading(true);
        req('PATCH', '/repos/' + full + '/issues/' + n, { milestone: v })
          .then(function () { UI.loading(false); done(v == null ? '已移除里程碑' : '里程碑已设置'); })
          .catch(function (e) { UI.loading(false); fail(e, t('设置里程碑')); });
      });
    }).catch(function (e) { fail(e, t('读取里程碑')); });
  }

  /* ---------- 指派人（多选，一次 add + 一次 remove） ---------- */
  function setAssignees(full, n, it) {
    req('GET', '/repos/' + full + '/assignees', { per_page: 100 }).then(function (r) {
      var users = r.data || [];
      if (!users.length) return toast('没有可指派的协作者');
      var cur = (it.assignees || []).map(function (a) { return a.login; });
      var root = document.getElementById('sheet-root');
      UI.sheet({
        title: t('管理指派人'),
        body: '<div class="list">' + users.map(function (u) {
          var on = cur.indexOf(u.login) >= 0;
          return '<button class="list-row" data-a="' + esc(u.login) + '" data-on="' + (on ? 1 : 0) + '">' +
            '<span class="row-main"><span class="row-title">' + esc(u.login) + '</span></span>' +
            '<span class="row-side" data-ck style="color:var(--accent)">' + (on ? window.icon('check', 18) : '') + '</span></button>';
        }).join('') + '</div>',
        foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>保存</button>',
        onMount: function () {
          UI.$$('.list-row[data-a]', root).forEach(function (b) {
            b.onclick = function () {
              var on = b.getAttribute('data-on') === '1';
              b.setAttribute('data-on', on ? '0' : '1');
              b.querySelector('[data-ck]').innerHTML = on ? '' : window.icon('check', 18);
            };
          });
          root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
          root.querySelector('[data-yes]').onclick = function () {
            var want = [];
            UI.$$('.list-row[data-a]', root).forEach(function (b) {
              if (b.getAttribute('data-on') === '1') want.push(b.getAttribute('data-a'));
            });
            var add = want.filter(function (x) { return cur.indexOf(x) < 0; });
            var rm = cur.filter(function (x) { return want.indexOf(x) < 0; });
            if (!add.length && !rm.length) return UI.closeSheet();
            UI.loading(true);
            var ps = [];
            if (add.length) ps.push(req('POST', '/repos/' + full + '/issues/' + n + '/assignees', { assignees: add }));
            if (rm.length) ps.push(req('DELETE', '/repos/' + full + '/issues/' + n + '/assignees', { assignees: rm }));
            Promise.all(ps).then(function () {
              UI.loading(false); UI.closeSheet(); done('指派人已更新');
            }).catch(function (e) { UI.loading(false); fail(e, t('更新指派人')); });
          };
        }
      });
    }).catch(function (e) { fail(e, t('读取协作者')); });
  }

  /* ---------- 标签批量设置（PUT 是官方的 set labels 语义） ---------- */
  function setLabels(full, n, it) {
    req('GET', '/repos/' + full + '/labels', { per_page: 100 }).then(function (r) {
      var ls = r.data || [];
      if (!ls.length) return toast('该仓库没有标签');
      var cur = (it.labels || []).map(function (l) { return l.name; });
      var root = document.getElementById('sheet-root');
      UI.sheet({
        title: t('批量设置标签'),
        body: '<div class="list">' + ls.map(function (l) {
          var on = cur.indexOf(l.name) >= 0;
          return '<button class="list-row" data-l="' + esc(l.name) + '" data-on="' + (on ? 1 : 0) + '">' +
            '<span class="label" style="' + U.labelStyle(l.color) + '">' + esc(l.name) + '</span>' +
            '<span class="row-side" data-ck style="color:var(--accent)">' + (on ? window.icon('check', 18) : '') + '</span></button>';
        }).join('') + '</div>',
        foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>保存</button>',
        onMount: function () {
          UI.$$('.list-row[data-l]', root).forEach(function (b) {
            b.onclick = function () {
              var on = b.getAttribute('data-on') === '1';
              b.setAttribute('data-on', on ? '0' : '1');
              b.querySelector('[data-ck]').innerHTML = on ? '' : window.icon('check', 18);
            };
          });
          root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
          root.querySelector('[data-yes]').onclick = function () {
            var want = [];
            UI.$$('.list-row[data-l]', root).forEach(function (b) {
              if (b.getAttribute('data-on') === '1') want.push(b.getAttribute('data-l'));
            });
            UI.loading(true);
            req('PUT', '/repos/' + full + '/issues/' + n + '/labels', { labels: want })
              .then(function () { UI.loading(false); UI.closeSheet(); done('标签已更新'); })
              .catch(function (e) { UI.loading(false); fail(e, t('更新标签')); });
          };
        }
      });
    }).catch(function (e) { fail(e, t('读取标签')); });
  }

  /* ---------- 关闭原因（官方 completed / not_planned） ---------- */
  function closeAs(full, n, it) {
    UI.menu(t('关闭原因'), [
      { icon: 'check', label: t('已完成（completed）'), key: 'completed' },
      { icon: 'circle-slash', label: t('非计划 / 不处理（not_planned）'), key: 'not_planned' },
      { icon: 'issue-closed', label: t('仅关闭，不标原因'), key: 'plain' }
    ]).then(function (k) {
      if (!k) return;
      var body = { state: 'closed' };
      if (k !== 'plain') body.state_reason = k;
      UI.loading(true);
      req('PATCH', '/repos/' + full + '/issues/' + n, body)
        .then(function () { UI.loading(false); done('议题已关闭'); })
        .catch(function (e) { UI.loading(false); fail(e, t('关闭')); });
    });
  }

  /* ---------- 订阅 ---------- */
  function subState(full, n) {
    return quiet(req('GET', '/repos/' + full + '/issues/' + n + '/subscription'))
      .then(function (r) { return !!(r.data && r.data.subscribed); });
  }
  function toggleSubscribe(full, n) {
    subState(full, n).then(function (on) {
      UI.confirm(on ? t('取消订阅？') : t('订阅此议题？'),
        on ? t('之后不再收到这个议题的新评论通知。') : t('有新评论时会收到通知。'),
        on ? t('取消订阅') : t('订阅')).then(function (ok) {
        if (!ok) return;
        UI.loading(true);
        var p = on
          ? req('DELETE', '/repos/' + full + '/issues/' + n + '/subscription', {})
          : req('PUT', '/repos/' + full + '/issues/' + n + '/subscription', { subscribed: true, ignored: false });
        p.then(function () { UI.loading(false); done(on ? '已取消订阅' : '已订阅'); })
          .catch(function (e) { UI.loading(false); fail(e, t('订阅操作')); });
      });
    });
  }

  /* ---------- 锁定 ---------- */
  function toggleLock(full, n, it) {
    if (it.locked) {
      UI.confirm(t('解锁会话？'), t('解锁后所有人又可以继续评论。'), t('解锁')).then(function (ok) {
        if (!ok) return;
        UI.loading(true);
        req('DELETE', '/repos/' + full + '/issues/' + n + '/lock', {})
          .then(function () { UI.loading(false); done('已解锁'); })
          .catch(function (e) { UI.loading(false); fail(e, t('解锁')); });
      });
      return;
    }
    var items = [{ icon: 'lock', label: t('不说明原因'), key: '__none' }];
    LOCK_REASONS.forEach(function (r) { items.push({ icon: 'lock', label: r.n, key: r.k }); });
    UI.menu(t('锁定会话 · 选择原因'), items).then(function (k) {
      if (!k) return;
      var body = {};
      if (k !== '__none') body.lock_reason = k;
      UI.loading(true);
      req('PUT', '/repos/' + full + '/issues/' + n + '/lock', body)
        .then(function () { UI.loading(false); done('会话已锁定'); })
        .catch(function (e) { UI.loading(false); fail(e, t('锁定')); });
    });
  }

  /* ---------- 置顶（GraphQL：官方没有 REST 端点） ---------- */
  function pinQuery(full, n) {
    var p = full.split('/');
    return window.API.graphql(
      'query($o:String!,$r:String!,$n:Int!){repository(owner:$o,name:$r){issue(number:$n){id isPinned}}}',
      { o: p[0], r: p[1], n: +n });
  }
  function togglePin(full, n, it) {
    pinQuery(full, n).then(function (d) {
      var issue = d && d.repository && d.repository.issue;
      if (!issue) throw new Error('GraphQL 未返回议题');
      var on = !!issue.isPinned;
      UI.confirm(on ? t('取消置顶？') : t('置顶此议题？'),
        on ? t('置顶标记会从这个仓库移除。') : t('置顶的议题会出现在仓库议题页顶部。'),
        on ? t('取消置顶') : t('置顶')).then(function (ok) {
        if (!ok) return;
        UI.loading(true);
        var q = on
          ? 'mutation($id:ID!){unpinIssue(input:{issueId:$id}){issue{id,isPinned}}}'
          : 'mutation($id:ID!){pinIssue(input:{issueId:$id}){issue{id,isPinned}}}';
        window.API.graphql(q, { id: issue.id }).then(function () {
          UI.loading(false); done(on ? '已取消置顶' : '已置顶');
        }).catch(function (e) { UI.loading(false); fail(e, t('置顶操作')); });
      });
    }).catch(function (e) { fail(e, t('读取置顶状态')); });
  }

  /* ---------- 转移议题 ---------- */
  function transferIssue(full, n) {
    var root = document.getElementById('sheet-root');
    UI.sheet({
      title: t('转移议题'),
      body: '<div class="field"><label>' + t('目标仓库（owner/repo）') + '</label>' +
        '<input class="input" id="ie-tr" placeholder="owner/repo"></div>' +
        '<div class="muted tiny">' + t('转移后原议题会留下一条指向新位置的记录，评论与标签一并过去。需要目标仓库的写权限。') + '</div>',
      foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>转移</button>',
      onMount: function () {
        root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
        root.querySelector('[data-yes]').onclick = function () {
          var v = (root.querySelector('#ie-tr').value || '').trim().replace(/^https?:\/\/github\.com\//, '').replace(/\/$/, '');
          if (!/^[^\s/]+\/[^\s/]+$/.test(v)) return toast('请填写 owner/repo 格式');
          UI.confirm(t('确认转移？'), t('议题 #') + n + t(' 将转移到 ') + v, t('转移'), true).then(function (ok) {
            if (!ok) return;
            UI.loading(true);
            req('POST', '/repos/' + full + '/issues/' + n + '/transfer', { new_repo: v })
              .then(function () {
                UI.loading(false); UI.closeSheet(); toast('已转移');
                try { window.Router.go('/' + v + '/issues'); } catch (e) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",e)}catch(__diagIgnored){} }
              })
              .catch(function (e) { UI.loading(false); fail(e, t('转移')); });
          });
        };
      }
    });
  }

  /* ---------- 复制议题 ---------- */
  function cloneIssue(full, n) {
    req('GET', '/repos/' + full + '/issues/' + n).then(function (r) {
      var it = r.data || {};
      UI.confirm(t('复制此议题？'),
        t('会在同一个仓库新建一份标题带「副本」的议题，正文/标签/指派人/里程碑一并带上。'),
        t('创建副本')).then(function (ok) {
        if (!ok) return;
        var body = {
          title: (it.title || '') + '（副本）',
          body: (it.body || '') + '\n\n---\n' + t('复制自') + ' #' + n,
          labels: (it.labels || []).map(function (l) { return l.name; }),
          assignees: (it.assignees || []).map(function (a) { return a.login; })
        };
        if (it.milestone && it.milestone.number) body.milestone = it.milestone.number;
        UI.loading(true);
        req('POST', '/repos/' + full + '/issues', body).then(function (rr) {
          UI.loading(false); toast('副本已创建');
          var num = rr.data && rr.data.number;
          try { window.Router.go('/' + full + '/issues/' + (num || '')); } catch (e) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",e)}catch(__diagIgnored){} }
        }).catch(function (e) { UI.loading(false); fail(e, t('复制议题')); });
      });
    }).catch(function (e) { fail(e, t('读取议题')); });
  }

  /* ---------- 从某条评论新建议题 ---------- */
  X.fromComment = function (full, n, c) {
    if (!login()) return toast('请先登录');
    var raw = String(c.body || c || '');
    var title = raw.split('\n')[0].replace(/^[#>\s*-]+/, '').slice(0, 80) || t('来自议题 #') + n + t(' 的讨论');
    var quote = raw.split('\n').slice(0, 12).map(function (l) { return '> ' + l; }).join('\n');
    var who = (c.user && c.user.login) || '';
    var root = document.getElementById('sheet-root');
    UI.sheet({
      title: t('从评论新建议题'), full: true,
      body: '<div class="field"><label>' + t('标题') + '</label><input class="input" id="ie-ft" value="' + esc(title) + '"></div>' +
        '<div class="field"><label>' + t('正文') + '</label><textarea class="textarea" id="ie-fb" style="min-height:200px">' +
        esc(quote + '\n\n' + t('来自') + ' ' + (who ? '@' + who + ' ' : '') + t('在') + ' #' + n + t(' 的评论：') + ' https://github.com/' + full + '/issues/' + n) +
        '</textarea></div>',
      foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>创建议题</button>',
      onMount: function () {
        root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
        root.querySelector('[data-yes]').onclick = function () {
          var ti = root.querySelector('#ie-ft').value.trim();
          if (!ti) return toast('标题不能为空');
          UI.loading(true);
          req('POST', '/repos/' + full + '/issues',
            { title: ti, body: root.querySelector('#ie-fb').value }).then(function (rr) {
            UI.loading(false); UI.closeSheet(); toast('议题已创建');
            var num = rr.data && rr.data.number;
            try { window.Router.go('/' + full + '/issues/' + (num || '')); } catch (e) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",e)}catch(__diagIgnored){} }
          }).catch(function (e) { UI.loading(false); fail(e, t('创建议题')); });
        };
      }
    });
  };

  /* ============================================================
   * 二、子议题 / 依赖
   * ============================================================ */
  function relEnd(full, n, tail) { return '/repos/' + full + '/issues/' + n + tail; }

  function loadRels(full, n) {
    return Promise.all([
      quiet(req('GET', relEnd(full, n, '/sub_issues'), { per_page: 100 })),
      quiet(req('GET', relEnd(full, n, '/parent_issue'), null)),
      quiet(req('GET', relEnd(full, n, '/dependencies/blocked_by'), { per_page: 100 })),
      quiet(req('GET', relEnd(full, n, '/dependencies/blocking'), { per_page: 100 }))
    ]).then(function (rs) {
      return {
        subs: (rs[0].data && (rs[0].data.sub_issues || rs[0].data)) || [],
        parent: rs[1].data || null,
        blockedBy: (rs[2].data && (rs[2].data.blocked_by || rs[2].data)) || [],
        blocking: (rs[3].data && (rs[3].data.blocking || rs[3].data)) || []
      };
    });
  }
  function arr(v) { return Array.isArray(v) ? v : (v ? [v] : []); }

  function relationsSheet(full, n, it) {
    UI.loading(true);
    loadRels(full, n).then(function (d) {
      UI.loading(false);
      var root = document.getElementById('sheet-root');
      var rows = [];
      function line(label, list) {
        var a = arr(list);
        if (!a.length) return '';
        return '<div class="muted tiny" style="margin:10px 0 4px">' + label + '</div>' +
          a.map(function (x) {
            var num = x.number || x.issue_number || x.id;
            var ttl = x.title || ('#' + num);
            return '<button class="list-row" data-goto="' + esc(String(num)) + '"><span class="row-main">' +
              '<span class="row-title">#' + esc(String(num)) + ' ' + esc(ttl) + '</span></span>' +
              window.icon('chevron-right', 15) + '</button>';
          }).join('');
      }
      var body =
        '<div class="section">' + t('层级与依赖') + '</div>' +
        line(t('父议题'), d.parent ? [d.parent.issue || d.parent.parent || d.parent] : []) +
        line(t('子议题'), d.subs) +
        line(t('被以下议题阻塞（blocked by）'), d.blockedBy) +
        line(t('阻塞了以下议题（blocking）'), d.blocking) +
        (!arr(d.subs).length && !d.parent && !arr(d.blockedBy).length && !arr(d.blocking).length
          ? '<div class="muted tiny" style="padding:14px 4px">' + t('暂无父子/依赖关系。GitHub 在部分仓库尚未开放这组接口时，这里也会是空的。') + '</div>' : '') +
        '<div class="section"></div>' +
        '<div class="set-group">' +
        '<button class="set-row" data-act="addsub"><span class="ico">' + window.icon('plus', 16) + '</span><span class="k">' + t('添加子议题（输入 #编号）') + '</span></button>' +
        '<button class="set-row" data-act="rmsub"><span class="ico">' + window.icon('x', 16) + '</span><span class="k">' + t('移除子议题') + '</span></button>' +
        '<button class="set-row" data-act="addblock"><span class="ico">' + window.icon('plus', 16) + '</span><span class="k">' + t('添加「被…阻塞」') + '</span></button>' +
        '<button class="set-row" data-act="rmblock"><span class="ico">' + window.icon('x', 16) + '</span><span class="k">' + t('移除「被…阻塞」') + '</span></button>' +
        '</div>';
      UI.sheet({
        title: t('子议题与依赖'), body: body,
        foot: '<button class="btn" data-no>关闭</button>',
        onMount: function () {
          root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
          UI.$$('[data-goto]', root).forEach(function (b) {
            b.onclick = function () {
              UI.closeSheet();
              try { window.Router.go('/' + full + '/issues/' + b.getAttribute('data-goto')); } catch (e) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",e)}catch(__diagIgnored){} }
            };
          });
          UI.$$('[data-act]', root).forEach(function (b) {
            b.onclick = function () { relAction(b.getAttribute('data-act'), full, n, d); };
          });
        }
      });
    }).catch(function () { UI.loading(false); toast('读取关系失败'); });
  }

  function askNum(title) {
    var root = document.getElementById('sheet-root');
    return new Promise(function (resolve) {
      UI.sheet({
        title: title,
        body: '<div class="field"><label>' + t('议题编号（纯数字，或 #12）') + '</label>' +
          '<input class="input" id="ie-num" inputmode="numeric" placeholder="12"></div>',
        foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>确定</button>',
        onMount: function () {
          root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); resolve(null); };
          root.querySelector('[data-yes]').onclick = function () {
            var v = (root.querySelector('#ie-num').value || '').trim().replace(/^#/, '');
            if (!/^\d+$/.test(v)) return toast('请输入纯数字编号');
            UI.closeSheet(); resolve(+v);
          };
        },
        onClose: function () { resolve(null); }
      });
    });
  }

  /* 子议题/依赖接口要的是 issue 的数据库 id，不是 number：先查一次 */
  function issueId(full, num) {
    return req('GET', '/repos/' + full + '/issues/' + num).then(function (r) {
      return r.data && r.data.id;
    });
  }

  function relAction(act, full, n, d) {
    var titleMap = {
      addsub: t('添加子议题'), rmsub: t('移除子议题'),
      addblock: t('添加「被…阻塞」'), rmblock: t('移除「被…阻塞」')
    };
    askNum(titleMap[act]).then(function (num) {
      if (!num) return;
      UI.loading(true);
      issueId(full, num).then(function (id) {
        if (!id) throw new Error('未读到议题 ID');
        var p;
        if (act === 'addsub') p = req('POST', relEnd(full, n, '/sub_issues'), { sub_issue_id: id });
        else if (act === 'rmsub') p = req('DELETE', relEnd(full, n, '/sub_issues/' + id), {});
        else if (act === 'addblock') p = req('POST', relEnd(full, n, '/dependencies/blocked_by'), { issue_id: id });
        else p = req('DELETE', relEnd(full, n, '/dependencies/blocked_by/' + id), {});
        return p;
      }).then(function () {
        UI.loading(false); done(t('关系已更新'));
      }).catch(function (e) { UI.loading(false); fail(e, t('更新关系')); });
    });
  }

  /* ============================================================
   * 三、Reactions
   * ============================================================ */
  function reactPath(full, n, cid) {
    return cid
      ? '/repos/' + full + '/issues/comments/' + cid + '/reactions'
      : '/repos/' + full + '/issues/' + n + '/reactions';
  }
  function reactionBar(full, n, cid, host) {
    if (!host) return;
    var bar = document.createElement('div');
    bar.className = 'ie-react';
    bar.setAttribute('style', 'display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin-top:10px;padding-top:8px;border-top:1px solid var(--border)');
    bar.innerHTML = '<span class="spinner" style="width:12px;height:12px"></span>';
    host.appendChild(bar);
    var base = reactPath(full, n, cid);
    req('GET', base, { per_page: 100 }, { accept: RACC }).then(function (r) {
      draw(r.data || []);
    }).catch(function () { bar.innerHTML = ''; });

    function draw(list) {
      bar.innerHTML = '';
      var mine = {};
      EMOJI.forEach(function (e) {
        var one = list.filter(function (x) { return x.content === e.k; });
        if (!one.length) return;
        var self = one.filter(function (x) { return x.user && x.user.login === me(); })[0];
        if (self) mine[e.k] = self.id;
        var b = document.createElement('button');
        b.className = 'btn sm';
        b.setAttribute('style', 'padding:3px 9px;font-size:12px;' + (self ? 'border-color:var(--accent);color:var(--accent)' : ''));
        b.innerHTML = e.e + ' <b>' + one.length + '</b>';
        b.title = e.n;
        b.onclick = function () { toggle(e.k, self && self.id, b); };
        bar.appendChild(b);
      });
      if (login()) {
        var add = document.createElement('button');
        add.className = 'btn sm';
        add.setAttribute('style', 'padding:3px 9px;font-size:12px');
        add.innerHTML = window.icon('smiley', 13) + '+';
        add.onclick = pick;
        bar.appendChild(add);
      }
    }
    function pick() {
      UI.menu(t('添加表情回应'), EMOJI.map(function (e) {
        return { icon: 'smiley', label: e.e + ' ' + e.n, key: e.k };
      })).then(function (k) { if (k) toggle(k, null, null); });
    }
    function toggle(content, ownId, btn) {
      if (!login()) return toast('请先登录');
      UI.loading(true);
      var p = ownId
        ? req('DELETE', base + '/' + ownId, {})
        : req('POST', base, { content: content }, { accept: RACC });
      p.then(function () {
        UI.loading(false);
        return req('GET', base, { per_page: 100 }, { accept: RACC });
      }).then(function (r) { draw(r.data || []); })
        .catch(function (e) { UI.loading(false); fail(e, t('表情回应')); });
    }
  }

  /* ============================================================
   * 四、任务清单：点 checkbox 回填正文（官方同款）
   * ============================================================ */
  function bindTasks(scope, owner) {
    if (!login()) return;
    var boxes = [];
    try { boxes = Array.prototype.slice.call(scope.querySelectorAll('input[type="checkbox"]')); } catch (e) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",e)}catch(__diagIgnored){} return; }
    boxes.forEach(function (cb, idx) {
      cb.disabled = false;
      cb.style.cursor = 'pointer';
      cb.onclick = function (e) {
        /* click 事件派发时浏览器已经把 checkbox 切成「用户想要的那一档」，
           preventDefault 之后才会回滚。所以这里直接读 cb.checked 当目标值，
           不能再取反 —— 那样会得到点击前的旧状态，写回去等于没改。 */
        e.preventDefault();
        var next = !!cb.checked;
        var body = String(owner.body || '');
        var re = /^(\s*[-*+]\s+\[)([ xX])(\])/gm;
        var k = -1, out = body.replace(re, function (m, a, c, b) {
          k++;
          if (k !== idx) return m;
          return a + (next ? 'x' : ' ') + b;
        });
        if (k < idx) return toast('这条任务清单不在原文里，无法回填');
        UI.loading(true);
        var path = owner.cid
          ? '/repos/' + owner.full + '/issues/comments/' + owner.cid
          : '/repos/' + owner.full + '/issues/' + owner.n;
        req('PATCH', path, { body: out }).then(function () {
          UI.loading(false); cb.checked = next; owner.body = out; toast('任务清单已更新');
        }).catch(function (er) { UI.loading(false); fail(er, t('更新任务清单')); });
      };
    });
  }

  /* ============================================================
   * 五、@ 提及协作者
   * ============================================================ */
  X.mention = function (ta, full) {
    req('GET', '/repos/' + full + '/collaborators', { per_page: 100 }, { cache: 60000 }).catch(function () {
      return req('GET', '/repos/' + full + '/contributors', { per_page: 100 });
    }).then(function (r) {
      var users = r.data || [];
      if (!users.length) return toast('没有可提及的协作者');
      UI.menu(t('提及协作者'), users.slice(0, 40).map(function (u) {
        return { icon: 'person', label: u.login, key: u.login };
      })).then(function (k) {
        if (!k) return;
        var s = ta.selectionStart, e = ta.selectionEnd;
        var ins = '@' + k + ' ';
        ta.value = ta.value.substring(0, s) + ins + ta.value.substring(e);
        try { if (window.LivePreview) window.LivePreview.fireInput(ta); } catch (er) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",er)}catch(__diagIgnored){} }
        ta.focus();
        try { ta.setSelectionRange(s + ins.length, s + ins.length); } catch (er) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",er)}catch(__diagIgnored){} }
      });
    }).catch(function (e) { fail(e, t('读取协作者')); });
  };

  /* ============================================================
   * 六、侧栏状态卡 + 详情页增强入口
   * ============================================================ */
  function stateLine(it) {
    if (it.state === 'open') return t('待处理');
    var r = it.state_reason || '';
    if (r === 'completed') return t('已完成');
    if (r === 'not_planned') return t('非计划 / 不处理');
    return t('已关闭');
  }

  X.enhance = function (full, n, it, box) {
    if (!box) return;
    var slot = UI.$('#iextras', box);
    if (slot) renderSidebar(slot, full, n, it);

    /* 正文 reactions + 任务清单 */
    var main = UI.$('#main-body', box);
    if (main) {
      var bubble = main.parentNode;
      if (bubble) reactionBar(full, n, null, bubble);
      bindTasks(main, { full: full, n: n, body: it.body || '', cid: null });
    }

    /* 评论：等时间线渲染完（page-detail 里是异步 Promise.all）再挂 */
    var tries = 0;
    var timer = setInterval(function () {
      tries++;
      var tl = UI.$('#tl', box);
      var nodes = tl ? UI.$$('.comment[data-cid]', tl) : [];
      if (nodes.length || tries > 40) {
        clearInterval(timer);
        nodes.forEach(function (el) {
          var cid = el.getAttribute('data-cid');
          var bub = el.querySelector('.bubble');
          if (!bub || bub.getAttribute('data-ie') === '1') return;
          bub.setAttribute('data-ie', '1');
          var raw = el.getAttribute('data-cbody') || '';
          var body = '';
          try { body = decodeURIComponent(raw); } catch (e) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",e)}catch(__diagIgnored){} body = raw; }
          reactionBar(full, n, cid, bub);
          var mb = el.querySelector('.bubble-body');
          if (mb) bindTasks(mb, { full: full, n: n, body: body, cid: cid });
        });
      }
      if (tries > 40) clearInterval(timer);
    }, 250);
  };

  function renderSidebar(slot, full, n, it) {
    var chips = [];
    chips.push('<span class="chip">' + window.icon(it.state === 'open' ? 'issue-opened' : 'issue-closed', 13) + esc(stateLine(it)) + '</span>');
    if (it.locked) chips.push('<span class="chip">' + window.icon('lock', 13) + t('会话已锁定') +
      (it.active_lock_reason ? ' · ' + esc(it.active_lock_reason) : '') + '</span>');
    if (it.pinned) chips.push('<span class="chip">' + window.icon('pin', 13) + t('已置顶') + '</span>');
    if (it.milestone) chips.push('<span class="chip">' + window.icon('milestone', 13) + esc(it.milestone.title) + '</span>');
    (it.assignees || []).forEach(function (a) {
      chips.push('<span class="chip">' + window.icon('person', 13) + esc(a.login) + '</span>');
    });
    slot.innerHTML = '<div class="rowflex wrap" style="gap:6px;padding:2px 12px 10px">' + chips.join('') + '</div>' +
      '<div id="ie-rel" class="rowflex wrap" style="gap:6px;padding:0 12px 10px"></div>';

    /* 订阅状态 */
    subState(full, n).then(function (on) {
      var box = UI.$('#ie-rel', slot);
      if (!box) return;
      var s = document.createElement('span');
      s.className = 'chip';
      s.innerHTML = window.icon(on ? 'bell' : 'bell-slash', 13) + (on ? t('已订阅') : t('未订阅'));
      box.appendChild(s);
    });

    /* 父子 / 依赖 */
    loadRels(full, n).then(function (d) {
      var box = UI.$('#ie-rel', slot);
      if (!box) return;
      var parent = d.parent && (d.parent.issue || d.parent.parent || d.parent);
      var pnum = parent && (parent.number || parent.issue_number);
      if (pnum) box.appendChild(chip('arrow-up', t('父议题 #') + pnum, '/' + full + '/issues/' + pnum));
      arr(d.subs).slice(0, 4).forEach(function (s) {
        if (s.number) box.appendChild(chip('git-branch', t('子议题 #') + s.number, '/' + full + '/issues/' + s.number));
      });
      if (arr(d.subs).length > 4) box.appendChild(plainChip(t('共 ') + arr(d.subs).length + t(' 个子议题')));
      arr(d.blockedBy).slice(0, 3).forEach(function (s) {
        if (s.number) box.appendChild(chip('alert', t('被 #') + s.number + t(' 阻塞'), '/' + full + '/issues/' + s.number));
      });
      arr(d.blocking).slice(0, 3).forEach(function (s) {
        if (s.number) box.appendChild(chip('chevron-right', t('阻塞 #') + s.number, '/' + full + '/issues/' + s.number));
      });
    }).catch(function () { });

    function chip(ico, label, go) {
      var b = document.createElement('button');
      b.className = 'chip';
      b.innerHTML = window.icon(ico, 13) + esc(label);
      b.onclick = function () { try { window.Router.go(go); } catch (e) {
    try{window.__diagJs&&window.__diagJs("issue-extras.js",e)}catch(__diagIgnored){} } };
      return b;
    }
    function plainChip(label) {
      var s = document.createElement('span');
      s.className = 'chip'; s.textContent = label; return s;
    }
  }
})();
