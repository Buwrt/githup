/* ============================================================
 * page-user.js — 用户/组织主页、我的、Gist、设置
 * ============================================================ */
(function () {
  'use strict';
  var U = window.Util, UI = window.UI, P = (window.Pages = window.Pages || {});

  var USER_TABS = [
    { key: 'overview', label: '概览' }, { key: 'repos', label: '仓库' },
    { key: 'stars', label: 'Star' }, { key: 'gists', label: 'Gist' },
    { key: 'activity', label: '动态' }, { key: 'followers', label: '粉丝' },
    { key: 'following', label: '关注' }, { key: 'orgs', label: '组织' }
  ];

  /* ============ 用户 / 组织 ============ */
  P.user = {
    title: function (ctx) { return ctx.login; },
    render: function (ctx, host) {
      var login = ctx.login;
      var tab = ctx.query.tab || 'overview';
      host.innerHTML = '<div class="card flat" style="border:0">' + UI.skeleton(3) + '</div>';

      return window.API.get('/users/' + login, null, { cache: 60000 }).then(function (r) {
        var u = r.data;
        var isMe = window.Session.user && window.Session.user.login === login;
        window.App.title(login, u.type === 'Organization' ? '组织' : (u.name || '用户'));

        host.innerHTML =
          '<div class="profile-head">' +
          '<div class="profile-top">' + UI.avatar(login, u.avatar_url, 80) +
          '<div class="grow"><div class="profile-name">' + U.esc(u.name || login) + '</div>' +
          '<div class="profile-login">@' + U.esc(login) + (u.type === 'Organization' ? ' · 组织' : '') + '</div></div></div>' +
          (u.bio ? '<div class="profile-bio">' + U.esc(u.bio) + '</div>' : '') +
          '<div class="profile-meta">' +
          (u.company ? '<span>' + window.icon('organization', 14) + U.esc(u.company) + '</span>' : '') +
          (u.location ? '<span>' + window.icon('globe', 14) + U.esc(u.location) + '</span>' : '') +
          (u.email ? '<span>' + window.icon('mail', 14) + U.esc(u.email) + '</span>' : '') +
          (u.blog ? '<span>' + window.icon('link', 14) + '<a href="' + U.esc(u.blog) + '" target="_blank">' + U.esc(u.blog.replace(/^https?:\/\//, '')) + '</a></span>' : '') +
          (u.created_at ? '<span>' + window.icon('clock', 14) + '加入于 ' + U.date(u.created_at) + '</span>' : '') +
          '</div>' +
          '<div class="stat-row">' +
          '<a href="#/' + U.esc(login) + '?tab=repos"><b>' + U.num(u.public_repos) + '</b>仓库</a>' +
          (u.type === 'Organization'
            ? '<a href="#/' + U.esc(login) + '?tab=followers"><b>' + U.num(u.followers) + '</b>成员</a>'
            : '<a href="#/' + U.esc(login) + '?tab=followers"><b>' + U.num(u.followers) + '</b>粉丝</a>' +
              '<a href="#/' + U.esc(login) + '?tab=following"><b>' + U.num(u.following) + '</b>关注</a>') +
          '<a href="#/' + U.esc(login) + '?tab=stars"><b id="starcount">—</b>Star</a>' +
          '<a href="#/' + U.esc(login) + '?tab=gists"><b>' + U.num(u.public_gists || 0) + '</b>Gist</a>' +
          '</div>' +
          (isMe ? '<button class="btn block mt12" id="edit">' + window.icon('pencil', 14) + ' 编辑资料（网页端）</button>'
            : u.type !== 'Organization' ? '<button class="btn block mt12" id="follow">' + window.icon('person', 14) + '<span id="ftxt">关注</span></button>' : '') +
          '</div>' +
          '<div class="tabs" id="utabs">' + USER_TABS.filter(function (t) {
            return !(u.type === 'Organization' && (t.key === 'stars' || t.key === 'following' || t.key === 'gists'));
          }).map(function (t) {
            return '<button data-t="' + t.key + '" class="' + (t.key === tab ? 'active' : '') + '">' + t.label + '</button>';
          }).join('') + '</div>' +
          '<div id="ubody">' + UI.skeleton(4) + '</div>';

        UI.$$('#utabs button', host).forEach(function (b) {
          b.onclick = function () { window.Router.go('/' + login + '?tab=' + b.getAttribute('data-t')); };
        });

        // Star 总数：/users/{login} 不返回，需异步统计（取第一页的 Link 尾页，最多 4 页兜底）
        starCount(login).then(function (n) {
          var el = UI.$('#starcount', host);
          if (el) el.textContent = n === null ? '—' : U.num(n);
        });

        var ed = UI.$('#edit', host);
        if (ed) ed.onclick = function () {
          var url = 'https://github.com/settings/profile';
          window.NativeBridge && NativeBridge.openExternal ? NativeBridge.openExternal(url) : window.open(url, '_blank');
        };
        var fb = UI.$('#follow', host);
        if (fb) {
          if (window.Session.isLogin) {
            window.API.get('/user/following/' + login, null, { cache: 0 }).then(function () {
              UI.$('#ftxt', host).textContent = '已关注'; UI.$('#ftxt', host).dataset.on = '1';
            }).catch(function () {});
          }
          fb.onclick = function () {
            if (!window.Session.isLogin) return UI.toast('请先登录');
            var txt = UI.$('#ftxt', host);
            var following = txt.dataset.on === '1';
            var call = following ? window.API.del('/user/following/' + login) : window.API.put('/user/following/' + login, {});
            call.then(function () {
              txt.dataset.on = following ? '0' : '1';
              txt.textContent = following ? '关注' : '已关注';
              UI.toast(following ? '已取消关注' : '已关注 ' + login);
            }).catch(function (e) { UI.toast('操作失败：' + e.message); });
          };
        }
        renderUserTab(tab, u, UI.$('#ubody', host));
        window.App.setActions([{
          icon: 'kebab-horizontal', onClick: function () {
            UI.menu('操作', [
              { icon: 'link-external', label: '在浏览器打开', key: 'web' },
              { icon: 'share-android', label: '分享主页', key: 'share' },
              { icon: 'copy', label: '复制主页链接', key: 'copy' }
            ]).then(function (k) {
              var url = u.html_url;
              if (k === 'web') NativeBridge.openExternal ? NativeBridge.openExternal(url) : window.open(url, '_blank');
              if (k === 'share') NativeBridge.share ? NativeBridge.share(url, login) : UI.copy(url, '已复制');
              if (k === 'copy') UI.copy(url, '已复制');
            });
          }
        }]);
      }).catch(function (e) { host.innerHTML = UI.errorBox(e); });
    }
  };

  function renderUserTab(tab, u, box) {
    var login = u.login;
    var isOrg = u.type === 'Organization';
    switch (tab) {
      case 'repos': return repoList('/users/' + login + '/repos', { sort: 'updated', per_page: 100 }, box, login, true);
      case 'stars': return isOrg ? memberList(login, box) : repoList('/users/' + login + '/starred', { sort: 'updated', per_page: 100 }, box, login);
      case 'gists': return gistList('/users/' + login + '/gists', box);
      case 'activity': return activity(login, box);
      case 'followers': return isOrg ? memberList(login, box) : peopleList('/users/' + login + '/followers', box);
      case 'following': return peopleList('/users/' + login + '/following', box);
      case 'orgs': return isOrg ? memberList(login, box) : orgList(login, box);
      default: return overview(u, box);
    }
  }

  function overview(u, box) {
    var login = u.login;
    Promise.all([
      window.API.get('/users/' + login + '/repos', { sort: 'updated', per_page: 6 }, { cache: 60000 }).catch(function () { return { data: [] }; }),
      window.API.get('/users/' + login + '/events/public', { per_page: 15 }, { cache: 60000 }).catch(function () { return { data: [] }; })
    ]).then(function (rs) {
      var repos = rs[0].data || [], evs = rs[1].data || [];
      box.innerHTML =
        (repos.length ? '<div class="section-title">' + window.icon('repo', 14) + ' 最近更新的仓库' +
          '<button class="btn sm" style="margin-left:auto" data-tab="repos">全部</button></div>' +
          '<div class="list">' + repos.map(window.repoRow).join('') + '</div>' : '') +
        (evs.length ? '<div class="section-title">' + window.icon('rss', 14) + ' 近期动态</div>' +
          '<div class="list">' + evs.slice(0, 8).map(window.renderEvent).join('') + '</div>' : '');
      window.bindRepoCards(box); window.bindHashLinks(box);
      var all = UI.$('[data-tab]', box);
      if (all) all.onclick = function () { window.Router.go('/' + login + '?tab=repos'); };
    }).catch(function (e) { box.innerHTML = UI.errorBox(e); });
  }

  function repoList(ep, params, box, login, withFilter) {
    var isSelf = !!(window.Session.user && login === window.Session.user.login);
    var newBtn = (isSelf && withFilter) ? '<button class="btn primary sm" id="newrepo" style="flex:none">' +
      window.icon('plus', 14) + ' 新建</button>' : '';
    box.innerHTML = (withFilter ? '<div class="rowflex" style="gap:8px;padding:10px 12px">' +
      UI.seg('rseg', [{ key: 'updated', label: '最近更新' }, { key: 'pushed', label: '最近推送' }, { key: 'created', label: '最新创建' }, { key: 'full_name', label: '名称' }], 'updated') +
      newBtn + '</div><div class="search-bar"><div class="search-input">' + window.icon('search', 15) +
      '<input id="rf" placeholder="筛选仓库…"></div></div>' : '') + '<div id="rl">' + UI.skeleton(4) + '</div>';
    var load = function (sort, q) {
      var p = Object.assign({}, params);
      if (sort) p.sort = sort;
      return window.API.get(ep, p, { cache: 60000 }).then(function (r) {
        var list = r.data || [];
        if (q) list = list.filter(function (x) { return (x.full_name || '').toLowerCase().indexOf(q.toLowerCase()) >= 0 || (x.description || '').toLowerCase().indexOf(q.toLowerCase()) >= 0; });
        var b = UI.$('#rl', box); if (!b) return;
        b.innerHTML = list.length ? '<div class="list">' + list.map(window.repoRow).join('') + '</div>'
          : (isSelf ? (q ? UI.empty('repo', '没有匹配的仓库', '换个关键词试试')
            : '<div class="empty">' + window.icon('repo', 36) + '<div class="t">还没有仓库</div>' +
              '<div class="d">创建第一个仓库，开始托管你的代码。</div>' +
              '<button class="btn primary mt12" id="newrepo2">' + window.icon('plus', 14) + ' 新建仓库</button></div>')
            : UI.empty('repo', '没有仓库', ''));
        window.bindRepoCards(b);
        bindNewRepo(box);
      });
    };
    UI.$$('#rseg button', box).forEach(function (b2) {
      b2.onclick = function () {
        UI.$$('#rseg button', box).forEach(function (x) { x.classList.remove('active'); });
        b2.classList.add('active');
        load(b2.getAttribute('data-v'), (UI.$('#rf', box) || {}).value);
      };
    });
    var rf = UI.$('#rf', box);
    if (rf) rf.oninput = U.debounce(function () { load(null, rf.value.trim()); }, 300);
    bindNewRepo(box);
    return load();
  }

  /** 绑定「新建仓库」按钮（列表顶部 + 空态各一个） */
  function bindNewRepo(box) {
    ['#newrepo', '#newrepo2'].forEach(function (sel) {
      var b = UI.$(sel, box);
      if (b) b.onclick = function () { window.newRepo(); };
    });
  }

  function peopleList(ep, box) {
    box.innerHTML = '<div id="pl">' + UI.skeleton(4) + '</div>';
    return window.API.get(ep, { per_page: 100 }, { cache: 60000 }).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#pl', box); if (!b) return;
      b.innerHTML = list.length ? '<div class="list">' + list.map(function (u) {
        return '<button class="list-row" data-go="/' + U.esc(u.login) + '">' + UI.avatar(u.login, u.avatar_url, 40) +
          '<span class="row-main"><span class="row-title">' + U.esc(u.login) + '</span>' +
          (u.name ? '<span class="row-desc">' + U.esc(u.name) + '</span>' : '') + '</span></button>';
      }).join('') + '</div>' : UI.empty('people', '暂无用户', '');
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#pl', box).innerHTML = UI.errorBox(e); });
  }

  function orgList(login, box) {
    box.innerHTML = '<div id="ol">' + UI.skeleton(3) + '</div>';
    return window.API.get('/users/' + login + '/orgs', { per_page: 100 }, { cache: 60000 }).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#ol', box); if (!b) return;
      b.innerHTML = list.length ? '<div class="list">' + list.map(function (o) {
        return '<button class="list-row" data-go="/' + U.esc(o.login) + '">' + UI.avatar(o.login, o.avatar_url, 40, true) +
          '<span class="row-main"><span class="row-title">' + U.esc(o.login) + '</span>' +
          (o.description ? '<span class="row-desc">' + U.esc(o.description) + '</span>' : '') + '</span></button>';
      }).join('') + '</div>' : UI.empty('organization', '没有加入的组织', '');
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#ol', box).innerHTML = UI.errorBox(e); });
  }

  function memberList(org, box) {
    box.innerHTML = '<div id="ml">' + UI.skeleton(3) + '</div>';
    return window.API.get('/orgs/' + org + '/members', { per_page: 100 }, { cache: 60000 }).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#ml', box); if (!b) return;
      b.innerHTML = list.length ? '<div class="list">' + list.map(function (u) {
        return '<button class="list-row" data-go="/' + U.esc(u.login) + '">' + UI.avatar(u.login, u.avatar_url, 40) +
          '<span class="row-main"><span class="row-title">' + U.esc(u.login) + '</span>' +
          '<span class="row-desc">' + (u.type === 'User' ? '成员' : '机器人') + '</span></span></button>';
      }).join('') + '</div>' : UI.empty('people', '没有公开成员', '');
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#ml', box).innerHTML = UI.errorBox(e); });
  }

  function activity(login, box) {
    box.innerHTML = '<div id="al">' + UI.skeleton(4) + '</div>';
    return window.API.get('/users/' + login + '/events/public', { per_page: 50 }, { cache: 30000 }).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#al', box); if (!b) return;
      b.innerHTML = list.length ? '<div class="list">' + list.map(window.renderEvent).join('') + '</div>' : UI.empty('rss', '暂无公开动态', '');
      window.bindHashLinks(b);
    }).catch(function (e) { UI.$('#al', box).innerHTML = UI.errorBox(e); });
  }

  /* ============ Gist ============ */
  P.gists = {
    title: '我的 Gist',
    render: function (ctx, host) {
      if (!window.Session.isLogin) return window.needLogin(host);
      host.innerHTML = '<div style="padding:10px 12px">' +
        UI.seg('gseg', [{ key: 'mine', label: '我的' }, { key: 'starred', label: '已 Star' }], ctx.query.type || 'mine') +
        '</div><div id="gl">' + UI.skeleton(4) + '</div>';
      UI.$$('#gseg button', host).forEach(function (b) {
        b.onclick = function () { window.Router.go('/gists?type=' + b.getAttribute('data-v')); };
      });
      var ep = ctx.query.type === 'starred' ? '/gists/starred' : '/gists';
      gistList(ep, UI.$('#gl', host));
    }
  };

  function gistList(ep, box) {
    box.innerHTML = UI.skeleton(4);
    return window.API.get(ep, { per_page: 100 }, { cache: 30000 }).then(function (r) {
      var list = r.data || [];
      box.innerHTML = list.length ? '<div class="list">' + list.map(function (g) {
        var files = Object.keys(g.files || {});
        return '<button class="list-row" data-go="/gist/' + U.esc(g.id) + '">' + UI.avatar(g.owner ? g.owner.login : 'ghost', g.owner ? g.owner.avatar_url : '', 32) +
          '<span class="row-main"><span class="row-title mono tiny">' + U.esc(files[0] || '(空)') + '</span>' +
          '<span class="row-desc">' + U.esc(g.description || (files.length > 1 ? files.length + ' 个文件' : '无描述')) + '</span>' +
          '<span class="row-meta"><span>' + window.icon('comment', 12) + g.comments + '</span>' +
          (g.public ? '<span class="chip">公开</span>' : '<span class="chip">私密</span>') +
          '<span>' + U.timeAgo(g.updated_at) + '</span></span></span></button>';
      }).join('') + '</div>' : UI.empty('code-square', '暂无 Gist', '');
      window.bindRepoCards(box);
    }).catch(function (e) { box.innerHTML = UI.errorBox(e); });
  }

  P.gist = {
    title: 'Gist',
    render: function (ctx, host) {
      host.innerHTML = '<div class="card flat" style="border:0">' + UI.skeleton(4) + '</div>';
      return window.API.get('/gists/' + ctx.id, null, { cache: 30000 }).then(function (r) {
        var g = r.data;
        var files = g.files || {};
        var names = Object.keys(files);
        host.innerHTML =
          '<div class="detail-head">' +
          '<div class="detail-title" style="font-size:17px">' + U.esc(g.description || names[0] || 'Gist') + '</div>' +
          '<div class="detail-sub">' + (g.owner ? UI.avatar(g.owner.login, g.owner.avatar_url, 20) + '<a href="#/' + U.esc(g.owner.login) + '">' + U.esc(g.owner.login) + '</a>' : '<span>匿名</span>') +
          '<span>' + U.timeAgo(g.updated_at) + '</span><span class="chip">' + (g.public ? '公开' : '私密') + '</span></div></div>' +
          names.map(function (n) {
            var f = files[n];
            return '<div class="section-title" style="padding-top:12px">' + window.icon('file-code', 14) + ' ' + U.esc(n) +
              '<button class="btn sm" style="margin-left:auto" data-copy="' + U.esc(n) + '">复制</button></div>' +
              '<div class="code-wrap"><pre style="font-size:' + (window.Store.get('codeFont') || 13) + 'px" id="f-' + U.esc(n) + '"></pre></div>';
          }).join('') +
          (g.comments ? '<div class="section-title">' + window.icon('comment', 14) + ' 评论（' + g.comments + '）</div><div id="gc"><div class="spinner"></div></div>' : '');

        names.forEach(function (n) {
          var f = files[n];
          var pre = UI.$('#f-' + CSS.escape(n), host) || UI.$$('pre', host)[names.indexOf(n)];
          if (pre) {
            try {
              pre.innerHTML = (window.hljs ? hljs.highlightAuto(f.content || '', [f.language ? f.language.toLowerCase() : '']).value : U.esc(f.content));
            } catch (e) { pre.textContent = f.content; }
          }
        });
        UI.$$('[data-copy]', host).forEach(function (b) {
          b.onclick = function () { UI.copy(files[b.getAttribute('data-copy')].content || '', '已复制'); };
        });
        if (g.comments) {
          window.API.get('/gists/' + ctx.id + '/comments', { per_page: 100 }).then(function (cr) {
            var box = UI.$('#gc', host); if (!box) return;
            var cs = cr.data || [];
            box.innerHTML = cs.length ? '<div class="list">' + cs.map(function (c) {
              return '<div class="comment">' + UI.avatar(c.user.login, c.user.avatar_url, 32) +
                '<div class="bubble"><div class="bubble-head"><b>' + U.esc(c.user.login) + '</b><span class="muted">' + U.timeAgo(c.created_at) + '</span></div>' +
                '<div class="bubble-body md"></div></div></div>';
            }).join('') + '</div>' : '';
            var bodies = UI.$$('.bubble-body', box);
            cs.forEach(function (c, i) { if (bodies[i]) window.MD.mount(bodies[i], c.body || ''); });
          }).catch(function () { var x = UI.$('#gc', host); if (x) x.innerHTML = ''; });
        }
        window.App.setActions([{
          icon: 'kebab-horizontal', onClick: function () {
            UI.menu('操作', [
              { icon: 'star', label: 'Star', key: 'star' },
              { icon: 'link-external', label: '在浏览器打开', key: 'web' },
              { icon: 'share-android', label: '分享', key: 'share' },
              { icon: 'copy', label: '复制克隆地址', key: 'clone' }
            ]).then(function (k) {
              if (k === 'star') window.API.put('/gists/' + ctx.id + '/star', {}).then(function () { UI.toast('已 Star'); }).catch(function (e) { UI.toast('已 Star 或失败'); });
              if (k === 'web') NativeBridge.openExternal ? NativeBridge.openExternal(g.html_url) : window.open(g.html_url, '_blank');
              if (k === 'share') NativeBridge.share ? NativeBridge.share(g.html_url, g.description || 'Gist') : UI.copy(g.html_url);
              if (k === 'clone') UI.copy(g.git_pull_url, '已复制');
            });
          }
        }]);
      }).catch(function (e) { host.innerHTML = UI.errorBox(e); });
    }
  };

  /* ============ 我的 ============ */
  P.profile = {
    tab: 'profile',
    title: '我的',
    menu: function () { return [{ icon: 'gear', label: '设置', key: 'settings' }]; },
    onMenu: function () { window.Router.go('/settings'); },
    render: function (ctx, host) {
      if (!window.Session.isLogin) {
        host.innerHTML = '<div class="page"><div class="card" style="padding:20px;text-align:center">' +
          '<div class="muted mb12">登录后查看你的仓库、Star 与动态</div>' +
          '<button class="btn primary block" id="lg">登录 GitHub</button></div>' +
          '<div class="card"><button class="set-row" id="st">' + window.icon('gear', 16) + '<span class="k">应用设置</span>' + window.icon('chevron-right', 16) + '</button></div></div>';
        UI.$('#lg', host).onclick = function () { window.Router.go('/login'); };
        UI.$('#st', host).onclick = function () { window.Router.go('/settings'); };
        return;
      }
      var me = window.Session.user;
      var paint = function (u) {
      host.innerHTML =
        '<div class="profile-head">' +
        '<div class="profile-top">' + UI.avatar(u.login, u.avatar_url, 64) +
        '<div class="grow"><div class="profile-name">' + U.esc(u.name || u.login) + '</div>' +
        '<div class="profile-login">@' + U.esc(u.login) + '</div></div>' +
        '</div>' +
        (u.bio ? '<div class="profile-bio">' + U.esc(u.bio) + '</div>' : '') +
        '<div class="stat-row">' +
        '<a href="#/' + U.esc(u.login) + '?tab=repos"><b>' + U.num(u.public_repos) + '</b>仓库</a>' +
        '<a href="#/' + U.esc(u.login) + '?tab=followers"><b>' + U.num(u.followers) + '</b>粉丝</a>' +
        '<a href="#/' + U.esc(u.login) + '?tab=following"><b>' + U.num(u.following) + '</b>关注</a>' +
        '<a href="#/' + U.esc(u.login) + '?tab=stars"><b id="starcount">—</b>Star</a>' +
        '<a href="#/gists"><b>' + U.num(u.public_gists || 0) + '</b>Gist</a>' +
        '</div></div>' +
        '<div class="section"></div>' +
        '<div class="set-group">' +
        '<button class="set-row" id="editprofile"><span class="ico">' + window.icon('pencil', 16) + '</span>' +
        '<span class="k">编辑资料</span><span class="v">' + (u.name ? '' : '未设置') + '</span>' + window.icon('chevron-right', 16) + '</button>' +
        '<button class="set-row" id="editavatar"><span class="ico">' + window.icon('person', 16) + '</span>' +
        '<span class="k">更换头像</span><span class="v">需网页端</span>' + window.icon('link-external', 16) + '</button>' +
        '<button class="set-row" id="newrepo"><span class="ico">' + window.icon('plus', 16) + '</span>' +
        '<span class="k">新建仓库</span><span class="v">' + window.icon('chevron-right', 16) + '</span></button>' +
        '</div>' +
        '<div class="section"></div>' +
        '<div class="set-group">' +
          setRow('repo', '我的仓库', '/' + u.login + '?tab=repos') +
          setRow('star', '我的 Star', '/' + u.login + '?tab=stars') +
          setRow('issue-opened', '我的议题', '/issues/mine') +
          setRow('git-pull-request', '我的拉取请求', '/pulls/mine') +
          setRow('code-square', '我的 Gist', '/gists') +
          setRow('organization', '我的组织', '/' + u.login + '?tab=orgs') +
          setRow('bell', '通知', '/notifications') +
        '</div>' +
        '<div class="section"></div>' +
        '<div class="set-group">' +
          setRow('gear', '设置', '/settings') +
          setRow('graph', 'API 配额', '/settings?tab=quota') +
        '</div>';
        // data-go 由 app.js 全局委托处理
        starCount(u.login).then(function (n) {
          var el = UI.$('#starcount', host);
          if (el) el.textContent = n === null ? '—' : U.num(n);
        });
        var ep = UI.$('#editprofile', host);
        if (ep) ep.onclick = function () { editProfile(u, paint); };
        var nr = UI.$('#newrepo', host);
        if (nr) nr.onclick = function () { window.newRepo(); };
        var ea = UI.$('#editavatar', host);
        if (ea) ea.onclick = function () {
          UI.confirm('更换头像',
            'GitHub 未开放头像上传接口，需要在网页端操作。将用应用内浏览器打开，登录态可复用。',
            '打开上传页').then(function (ok) {
            if (!ok) return;
            var url = 'https://github.com/settings/profile';
            if (window.openWeb) window.openWeb(url, '更换头像');
            else if (window.NativeBridge && NativeBridge.openExternal) NativeBridge.openExternal(url);
            else window.open(url, '_blank');
          });
        };
      };
      if (me) { paint(me); }
      return window.API.me().then(function (r) { window.Session.user = r.data; paint(r.data); })
        .catch(function (e) { if (!me) host.innerHTML = UI.errorBox(e); });
    }
  };

  /* 统计某用户的 Star 总数。
     GitHub 的 /users/{login} 不含 star 计数；/starred 的 Link 响应头会给出尾页页码。
     这里按尾页反推总数：尾页满 100 则直接 *100，否则需要再取一次尾页。
     失败返回 null（界面显示 —）。 */
  function starCount(login) {
    return window.API.get('/users/' + login + '/starred', { per_page: 100, page: 1 }, { cache: 300000 })
      .then(function (r) {
        var first = (r.data || []).length;
        var lastUrl = r.link && r.link.last;
        if (!lastUrl) return first;             // 只有一页
        var m = String(lastUrl).match(/[?&]page=(\d+)/);
        if (!m) return first;
        var lastPage = +m[1];
        if (lastPage <= 1) return first;
        // 取尾页拿到最后一页的条目数，得出精确总数
        return window.API.get('/users/' + login + '/starred', { per_page: 100, page: lastPage }, { cache: 300000 })
          .then(function (r2) { return (lastPage - 1) * 100 + ((r2.data || []).length); })
          .catch(function () { return (lastPage - 1) * 100; });
      })
      .catch(function () { return null; });
  }

  function setRow(icon, label, path) {    return '<button class="set-row" data-p="' + U.esc(path) + '"><span class="ico">' + window.icon(icon, 16) + '</span>' +
      '<span class="k">' + U.esc(label) + '</span>' + window.icon('chevron-right', 16) + '</button>';
  }

  /* ---- 编辑资料：通过 PATCH /user 修改昵称、简介、公司、地区、博客 ---- */
  function editProfile(u, repaint) {
    // 社交账号：GitHub 存 [{provider, url}]，常见 provider 见下
    var social = {};
    (u.social_accounts || []).forEach(function (s) {
      if (s && s.provider) social[s.provider] = s.url || '';
    });
    var PROVIDERS = [
      { k: 'twitter', label: 'X / Twitter', ph: 'https://x.com/用户名' },
      { k: 'mastodon', label: 'Mastodon', ph: 'https://mastodon.social/@用户名' },
      { k: 'linkedin', label: 'LinkedIn', ph: 'https://linkedin.com/in/用户名' },
      { k: 'zhihu', label: '知乎', ph: 'https://zhihu.com/people/用户名' },
      { k: 'bilibili', label: '哔哩哔哩', ph: 'https://space.bilibili.com/UID' }
    ];

    var body =
      /* 头像：展示 + 跳网页端修改 */
      '<div class="rowflex" style="gap:12px;align-items:center;margin-bottom:14px">' +
      UI.avatar(u.login, u.avatar_url, 56) +
      '<span class="grow"><b>头像</b>' +
      '<div class="tiny muted" style="margin-top:4px">GitHub 未开放头像上传接口，需在网页端修改。</div></span>' +
      '<button class="btn sm" style="flex:none" id="ep_avatar">去修改</button></div>' +

      '<div class="form-row"><label>昵称</label><input class="input" id="ep_name" value="' + U.esc(u.name || '') + '" placeholder="显示名称"></div>' +
      '<div class="form-row"><label>简介</label><textarea class="input" id="ep_bio" rows="3" placeholder="一句话介绍自己">' + U.esc(u.bio || '') + '</textarea></div>' +
      '<div class="form-row"><label>公司</label><input class="input" id="ep_company" value="' + U.esc(u.company || '') + '" placeholder="@company"></div>' +
      '<div class="form-row"><label>地区</label><input class="input" id="ep_location" value="' + U.esc(u.location || '') + '" placeholder="城市"></div>' +
      '<div class="form-row"><label>博客</label><input class="input" id="ep_blog" value="' + U.esc(u.blog || '') + '" placeholder="https://"></div>' +

      '<div class="set-group-title" style="padding-left:0">社交账号</div>' +
      PROVIDERS.map(function (p) {
        return '<div class="form-row"><label>' + U.esc(p.label) + '</label>' +
          '<input class="input" id="ep_soc_' + p.k + '" value="' + U.esc(social[p.k] || '') + '" placeholder="' + U.esc(p.ph) + '"></div>';
      }).join('') +

      '<div class="set-group-title" style="padding-left:0">其他</div>' +
      '<label class="rowflex" style="gap:10px;padding:10px 0">' +
      '<input type="checkbox" id="ep_hire" style="width:16px;height:16px"' + (u.hireable ? ' checked' : '') + '>' +
      '<span>愿意接受工作机会（显示「可雇佣」标记）</span></label>' +
      '<label class="rowflex" style="gap:10px;padding:10px 0">' +
      '<input type="checkbox" id="ep_emailvis" style="width:16px;height:16px"' + (u.email ? ' checked' : '') + '>' +
      '<span>公开显示邮箱' + (u.email ? '：' + U.esc(u.email) : '（未设置）') + '</span></label>' +

      '<div class="set-note">头像与用户名暂不支持在应用内修改，GitHub 未开放对应接口。</div>';

    UI.sheet({
      title: '编辑资料',
      icon: 'pencil',
      full: true,
      body: body,
      foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>保存</button>',
      onMount: function (b, close) {
        var root = document.getElementById('sheet-root');
        var get = function (id) { var el = root.querySelector('#' + id); return el ? el.value : ''; };
        var chk = function (id) { var el = root.querySelector('#' + id); return !!(el && el.checked); };

        var av = root.querySelector('#ep_avatar');
        if (av) av.onclick = function () {
          var url = 'https://github.com/settings/profile';
          if (window.openWeb) window.openWeb(url, '更换头像');
          else if (window.NativeBridge && NativeBridge.openExternal) NativeBridge.openExternal(url);
          else window.open(url, '_blank');
        };

        root.querySelector('[data-no]').onclick = function () { close(); };
        root.querySelector('[data-yes]').onclick = function () {
          // 社交账号：只提交填了内容的项
          var accounts = [];
          PROVIDERS.forEach(function (p) {
            var v = get('ep_soc_' + p.k).trim();
            if (v) accounts.push({ provider: p.k, url: v });
          });
          var payload = {
            name: get('ep_name').trim() || null,
            bio: get('ep_bio').trim() || null,
            company: get('ep_company').trim() || null,
            location: get('ep_location').trim() || null,
            blog: get('ep_blog').trim() || null,
            hireable: chk('ep_hire'),
            social_accounts: accounts
          };
          // 邮箱：留空表示不公开
          var keepEmail = chk('ep_emailvis');
          if (!keepEmail && u.email) payload.email = '';
          else if (keepEmail && u.email) payload.email = u.email;

          close();
          UI.loading(true);
          window.API.patch('/user', payload).then(function (r) {
            UI.loading(false);
            window.Session.user = r.data;
            repaint(r.data);
            window.App.clearPageCache();
            UI.toast('资料已更新');
          }).catch(function (e) {
            UI.loading(false);
            UI.toast(e.status === 404 || e.status === 403
              ? '令牌权限不足，需要 user 权限才能修改资料'
              : '保存失败：' + e.message);
          });
        };
      }
    });
  }

  /* ============ 设置 ============ */
  P.settings = {
    title: '设置',
    render: function (ctx, host) {
      var s = window.Store.settings || window.Store.load();
      var ver = 'v' + window.Native.appVersion();
      var themeText = { auto: '跟随系统', light: '浅色', dark: '深色' }[s.theme];
      host.innerHTML =
        '<div class="set-group">' +
        setItem('paintbrush', '主题外观', themeText, 'theme') +
        setItem('typography', '代码字号', (s.codeFont || 13) + 'px', 'font') +
        setItem('home', '启动页', { home: '首页', notifications: '通知', explore: '探索', profile: '我的' }[s.startTab], 'start') +
        '</div>' +
        '<div class="set-note">启动页决定应用打开时默认显示的标签页。</div>' +
        '<div class="section"></div>' +
        '<div class="set-group">' +
        setItem('graph', 'API 配额', '', 'quota') +
        setItem('trash', '清除缓存', '', 'cache') +
        setItem('info', '关于 githup', ver, 'about') +
        '</div>' +
        '<div class="section"></div>' +
        (window.Session.isLogin ?
          '<div class="set-group"><button class="set-row danger" id="logout"><span class="ico">' + window.icon('sign-out', 16) + '</span><span class="k">退出登录</span></button></div>' +
          '<div class="set-note">退出后本机会删除保存的访问令牌。</div>' :
          '<div class="set-group"><button class="set-row" id="login"><span class="ico">' + window.icon('key', 16) + '</span><span class="k">登录 GitHub</span></button></div><div class="set-note">登录后可解锁通知、评论、Star 等完整能力。</div>');

      UI.$$('[data-s]', host).forEach(function (b) {
        b.onclick = function () {
          var k = b.getAttribute('data-s');
          if (k === 'theme') UI.choose('主题外观', [
            { key: 'auto', label: '跟随系统', icon: 'device-desktop' },
            { key: 'light', label: '浅色', icon: 'sun' },
            { key: 'dark', label: '深色', icon: 'moon' }
          ], s.theme, function (v) { window.Store.set('theme', v); window.App.applyTheme(); window.Router.reload(); });
          if (k === 'font') UI.choose('代码字号', [
            { key: '12', label: '小（12px）', icon: 'typography' },
            { key: '13', label: '标准（13px）', icon: 'typography' },
            { key: '15', label: '大（15px）', icon: 'typography' },
            { key: '17', label: '特大（17px）', icon: 'typography' }
          ], String(s.codeFont), function (v) { window.Store.set('codeFont', +v); UI.toast('已调整'); window.Router.reload(); });
          if (k === 'start') UI.choose('启动页', [
            { key: 'home', label: '首页', icon: 'home' },
            { key: 'notifications', label: '通知', icon: 'bell' },
            { key: 'explore', label: '探索', icon: 'telescope' },
            { key: 'profile', label: '我的', icon: 'person' }
          ], s.startTab, function (v) { window.Store.set('startTab', v); window.Router.reload(); });
          if (k === 'quota') return quota(host);
          if (k === 'cache') {
            window.API.clearCache(); window.App.clearPageCache();
            try { if (window.NativeBridge && NativeBridge.clearCache) NativeBridge.clearCache(); } catch (e) {}
            UI.toast('缓存已清除');
          }
          if (k === 'about') return about();
        };
      });
      var lg = UI.$('#logout', host);
      if (lg) lg.onclick = function () {
        UI.confirm('退出登录', '退出后将回到未登录状态。本机保存的令牌会保留，下次可在登录页一键登录。', '退出', true).then(function (ok) {
          if (!ok) return;
          window.Session.clear(); window.App.clearPageCache(); window.App.updateBadge();
          UI.toast('已退出，令牌已保留');
          window.Router.replace('/login');
        });
      };
      var li = UI.$('#login', host);
      if (li) li.onclick = function () { window.Router.go('/login'); };
    }
  };

  function setItem(icon, label, value, key) {
    return '<button class="set-row" data-s="' + key + '"><span class="ico">' + window.icon(icon, 16) + '</span>' +
      '<span class="k">' + U.esc(label) + '</span>' +
      (value ? '<span class="v">' + U.esc(value) + '</span>' : '') + window.icon('chevron-right', 16) + '</button>';
  }

  function quota(host) {
    window.API.rateLimit().then(function (r) {
      var d = r.data && r.data.rate;
      if (!d) return UI.toast('无法获取配额');
      UI.sheet({
        title: 'API 配额',
        body: '<div class="card" style="padding:14px">' +
          '<div class="rowflex" style="justify-content:space-between"><span>核心接口</span><b>' + d.remaining + ' / ' + d.limit + '</b></div>' +
          '<div class="skel" style="height:6px;margin:10px 0;border-radius:3px;position:relative;overflow:hidden">' +
          '<span style="position:absolute;left:0;top:0;bottom:0;width:' + (d.remaining / d.limit * 100) + '%;background:var(--success)"></span></div>' +
          '<div class="tiny muted">重置时间：' + new Date(d.reset * 1000).toLocaleString() + '</div>' +
          '</div>' +
          '<div class="muted tiny" style="padding:0 4px">未登录时每小时 60 次；登录后提升至每小时 5000 次。搜索接口另有独立配额。</div>'
      });
    });
  }

  function about() {
    UI.sheet({
      title: '关于',
      body: '<div class="center" style="padding:10px 0 4px">' + '<div style="display:flex;justify-content:center;color:var(--fg)">' + window.icon('mark-github', 48) + '</div>' +
        '<div style="font-size:18px;font-weight:600;margin-top:10px">githup</div>' +
        '<div class="muted tiny">版本 ' + ver.replace(/^v/, '') + ' · GitHub 第三方客户端</div></div>' +
        '<div class="muted" style="font-size:13px;line-height:1.8;margin-top:14px">' +
        '本应用为个人学习用途的第三方 GitHub 客户端，与 GitHub, Inc. 无隶属关系。' +
        '所有数据均通过 GitHub 官方公开 API 获取，访问令牌仅保存在你的设备本机。' +
        '<br><br>图标使用 GitHub Octicons（MIT License），代码高亮基于 highlight.js（BSD），Markdown 解析基于 marked（MIT）。' +
        '</div>' +
        '<div class="card" style="margin-top:14px">' +
        '<button class="set-row" id="web"><span class="ico">' + window.icon('link-external', 16) + '</span><span class="k">GitHub API 文档</span></button>' +
        '<button class="set-row" id="lic"><span class="ico">' + window.icon('law', 16) + '</span><span class="k">开源许可</span></button>' +
        '</div>',
      onMount: function () {
        UI.$('#web').onclick = function () { NativeBridge.openExternal ? NativeBridge.openExternal('https://docs.github.com/rest') : window.open('https://docs.github.com/rest', '_blank'); };
        UI.$('#lic').onclick = function () { NativeBridge.openExternal ? NativeBridge.openExternal('https://github.com/primer/octicons/blob/main/LICENSE') : window.open('https://github.com/primer/octicons/blob/main/LICENSE', '_blank'); };
      }
    });
  }
})();
