/* ============================================================
 * page-repo.js — 仓库主页：代码、议题、拉取请求、Actions、发布等
 * ============================================================ */
(function () {
  'use strict';
  var U = window.Util, UI = window.UI, P = (window.Pages = window.Pages || {});

  var TABS = [
    { key: 'code', label: '代码', icon: 'code' },
    { key: 'issues', label: '议题', icon: 'issue-opened' },
    { key: 'pulls', label: '拉取请求', icon: 'git-pull-request' },
    { key: 'actions', label: 'Actions', icon: 'workflow' },
    { key: 'releases', label: '发布', icon: 'tag' },
    { key: 'more', label: '更多', icon: 'three-bars' }
  ];

  var state = { repo: null, starred: false, watching: false, ref: null };

  /* ---------------- 入口 ---------------- */
  P.repo = {
    title: function (ctx) { return ctx.owner + '/' + ctx.repo; },
    subtitle: function (ctx) { return ctx.owner; },
    render: function (ctx, host) {
      var full = ctx.owner + '/' + ctx.repo;
      var tab = ctx.tab || 'code';
      var key = 'repo_' + full;
      var cached = window.App.cacheGet(key);

      /** 只认服务端刚返回的那份，避免用缓存里的旧对象画页面 */
      var latest = null;

      var paint = function (repo) {
        state.repo = repo;
        state.ref = ctx.ref || repo.default_branch;
        host.innerHTML = headHtml(repo, tab, ctx) + '<div id="tabbody">' + UI.skeleton(4) + '</div>';
        bindHead(host, repo, tab, ctx);
        renderTab(tab, repo, ctx, UI.$('#tabbody', host), host);
        setupFab(tab, repo, ctx);
        refreshFlags(repo, host);
        // 渲染用的是这一份，设置页里的切换就拿它当基准
        if (tab === 'settings') latest = repo;
      };

      // 缓存只用来先铺个骨架，不当作最终状态（否则上次切换的结果不会体现）
      if (cached) { paint(Object.assign({}, cached)); }
      else {
        host.innerHTML = '<div class="repo-head"><div class="skel" style="height:18px;width:55%;margin-bottom:8px"></div>' +
          '<div class="skel" style="height:14px;width:80%"></div></div><div id="tabbody">' + UI.skeleton(4) + '</div>';
      }
      return window.API.get('/repos/' + full, null, { cache: 0, dedupe: false }).then(function (r) {
        if (!r.data || !r.data.full_name) throw new Error('仓库不存在或无访问权限');
        window.App.cacheSet(key, r.data);
        paint(r.data);
      }).catch(function (e) {
        host.innerHTML = UI.errorBox(e);
      });
    }
  };

  function headHtml(repo, tab, ctx) {
    var parts = repo.full_name.split('/');
    var isSub = ['issues', 'pulls', 'actions', 'releases', 'commits', 'contributors', 'branches', 'tags', 'settings', 'stargazers', 'watchers', 'forks'].indexOf(tab) >= 0;
    var activeTab = isSub ? tab : 'code';
    return '<div class="repo-head">' +
      '<div class="repo-name">' + window.icon(repo.fork ? 'repo-forked' : 'repo', 16) +
      '<a class="owner" href="#/' + U.esc(parts[0]) + '">' + U.esc(parts[0]) + '</a>' +
      '<span class="slash">/</span><span class="name">' + U.esc(parts[1]) + '</span>' +
      (repo.private ? '<span class="chip" style="padding:0 6px">私有</span>' : '') +
      (repo.archived ? '<span class="chip" style="padding:0 6px">已归档</span>' : '') + '</div>' +
      (repo.description ? '<div class="repo-desc">' + U.esc(repo.description) + '</div>' : '') +
      '<div class="repo-stats">' +
      '<span data-act="stargazers">' + window.icon('star', 14) + U.num(repo.stargazers_count) + ' star</span>' +
      '<span data-act="forks">' + window.icon('repo-forked', 14) + U.num(repo.forks_count) + ' fork</span>' +
      '<span data-act="watchers">' + window.icon('eye', 14) + U.num(repo.subscribers_count) + ' 关注</span>' +
      (repo.language ? '<span><i style="width:8px;height:8px;border-radius:50%;background:' + U.langColor(repo.language) + ';display:inline-block"></i>' + U.esc(repo.language) + '</span>' : '') +
      (repo.license && repo.license.spdx_id !== 'NOASSERTION' ? '<span>' + window.icon('law', 14) + U.esc(repo.license.spdx_id) + '</span>' : '') +
      '</div>' +
      '<div class="repo-actions">' +
      '<button class="btn" id="btn-star">' + window.icon(state.starred ? 'star-fill' : 'star', 15) + '<span id="star-txt">Star</span></button>' +
      '<button class="btn" id="btn-watch">' + window.icon('eye', 15) + '<span id="watch-txt">关注</span></button>' +
      '<button class="btn" id="btn-fork">' + window.icon('repo-forked', 15) + 'Fork</button>' +
      '</div>' +
      '</div>' +
      '<div class="tabs" id="rtabs">' + TABS.map(function (t) {
        var cnt = '';
        if (t.key === 'issues' && repo.open_issues_count) cnt = '<span class="cnt">' + U.num(repo.open_issues_count) + '</span>';
        return '<button data-t="' + t.key + '" class="' + (activeTab === t.key ? 'active' : '') + '">' +
          window.icon(t.icon, 15) + '<span>' + t.label + '</span>' + cnt + '</button>';
      }).join('') + '</div>';
  }

  function bindHead(host, repo, tab, ctx) {
    UI.$$('#rtabs button', host).forEach(function (b) {
      b.onclick = function () {
        var t = b.getAttribute('data-t');
        if (t === 'more') return moreMenu(repo);
        window.Router.go('/' + repo.full_name + (t === 'code' ? '' : '/' + (t === 'pulls' ? 'pulls' : t)));
      };
    });
    UI.$$('.repo-stats span[data-act]', host).forEach(function (s) {
      s.onclick = function () { window.Router.go('/' + repo.full_name + '/' + s.getAttribute('data-act')); };
    });
    UI.$('#btn-star', host).onclick = function () { toggleStar(repo, host); };
    UI.$('#btn-watch', host).onclick = function () { toggleWatch(repo, host); };
    UI.$('#btn-fork', host).onclick = function () { doFork(repo); };
  }

  function refreshFlags(repo, host) {
    if (!window.Session.isLogin) return;
    window.API.get('/user/starred/' + repo.full_name, null, { cache: 0 }).then(function () {
      state.starred = true; paintFlags(host);
    }).catch(function (e) { state.starred = !(e.status === 404); paintFlags(host); });
    window.API.get('/repos/' + repo.full_name + '/subscription', null, { cache: 0 }).then(function (r) {
      state.watching = !!(r.data && (r.data.subscribed || r.data.reason)); paintFlags(host);
    }).catch(function () { state.watching = false; paintFlags(host); });
  }
  function paintFlags(host) {
    var st = UI.$('#star-txt', host), wt = UI.$('#watch-txt', host);
    if (st) st.textContent = state.starred ? '已 Star' : 'Star';
    if (wt) wt.textContent = state.watching ? '已关注' : '关注';
  }
  function toggleStar(repo, host) {
    if (!window.Session.isLogin) return UI.toast('请先登录');
    var next = !state.starred;
    var call = next ? window.API.put('/user/starred/' + repo.full_name, {}) : window.API.del('/user/starred/' + repo.full_name);
    call.then(function () {
      state.starred = next; paintFlags(host);
      repo.stargazers_count += next ? 1 : -1;
      UI.toast(next ? '已 Star' : '已取消 Star');
    }).catch(function (e) { UI.toast('操作失败：' + e.message); });
  }
  function toggleWatch(repo, host) {
    if (!window.Session.isLogin) return UI.toast('请先登录');
    var next = !state.watching;
    window.API.put('/repos/' + repo.full_name + '/subscription', { subscribed: next, ignored: false }).then(function () {
      state.watching = next; paintFlags(host); UI.toast(next ? '已关注' : '已取消关注');
    }).catch(function (e) { UI.toast('操作失败：' + e.message); });
  }
  function doFork(repo) {
    if (!window.Session.isLogin) return UI.toast('请先登录');
    UI.confirm('Fork 仓库', '将在你的账号下创建 ' + repo.full_name + ' 的副本。', 'Fork').then(function (ok) {
      if (!ok) return;
      UI.loading(true);
      window.API.post('/repos/' + repo.full_name + '/forks', {}).then(function (r) {
        UI.loading(false);
        UI.toast('Fork 成功');
        if (r.data && r.data.full_name) window.Router.go('/' + r.data.full_name);
      }).catch(function (e) { UI.loading(false); UI.toast('Fork 失败：' + e.message); });
    });
  }

  function moreMenu(repo) {
    UI.menu('更多', [
      { icon: 'git-commit', label: '提交记录', key: 'commits' },
      { icon: 'people', label: '贡献者', key: 'contributors' },
      { icon: 'git-branch', label: '分支', key: 'branches' },
      { icon: 'tag', label: '标签', key: 'tags' },
      { icon: 'gear', label: '仓库设置', key: 'settings' },
      '-',
      { icon: 'link-external', label: '在浏览器打开', key: 'web' },
      { icon: 'share-android', label: '分享仓库', key: 'share' },
      { icon: 'copy', label: '复制克隆地址', key: 'clone' }
    ]).then(function (k) {
      if (!k) return;
      if (k === 'web') return window.NativeBridge && NativeBridge.openExternal ? NativeBridge.openExternal(repo.html_url) : window.open(repo.html_url, '_blank');
      if (k === 'share') return window.NativeBridge && NativeBridge.share ? NativeBridge.share(repo.html_url, repo.full_name) : UI.copy(repo.html_url, '链接已复制');
      if (k === 'clone') return UI.copy(repo.clone_url, '克隆地址已复制');
      window.Router.go('/' + repo.full_name + '/' + k);
    });
  }

  function setupFab(tab, repo, ctx) {
    var fab = document.getElementById('fab');
    if (tab === 'issues') {
      fab.hidden = false; fab.innerHTML = window.icon('plus', 24);
      fab.onclick = function () { newIssue(repo); };
    } else if (tab === 'releases') {
      // 有写权限才显示发布按钮
      var can = canPush(repo);
      fab.hidden = !can;
      if (can) {
        fab.innerHTML = window.icon('tag', 22);
        fab.onclick = function () { newRelease(repo); };
      }
    } else if (tab === 'pulls') {
      fab.hidden = false; fab.innerHTML = window.icon('git-compare', 22);
      fab.onclick = function () {
        UI.confirm('新建拉取请求', '创建 PR 需要选择源分支与目标分支，建议在网页端完成。是否前往浏览器？', '前往').then(function (ok) {
          if (ok && window.NativeBridge && NativeBridge.openExternal) NativeBridge.openExternal(repo.html_url + '/compare');
          else if (ok) window.open(repo.html_url + '/compare', '_blank');
        });
      };
    } else {
      fab.hidden = true;
    }
  }

  /* ---------------- Tab 分发 ---------------- */
  function renderTab(tab, repo, ctx, box, host) {
    switch (tab) {
      case 'issues': return tabIssues(repo, ctx, box);
      case 'pulls': return tabPulls(repo, ctx, box);
      case 'actions': return tabActions(repo, ctx, box);
      case 'releases': return tabReleases(repo, ctx, box);
      case 'commits': return tabCommits(repo, ctx, box);
      case 'contributors': return tabContributors(repo, ctx, box);
      case 'branches': return tabRefs(repo, ctx, box, 'branches');
      case 'tags': return tabRefs(repo, ctx, box, 'tags');
      case 'stargazers': return tabPeople(repo, ctx, box, 'stargazers', 'Star 的人');
      case 'watchers': return tabPeople(repo, ctx, box, 'subscribers', '关注者');
      case 'forks': return tabForks(repo, ctx, box);
      case 'settings': return tabSettings(repo, ctx, box);
      default: return tabCode(repo, ctx, box);
    }
  }

  /* ============ 代码 ============ */
  function tabCode(repo, ctx, box) {
    var ref = ctx.ref || repo.default_branch;
    var path = ctx.path || '';
    state.ref = ref;

    if (ctx.kind === 'blob' && path) return showFile(repo, ref, path, box);

    box.innerHTML = '<div class="breadcrumb" id="bc"></div>';
    var bc = UI.$('#bc', box);
    bc.innerHTML = '<button data-root="1">' + window.icon('repo', 14) + '</button>' +
      '<button data-root="1" style="margin-left:4px">' + U.esc(repo.name) + '</button>' +
      '<span style="margin-left:auto;display:flex;align-items:center;gap:6px">' +
      (window.Session.isLogin ? '<button id="upbtn" title="上传文件" style="display:flex;align-items:center;background:var(--bg);border:1px solid var(--border);border-radius:6px;padding:3px 8px">' +
        window.icon('upload', 13) + '</button>' : '') +
      '<button id="refbtn" style="display:flex;align-items:center;gap:4px;background:var(--bg);border:1px solid var(--border);border-radius:6px;padding:3px 8px">' +
      window.icon('git-branch', 13) + '<span>' + U.esc(ref) + '</span>' + window.icon('chevron-down', 12) + '</button>' +
      '</span>';
    // 面包屑：一层一层往回退，而不是不管在第几层都跳回仓库首页
    // 当前层自己不给点（点了是原地重载，看着像"卡住"）
    // 插入位置在右侧按钮组之前，顺序才对：仓库名 › 一级 › 二级
    var crumbs = path ? path.split('/') : [];
    var tail = bc.lastElementChild;
    crumbs.forEach(function (name, i) {
      var sub = crumbs.slice(0, i + 1);
      var btn = document.createElement('button');
      btn.className = 'crumb-seg';
      btn.innerHTML = window.icon('chevron-right', 12) + '<span>' + U.esc(name) + '</span>';
      btn.onclick = function () {
        if (i === crumbs.length - 1) return;
        window.Router.go('/' + repo.full_name + '/tree/' + encodeURIComponent(ref) + '/' + encodePath(sub.join('/')));
      };
      bc.insertBefore(btn, tail);
    });
    // 回到仓库根目录；已经在根目录就不重复加载（否则看着像点了没反应）
    UI.$$('#bc > button[data-root="1"]', bc).forEach(function (b) {
      b.onclick = function () {
        if (!path) return;
        window.Router.go('/' + repo.full_name + '/tree/' + encodeURIComponent(ref));
      };
    });
    UI.$('#refbtn', bc).onclick = function () { pickRef(repo, ref, path); };
    var up = UI.$('#upbtn', bc);
    if (up) up.onclick = function () { uploadFile(repo, ref, path); };

    return Promise.all([
      window.API.get('/repos/' + repo.full_name + '/contents/' + encodePath(path), { ref: ref }, { cache: 30000 }),
      path ? null : window.API.get('/repos/' + repo.full_name + '/readme', { ref: ref }, { cache: 60000 }).catch(function () { return null; }),
      path ? null : window.API.get('/repos/' + repo.full_name + '/languages', null, { cache: 300000 }).catch(function () { return null; })
    ]).then(function (rs) {
      var entries = rs[0].data || [];
      if (!Array.isArray(entries)) return showFile(repo, ref, path, box);
      entries.sort(function (a, b) {
        if (a.type === b.type) return a.name.localeCompare(b.name);
        return a.type === 'dir' ? -1 : 1;
      });
      var html = '<div class="list">' + entries.map(function (e) {
        var isDir = e.type === 'dir';
        var sub = isDir ? '' : '<span class="fmeta">' + U.bytes(e.size) + '</span>';
        // 在子目录里，接口返回的 path 是相对当前目录的（GitHub 就是这个约定），
        // 直接拼会把父级目录丢掉，点进去就跳到同级去了。这里补全成完整路径。
        var full = String(e.path || e.name || '');
        if (path && full.indexOf(path + '/') !== 0) full = path + '/' + full;
        // 目录名里的 # 和 ? 不转义会在 hash 路由里被当成片段/查询分隔符，
        // 结果就是点进去跳回仓库首页，所以路径必须走 encodePath
        var target = '/' + repo.full_name + '/' + (isDir ? 'tree' : 'blob') + '/' +
          encodeURIComponent(ref) + '/' + encodePath(full);
        return '<button class="file-row" data-go="' + U.esc(target) + '">' +
          '<span class="file-ico' + (isDir ? ' dir' : '') + '">' + window.icon(isDir ? 'file-directory-fill' : 'file', 16) + '</span>' +
          '<span class="fname">' + U.esc(e.name) + '</span>' + sub + '</button>';
      }).join('') + '</div>';

      // 根目录额外拼上「关于」卡片、语言分布和 README；
      // 子目录只用文件列表（原来整块都写在 if (!path) 里，
      // 导致点进任何文件夹都只剩一个空壳）
      if (!path) html = aboutCard(repo) + html;

      // README 在根目录默认展开；子目录里有 README 也在列表下面带上
      if (path && entries.some(function (e) { return /^readme(\.md|\.markdown)?$/i.test(e.name); })) {
        rs[1] = null;   // 子目录不重复请求 /readme，由下面按需加载
      }

      if (!path) {
        var langs = rs[2] && rs[2].data;
        if (langs && Object.keys(langs).length) {
          html += '<div class="card"><div class="list-row static" style="flex-direction:column;align-items:stretch">' +
            '<div style="font-weight:600;margin-bottom:4px">语言</div>' + UI.langBar(langs) + '</div></div>';
        }
        var readme = rs[1] && rs[1].data;
        if (readme && readme.content) {
          html += '<div class="card"><div class="list-row static" style="flex-direction:column;align-items:stretch">' +
            '<div class="rowflex" style="justify-content:space-between;margin-bottom:8px">' +
            '<span style="font-weight:600">' + U.esc(readme.name) + '</span>' +
            '<button class="btn sm" data-go="/' + repo.full_name + '/blob/' + encodeURIComponent(ref) + '/' + encodePath(readme.path) + '">查看源码</button></div>' +
            '<div id="readme"></div></div></div>';
        }
      }

      box.insertAdjacentHTML('beforeend', html);
      window.bindRepoCards(box);
      if (!path) {
        bindAbout(repo, box);
        var rm = UI.$('#readme', box);
        if (rm) {
          var rdata = rs[1].data;
          // 带上 ref 和 README 自己的路径：图片多为相对地址，
          // 渲染器要靠这俩才能补成 raw 地址（见 md.js 的 resolveImgUrl）
          window.MD.mount(rm, U.decodeBase64(rdata.content),
            { repo: repo.full_name, ref: ref, path: rdata.path });
        }
      }
      window.bindHashLinks(box);
    }).catch(function (e) {
      box.insertAdjacentHTML('beforeend', e.status === 404 ? UI.empty('file', '路径不存在', U.esc(path) + ' 在 ' + ref + ' 上找不到')
        : UI.errorBox(e));
    });
  }

  function encodePath(p) { return String(p || '').split('/').map(encodeURIComponent).join('/'); }

  /**
   * 是否对仓库有写权限。
   * 官方 API 在 permissions 里给 push/admin；但部分接口（如搜索结果、缓存数据）
   * 不带 permissions，此时用「仓库归属人 == 当前登录用户」兜底，避免自己的仓库
   * 反而看不到新建/上传/发布入口。
   */
  function canPush(repo) {
    if (!window.Session.user) return false;
    if (repo.permissions && (repo.permissions.push || repo.permissions.admin)) return true;
    var owner = repo.owner || {};
    return !!owner.login && owner.login === window.Session.user.login;
  }

  /* ---------------- 关于卡片（对标 GitHub 官网右栏 About） ---------------- */
  function aboutCard(repo) {
    var h = '<div class="card about-card">' +
      '<div class="about-title">关于</div>' +
      (repo.description ? '<div class="about-desc">' + U.esc(repo.description) + '</div>'
        : '<div class="about-desc" style="font-style:italic">暂无简介</div>');

    if (repo.topics && repo.topics.length) {
      h += '<div class="about-topics">' + repo.topics.map(function (t) {
        return '<a class="chip" href="#/search?q=' + encodeURIComponent('topic:' + t) + '&type=repositories">' + U.esc(t) + '</a>';
      }).join('') + '</div>';
    }

    if (repo.homepage) {
      var hp = String(repo.homepage).replace(/^https?:\/\//, '');
      h += '<div class="about-link">' + window.icon('link', 15) +
        '<a href="' + U.esc(repo.homepage) + '" target="_blank" rel="noopener">' + U.esc(hp) + '</a></div>';
    }

    // 元信息：许可证 / 默认分支 / 更新时间（官网 About 的 Readme·License·Activity 行）
    var meta = [];
    if (repo.license && repo.license.spdx_id && repo.license.spdx_id !== 'NOASSERTION') {
      meta.push('<span class="mrow">' + window.icon('law', 15) + U.esc(repo.license.spdx_id) + ' 许可证</span>');
    }
    if (repo.default_branch) {
      meta.push('<span class="mrow">' + window.icon('git-branch', 15) + '<span class="mono">' + U.esc(repo.default_branch) + '</span></span>');
    }
    if (repo.pushed_at) {
      meta.push('<span class="mrow">' + window.icon('history', 15) + '更新于 ' + U.timeAgo(repo.pushed_at) + '</span>');
    }
    if (meta.length) h += '<div class="about-meta">' + meta.join('') + '</div>';

    // Star / Fork / Watch 统计（官网 About 底部的三项）
    h += '<div class="about-stats mt12">' +
      '<span data-act="stargazers">' + window.icon('star', 14) + '<b>' + U.num(repo.stargazers_count) + '</b> Star</span>' +
      '<span data-act="forks">' + window.icon('repo-forked', 14) + '<b>' + U.num(repo.forks_count) + '</b> Fork</span>' +
      '<span data-act="watchers">' + window.icon('eye', 14) + '<b>' + U.num(repo.subscribers_count || 0) + '</b> 关注</span>' +
      '</div>';

    // 克隆地址：可切换 HTTPS / SSH，文本框可选中输入，一键复制
    h += '<div class="clone-box">' +
      '<div class="clone-head"><span class="t">克隆地址</span>' +
      '<button class="btn sm" id="clone-copy">' + window.icon('copy', 13) + '<span id="clone-copy-t">复制</span></button></div>' +
      '<div class="clone-seg" id="clone-seg">' +
      '<button data-k="https" class="active">HTTPS</button>' +
      '<button data-k="ssh">SSH</button>' +
      '<button data-k="zip">下载 ZIP</button>' +
      '</div>' +
      '<div class="clone-row">' +
      '<input class="input" id="clone-input" type="text" readonly spellcheck="false" autocomplete="off">' +
      '</div>' +
      '<div class="clone-tip" id="clone-tip">点击输入框可全选地址，长按可复制。</div>' +
      '</div></div>';
    return h;
  }

  function bindAbout(repo, box) {
    // 统计项点击跳转
    UI.$$('.about-stats span[data-act]', box).forEach(function (s) {
      s.style.cursor = 'pointer';
      s.onclick = function () { window.Router.go('/' + repo.full_name + '/' + s.getAttribute('data-act')); };
    });

    var input = UI.$('#clone-input', box);
    if (!input) return;
    var urls = {
      https: repo.clone_url || ('https://github.com/' + repo.full_name + '.git'),
      ssh: repo.ssh_url || ('git@github.com:' + repo.full_name + '.git'),
      zip: repo.html_url + '/archive/refs/heads/' + (repo.default_branch || 'main') + '.zip'
    };
    var kind = 'https';

    var paint = function () {
      input.value = urls[kind] || '';
      input.title = urls[kind] || '';
      var tip = UI.$('#clone-tip', box);
      if (tip) {
        tip.textContent = kind === 'zip' ? '点击输入框可全选地址，或点右上角复制。' : '点击输入框可全选地址，长按可复制。';
      }
      var cb = UI.$('#clone-copy-t', box);
      if (cb) cb.textContent = kind === 'zip' ? '复制链接' : '复制';
    };
    paint();

    UI.$$('#clone-seg button', box).forEach(function (b) {
      b.onclick = function () {
        kind = b.getAttribute('data-k');
        UI.$$('#clone-seg button', box).forEach(function (x) { x.classList.toggle('active', x === b); });
        paint();
      };
    });

    // 点击输入框 -> 自动全选，便于用户直接用键盘/输入法操作
    input.onclick = function () { input.focus(); input.select(); };
    input.onfocus = function () {
      // 粘性全选：避免每次点都反选
      if (input.selectionStart === input.selectionEnd) input.setSelectionRange(0, input.value.length);
    };

    var copyBtn = UI.$('#clone-copy', box);
    if (copyBtn) {
      copyBtn.onclick = function () {
        UI.copy(urls[kind], kind === 'zip' ? '下载链接已复制' : '克隆地址已复制');
        var t = UI.$('#clone-copy-t', box);
        if (t) { var old = t.textContent; t.textContent = '已复制'; setTimeout(function () { t.textContent = old; }, 1500); }
      };
    }
  }

  function pickRef(repo, cur, path) {
    UI.loading(true);
    Promise.all([
      window.API.get('/repos/' + repo.full_name + '/branches', { per_page: 100 }, { cache: 60000 }).catch(function () { return { data: [] }; }),
      window.API.get('/repos/' + repo.full_name + '/tags', { per_page: 100 }, { cache: 60000 }).catch(function () { return { data: [] }; })
    ]).then(function (rs) {
      UI.loading(false);
      var brs = rs[0].data || [], tags = rs[1].data || [];
      var body = '<div class="section-title">' + window.icon('git-branch', 14) + ' 分支（' + brs.length + '）</div><div class="list">' +
        brs.map(function (b) {
          return '<button class="list-row" data-r="' + U.esc(b.name) + '"><span class="row-main"><span class="row-title mono">' + U.esc(b.name) + '</span></span>' +
            (b.name === cur ? '<span class="row-side" style="color:var(--accent)">' + window.icon('check', 16) + '</span>' : '') + '</button>';
        }).join('') + '</div>' +
        (tags.length ? '<div class="section-title">' + window.icon('tag', 14) + ' 标签（' + tags.length + '）</div><div class="list">' +
          tags.map(function (t) {
            return '<button class="list-row" data-r="' + U.esc(t.name) + '"><span class="row-main"><span class="row-title mono">' + U.esc(t.name) + '</span>' +
              (t.commit ? '<span class="row-desc tiny">' + U.esc((t.commit.sha || '').substring(0, 7)) + '</span>' : '') + '</span>' +
              (t.name === cur ? '<span class="row-side" style="color:var(--accent)">' + window.icon('check', 16) + '</span>' : '') + '</button>';
          }).join('') + '</div>' : '');
      var root = document.getElementById('sheet-root');
      UI.sheet({
        title: '切换分支/标签', full: true, body: body,
        onMount: function () {
          UI.$$('.list-row', root).forEach(function (b) {
            b.onclick = function () {
              var r = b.getAttribute('data-r');
              UI.closeSheet();
              window.Router.go('/' + repo.full_name + (path ? '/tree/' + encodeURIComponent(r) + '/' + encodePath(path) : '/tree/' + encodeURIComponent(r)));
            };
          });
        }
      });
    }).catch(function (e) { UI.loading(false); UI.toast('加载失败：' + e.message); });
  }

  /* ---- 单文件查看 ---- */
  function showFile(repo, ref, path, box) {
    var name = path.split('/').pop();
    box.innerHTML = '<div class="code-meta"><span class="mono">' + U.esc(path) + '</span>' +
      '<span class="rowflex">' +
      '<button class="btn sm" id="cpf">' + window.icon('copy', 13) + '复制</button>' +
      '<button class="btn sm" id="dlf">' + window.icon('download', 13) + '下载</button>' +
      '<button class="btn sm" id="shf">' + window.icon('share-android', 13) + '</button>' +
      '</span></div><div id="fbody"><div style="padding:20px"><div class="spinner"></div></div></div>';

    UI.$('#cpf', box).onclick = function () {
      var t = UI.$('#srccode', box);
      UI.copy(t ? t.textContent : '', '已复制文件内容');
    };
    UI.$('#dlf', box).onclick = function () {
      var url = 'https://raw.githubusercontent.com/' + repo.full_name + '/' + encodeURIComponent(ref) + '/' + encodePath(path);
      if (window.NativeBridge && NativeBridge.download) NativeBridge.download(url, name);
      else window.open(url, '_blank');
    };
    UI.$('#shf', box).onclick = function () {
      var url = repo.html_url + '/blob/' + encodeURIComponent(ref) + '/' + path;
      window.NativeBridge && NativeBridge.share ? NativeBridge.share(url, name) : UI.copy(url, '链接已复制');
    };

    var isImage = /\.(png|jpe?g|gif|svg|webp|bmp|ico)$/i.test(name);
    if (isImage) {
      var url = 'https://raw.githubusercontent.com/' + repo.full_name + '/' + encodeURIComponent(ref) + '/' + encodePath(path);
      UI.$('#fbody', box).innerHTML = '<div style="padding:14px;text-align:center"><img src="' + U.esc(url) + '" style="max-width:100%" onclick="window.UI.viewImage(this.src)"></div>';
      return;
    }

    return window.API.get('/repos/' + repo.full_name + '/contents/' + encodePath(path), { ref: ref }, { cache: 60000 })
      .then(function (r) {
        var f = r.data;
        if (Array.isArray(f)) throw new Error('这是一个目录');
        if (!f.content) throw new Error('文件过大，请使用下载');
        var text = U.decodeBase64(f.content);
        paintCode(text, name, box);
      })
      .catch(function (e) {
        // 回退到 raw
        return fetchRaw(repo, ref, path).then(function (text) {
          if (text === null) throw e;
          paintCode(text, name, box);
        }).catch(function () {
          UI.$('#fbody', box).innerHTML = UI.empty('file', '无法预览', '文件可能过大或为二进制格式，请点击下载');
        });
      });
  }

  function fetchRaw(repo, ref, path) {
    var url = 'https://raw.githubusercontent.com/' + repo.full_name + '/' + encodeURIComponent(ref) + '/' + encodePath(path);
    if (window.Native.has()) {
      return window.Native.http('GET', url, null, { 'Accept': 'text/plain' }).then(function (res) {
        return res.status === 200 ? res.body : null;
      }).catch(function () { return null; });
    }
    return fetch(url).then(function (r) { return r.ok ? r.text() : null; }).catch(function () { return null; });
  }

  function paintCode(text, name, box) {
    var lines = text.split('\n');
    if (lines.length && lines[lines.length - 1] === '') lines.pop();
    var ext = name.split('.').pop().toLowerCase();
    var langMap = { js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript', py: 'python', rb: 'ruby', md: 'markdown', yml: 'yaml', sh: 'bash', kt: 'kotlin', java: 'java', go: 'go', rs: 'rust', json: 'json', html: 'xml', css: 'css', c: 'c', h: 'c', cpp: 'cpp', cs: 'csharp', php: 'php', swift: 'swift', sql: 'sql', vue: 'xml' };
    var lang = langMap[ext] || '';
    var html;
    try {
      html = (lang && window.hljs && hljs.getLanguage(lang)) ? hljs.highlight(text, { language: lang, ignoreIllegals: true }).value : U.esc(text);
    } catch (e) { html = U.esc(text); }
    var gutter = '';
    for (var i = 1; i <= lines.length; i++) gutter += '<div>' + i + '</div>';
    UI.$('#fbody', box).innerHTML =
      '<div class="code-lines"><div class="gutter">' + gutter + '</div>' +
      '<code class="src hljs" id="srccode" style="font-size:' + (window.Store.get('codeFont') || 13) + 'px">' + html + '</code></div>';
  }

  /* ============ 议题 / PR 列表 ============ */
  function issueParams(ctx, isPR) {
    var p = { state: ctx.query.state || 'open', per_page: 30, sort: 'updated', direction: 'desc' };
    if (ctx.query.labels) p.labels = ctx.query.labels;
    if (ctx.query.assignee) p.assignee = ctx.query.assignee;
    if (ctx.query.creator) p.creator = ctx.query.creator;
    if (ctx.query.milestone) p.milestone = ctx.query.milestone;
    return p;
  }

  function listFilterBar(repo, ctx, isPR, box, onReload) {
    var base = '/' + repo.full_name + (isPR ? '/pulls' : '/issues');
    var html = '<div style="padding:10px 12px 4px">' +
      UI.seg('ist', [{ key: 'open', label: '待处理' }, { key: 'closed', label: '已完成' }, { key: 'all', label: '全部' }], ctx.query.state || 'open') +
      '</div><div class="chips">' +
      '<span class="chip" id="f-label">' + window.icon('tag', 13) + '标签</span>' +
      '<span class="chip" id="f-assign">' + window.icon('person', 13) + '指派</span>' +
      '<span class="chip" id="f-sort">' + window.icon('filter', 13) + '排序</span>' +
      (ctx.query.labels || ctx.query.assignee ? '<span class="chip" id="f-clear">' + window.icon('x', 13) + '清除筛选</span>' : '') +
      '</div>';
    return html;
  }

  function bindFilters(repo, ctx, isPR, box, reload) {
    var base = '/' + repo.full_name + (isPR ? '/pulls' : '/issues');
    UI.$$('#ist button', box).forEach(function (b) {
      b.onclick = function () {
        var q = Object.assign({}, ctx.query, { state: b.getAttribute('data-v') });
        window.Router.go(base + '?' + qs(q));
      };
    });
    var fl = UI.$('#f-label', box);
    if (fl) fl.onclick = function () {
      window.API.get('/repos/' + repo.full_name + '/labels', { per_page: 100 }, { cache: 60000 }).then(function (r) {
        var items = (r.data || []).map(function (l) { return { icon: 'tag', label: l.name, key: l.name }; });
        if (!items.length) return UI.toast('该仓库没有标签');
        UI.menu('按标签筛选', items, {}).then(function (k) {
          if (k) window.Router.go(base + '?' + qs(Object.assign({}, ctx.query, { labels: k })));
        });
      });
    };
    var fa = UI.$('#f-assign', box);
    if (fa) fa.onclick = function () {
      window.API.get('/repos/' + repo.full_name + '/assignees', { per_page: 100 }, { cache: 60000 }).then(function (r) {
        var users = r.data || [];
        var items = users.map(function (u) { return { icon: 'person', label: u.login, key: u.login }; });
        if (window.Session.user) items.unshift({ icon: 'person', label: '指派给我（@' + window.Session.user.login + '）', key: window.Session.user.login });
        items.push({ icon: 'circle-slash', label: '未指派', key: 'none' });
        UI.menu('按指派人筛选', items, {}).then(function (k) {
          if (k) window.Router.go(base + '?' + qs(Object.assign({}, ctx.query, { assignee: k })));
        });
      });
    };
    var fs = UI.$('#f-sort', box);
    if (fs) fs.onclick = function () {
      UI.menu('排序方式', [
        { icon: 'clock', label: '最近更新', key: 'updated' },
        { icon: 'plus', label: '最新创建', key: 'created' },
        { icon: 'comment', label: '评论最多', key: 'comments' }
      ]).then(function (k) {
        if (k) window.Router.go(base + '?' + qs(Object.assign({}, ctx.query, { sort: k })));
      });
    };
    var fc = UI.$('#f-clear', box);
    if (fc) fc.onclick = function () {
      var q = Object.assign({}, ctx.query); delete q.labels; delete q.assignee;
      window.Router.go(base + '?' + qs(q));
    };
  }
  function qs(o) {
    return Object.keys(o).filter(function (k) { return o[k]; }).map(function (k) { return k + '=' + encodeURIComponent(o[k]); }).join('&');
  }
  window.qs = qs;

  function issueRow(it, repo, isPR) {
    var num = isPR ? 'pull/' + it.number : 'issues/' + it.number;
    return '<button class="list-row" data-go="/' + U.esc(repo.full_name) + '/' + num + '">' +
      '<span style="margin-top:2px;color:' + (isPR ? (it.merged ? 'var(--done)' : it.state === 'open' ? 'var(--success)' : 'var(--danger)') : (it.state === 'open' ? 'var(--success)' : 'var(--done)')) + '">' +
      window.icon(isPR ? (it.merged ? 'git-merge' : it.draft ? 'git-pull-request-draft' : 'git-pull-request') : (it.state === 'open' ? 'issue-opened' : 'issue-closed'), 16) + '</span>' +
      '<span class="row-main"><span class="row-title">' + U.esc(it.title) + '</span>' +
      '<span class="row-desc">#' + it.number + ' ' + (it.state === 'open' ? '由' : '由') + ' ' + U.esc((it.user && it.user.login) || '') + ' ' + (it.state === 'open' ? '创建于' : '创建于') + ' ' + U.timeAgo(it.created_at) + '</span>' +
      (it.labels && it.labels.length ? '<span class="rowflex wrap mt8">' + it.labels.map(function (l) {
        return '<span class="label" style="' + U.labelStyle(l.color) + '">' + U.esc(l.name) + '</span>';
      }).join('') + '</span>' : '') +
      '<span class="row-meta">' +
      (it.comments ? '<span>' + window.icon('comment', 12) + it.comments + '</span>' : '') +
      (it.assignee ? '<span>' + window.icon('person', 12) + U.esc(it.assignee.login) + '</span>' : '') +
      (it.milestone ? '<span>' + window.icon('milestone', 12) + U.esc(it.milestone.title) + '</span>' : '') +
      '</span></span></button>';
  }
  window.issueRow = issueRow;

  function tabIssues(repo, ctx, box) {
    box.innerHTML = listFilterBar(repo, ctx, false, box) + '<div id="ilist">' + UI.skeleton(4) + '</div>';
    bindFilters(repo, ctx, false, box);
    var p = issueParams(ctx, false);
    if (ctx.query.sort) p.sort = ctx.query.sort;
    return window.API.get('/repos/' + repo.full_name + '/issues', p).then(function (r) {
      var list = (r.data || []).filter(function (i) { return !i.pull_request; });
      var b = UI.$('#ilist', box); if (!b) return;
      b.innerHTML = list.length ? '<div class="list">' + list.map(function (i) { return issueRow(i, repo, false); }).join('') + '</div>'
        : UI.empty('issue-opened', '没有符合条件的议题', '试试切换筛选条件');
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#ilist', box).innerHTML = UI.errorBox(e); });
  }

  function tabPulls(repo, ctx, box) {
    box.innerHTML = listFilterBar(repo, ctx, true, box) + '<div id="plist">' + UI.skeleton(4) + '</div>';
    bindFilters(repo, ctx, true, box);
    var p = issueParams(ctx, true);
    if (ctx.query.sort) p.sort = ctx.query.sort;
    return window.API.get('/repos/' + repo.full_name + '/pulls', p).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#plist', box); if (!b) return;
      b.innerHTML = list.length ? '<div class="list">' + list.map(function (i) { return issueRow(i, repo, true); }).join('') + '</div>'
        : UI.empty('git-pull-request', '没有符合条件的拉取请求', '');
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#plist', box).innerHTML = UI.errorBox(e); });
  }

  /* ============ Actions ============ */
  function tabActions(repo, ctx, box) {
    var canRun = canPush(repo);
    box.innerHTML = '<div style="padding:10px 12px 4px">' +
      UI.seg('aseg', [{ key: 'all', label: '全部' }, { key: 'success', label: '成功' }, { key: 'failure', label: '失败' }, { key: 'in_progress', label: '运行中' }], ctx.query.status || 'all') +
      (canRun ? '<button class="btn primary block mt8" id="addwf">' + window.icon('rocket', 15) + ' 一键打包 APK</button>' +
        '<button class="btn block mt8" id="runwf">' + window.icon('zap', 15) + ' 手动触发构建</button>' : '') +
      '</div><div id="alist">' + UI.skeleton(4) + '</div>';
    UI.$$('#aseg button', box).forEach(function (b) {
      b.onclick = function () { window.Router.go('/' + repo.full_name + '/actions?status=' + b.getAttribute('data-v')); };
    });
    var runBtn = UI.$('#runwf', box);
    if (runBtn) runBtn.onclick = function () { triggerWorkflow(repo); };
    var addBtn = UI.$('#addwf', box);
    if (addBtn) addBtn.onclick = function () { buildApkWizard(repo); };
    var p = { per_page: 30 };
    if (ctx.query.status && ctx.query.status !== 'all') {
      p.status = ctx.query.status === 'in_progress' ? 'in_progress' : ctx.query.status === 'success' ? 'success' : 'failure';
    }
    return window.API.get('/repos/' + repo.full_name + '/actions/runs', p).then(function (r) {
      var runs = (r.data && r.data.workflow_runs) || [];
      var b = UI.$('#alist', box); if (!b) return;
      if (!runs.length) { b.innerHTML = UI.empty('workflow', '暂无运行记录', '仓库启用 Actions 后，运行记录会显示在这里'); return; }
      b.innerHTML = '<div class="list">' + runs.map(function (run) {
        var st = run.conclusion || run.status;
        var color = st === 'success' ? 'var(--success)' : st === 'failure' ? 'var(--danger)' : st === 'in_progress' || st === 'queued' ? 'var(--attention)' : 'var(--fg-muted)';
        var ico = st === 'success' ? 'check-circle-fill' : st === 'failure' ? 'x-circle-fill' : st === 'in_progress' ? 'play' : st === 'cancelled' ? 'skip' : 'dot-fill';
        return '<button class="list-row" data-go="/' + U.esc(repo.full_name) + '/actions/' + run.id + '">' +
          '<span style="color:' + color + ';margin-top:3px">' + window.icon(ico, 16) + '</span>' +
          '<span class="row-main"><span class="row-title">' + U.esc(run.display_title || run.name) + '</span>' +
          '<span class="row-desc">' + U.esc(run.name || '') + ' · ' + U.esc(run.head_branch || '') + '</span>' +
          '<span class="row-meta"><span>' + U.timeAgo(run.created_at) + '</span>' +
          '<span class="mono">' + U.esc((run.head_sha || '').substring(0, 7)) + '</span>' +
          '<span># ' + run.run_number + '</span></span></span></button>';
      }).join('') + '</div>';
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#alist', box).innerHTML = UI.errorBox(e); });
  }

  /* ============ 手动触发构建（workflow_dispatch） ============ */
  /**
   * 官网在 Actions 页右上角提供「Run workflow」。
   * 只有声明了 workflow_dispatch 的工作流才能手动触发，这里先筛选再让用户选分支。
   */
  function triggerWorkflow(repo) {
    if (!window.Session.isLogin) return UI.toast('请先登录');
    UI.loading(true);
    window.API.get('/repos/' + repo.full_name + '/actions/workflows', { per_page: 100 })
      .then(function (r) {
        UI.loading(false);
        var all = (r.data && r.data.workflows) || [];
        var list = all.filter(function (w) { return w.state === 'active'; });
        // 仓库还没有工作流时，直接引导到「一键打包 APK」：自动写入构建配置
        if (!list.length) return buildApkWizard(repo, '这个仓库还没有工作流，可以直接生成一个打包 APK 的配置。');
        pickWorkflow(repo, list);
      })
      .catch(function (e) {
        UI.loading(false);
        UI.toast('加载工作流失败：' + e.message);
      });
  }

  function pickWorkflow(repo, list) {
    var body = '<div class="muted tiny" style="margin-bottom:10px">选择一个工作流，然后指定在哪个分支上构建。</div>' +
      '<div class="list">' + list.map(function (w) {
        return '<button class="list-row" data-wf="' + U.esc(w.id) + '" data-wn="' + U.esc(w.name || '') + '">' +
          window.icon('workflow', 16) +
          '<span class="row-main"><span class="row-title">' + U.esc(w.name || w.path) + '</span>' +
          '<span class="row-desc mono tiny">' + U.esc(w.path || '') + '</span></span>' +
          window.icon('chevron-right', 16) + '</button>';
      }).join('') + '</div>';
    var root = document.getElementById('sheet-root');
    UI.sheet({
      title: '触发构建', body: body, full: true,
      onMount: function () {
        UI.$$('[data-wf]', root).forEach(function (b) {
          b.onclick = function () {
            var wfId = b.getAttribute('data-wf');
            var wfName = b.getAttribute('data-wn');
            pickBranchAndRun(repo, wfId, wfName);
          };
        });
      }
    });
  }

  function pickBranchAndRun(repo, wfId, wfName) {
    UI.loading(true);
    window.API.get('/repos/' + repo.full_name + '/branches', { per_page: 100 })
      .then(function (r) {
        UI.loading(false);
        var branches = r.data || [];
        var names = branches.map(function (x) { return x.name; });
        if (names.indexOf(repo.default_branch) < 0) names.unshift(repo.default_branch);
        var body =
          '<div class="field"><label>工作流</label>' +
          '<div class="del-target">' + window.icon('workflow', 15) +
          '<span class="mono">' + U.esc(wfName || wfId) + '</span></div></div>' +
          '<div class="field"><label>在哪个分支上构建</label>' +
          '<select class="input" id="wf-ref">' + names.map(function (n) {
            return '<option value="' + U.esc(n) + '"' + (n === repo.default_branch ? ' selected' : '') + '>' + U.esc(n) + '</option>';
          }).join('') + '</select>' +
          '<div class="hint">触发后 GitHub 会开始构建，完成后回到这里点开运行记录即可下载产物（APK）。</div></div>';
        var root = document.getElementById('sheet-root');
        UI.sheet({
          title: '触发构建', body: body,
          foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>开始构建</button>',
          onMount: function () {
            root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
            root.querySelector('[data-yes]').onclick = function () {
              var ref = root.querySelector('#wf-ref').value;
              UI.loading(true);
              window.API.post('/repos/' + repo.full_name + '/actions/workflows/' + wfId + '/dispatches',
                { ref: ref })
                .then(function () {
                  UI.loading(false);
                  UI.closeSheet();
                  UI.toast('已触发构建，稍后刷新查看进度');
                  window.Router.go('/' + repo.full_name + '/actions');
                  window.Router.reload();
                })
                .catch(function (e) {
                  UI.loading(false);
                  UI.toast(e.status === 404 ? '该工作流不支持手动触发' : '触发失败：' + e.message);
                });
            };
          }
        });
      })
      .catch(function (e) { UI.loading(false); UI.toast('加载分支失败：' + e.message); });
  }

  /* ============ 一键打包 APK ============ */
  /**
   * 官网本身不会"打包 APK"——它靠仓库里的 Actions 工作流去构建。
   * 这里把这个门槛也做掉：App 内直接生成构建配置并触发，
   * 用户不需要在电脑上写 .github/workflows/*.yml。
   */
  /**
   * 构建配置模板。
   *
   * 生成的 YAML 每一步都带中文注释，并且可以直接在手机上改；
   * yaml(variant, sign, note)：
   *   variant = 'debug' | 'release'
   *   sign    = 签名配置对象（不需要签名为 null）
   *   note    = 修改说明，会写进文件头部注释
   */
  /**
   * 构建配置模板。
   *
   * 生成的 YAML 每一步都带中文注释，可以直接在手机上改；
   * yaml(variant, sign, note, info)：
   *   variant = 'debug' | 'release'
   *   sign    = 签名配置对象（不需要签名为 null）
   *   note    = 修改说明，会写进文件头部注释
   *   info    = {appName, versionName, versionCode} 自定义名称与版本号
   */
  var WF_TEMPLATES = {
    android: {
      key: 'android',
      label: 'Android（Gradle）',
      desc: '检测到 settings.gradle / build.gradle / gradlew',
      file: '.github/workflows/build-apk.yml',
      yaml: function (variant, sign, note, info) {
        var rel = variant === 'release';
        return yamlHead(note, 'Android Gradle') +
          'name: Build APK\n' +
          '\n' +
          onBlock(info) +
          'jobs:\n' +
          '  build:\n' +
          '    runs-on: ubuntu-latest   # GitHub 免费提供的 Linux 构建机\n' +
          '    steps:\n' +
          '      # 1. 把仓库代码拉到构建机上\n' +
          '      - name: Checkout\n' +
          '        uses: actions/checkout@v4\n' +
          '\n' +
          '      # 2. 准备 JDK（Gradle 8 需要 17，老项目可改成 11）\n' +
          '      - name: Set up JDK 17\n' +
          '        uses: actions/setup-java@v4\n' +
          '        with:\n' +
          '          distribution: temurin\n' +
          "          java-version: '17'\n" +
          '\n' +
          '      # 3. gradlew 必须可执行，否则报 Permission denied\n' +
          '      - name: Grant execute permission for gradlew\n' +
          '        run: chmod +x ./gradlew || true\n' +
          '\n' +
          versionStep(info) +
          '      # 5. 构建；命令可以按需改，例如只编某个模块 :app:assembleDebug\n' +
          '      - name: Build ' + (rel ? 'Release' : 'Debug') + ' APK\n' +
          '        run: ./gradlew ' + (rel ? 'assembleRelease' : 'assembleDebug') + ' --no-daemon\n' +
          '\n' +
          (rel && sign ? signStep(sign, '*/outputs/apk/release/*') : '') +
          renameStep(info) +
          '      # 上传产物：构建完后在 App 里点开这条运行记录即可下载、自动安装\n' +
          '      - name: Upload APK\n' +
          '        uses: actions/upload-artifact@v4\n' +
          '        with:\n' +
          '          name: ' + artifactName() + '\n' +
          '          path: |\n' +
          '            **/build/outputs/apk/**/*.apk\n' +
          '            **/build/outputs/bundle/**/*.aab\n' +
          '          if-no-files-found: error\n';
      }
    },
    flutter: {
      key: 'flutter',
      label: 'Flutter',
      desc: '检测到 pubspec.yaml',
      file: '.github/workflows/build-apk.yml',
      yaml: function (variant, sign, note, info) {
        var rel = variant === 'release';
        return yamlHead(note, 'Flutter') +
          'name: Build APK\n' +
          '\n' +
          onBlock(info) +
          'jobs:\n' +
          '  build:\n' +
          '    runs-on: ubuntu-latest   # GitHub 免费提供的 Linux 构建机\n' +
          '    steps:\n' +
          '      # 1. 把仓库代码拉到构建机上\n' +
          '      - name: Checkout\n' +
          '        uses: actions/checkout@v4\n' +
          '\n' +
          '      # 2. 准备 JDK（打 Android 包必备）\n' +
          '      - name: Set up JDK 17\n' +
          '        uses: actions/setup-java@v4\n' +
          '        with:\n' +
          '          distribution: temurin\n' +
          "          java-version: '17'\n" +
          '\n' +
          '      # 3. 安装 Flutter SDK；channel 可改成 beta / master\n' +
          '      - name: Set up Flutter\n' +
          '        uses: subosito/flutter-action@v2\n' +
          '        with:\n' +
          '          channel: stable\n' +
          '\n' +
          '      # 4. 拉依赖\n' +
          '      - name: Install dependencies\n' +
          '        run: flutter pub get\n' +
          '\n' +
          versionStep(info) +
          '      # 6. 构建；--split-per-abi 可拆成多个架构包\n' +
          '      - name: Build ' + (rel ? 'Release' : 'Debug') + ' APK\n' +
          '        run: flutter build apk --' + (rel ? 'release' : 'debug') + '\n' +
          '\n' +
          (rel && sign ? signStep(sign, '*/flutter-apk/*') : '') +
          renameStep(info) +
          '      # 上传产物：构建完后在 App 里点开这条运行记录即可下载、自动安装\n' +
          '      - name: Upload APK\n' +
          '        uses: actions/upload-artifact@v4\n' +
          '        with:\n' +
          '          name: ' + artifactName() + '\n' +
          '          path: build/app/outputs/flutter-apk/*.apk\n' +
          '          if-no-files-found: error\n';
      }
    },
    zip: {
      key: 'zip',
      label: '通用打包（ZIP）',
      desc: '把仓库打成压缩包作为产物',
      file: '.github/workflows/build-package.yml',
      yaml: function (variant, sign, note, info) {
        return yamlHead(note, '通用 ZIP 打包') +
          'name: Build Package\n' +
          '\n' +
          onBlock(info) +
          'jobs:\n' +
          '  build:\n' +
          '    runs-on: ubuntu-latest\n' +
          '    steps:\n' +
          '      # 1. 拉取代码\n' +
          '      - name: Checkout\n' +
          '        uses: actions/checkout@v4\n' +
          '\n' +
          '      # 2. 打包；-x 后面是要排除的目录\n' +
          '      - name: Zip\n' +
          "        run: zip -r app.zip . -x '.git/*'\n" +
          '\n' +
          renameStep(info) +
          '      # 上传产物\n' +
          '      - name: Upload\n' +
          '        uses: actions/upload-artifact@v4\n' +
          '        with:\n' +
          '          name: ' + artifactName() + '\n' +
          '          path: app.zip\n' +
          '          if-no-files-found: error\n';
      }
    }
  };

  /** YAML 里的单引号字符串转义 */
  function yq(s) {
    return String(s == null ? '' : s).replace(/'/g, "''");
  }

  /**
   * 文件头部注释：谁生成、改了什么。
   * 用户要求「改配置必须带注释」，这里把说明同时落进文件和提交信息。
   */
  function yamlHead(note, kind) {
    var d = new Date();
    var stamp = d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) +
      '-' + ('0' + d.getDate()).slice(-2);
    return '# 由 githup App 生成（' + kind + '）· ' + stamp + '\n' +
      '# 修改说明：' + (note && note.trim() ? note.trim() : '首次生成，未做改动') + '\n' +
      '# 每个步骤都带注释，可直接修改；改完请在上方填写修改说明再保存。\n' +
      '\n';
  }

  /**
   * 手动触发时可以临时改名称与版本号，不用改配置。
   * 默认值就是用户在 App 里填的那些。
   */
  function onBlock(info) {
    var i = info || {};
    return 'on:\n' +
      '  workflow_dispatch:   # 允许在 App 里点「手动触发构建」\n' +
      '    inputs:\n' +
      '      app_name:\n' +
      '        description: 安装包名称\n' +
      "        default: '" + yq(i.appName || 'app') + "'\n" +
      '      version_name:\n' +
      '        description: 版本号（versionName）\n' +
      "        default: '" + yq(i.versionName || '1.0.0') + "'\n" +
      '      version_code:\n' +
      '        description: 版本代号（versionCode，整数）\n' +
      "        default: '" + yq(String(i.versionCode || '1')) + "'\n" +
      '\n';
  }

  /** 产物名：名称-版本号 */
  function artifactName() {
    return '${{ github.event.inputs.app_name }}-${{ github.event.inputs.version_name }}';
  }

  /** 把 build.gradle / build.gradle.kts 里的版本号改成用户填的值 */
  function versionStep(info) {
    if (!info || !info.versionName) return '';
    var L = [];
    L.push('      # 4. 改成你在 App 里填的版本号（同时兼容 build.gradle 与 .kts）');
    L.push('      - name: Set version');
    L.push('        env:');
    L.push('          VN: ' + artifactVN());
    L.push('          VC: ' + artifactVC());
    L.push('        run: |');
    L.push('          for f in $(find . -name "build.gradle" -o -name "build.gradle.kts"); do');
    L.push('            sed -i -E "s/versionName.*/versionName = \\"$VN\\"/" "$f"');
    L.push('            sed -i -E "s/versionCode.*/versionCode = $VC/" "$f"');
    L.push('          done');
    L.push('          echo "版本已设为 $VN ($VC)"');
    return L.join('\n') + '\n\n';
  }

  function artifactVN() { return '${{ github.event.inputs.version_name }}'; }
  function artifactVC() { return '${{ github.event.inputs.version_code }}'; }

  /** 把产物改名成「名称-版本号.apk」 */
  function renameStep(info) {
    if (!info || !info.appName) return '';
    var L = [];
    L.push('      # 把产物改成你指定的安装包名称');
    L.push('      - name: Rename APK');
    L.push('        env:');
    L.push('          NAME: ${{ github.event.inputs.app_name }}');
    L.push('          VN: ' + artifactVN());
    L.push('        run: |');
    L.push('          APK=$(find . -name "*.apk" | head -1)');
    L.push('          if [ -n "$APK" ]; then');
    L.push('            D=$(dirname "$APK")');
    L.push('            mv "$APK" "$D/$NAME-$VN.apk"');
    L.push('            echo "产物: $D/$NAME-$VN.apk"');
    L.push('          fi');
    return L.join('\n') + '\n\n';
  }

  /**
   * 签名步骤：把 Base64 还原成 keystore，再用 apksigner 给 APK 签名。
   * apksigner 在 GitHub 构建机自带的 Android SDK 里，不需额外安装。
   */
  function signStep(sign, glob) {
    var plain = sign && sign.mode === 'plain';
    var env = plain
      ? "          KEYSTORE_BASE64: '" + yq(sign.b64) + "'\n" +
        "          KEYSTORE_PASSWORD: '" + yq(sign.storePass) + "'\n" +
        "          KEY_ALIAS: '" + yq(sign.alias) + "'\n" +
        "          KEY_PASSWORD: '" + yq(sign.keyPass) + "'\n"
      : '          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}\n' +
        '          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}\n' +
        '          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}\n' +
        '          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}\n';
    return '      # 签名：Base64 还原成 keystore，再用 apksigner 给 APK 签名\n' +
      (plain ? '      # 警告：密钥以明文写在配置里，公开仓库请勿这样用！\n' : '') +
      '      - name: Sign APK\n' +
      '        env:\n' + env +
      '        run: |\n' +
      '          echo "$KEYSTORE_BASE64" | base64 -d > /tmp/app.keystore\n' +
      '          APK=$(find . -name "*.apk" -path "' + glob + '" | head -1)\n' +
      '          if [ -z "$APK" ]; then echo "没有找到 APK"; exit 1; fi\n' +
      '          SIGNER=$(ls $ANDROID_HOME/build-tools/*/apksigner | head -1)\n' +
      '          "$SIGNER" sign --ks /tmp/app.keystore --ks-key-alias "$KEY_ALIAS" \\\n' +
      '            --ks-pass pass:"$KEYSTORE_PASSWORD" --key-pass pass:"$KEY_PASSWORD" \\\n' +
      '            --out "$APK.signed" "$APK"\n' +
      '          mv "$APK.signed" "$APK"\n' +
      '\n';
  }

  /** 需要用户在 GitHub 网页里添加的 4 个 Secret 名称 */
  var SIGN_SECRETS = ['KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD'];

  /** 读仓库根目录，猜项目类型，选不准也没关系——用户可以手动改。 */
  function detectProject(repo) {
    return window.API.get('/repos/' + repo.full_name + '/contents/', { ref: repo.default_branch }, { cache: 0 })
      .then(function (r) {
        var files = r.data || [];
        var names = files.map(function (f) { return f.name; });
        var has = function (re) { return names.some(function (n) { return re.test(n); }); };
        if (names.indexOf('pubspec.yaml') >= 0) return 'flutter';
        if (has(/^settings\.gradle(\.kts)?$/) || has(/^build\.gradle(\.kts)?$/) || names.indexOf('gradlew') >= 0) return 'android';
        if (names.indexOf('package.json') >= 0) return 'zip';
        return 'android';
      })
      .catch(function () { return 'android'; });
  }

  function b64(str) {
    return btoa(unescape(encodeURIComponent(str)));
  }

  function buildApkWizard(repo, notice) {
    if (!window.Session.isLogin) return UI.toast('请先登录');
    if (!canPush(repo)) return UI.toast('没有该仓库的写入权限');
    UI.loading(true);
    Promise.all([
      detectProject(repo),
      window.API.get('/repos/' + repo.full_name + '/branches', { per_page: 100 }).catch(function () { return { data: [] }; })
    ]).then(function (rs) {
      UI.loading(false);
      var kind = rs[0];
      var branches = (rs[1].data || []).map(function (x) { return x.name; });
      if (branches.indexOf(repo.default_branch) < 0) branches.unshift(repo.default_branch);
      pickBuildTemplate(repo, kind, branches, notice);
    }).catch(function (e) {
      UI.loading(false);
      UI.toast('初始化失败：' + e.message);
    });
  }

  function pickBuildTemplate(repo, kind, branches, notice) {
    var order = ['android', 'flutter', 'zip'];
    // 检测出来的类型排在最前面，减少用户选择成本
    order.sort(function (a, b) { return (b === kind ? 1 : 0) - (a === kind ? 1 : 0); });
    // 用户点的是「打包 APK」：没识别出 Android/Flutter 时也默认给 Android，
    // 而不是给一个根本产不出 APK 的 ZIP 模板
    var picked = (kind === 'zip') ? 'android' : kind;
    var variant = 'debug';
    /** 用户是否手动改过配置：改过就必须填修改说明，且不再被模板覆盖 */
    var edited = false;
    /** App 内生成的签名结果（由口令派生） */
    var madeKs = null;

    var body =
      (notice ? '<div class="hint" style="margin-bottom:10px">' + U.esc(notice) + '</div>' : '') +
      (kind === 'zip' ? '<div class="hint" style="margin-bottom:10px">' +
        '没有检测到 Android / Flutter 项目特征，已默认按 Android Gradle 生成；' +
        '如果构建失败，请在下面改选项目类型。</div>' : '') +
      '<div class="field"><label>项目类型</label><div class="list" id="tpl-list">' +
      order.map(function (k) {
        var t = WF_TEMPLATES[k];
        return '<button class="list-row tpl" data-tpl="' + k + '">' +
          '<span style="color:' + (k === picked ? 'var(--accent)' : 'var(--fg-muted)') + '">' +
          window.icon(k === picked ? 'check-circle-fill' : 'circle', 16) + '</span>' +
          '<span class="row-main"><span class="row-title">' + U.esc(t.label) + '</span>' +
          '<span class="row-desc tiny">' + U.esc(t.desc) + '</span></span></button>';
      }).join('') + '</div></div>' +
      '<div class="field"><label>构建类型</label>' +
      '<select class="input" id="wf-variant">' +
      '<option value="debug" selected>Debug（无需签名，装到手机就能跑）</option>' +
      '<option value="release">Release（未签名，正式发布不能上架）</option>' +
      '<option value="sign">Release + 签名</option>' +
      '</select></div>' +
      '<div class="field"><label>安装包名称</label>' +
      '<input class="input mono" id="wf-name" type="text" autocomplete="off" ' +
      'autocapitalize="none" spellcheck="false" placeholder="例如 MyApp">' +
      '<div class="hint">产物会命名为「名称-版本号.apk」。</div></div>' +
      '<div class="field"><label>版本号（versionName）</label>' +
      '<input class="input mono" id="wf-ver" type="text" autocomplete="off" ' +
      'spellcheck="false" placeholder="例如 1.0.0">' +
      '<div class="hint">会写进 build.gradle / build.gradle.kts。</div></div>' +
      '<div class="field"><label>版本代号（versionCode）</label>' +
      '<input class="input mono" id="wf-vcode" type="text" inputmode="numeric" ' +
      'autocomplete="off" spellcheck="false" placeholder="例如 1">' +
      '<div class="hint">必须是整数，每次发版递增。</div></div>' +
      // 签名配置：只在选了「Release + 签名」时出现
      '<div class="field" id="sign-box" hidden>' +
      '<label>签名方式</label>' +
      '<select class="input" id="sg-kind">' +
      '<option value="make" selected>用口令生成（在 App 内生成，推荐）</option>' +
      '<option value="import">用已有的 keystore（粘贴 Base64）</option>' +
      '</select>' +
      '<div id="sg-make" style="margin-top:10px">' +
      '<div class="field"><label class="sub">签名口令</label>' +
      '<input class="input mono" id="sg-seed" type="text" autocomplete="off" ' +
      'autocapitalize="none" spellcheck="false" placeholder="输入英文字母和数字，例如 githup2024abc">' +
      '<div class="hint">这串字符就是你的签名：口令相同，签名就完全相同，' +
      '新包可以直接覆盖安装。口令不同就是另一个签名，请务必记牢。</div></div>' +
      '<button class="btn block" id="sg-gen">' + window.icon('key', 15) + ' 生成签名</button>' +
      '<div class="hint" id="sg-res"></div>' +
      '</div>' +
      '<div id="sg-import" hidden style="margin-top:10px">' +
      '<div class="field"><label class="sub">keystore（Base64）</label>' +
      '<textarea class="textarea mono tiny" id="sg-b64" rows="3" spellcheck="false"' +
      ' autocapitalize="none" autocorrect="off" ' +
      'placeholder="粘贴 keystore 文件的 Base64，是一长串数字和字母"></textarea>' +
      '<div class="hint">电脑上生成：' +
      'keytool -genkey -v -keystore app.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 10000' +
      '，再执行 base64 app.keystore，把输出整段粘进来。</div></div>' +
      '<div class="field"><label class="sub">密钥库密码</label>' +
      '<input class="input" id="sg-sp" type="text" autocomplete="off" spellcheck="false" placeholder="store password"></div>' +
      '<div class="field"><label class="sub">密钥别名</label>' +
      '<input class="input mono" id="sg-alias" type="text" autocomplete="off" spellcheck="false" placeholder="例如 mykey"></div>' +
      '<div class="field"><label class="sub">密钥密码</label>' +
      '<input class="input" id="sg-kp" type="text" autocomplete="off" spellcheck="false" placeholder="key password"></div>' +
      '</div>' +
      '<div class="field" style="margin-top:10px"><label class="sub">密钥写在哪里</label>' +
      '<select class="input" id="sg-mode">' +
      '<option value="secrets" selected>仓库 Secrets（推荐，配置里只留变量名）</option>' +
      '<option value="plain">明文写进配置（仅私有仓库，公开仓库会泄露密钥）</option>' +
      '</select>' +
      '<div class="hint" id="sg-tip"></div>' +
      '<button class="btn block mt8" id="sg-help" hidden>' + window.icon('link-external', 15) +
      ' 去 GitHub 添加这 4 个 Secret</button>' +
      '</div></div>' +
      '<div class="field"><label>目标分支</label>' +
      '<select class="input" id="wf-ref2">' + branches.map(function (n) {
        return '<option value="' + U.esc(n) + '"' + (n === repo.default_branch ? ' selected' : '') + '>' + U.esc(n) + '</option>';
      }).join('') + '</select>' +
      '<div class="hint" id="wf-path"></div></div>' +
      '<div class="field"><label id="wf-note-label">修改说明</label>' +
      '<input class="input" id="wf-note" type="text" autocomplete="off" ' +
      'placeholder="例如：改成 JDK 11、只编 app 模块">' +
      '<div class="hint">改了下面的配置就必填，会同时写进文件头部的注释和提交信息。</div></div>' +
      // 默认展开：藏在折叠区里用户根本点不到，更别说改
      '<details class="wf-preview" id="wf-box" open><summary>查看并编辑将要写入的配置</summary>' +
      '<textarea class="textarea mono tiny" id="wf-code" rows="16" spellcheck="false"' +
      ' autocapitalize="none" autocorrect="off"></textarea>' +
      '<div style="margin-top:6px"><button class="btn sm" data-reset>恢复默认模板</button></div>' +
      '<div class="hint err-hint" id="wf-err"></div></details>';

    var root = document.getElementById('sheet-root');

    function v(sel) {
      var el = root.querySelector(sel);
      return el ? String(el.value || '').trim() : '';
    }

    /** 前端算个短指纹，让用户确认签名确实生成了（不参与密码学） */
    function fp(s) {
      var h = 5381, i;
      for (i = 0; i < s.length; i++) h = ((h * 33) ^ s.charCodeAt(i)) & 0x7fffffff;
      return ('0000000' + h.toString(16)).slice(-8);
    }

    /**
     * 读取并校验签名表单。
     * silent=true 用于实时预览（还没填完不算错，不弹提示）；
     * silent=false 用于点「写入并构建」时做完整校验。
     */
    function readSign(silent) {
      function bad(id, msg) {
        if (silent) return null;
        var el = root.querySelector(id);
        if (el) { el.focus(); el.classList.add('bad'); }
        UI.toast(msg);
        return null;
      }
      UI.$$('#sign-box .input, #sign-box .textarea', root).forEach(function (el) {
        el.classList.remove('bad');
      });
      var mode = v('#sg-mode') || 'secrets';
      if (v('#sg-kind') === 'make') {
        if (!madeKs) return bad('#sg-gen', '请先点「生成签名」');
        madeKs.mode = mode;
        return madeKs;
      }
      var b64 = v('#sg-b64'), sp = v('#sg-sp'), alias = v('#sg-alias'), kp = v('#sg-kp');
      if (!b64) return bad('#sg-b64', '请粘贴 keystore 的 Base64');
      if (b64.length < 80) return bad('#sg-b64', 'Base64 太短了，应该是一长串数字字母');
      if (!/^[A-Za-z0-9+/=\s]+$/.test(b64)) return bad('#sg-b64', 'Base64 只能包含数字、字母和 + / =');
      if (!sp) return bad('#sg-sp', '请填写密钥库密码');
      if (!alias) return bad('#sg-alias', '请填写密钥别名');
      if (!kp) return bad('#sg-kp', '请填写密钥密码');
      return {
        b64: b64.replace(/\s+/g, ''), storePass: sp, alias: alias, keyPass: kp, mode: mode
      };
    }

    function note() { return v('#wf-note'); }

    function info() {
      return {
        appName: v('#wf-name') || 'app',
        versionName: v('#wf-ver') || '1.0.0',
        versionCode: v('#wf-vcode') || '1'
      };
    }

    function sync() {
      var ta = root.querySelector('#wf-code');
      // 用户手动改过就不再覆盖，避免把人家的改动冲掉
      if (ta && !edited) {
        var s = null;
        if (variant === 'sign') s = readSign(true);
        ta.value = WF_TEMPLATES[picked].yaml(
          variant === 'sign' ? 'release' : variant, s, note(), info());
      }
      var pathTip = root.querySelector('#wf-path');
      if (pathTip) pathTip.textContent = '配置文件：' + WF_TEMPLATES[picked].file;
      UI.$$('#tpl-list .tpl', root).forEach(function (b) {
        var on = b.getAttribute('data-tpl') === picked;
        b.classList.toggle('sel', on);
        var wrap = b.firstElementChild;
        if (wrap && wrap.tagName === 'SPAN') {
          wrap.innerHTML = window.icon(on ? 'check-circle-fill' : 'circle', 16);
          wrap.style.color = on ? 'var(--accent)' : 'var(--fg-muted)';
        }
      });
      var box = root.querySelector('#sign-box');
      if (box) box.hidden = variant !== 'sign';
      var mk = root.querySelector('#sg-make'), im = root.querySelector('#sg-import');
      if (mk) mk.hidden = v('#sg-kind') !== 'make';
      if (im) im.hidden = v('#sg-kind') === 'make';
      var tip = root.querySelector('#sg-tip');
      if (tip) {
        var plain = v('#sg-mode') === 'plain';
        tip.innerHTML = plain
          ? '<b style="color:var(--danger)">密钥会明文写进配置文件，公开仓库任何人都能看到，请谨慎。</b>'
          : '需要在仓库里添加 ' + SIGN_SECRETS.join('、') + ' 四个 Secret，配置里只会出现变量名。';
      }
      var help = root.querySelector('#sg-help');
      if (help) help.hidden = v('#sg-mode') === 'plain' || variant !== 'sign';
    }

    UI.sheet({
      title: '一键打包 APK', body: body, full: true,
      foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>写入并构建</button>',
      onMount: function () {
        UI.$$('#tpl-list .tpl', root).forEach(function (b) {
          b.onclick = function () {
            picked = b.getAttribute('data-tpl');
            sync();
          };
        });
        root.querySelector('#wf-variant').onchange = function () { variant = this.value; sync(); };
        ['#sg-kind', '#sg-mode'].forEach(function (sel) {
          var el = root.querySelector(sel);
          if (el) el.onchange = function () { sync(); };
        });
        ['#wf-name', '#wf-ver', '#wf-vcode'].forEach(function (sel) {
          var el = root.querySelector(sel);
          if (el) el.addEventListener('input', function () { if (!edited) sync(); });
        });
        UI.$$('#sign-box .input, #sign-box .textarea', root).forEach(function (el) {
          el.addEventListener('input', function () { if (!edited) sync(); });
        });

        var help = root.querySelector('#sg-help');
        if (help) help.onclick = function () {
          window.Native.openInApp('https://github.com/' + repo.full_name +
            '/settings/secrets/actions/new', '添加 Secret');
        };

        // 在 App 内把口令变成真正的签名密钥
        var gen = root.querySelector('#sg-gen');
        if (gen) gen.onclick = function () {
          var seed = v('#sg-seed');
          if (seed.length < 6) return UI.toast('口令至少 6 位');
          if (!/^[A-Za-z0-9]+$/.test(seed)) return UI.toast('口令只能用英文字母和数字');
          UI.loading(true);
          window.Native.makeKeystore(seed, 'githup', seed).then(function (b64) {
            UI.loading(false);
            madeKs = { b64: b64, storePass: seed, alias: 'githup', keyPass: seed };
            var res = root.querySelector('#sg-res');
            if (res) {
              res.innerHTML = '<b style="color:var(--success)">签名已生成</b>，指纹 <span class="mono">' +
                fp(b64) + '</span>　<button class="btn sm" id="sg-copy">复制密钥</button>' +
                '<div class="tiny muted">同一串口令 → 同一个签名，可覆盖安装；换口令就是另一个签名。</div>';
              var cp = root.querySelector('#sg-copy');
              if (cp) cp.onclick = function () {
                window.Native.copy(b64);
                UI.toast('密钥已复制，去 GitHub 存为 KEYSTORE_BASE64');
                window.Native.openInApp('https://github.com/' + repo.full_name +
                  '/settings/secrets/actions/new', '添加 Secret');
              };
            }
            sync();
          }).catch(function (e) {
            UI.loading(false);
            UI.toast('生成失败：' + e.message);
          });
        };

        var noteEl = root.querySelector('#wf-note');
        if (noteEl) noteEl.addEventListener('input', function () { if (!edited) sync(); });

        var ta = root.querySelector('#wf-code');
        ta.addEventListener('input', function () {
          edited = true;
          var lab = root.querySelector('#wf-note-label');
          if (lab) lab.innerHTML = '修改说明 <b style="color:var(--danger)">（改了配置，必填）</b>';
          validate();
        });
        var reset = root.querySelector('[data-reset]');
        if (reset) reset.onclick = function () {
          edited = false;
          var lab = root.querySelector('#wf-note-label');
          if (lab) lab.textContent = '修改说明';
          sync();
          UI.toast('已恢复默认模板');
        };

        /** 编辑后做基本校验，把错误直接写在编辑区下面 */
        function validate() {
          var err = root.querySelector('#wf-err');
          var msg = '';
          var val = ta.value;
          if (!val.trim()) msg = '配置内容不能为空';
          else if (val.indexOf('jobs:') < 0) msg = '配置里必须有 jobs: 段落';
          else if (/\t/.test(val)) msg = 'YAML 不能用 Tab 缩进，请改用空格';
          if (err) err.textContent = msg;
          return !msg;
        }

        root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
        root.querySelector('[data-yes]').onclick = function () {
          var ref = root.querySelector('#wf-ref2').value;
          if (!validate()) return UI.toast('请先修正配置里的错误');
          var s = null;
          if (variant === 'sign') {
            s = readSign(false);
            if (!s) return;
          }
          // 改过配置就必须写说明：这是硬要求，不然以后没人知道改了什么
          if (edited && !note()) {
            UI.toast('你修改了配置，请先填写修改说明');
            noteEl.classList.add('bad');
            noteEl.focus();
            return;
          }
          noteEl.classList.remove('bad');
          writeWorkflowAndRun(repo, WF_TEMPLATES[picked], ta.value, ref, note(), edited, info());
        };
        sync();
      }
    });
  }

  function writeWorkflowAndRun(repo, tpl, content, ref, note, edited, info) {
    UI.loading(true);
    var path = tpl.file;
    var enc = path.split('/').map(encodeURIComponent).join('/');
    // 说明既进提交信息，也进文件头部注释——配置里看不到改了什么是很痛苦的
    if (note) {
      if (/#\s*修改说明：/.test(content)) {
        content = content.replace(/#\s*修改说明：.*\n/, '# 修改说明：' + note + '\n');
      } else {
        content = '# 修改说明：' + note + '\n' + content;
      }
    }
    // 已存在则覆盖（需要 sha）
    window.API.get('/repos/' + repo.full_name + '/contents/' + enc, { ref: ref }, { cache: 0 })
      .catch(function (e) {
        if (e && (e.status === 404 || e.status === 422)) return { data: null };
        throw e;
      })
      .then(function (r) {
        var sha = (r && r.data && !Array.isArray(r.data)) ? r.data.sha : null;
        var payload = {
          message: 'ci: ' + (edited ? 'update ' : 'add ') + path +
            (note ? ' — ' + note : ' (build APK)'),
          content: b64(content),
          branch: ref
        };
        if (sha) payload.sha = sha;
        return window.API.put('/repos/' + repo.full_name + '/contents/' + enc, payload);
      })
      .then(function () {
        // 新写入的工作流 GitHub 需要几秒才可见，用文件名触发，失败也只是晚几秒
        var i = info || {};
        return window.API.post('/repos/' + repo.full_name + '/actions/workflows/' +
          path.split('/').pop() + '/dispatches', {
            ref: ref,
            inputs: {
              app_name: i.appName || 'app',
              version_name: i.versionName || '1.0.0',
              version_code: String(i.versionCode || '1')
            }
          }).catch(function (e) { return { pending: true, err: e }; });
      })
      .then(function (r) {
        UI.loading(false);
        UI.closeSheet();
        if (r && r.pending) {
          UI.toast('配置已写入，GitHub 正在识别，请稍等几秒后点「手动触发构建」');
        } else {
          UI.toast('已开始构建，完成后点开运行记录即可下载 APK');
        }
        try { window.App.cacheDel('repo_' + repo.full_name); } catch (e) {}
        window.Router.go('/' + repo.full_name + '/actions');
        window.Router.reload();
      })
      .catch(function (e) {
        UI.loading(false);
        UI.toast('写入失败：' + (e.status === 404 ? '找不到该分支' : e.status === 422 ? '无写入权限' : e.message));
      });
  }

  /* ============ 发布 ============ */
  function tabReleases(repo, ctx, box) {
    var canRelease = canPush(repo);

    box.innerHTML =
      (canRelease ? '<div style="padding:10px 12px">' +
        '<button class="btn primary block" id="newrel">' + window.icon('tag', 15) + ' 创建新的发布版本</button></div>' : '') +
      '<div id="rlist">' + UI.skeleton(4) + '</div>';

    var nb = UI.$('#newrel', box);
    if (nb) nb.onclick = function () { newRelease(repo); };

    return window.API.get('/repos/' + repo.full_name + '/releases', { per_page: 50 }, { cache: 60000 }).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#rlist', box); if (!b) return;
      if (!list.length) {
        b.innerHTML = UI.empty('tag', '暂无发布版本',
          canRelease ? '点上方按钮发布第一个版本，可附加 APK 等文件' : '维护者发布版本后会显示在这里');
        return;
      }
      b.innerHTML = '<div class="list">' + list.map(function (rel) {
        var assets = rel.assets || [];
        return '<button class="list-row" data-go="/' + U.esc(repo.full_name) + '/releases/' + U.esc(rel.tag_name) + '">' +
          '<span style="color:' + (rel.prerelease ? 'var(--attention)' : 'var(--success)') + ';margin-top:3px">' + window.icon('tag', 16) + '</span>' +
          '<span class="row-main"><span class="row-title">' + U.esc(rel.name || rel.tag_name) +
          (rel.draft ? ' <span class="chip" style="padding:0 5px">草稿</span>' : '') + '</span>' +
          '<span class="row-desc mono">' + U.esc(rel.tag_name) + (rel.prerelease ? ' · 预览版' : '') + '</span>' +
          '<span class="row-meta"><span>' + U.esc((rel.author && rel.author.login) || '') + '</span><span>' + U.date(rel.published_at) + '</span>' +
          (assets.length ? '<span>' + window.icon('package', 12) + assets.length + ' 个附件 · ' +
            U.num(assets.reduce(function (s, a) { return s + (a.download_count || 0); }, 0)) + ' 次下载</span>' : '') +
          '</span></span></button>';
      }).join('') + '</div>';
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#rlist', box).innerHTML = UI.errorBox(e); });
  }

  /* ============ 创建 Release（可上传 APK 等附件） ============ */
  function newRelease(repo) {
    if (!window.Session.isLogin) return UI.toast('请先登录');

    var files = [];      // [{name,size,mime,uri}]
    var MAX_ASSET = 200 * 1024 * 1024;

    var body =
      '<div class="field"><label>标签版本 <span style="color:var(--danger)">*</span></label>' +
      '<input class="input mono" id="rl-tag" placeholder="v1.0.0" autocomplete="off">' +
      '<div class="hint">如果标签不存在，会自动基于目标分支创建。</div></div>' +
      '<div class="field"><label>目标分支</label>' +
      '<input class="input mono" id="rl-branch" value="' + U.esc(repo.default_branch || 'main') + '" autocomplete="off"></div>' +
      '<div class="field"><label>发布标题</label>' +
      '<input class="input" id="rl-name" placeholder="留空则使用标签名"></div>' +
      '<div class="field"><label>说明</label>' +
      '<textarea class="textarea" id="rl-body" rows="5" placeholder="本次更新内容…"></textarea></div>' +
      '<div class="field"><label>附加文件（APK 等）</label>' +
      '<div class="upload-box">' +
      '<button class="btn block" id="rl-pick">' + window.icon('upload', 15) + ' 选择文件</button>' +
      '<div id="rl-files" class="upload-list"></div>' +
      '<div class="hint">支持 APK / AAB / ZIP 等任意文件，单个不超过 200MB。</div>' +
      '</div></div>' +
      '<div class="field"><label>选项</label>' +
      '<label class="rowflex" style="gap:8px;padding:8px 0"><input type="checkbox" id="rl-pre" style="width:16px;height:16px">' +
      '<span>标记为预发布版本</span></label>' +
      '<label class="rowflex" style="gap:8px;padding:8px 0"><input type="checkbox" id="rl-draft" style="width:16px;height:16px">' +
      '<span>保存为草稿（暂不公开）</span></label>' +
      '</div>';

    var root = document.getElementById('sheet-root');
    UI.sheet({
      title: '创建发布版本', full: true, body: body,
      foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>发布</button>',
      onMount: function () {
        var pick = root.querySelector('#rl-pick');
        var listEl = root.querySelector('#rl-files');

        function paintFiles() {
          if (!files.length) { listEl.innerHTML = ''; return; }
          listEl.innerHTML = files.map(function (f, i) {
            return '<div class="upload-item">' +
              '<span class="fi">' + window.icon(isApk(f.name) ? 'package' : 'file', 18) + '</span>' +
              '<span class="grow"><span class="fn">' + U.esc(f.name) + '</span>' +
              '<span class="fs">' + U.bytes(f.size) + ' · ' + U.esc(f.mime) + '</span></span>' +
              '<button class="icon-btn" data-rm="' + i + '" aria-label="移除">' + window.icon('x', 16) + '</button></div>';
          }).join('');
          UI.$$('[data-rm]', listEl).forEach(function (b) {
            b.onclick = function () {
              files.splice(parseInt(b.getAttribute('data-rm'), 10), 1);
              paintFiles();
            };
          });
        }

        pick.onclick = function () {
          if (!window.Native.canPick()) {
            return UI.confirm('需要应用内支持',
              '当前环境无法选择本地文件。请安装最新版应用后重试。', '知道了').then(function () {});
          }
          window.Native.pickFile('*/*').then(function (meta) {
            if (!meta) return;
            if (meta.size > MAX_ASSET) return UI.toast('文件过大，单个附件不超过 200MB');
            files.push(meta);
            paintFiles();
          }).catch(function (e) {
            if (e.message !== '选择文件超时') UI.toast('选择失败：' + e.message);
          });
        };

        root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
        root.querySelector('[data-yes]').onclick = function () {
          var tag = root.querySelector('#rl-tag').value.trim();
          if (!tag) return UI.toast('请填写标签版本');
          var isPre = root.querySelector('#rl-pre').checked;
          var isDraft = root.querySelector('#rl-draft').checked;

          UI.loading(true);
          window.API.post('/repos/' + repo.full_name + '/releases', {
            tag_name: tag,
            target_commitish: root.querySelector('#rl-branch').value.trim() || repo.default_branch,
            name: root.querySelector('#rl-name').value.trim() || tag,
            body: root.querySelector('#rl-body').value || '',
            draft: isDraft,
            prerelease: isPre
          }).then(function (r) {
            var rel = r.data;
            if (!files.length) { UI.loading(false); return rel; }
            // 逐个上传附件（二进制走原生）
            var chain = Promise.resolve();
            files.forEach(function (f) {
              chain = chain.then(function () {
                var url = 'https://uploads.github.com/repos/' + repo.full_name +
                  '/releases/' + rel.id + '/assets?name=' + encodeURIComponent(f.name) +
                  '&label=' + encodeURIComponent(f.name);
                return window.Native.uploadBinary(url, f.uri, {
                  'Authorization': 'Bearer ' + window.Session.token,
                  'Accept': 'application/vnd.github+json',
                  'X-GitHub-Api-Version': '2022-11-28',
                  'Content-Type': f.mime || 'application/octet-stream'
                });
              });
            });
            return chain.then(function () { return rel; });
          }).then(function (rel) {
            UI.loading(false);
            UI.closeSheet();
            UI.toast(files.length ? '已发布，附件上传完成' : '发布成功');
            try { window.App.invalidate('/repos/' + repo.full_name + '/releases'); } catch (e) {}
            window.Router.go('/' + repo.full_name + '/releases/' + encodeURIComponent(rel.tag_name));
          }).catch(function (e) {
            UI.loading(false);
            UI.toast('发布失败：' + (e.status === 422 ? '标签已存在或无权限' : e.message));
          });
        };
      }
    });
  }

  function isApk(name) { return /\.apk$/i.test(name || ''); }
  window.newRelease = newRelease;

  /* ============ 上传文件到仓库（对标官网 Add file → Upload files） ============ */
  function uploadFile(repo, ref, dirpath) {
    if (!window.Session.isLogin) return UI.toast('请先登录');
    if (!window.Native.canPick()) {
      return UI.confirm('需要应用内支持',
        '当前环境无法选择本地文件。请安装最新版应用后重试。', '知道了').then(function () {});
    }
    var MAX = 25 * 1024 * 1024;   // Base64 后经 WebView 传递，保守限制
    var file = null;

    var body =
      '<div class="field"><label>选择文件 <span style="color:var(--danger)">*</span></label>' +
      '<div class="upload-box">' +
      '<button class="btn block" id="uf-pick">' + window.icon('upload', 15) + ' 选择文件</button>' +
      '<div id="uf-file" class="upload-list"></div>' +
      '<div class="hint">单个文件建议不超过 25MB；更大的文件建议在网页端上传。</div>' +
      '</div></div>' +
      '<div class="field"><label>上传到目录</label>' +
      '<input class="input mono" id="uf-dir" value="' + U.esc(dirpath || '') + '" placeholder="留空则上传到仓库根目录"></div>' +
      '<div class="field"><label>提交信息 <span style="color:var(--danger)">*</span></label>' +
      '<input class="input" id="uf-msg" placeholder="Add files via upload"></div>' +
      '<div class="field"><label>分支</label>' +
      '<input class="input mono" id="uf-branch" value="' + U.esc(ref || repo.default_branch) + '"></div>';

    var root = document.getElementById('sheet-root');
    UI.sheet({
      title: '上传文件', full: true, body: body,
      foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>提交</button>',
      onMount: function () {
        var fileEl = root.querySelector('#uf-file');
        var pick = root.querySelector('#uf-pick');

        pick.onclick = function () {
          window.Native.pickFile('*/*').then(function (meta) {
            if (!meta) return;
            if (meta.size > MAX) return UI.toast('文件过大（' + U.bytes(meta.size) + '），请控制在 25MB 内');
            file = meta;
            fileEl.innerHTML = '<div class="upload-item">' +
              '<span class="fi">' + window.icon('file', 18) + '</span>' +
              '<span class="grow"><span class="fn">' + U.esc(meta.name) + '</span>' +
              '<span class="fs">' + U.bytes(meta.size) + ' · ' + U.esc(meta.mime) + '</span></span></div>';
            pick.textContent = '重新选择';
          }).catch(function (e) {
            if (e.message !== '选择文件超时') UI.toast('选择失败：' + e.message);
          });
        };

        root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
        root.querySelector('[data-yes]').onclick = function () {
          if (!file) return UI.toast('请先选择文件');
          var msg = root.querySelector('#uf-msg').value.trim();
          if (!msg) msg = 'Add ' + file.name + ' via upload';
          var dir = root.querySelector('#uf-dir').value.trim().replace(/^\/+|\/+$/g, '');
          var path = (dir ? dir + '/' : '') + file.name;
          var branch = root.querySelector('#uf-branch').value.trim() || repo.default_branch;

          UI.loading(true);
          var existed = false;
          // 1) 若文件已存在，需要先取 sha（走更新而非新建）
          window.API.get('/repos/' + repo.full_name + '/contents/' + encodePath(path),
            { ref: branch }, { cache: 0 }).catch(function (e) {
              // 404 = 文件还不存在，属于正常的新建上传，不能当成失败
              if (e && (e.status === 404 || e.status === 422)) return { data: null };
              throw e;
            }).then(function (r) {
              var sha = (r && r.data && !Array.isArray(r.data)) ? r.data.sha : null;
              existed = !!sha;
              // 2) 读取 Base64 内容后提交
              return window.Native.readFileBase64(file.uri, MAX).then(function (b64) {
                var payload = { message: msg, content: b64, branch: branch };
                if (sha) payload.sha = sha;
                return window.API.put('/repos/' + repo.full_name + '/contents/' + encodePath(path), payload);
              });
            }).then(function () {
              UI.loading(false);
              UI.closeSheet();
              UI.toast(existed ? '文件已更新' : '文件已上传');
              try { window.App.cacheDel('repo_' + repo.full_name); } catch (e) {}
              window.Router.go('/' + repo.full_name + '/tree/' + encodeURIComponent(branch) +
                (dir ? '/' + encodePath(dir) : ''));
              window.Router.reload();
            }).catch(function (e) {
              UI.loading(false);
              UI.toast('上传失败：' + (e.status === 422 ? '无写入权限或内容不合法' : e.message));
            });
        };
      }
    });
  }
  window.uploadFile = uploadFile;

  /* ============ 提交 / 贡献者 / 分支 ============ */
  function tabCommits(repo, ctx, box) {
    var ref = ctx.query.ref || repo.default_branch;
    box.innerHTML = '<div id="clist">' + UI.skeleton(5) + '</div>';
    return window.API.get('/repos/' + repo.full_name + '/commits', { sha: ref, per_page: 40 }, { cache: 30000 }).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#clist', box); if (!b) return;
      if (!list.length) { b.innerHTML = UI.empty('git-commit', '暂无提交', ''); return; }
      b.innerHTML = '<div class="list">' + list.map(function (c) {
        var au = c.author && c.author.login;
        return '<button class="list-row" data-go="/' + U.esc(repo.full_name) + '/commit/' + c.sha + '">' +
          (c.author && c.author.avatar_url ? UI.avatar(au, c.author.avatar_url, 24) : '') +
          '<span class="row-main"><span class="row-title">' + U.esc((c.commit.message || '').split('\n')[0]) + '</span>' +
          '<span class="row-desc">' + U.esc(c.commit.author ? c.commit.author.name : '') + ' · ' + U.timeAgo(c.commit.author ? c.commit.author.date : '') + '</span></span>' +
          '<span class="row-side mono tiny">' + U.esc(c.sha.substring(0, 7)) + '</span></button>';
      }).join('') + '</div>';
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#clist', box).innerHTML = UI.errorBox(e); });
  }

  function tabContributors(repo, ctx, box) {
    box.innerHTML = '<div id="colist">' + UI.skeleton(5) + '</div>';
    return window.API.get('/repos/' + repo.full_name + '/contributors', { per_page: 100 }, { cache: 300000 }).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#colist', box); if (!b) return;
      if (!list.length) { b.innerHTML = UI.empty('people', '暂无贡献者数据', ''); return; }
      var max = list[0].contributions || 1;
      b.innerHTML = '<div class="list">' + list.map(function (c) {
        return '<button class="list-row" data-go="/' + U.esc(c.login) + '">' +
          UI.avatar(c.login, c.avatar_url, 32) +
          '<span class="row-main"><span class="row-title">' + U.esc(c.login) + '</span>' +
          '<span class="skel" style="height:5px;border-radius:3px;margin-top:6px;width:' + Math.max(6, (c.contributions / max * 100)) + '%"></span></span>' +
          '<span class="row-side tiny">' + U.num(c.contributions) + ' 次提交</span></button>';
      }).join('') + '</div>';
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#colist', box).innerHTML = UI.errorBox(e); });
  }

  function tabRefs(repo, ctx, box, kind) {
    box.innerHTML = '<div id="blist">' + UI.skeleton(5) + '</div>';
    return window.API.get('/repos/' + repo.full_name + '/' + kind, { per_page: 100 }, { cache: 60000 }).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#blist', box); if (!b) return;
      if (!list.length) { b.innerHTML = UI.empty(kind === 'tags' ? 'tag' : 'git-branch', '暂无' + (kind === 'tags' ? '标签' : '分支'), ''); return; }
      b.innerHTML = '<div class="list">' + list.map(function (x) {
        var name = x.name;
        var isDefault = name === repo.default_branch;
        return '<button class="list-row" data-go="/' + U.esc(repo.full_name) + '/tree/' + encodeURIComponent(name) + '">' +
          '<span style="color:var(--fg-muted)">' + window.icon(kind === 'tags' ? 'tag' : 'git-branch', 16) + '</span>' +
          '<span class="row-main"><span class="row-title mono">' + U.esc(name) + '</span>' +
          (x.commit ? '<span class="row-desc mono">' + U.esc((x.commit.sha || '').substring(0, 7)) + '</span>' : '') + '</span>' +
          (isDefault ? '<span class="chip">默认</span>' : '') +
          (x.protected ? '<span class="chip">' + window.icon('shield', 12) + '受保护</span>' : '') + '</button>';
      }).join('') + '</div>';
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#blist', box).innerHTML = UI.errorBox(e); });
  }

  function tabPeople(repo, ctx, box, ep, title) {
    box.innerHTML = '<div id="pelist">' + UI.skeleton(5) + '</div>';
    return window.API.get('/repos/' + repo.full_name + '/' + ep, { per_page: 100 }, { cache: 60000 }).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#pelist', box); if (!b) return;
      if (!list.length) { b.innerHTML = UI.empty('people', '暂无' + title, ''); return; }
      b.innerHTML = '<div class="list">' + list.map(function (u) {
        return '<button class="list-row" data-go="/' + U.esc(u.login) + '">' + UI.avatar(u.login, u.avatar_url, 32) +
          '<span class="row-main"><span class="row-title">' + U.esc(u.login) + '</span>' +
          (u.name ? '<span class="row-desc">' + U.esc(u.name) + '</span>' : '') + '</span></button>';
      }).join('') + '</div>';
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#pelist', box).innerHTML = UI.errorBox(e); });
  }

  function tabForks(repo, ctx, box) {
    box.innerHTML = '<div id="flist">' + UI.skeleton(4) + '</div>';
    return window.API.get('/repos/' + repo.full_name + '/forks', { per_page: 50, sort: 'stargazers' }, { cache: 60000 }).then(function (r) {
      var list = r.data || [];
      var b = UI.$('#flist', box); if (!b) return;
      if (!list.length) { b.innerHTML = UI.empty('repo-forked', '暂无 fork', ''); return; }
      b.innerHTML = '<div class="list">' + list.map(function (f) {
        return '<button class="list-row" data-go="/' + U.esc(f.full_name) + '">' + UI.avatar(f.owner.login, f.owner.avatar_url, 32) +
          '<span class="row-main"><span class="row-title">' + U.esc(f.full_name) + '</span>' +
          (f.description ? '<span class="row-desc">' + U.esc(f.description) + '</span>' : '') +
          '<span class="row-meta"><span>' + window.icon('star', 12) + U.num(f.stargazers_count) + '</span></span></span></button>';
      }).join('') + '</div>';
      window.bindRepoCards(b);
    }).catch(function (e) { UI.$('#flist', box).innerHTML = UI.errorBox(e); });
  }

  /* ============ 仓库设置（可编辑，对标官网 Settings） ============ */
  function tabSettings(repo, ctx, box) {
    var canEdit = !!(window.Session.user && repo.owner &&
      window.Session.user.login === repo.owner.login);

    box.innerHTML =
      (canEdit ? '' :
        '<div class="card" style="padding:12px 14px"><div class="muted tiny">' +
        '你不是该仓库的所有者，只能查看设置信息。</div></div>') +

      /* ---- 基础信息（可编辑） ---- */
      '<div class="set-group-title">基础信息</div>' +
      '<div class="card" style="padding:14px">' +
      '<div class="field"><label>仓库名称</label>' +
      '<input class="input" id="s-name" value="' + U.esc(repo.name) + '"' + (canEdit ? '' : ' disabled') + '>' +
      '<div class="hint">重命名后旧地址会自动跳转到新地址。</div></div>' +
      '<div class="field"><label>简介</label>' +
      '<input class="input" id="s-desc" value="' + U.esc(repo.description || '') + '" placeholder="一句话描述这个仓库"' + (canEdit ? '' : ' disabled') + '></div>' +
      '<div class="field"><label>网站</label>' +
      '<input class="input" id="s-home" value="' + U.esc(repo.homepage || '') + '" placeholder="https://example.com"' + (canEdit ? '' : ' disabled') + '></div>' +
      '<div class="field"><label>Topics</label>' +
      '<input class="input" id="s-topics" value="' + U.esc((repo.topics || []).join(', ')) + '" placeholder="用英文逗号分隔，如 android, root, kernel"' + (canEdit ? '' : ' disabled') + '>' +
      '<div class="hint">最多 20 个，只能包含小写字母、数字和连字符。</div></div>' +
      (canEdit ? '<button class="btn primary block" id="s-save">' + window.icon('check', 15) + ' 保存修改</button>' : '') +
      '</div>' +

      '<div class="section"></div>' +

      /* ---- 危险区域 ---- */
      '<div class="set-group-title">可见性</div>' +
      '<div class="card" style="padding:14px">' +
      '<div class="rowflex" style="gap:10px;align-items:flex-start">' +
      '<span style="flex:none;margin-top:2px">' + window.icon(repo.private ? 'lock' : 'globe', 16) + '</span>' +
      '<span class="grow"><b>' + (repo.private ? '私有仓库' : '公开仓库') + '</b>' +
      '<div class="tiny muted" style="margin-top:4px">' +
      (repo.private ? '只有你和你选择的人可以查看。' : '任何人都可以查看这个仓库。') + '</div></span></div>' +
      (canEdit ? '<button class="btn block mt12" id="s-vis">' + window.icon('sync', 14) +
        ' 改为' + (repo.private ? '公开' : '私有') + '</button>' : '') +
      '</div>' +

      (canEdit ? '<div class="section"></div>' +
        '<div class="set-group-title" style="color:var(--danger)">危险区域</div>' +
        '<div class="card" style="padding:14px">' +
        '<div class="rowflex" style="gap:10px;align-items:flex-start;padding-bottom:12px;border-bottom:1px solid var(--border-muted)">' +
        '<span class="grow"><b>' + (repo.archived ? '取消归档' : '归档仓库') + '</b>' +
        '<div class="tiny muted" style="margin-top:4px">' +
        (repo.archived ? '归档后仓库为只读状态，取消后可恢复写入。' : '归档后仓库变为只读，任何人都无法推送。') + '</div></span>' +
        '<button class="btn sm" style="flex:none" id="s-arch">' + (repo.archived ? '取消归档' : '归档') + '</button></div>' +
        '<div class="rowflex" style="gap:10px;align-items:flex-start;padding-top:12px">' +
        '<span class="grow"><b style="color:var(--danger)">删除仓库</b>' +
        '<div class="tiny muted" style="margin-top:4px">此操作不可撤销，所有代码、议题、PR 都会被永久删除。</div></span>' +
        '<button class="btn sm danger" style="flex:none;border-color:var(--danger)" id="s-del">删除</button></div>' +
        '</div>' : '') +

      '<div class="section"></div>' +

      /* ---- 只读信息 ---- */
      '<div class="set-group-title">仓库信息（只读）</div>' +
      '<div class="set-group">' +
      setInfoRow('默认分支', repo.default_branch || '—') +
      setInfoRow('议题', repo.has_issues ? '已启用' : '已关闭') +
      setInfoRow('Projects', repo.has_projects ? '已启用' : '已关闭') +
      setInfoRow('Wiki', repo.has_wiki ? '已启用' : '已关闭') +
      setInfoRow('Discussions', repo.has_discussions ? '已启用' : '已关闭') +
      setInfoRow('Fork 数量', String(repo.forks_count)) +
      setInfoRow('Star 数量', String(repo.stargazers_count)) +
      setInfoRow('创建时间', U.date(repo.created_at)) +
      setInfoRow('最近推送', U.timeAgo(repo.pushed_at)) +
      '</div>' +
      '<div class="card" style="padding:12px 14px;margin-top:12px"><div class="tiny muted">' +
      '议题 / Projects / Wiki / Discussions 的开关，GitHub 未开放 REST API，' +
      '需要前往网页端设置。</div></div>' +
      '<div class="card" style="margin-top:12px"><button class="btn block" id="s-web">' +
      window.icon('link-external', 14) + ' 在浏览器打开仓库设置</button></div>';

    // ---- 绑定 ----
    UI.$('#s-web', box).onclick = function () {
      openWeb(repo.html_url + '/settings');
    };

    var saveBtn = UI.$('#s-save', box);
    if (saveBtn) {
      saveBtn.onclick = function () {
        var name = UI.$('#s-name', box).value.trim();
        if (!name) return UI.toast('仓库名称不能为空');
        if (!/^[A-Za-z0-9._-]+$/.test(name)) return UI.toast('仓库名只能包含字母、数字、- _ .');
        var topics = UI.$('#s-topics', box).value.split(',')
          .map(function (t) { return t.trim().toLowerCase(); })
          .filter(function (t) { return t; });
        if (topics.some(function (t) { return !/^[a-z0-9][a-z0-9-]*$/.test(t); })) {
          return UI.toast('Topics 只能包含小写字母、数字和连字符');
        }
        var payload = {
          name: name,
          description: UI.$('#s-desc', box).value.trim() || null,
          homepage: UI.$('#s-home', box).value.trim() || null,
          topics: topics
        };
        UI.loading(true);
        window.API.patch('/repos/' + repo.full_name, payload).then(function (r) {
          UI.loading(false);
          var full = (r.data && r.data.full_name) || repo.full_name;
          try { window.App.invalidate('/user/repos'); } catch (e) {}
          try { window.App.cacheDel('repo_' + repo.full_name); } catch (e) {}
          UI.toast(full === repo.full_name ? '已保存' : '已重命名');
          if (full !== repo.full_name) window.Router.go('/' + full + '/settings');
          else window.Router.reload();
        }).catch(function (e) {
          UI.loading(false);
          UI.toast('保存失败：' + (e.status === 422 ? '仓库名已存在或 Topics 不合法' : e.message));
        });
      };

      // 切换可见性 / 归档：先取服务端最新状态，再决定方向。
      // 直接用传入的 repo.private 判断是不行的——这个对象可能来自页面缓存，
      // 上一次切换过的结果没同步进来，就会出现「点了改为私有、弹窗却说改为公开」。
      function withFreshRepo(run) {
        // cache:0 跳过 TTL 缓存；dedupe:false 还要绕开「同一请求正在飞行中」的复用——
        // 否则刚渲染过的 GET /repos/xxx 会被原样复用，拿回来的还是旧状态。
        return window.API.get('/repos/' + repo.full_name, null, { cache: 0, dedupe: false })
          .then(function (r) {
            var fresh = r.data || repo;
            // 顺手把本地这份也同步了，页面上的按钮文案立刻跟着变
            Object.keys(fresh).forEach(function (k) { repo[k] = fresh[k]; });
            return run(fresh);
          })
          .catch(function () { return run(repo); });
      }

      UI.$('#s-vis', box).onclick = function () {
        withFreshRepo(function (cur) {
          // next 是「切换后的 private 值」：true = 要变成私有
          // 所以文案要在 next 为 true 时说「改为私有」，别再写反
          var next = !cur.private;
          UI.confirm(next ? '改为私有仓库' : '改为公开仓库',
            next ? '改为私有后，只有你和你选择的人可以查看。' : '公开后任何人都能查看这个仓库的代码。',
            '确认修改', next).then(function (ok) {
              if (!ok) return;
              UI.loading(true);
              window.API.patch('/repos/' + repo.full_name, { private: next }).then(function (r) {
                UI.loading(false);
                repo.private = next;                 // 本地状态立刻翻转
                if (r && r.data) Object.keys(r.data).forEach(function (k) { repo[k] = r.data[k]; });
                // 仓库对象和列表都缓存在内存里，不清掉的话刷新还会看到旧值
                try { window.API.clearCache(); } catch (e) {}
                try { window.App.clearPageCache(); } catch (e) {}
                try { window.App.invalidate('/user/repos'); } catch (e) {}
                try { window.App.invalidate('/search/repositories'); } catch (e) {}
                UI.toast(next ? '已改为私有' : '已改为公开');
                window.Router.reload();
              }).catch(function (e) { UI.loading(false); UI.toast('修改失败：' + e.message); });
            });
        });
      };

      UI.$('#s-arch', box).onclick = function () {
        withFreshRepo(function (cur) {
          var next = !cur.archived;
          UI.confirm(next ? '归档仓库' : '取消归档',
            next ? '归档后仓库变为只读，所有人都无法推送。' : '取消归档后恢复可写入状态。',
            '确认', false).then(function (ok) {
              if (!ok) return;
              UI.loading(true);
              window.API.patch('/repos/' + repo.full_name, { archived: next }).then(function (r) {
                UI.loading(false);
                repo.archived = next;
                if (r && r.data) Object.keys(r.data).forEach(function (k) { repo[k] = r.data[k]; });
                try { window.API.clearCache(); } catch (e) {}
                try { window.App.clearPageCache(); } catch (e) {}
                UI.toast(next ? '已归档' : '已取消归档');
                window.Router.reload();
              }).catch(function (e) { UI.loading(false); UI.toast('操作失败：' + e.message); });
            });
        });
      };

      UI.$('#s-del', box).onclick = function () {
        // 二次确认：要求用户手动输入仓库名，避免误删（对齐官网）
        // 注意：提示里必须明确写出「要输入的是哪个名字」，不能只放 placeholder，
        // 否则用户不知道输入什么；输入框也用明文（仓库名不是敏感信息）。
        var body =
          '<div class="field">' +
          '<label>要删除的仓库</label>' +
          '<div class="del-target">' + window.icon('repo', 15) +
          '<span class="mono">' + U.esc(repo.full_name) + '</span></div></div>' +
          '<div class="field">' +
          '<label>请输入仓库名称 <b class="mono">' + U.esc(repo.name) + '</b> 以确认删除</label>' +
          '<input class="input mono" id="del-cf" type="text" inputmode="text" ' +
          'autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" ' +
          'enterkeyhint="done" placeholder="在此输入 ' + U.esc(repo.name) + '">' +
          '<div class="hint">必须与仓库名称完全一致，包括大小写。删除后此仓库及其全部代码、议题、发布版本都会被永久移除，此操作不可撤销。</div>' +
          '<div class="hint" id="del-tip"></div>' +
          '</div>';
        var root = document.getElementById('sheet-root');
        UI.sheet({
          title: '删除仓库',
          body: body,
          foot: '<button class="btn" data-no>取消</button><button class="btn danger" data-yes disabled>确认删除</button>',
          onMount: function () {
            var inp = root.querySelector('#del-cf');
            var yes = root.querySelector('[data-yes]');
            setTimeout(function () { inp.focus(); }, 260);
            inp.addEventListener('input', function () {
              var match = inp.value.trim() === repo.name;
              yes.disabled = !match;
              // 实时反馈：让用户明确看到「还差什么 / 已经对了」
              inp.classList.toggle('ok', match);
              var tip = root.querySelector('#del-tip');
              if (tip) {
                tip.textContent = match ? '名称一致，可以确认删除' : '';
                tip.className = match ? 'hint ok-hint' : 'hint';
              }
            });
            // 回车直接提交（名称一致时）
            inp.addEventListener('keydown', function (e) {
              if (e.key === 'Enter') { e.preventDefault(); if (!yes.disabled) yes.click(); }
            });
            root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
            yes.onclick = function () {
              UI.closeSheet();
              UI.loading(true);
              window.API.del('/repos/' + repo.full_name).then(function () {
                UI.loading(false);
                try { window.App.invalidate('/user/repos'); } catch (e) {}
                try { window.App.cacheDel('repo_' + repo.full_name); } catch (e) {}
                UI.toast('仓库已删除');
                window.Router.go('/' + (window.Session.user ? window.Session.user.login : ''));
              }).catch(function (e) { UI.loading(false); UI.toast('删除失败：' + e.message); });
            };
          }
        });
      };
    }
  }

  function setInfoRow(k, v) {
    return '<div class="set-row static"><span class="k">' + U.esc(k) + '</span>' +
      '<span class="v">' + U.esc(v) + '</span></div>';
  }

  /** 统一的「在浏览器打开」：优先内置浏览器 */
  function openWeb(url, title) {
    if (window.Native && window.Native.openInApp) return window.Native.openInApp(url, title || 'GitHub');
    if (window.NativeBridge && NativeBridge.openInApp) return NativeBridge.openInApp(url, title || 'GitHub');
    if (window.NativeBridge && NativeBridge.openExternal) return NativeBridge.openExternal(url);
    window.open(url, '_blank');
  }
  window.openWeb = openWeb;

  /* ============ 新建议题 ============ */
  function newIssue(repo) {
    if (!window.Session.isLogin) return UI.toast('请先登录');
    var labels = [];
    var body =
      '<div class="field"><label>标题</label><input class="input" id="it" placeholder="简洁描述问题"></div>' +
      '<div class="field"><label>内容（支持 Markdown）</label>' +
      '<div class="rowflex" style="gap:4px;margin-bottom:6px">' + ['bold', 'italic', 'quote', 'code', 'link', 'list-unordered', 'tasklist'].map(function (i) {
        return '<button class="btn sm" data-md="' + i + '">' + window.icon(i, 14) + '</button>';
      }).join('') +
      '<button class="btn sm" data-md="attach" title="插入图片或视频">' + window.icon('image', 14) + '</button>' +
      '<button class="btn sm" data-md="preview" style="margin-left:auto">预览</button></div>' +
      '<textarea class="textarea" id="ib" placeholder="详细描述、复现步骤、环境信息…"></textarea></div>' +
      '<div class="field"><label>标签</label><div id="lbwrap" class="rowflex wrap"><span class="muted tiny">加载中…</span></div></div>' +
      '<div id="prev" class="card" hidden style="padding:12px"></div>';
    var root = document.getElementById('sheet-root');
    UI.sheet({
      title: '新建议题', full: true, body: body,
      foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>提交</button>',
      onMount: function () {
        var titleEl = root.querySelector('#it'), bodyEl = root.querySelector('#ib');
        window.API.get('/repos/' + repo.full_name + '/labels', { per_page: 100 }, { cache: 60000 }).then(function (r) {
          var ls = r.data || [];
          root.querySelector('#lbwrap').innerHTML = ls.length ? ls.map(function (l) {
            return '<span class="chip" data-l="' + U.esc(l.name) + '" style="' + U.labelStyle(l.color) + '">' + U.esc(l.name) + '</span>';
          }).join('') : '<span class="muted tiny">无可用标签</span>';
          UI.$$('#lbwrap .chip[data-l]', root).forEach(function (c) {
            c.onclick = function () {
              var n = c.getAttribute('data-l');
              var i = labels.indexOf(n);
              if (i >= 0) { labels.splice(i, 1); c.style.opacity = '.5'; }
              else { labels.push(n); c.style.opacity = '1'; c.style.outline = '2px solid var(--accent)'; }
            };
          });
        });
        UI.$$('[data-md]', root).forEach(function (b) {
          b.onclick = function () {
            var k = b.getAttribute('data-md');
            if (k === 'attach') {
              if (!window.Attach || !window.Attach.canUpload()) {
                return UI.confirm('需要应用内支持',
                  '当前环境无法选择本地文件，请安装最新版应用后重试。', '知道了')
                  .then(function () {});
              }
              b.disabled = true;
              UI.toast('请选择图片或视频');
              window.Attach.pickAndUpload({ repoFull: repo.full_name, multiple: true }).then(function (arr) {
                b.disabled = false;
                if (!arr || !arr.length) return;
                var md = arr.map(function (r) { return r.markdown; }).join('\n\n');
                insertAtCursor(bodyEl, '\n' + md + '\n');
                var nImg = arr.filter(function (r) { return r.kind === 'image'; }).length;
                var nVid = arr.filter(function (r) { return r.kind === 'video'; }).length;
                var parts = [];
                if (nImg) parts.push(nImg + ' 张图片');
                if (nVid) parts.push(nVid + ' 个视频');
                UI.toast((parts.join('、') || '附件') + '已插入');
                if (arr.failed && arr.failed.length) {
                  UI.toast(arr.failed.length + ' 个文件上传失败：' + arr.failed[0].message);
                }
              }).catch(function (e) { b.disabled = false; UI.toast('上传失败：' + e.message); });
              return;
            }
            if (k === 'preview') {
              var pv = root.querySelector('#prev');
              pv.hidden = !pv.hidden;
              if (!pv.hidden) window.MD.mount(pv, bodyEl.value || '（无内容）', { repo: repo.full_name });
              return;
            }
            wrapSelection(bodyEl, k);
          };
        });
        root.querySelector('[data-yes]').onclick = function () {
          var t = titleEl.value.trim();
          if (!t) return UI.toast('请填写标题');
          UI.loading(true);
          window.API.post('/repos/' + repo.full_name + '/issues', { title: t, body: bodyEl.value, labels: labels }).then(function (r) {
            UI.loading(false);
            UI.closeSheet(); UI.toast('议题已创建');
            window.App.invalidate('/repos/' + repo.full_name + '/issues');
            /* 创建成功但响应体没带上 number 时不要崩 —— 回列表页就行，
             * 议题其实已经建好了。 */
            var num = r.data && r.data.number;
            window.Router.go(num
              ? '/' + repo.full_name + '/issues/' + num
              : '/' + repo.full_name + '/issues');
          }).catch(function (e) { UI.loading(false); UI.toast('创建失败：' + e.message); });
        };
        root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
      }
    });
  }
  window.newIssue = newIssue;

  /* ============ 新建 / 导入仓库 ============ */

  /**
   * 生成一个可复用的「克隆地址」控件（表单预览与创建成功弹窗共用）。
   * @param {string} id      控件唯一后缀
   * @param {object} opts    {https, ssh, zip, hint}
   */
  function cloneWidget(id, opts) {
    opts = opts || {};
    return '<div class="clone-box" style="border-top:0;padding-top:0;margin-top:10px" data-clone="' + id + '">' +
      '<div class="clone-seg" id="seg-' + id + '">' +
      '<button data-k="https" class="active">HTTPS</button>' +
      '<button data-k="ssh">SSH</button>' +
      (opts.zip ? '<button data-k="zip">ZIP</button>' : '') +
      '</div>' +
      '<div class="clone-row">' +
      '<input class="input" id="in-' + id + '" type="text" readonly spellcheck="false" autocomplete="off" placeholder="输入仓库名称后自动生成">' +
      '<button class="btn" id="cp-' + id + '" title="复制">' + window.icon('copy', 14) + '</button>' +
      '</div>' +
      '<div class="clone-tip" id="tip-' + id + '">' + (opts.hint || '点击输入框可全选地址，长按可复制。') + '</div>' +
      '</div>';
  }

  /** 绑定克隆控件行为；返回 {set: fn({https,ssh,zip})} 供外部更新地址 */
  function bindClone(root, id) {
    var seg = root.querySelector('#seg-' + id);
    var input = root.querySelector('#in-' + id);
    var copyBtn = root.querySelector('#cp-' + id);
    var urls = {};
    var kind = 'https';

    var paint = function () {
      if (!input) return;
      input.value = urls[kind] || (urls.https || '');
      input.title = urls[kind] || '';
    };
    if (seg) {
      UI.$$('button', seg).forEach(function (b) {
        b.onclick = function () {
          kind = b.getAttribute('data-k');
          UI.$$('button', seg).forEach(function (x) { x.classList.toggle('active', x === b); });
          paint();
        };
      });
    }
    if (input) {
      input.onclick = function () { input.focus(); input.select(); };
      input.onfocus = function () {
        if (input.selectionStart === input.selectionEnd) input.setSelectionRange(0, input.value.length);
      };
    }
    if (copyBtn) {
      copyBtn.onclick = function () {
        var v = urls[kind] || urls.https || '';
        if (!v) return UI.toast('请先填写仓库名称');
        UI.copy(v, kind === 'zip' ? '下载链接已复制' : '克隆地址已复制');
        var old = copyBtn.innerHTML;
        copyBtn.innerHTML = window.icon('check', 14);
        setTimeout(function () { copyBtn.innerHTML = old; }, 1400);
      };
    }
    return {
      set: function (u) { urls = u || {}; paint(); }
    };
  }

  /** 根据账号与仓库名生成三种克隆地址 */
  function cloneUrls(owner, name) {
    if (!owner || !name) return {};
    return {
      https: 'https://github.com/' + owner + '/' + name + '.git',
      ssh: 'git@github.com:' + owner + '/' + name + '.git',
      zip: 'https://github.com/' + owner + '/' + name + '/archive/refs/heads/main.zip'
    };
  }

  function newRepo(opts) {
    opts = opts || {};
    if (!window.Session.isLogin) {
      return UI.confirm('需要登录', '创建仓库需要先登录 GitHub 账号。', '去登录').then(function (ok) {
        if (ok) window.Router.go('/login');
      });
    }
    var owner = (window.Session.user && window.Session.user.login) || '';
    var gitignore = [
      { v: '', t: '无' }, { v: 'Node', t: 'Node' }, { v: 'Python', t: 'Python' },
      { v: 'Java', t: 'Java' }, { v: 'Android', t: 'Android' }, { v: 'Gradle', t: 'Gradle' },
      { v: 'C++', t: 'C++' }, { v: 'Go', t: 'Go' }, { v: 'Rust', t: 'Rust' },
      { v: 'Swift', t: 'Swift' }, { v: 'Xcode', t: 'Xcode' }, { v: 'VisualStudio', t: 'Visual Studio' }
    ];
    var licenses = [
      { v: '', t: '无' }, { v: 'mit', t: 'MIT License' }, { v: 'apache-2.0', t: 'Apache License 2.0' },
      { v: 'gpl-3.0', t: 'GNU GPLv3' }, { v: 'agpl-3.0', t: 'GNU AGPLv3' },
      { v: 'lgpl-3.0', t: 'GNU LGPLv3' }, { v: 'mpl-2.0', t: 'Mozilla Public License 2.0' },
      { v: 'bsd-3-clause', t: 'BSD 3-Clause' }, { v: 'unlicense', t: 'The Unlicense' }
    ];

    var body =
      '<div class="seg-wrap" style="margin-bottom:12px">' + UI.seg('nmode', [
        { key: 'create', label: '新建仓库' }, { key: 'import', label: '导入仓库' }], 'create') + '</div>' +
      '<div class="card" style="margin:0 0 12px;padding:12px">' +
      '<div class="muted tiny">仓库将创建在你的账号 <b>@' + U.esc(owner) + '</b> 下</div></div>' +

      /* ---------- 新建模式 ---------- */
      '<div id="m-create">' +
      '<div class="field"><label>仓库名称 <span style="color:var(--danger)">*</span></label>' +
      '<input class="input" id="rn" placeholder="my-awesome-project" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false">' +
      '<div class="hint">只能包含字母、数字、连字符（-）、下划线（_）和点（.）</div></div>' +
      '<div class="field"><label>简介</label>' +
      '<input class="input" id="rd" placeholder="一句话描述这个仓库（可选）"></div>' +
      '<div class="field"><label>可见性</label>' +
      '<div class="newrepo-radio on" data-vis="public"><span class="dot"></span><span class="grow">' +
      '<span class="rt">公开</span><span class="rd">任何人都可以看到这个仓库</span></span></div>' +
      '<div class="newrepo-radio" data-vis="private"><span class="dot"></span><span class="grow">' +
      '<span class="rt">私有</span><span class="rd">只有你和你选择的人可以看到</span></span></div>' +
      '</div>' +
      '<div class="field"><label>初始化选项</label>' +
      '<label class="rowflex" style="gap:8px;padding:8px 0"><input type="checkbox" id="rreadme" checked style="width:16px;height:16px">' +
      '<span>添加 README 文件</span></label>' +
      '<div class="subfield">' +
      '<label class="tiny muted">.gitignore 模板</label>' +
      '<select class="input" id="rgi">' + gitignore.map(function (g) {
        return '<option value="' + U.esc(g.v) + '">' + U.esc(g.t) + '</option>';
      }).join('') + '</select></div>' +
      '<div class="subfield">' +
      '<label class="tiny muted">开源许可证</label>' +
      '<select class="input" id="rlic">' + licenses.map(function (l) {
        return '<option value="' + U.esc(l.v) + '">' + U.esc(l.t) + '</option>';
      }).join('') + '</select></div>' +
      '</div>' +
      /* 克隆地址实时预览 */
      '<div class="field"><label>克隆地址（创建后即可使用）</label>' +
      cloneWidget('new', { hint: '地址会随仓库名称自动生成，可先复制备用。' }) +
      '</div>' +
      '</div>' +

      /* ---------- 导入模式 ---------- */
      '<div id="m-import" hidden>' +
      '<div class="field"><label>源仓库地址 <span style="color:var(--danger)">*</span></label>' +
      '<input class="input" id="iurl" type="text" placeholder="https://github.com/用户名/仓库.git" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false">' +
      '<div class="hint">填写要导入的 Git 仓库地址，支持 GitHub / GitLab / Bitbucket 等公开仓库。</div></div>' +
      '<div class="field"><label>新仓库名称 <span style="color:var(--danger)">*</span></label>' +
      '<input class="input" id="iname" placeholder="导入后在你账号下的仓库名" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false"></div>' +
      '<div class="field"><label>可见性</label>' +
      '<div class="newrepo-radio on" data-vis="public"><span class="dot"></span><span class="grow">' +
      '<span class="rt">公开</span><span class="rd">任何人都可以看到这个仓库</span></span></div>' +
      '<div class="newrepo-radio" data-vis="private"><span class="dot"></span><span class="grow">' +
      '<span class="rt">私有</span><span class="rd">只有你和你选择的人可以看到</span></span></div>' +
      '</div>' +
      '<div class="card" style="padding:12px;margin-bottom:12px">' +
      '<div class="muted tiny">导入说明</div>' +
      '<div class="tiny muted" style="margin-top:6px;line-height:1.6">' +
      'GitHub 会在服务端拉取源仓库的全部提交历史与分支，大型仓库可能需要较长时间。' +
      '导入开始后可在仓库页查看进度。</div></div>' +
      '<div class="field"><label>克隆地址（创建后即可使用）</label>' +
      cloneWidget('imp', { hint: '地址会随新仓库名称自动生成，可先复制备用。' }) +
      '</div>' +
      '</div>';

    var root = document.getElementById('sheet-root');
    var mode = 'create';
    UI.sheet({
      title: '新建仓库', full: true, body: body,
      foot: '<button class="btn" data-no>取消</button><button class="btn primary" data-yes>创建仓库</button>',
      onMount: function () {
        var vis = { create: 'public', import: 'public' };
        var cloneNew = bindClone(root, 'new');
        var cloneImp = bindClone(root, 'imp');
        var nameEl = root.querySelector('#rn');
        var impUrl = root.querySelector('#iurl');
        var impName = root.querySelector('#iname');

        // 可见性单选（两个模式各自独立）
        var bindRadios = function () {
          UI.$$('.newrepo-radio', root).forEach(function (r) {
            r.onclick = function () {
              var box = r.closest('#m-create') || r.closest('#m-import');
              if (!box) return;
              var m = box.id === 'm-import' ? 'import' : 'create';
              vis[m] = r.getAttribute('data-vis');
              UI.$$('.newrepo-radio', box).forEach(function (x) { x.classList.toggle('on', x === r); });
            };
          });
        };
        bindRadios();

        // ---- 模式切换 ----
        UI.$$('#nmode button', root).forEach(function (b) {
          b.onclick = function () {
            mode = b.getAttribute('data-v');
            UI.$$('#nmode button', root).forEach(function (x) { x.classList.toggle('active', x === b); });
            root.querySelector('#m-create').hidden = mode !== 'create';
            root.querySelector('#m-import').hidden = mode !== 'import';
            root.querySelector('[data-yes]').textContent = mode === 'import' ? '导入仓库' : '创建仓库';
            setTimeout(function () {
              if (mode === 'create') nameEl.focus(); else impUrl.focus();
            }, 120);
          };
        });

        // ---- 实时生成克隆地址 ----
        var syncClone = function () {
          var n = nameEl.value.trim();
          cloneNew.set(cloneUrls(owner, n));
          var in2 = impName.value.trim();
          cloneImp.set(cloneUrls(owner, in2));
        };
        // 仓库名：空格转连字符（对齐官网）
        nameEl.addEventListener('input', function () {
          var p = nameEl.selectionStart;
          var v = nameEl.value.replace(/\s+/g, '-');
          if (v !== nameEl.value) { nameEl.value = v; nameEl.setSelectionRange(p, p); }
          syncClone();
        });
        // 从源地址自动推断新仓库名
        impUrl.addEventListener('input', function () {
          if (!impName.dataset.touched) {
            var m = impUrl.value.trim().match(/\/([^\/]+?)(?:\.git)?$/);
            if (m) impName.value = m[1];
          }
          syncClone();
        });
        impName.addEventListener('input', function () {
          impName.dataset.touched = '1';
          impName.value = impName.value.replace(/\s+/g, '-');
          syncClone();
        });
        syncClone();
        setTimeout(function () { nameEl.focus(); }, 260);

        root.querySelector('[data-no]').onclick = function () { UI.closeSheet(); };
        root.querySelector('[data-yes]').onclick = function () {
          if (mode === 'import') return doImport();
          return doCreate();
        };

        // ---- 新建 ----
        function doCreate() {
          var name = nameEl.value.trim();
          if (!name) return UI.toast('请填写仓库名称');
          if (!/^[A-Za-z0-9._-]+$/.test(name)) return UI.toast('仓库名只能包含字母、数字、- _ .');
          var payload = {
            name: name,
            description: (root.querySelector('#rd').value || '').trim() || null,
            private: vis.create === 'private',
            has_issues: true, has_projects: true, has_wiki: true,
            auto_init: !!root.querySelector('#rreadme').checked
          };
          var gi = root.querySelector('#rgi').value;
          var lic = root.querySelector('#rlic').value;
          if (gi) payload.gitignore_template = gi;
          if (lic) payload.license_template = lic;

          UI.loading(true);
          window.API.post('/user/repos', payload).then(function (r) {
            UI.loading(false);
            afterCreate(r.data, name);
          }).catch(function (e) {
            UI.loading(false);
            UI.toast('创建失败：' + (e.status === 422 ? '仓库名已存在或不可用' : e.message));
          });
        }

        // ---- 导入 ----
        function doImport() {
          var src = impUrl.value.trim();
          var name = impName.value.trim();
          if (!src) return UI.toast('请填写源仓库地址');
          if (!/^(https?:\/\/|git@)/.test(src)) return UI.toast('源地址需以 https:// 或 git@ 开头');
          if (!name) return UI.toast('请填写新仓库名称');
          if (!/^[A-Za-z0-9._-]+$/.test(name)) return UI.toast('仓库名只能包含字母、数字、- _ .');

          UI.loading(true);
          // 1) 先建一个空仓库（不用 auto_init，导入需要空仓库）
          window.API.post('/user/repos', {
            name: name,
            private: vis.import === 'private',
            has_issues: true, has_projects: true, has_wiki: true,
            auto_init: false
          }).then(function (r) {
            // 2) 发起导入
            /* 建仓库这一步如果没返回 full_name，说明请求没真的成功，
             * 别拿 undefined 去拼下一请求的 URL —— 那样既看不出错在哪，
             * 还会在 )/import 这种畸形地址上再失败一次。 */
            var full = r.data && r.data.full_name;
            if (!full) throw new Error('仓库创建失败，请重试');
            return window.API.put('/repos/' + full + '/import',
              { vcs: 'git', vcs_url: src }).then(function () {
              return { data: { full_name: full } };
            });
          }).then(function (repo) {
            UI.loading(false);
            try { window.App.invalidate('/user/repos'); } catch (e) {}
            UI.closeSheet();
            UI.toast('导入已开始，可在仓库页查看进度');
            window.Router.go('/' + repo.full_name + '?importing=1');
          }).catch(function (e) {
            UI.loading(false);
            UI.toast('导入失败：' + (e.status === 422 ? '仓库名已存在，或源地址不可访问' : e.message));
          });
        }

        /** 创建/导入成功后：弹层展示仓库信息 + 克隆地址，再前往仓库 */
        function afterCreate(repo, name) {
          try { window.App.invalidate('/user/repos'); } catch (e) {}
          try { window.App.cacheDel('repo_' + repo.full_name); } catch (e) {}
          var full = repo.full_name || (owner + '/' + name);
          var url = repo.html_url || ('https://github.com/' + full);
          // sheet() 会整体替换 sheet-root，必须先收起当前弹层
          UI.closeSheet();
          setTimeout(function () {
            var r2 = document.getElementById('sheet-root');
            UI.sheet({
              title: '仓库已创建',
              body: '<div class="card" style="padding:14px;margin-bottom:12px">' +
                '<div class="rowflex" style="gap:10px;align-items:center">' +
                '<span class="ok-badge">' + window.icon('check', 15) + '</span>' +
                '<span class="grow"><b>' + U.esc(full) + '</b>' +
                '<div class="tiny muted" style="margin-top:2px">' + (repo.private ? '私有仓库' : '公开仓库') +
                ' · 可直接克隆到本地</div></span></div></div>' +
                '<div class="field"><label>克隆地址</label>' + cloneWidget('done', { zip: true }) + '</div>',
              foot: '<button class="btn" data-no>留在首页</button><button class="btn primary" data-yes>前往仓库</button>',
              onMount: function () {
                var u = cloneUrls(owner, name);
                u.zip = url + '/archive/refs/heads/main.zip';
                bindClone(r2, 'done').set(u);
                r2.querySelector('[data-no]').onclick = function () {
                  UI.closeSheet();
                  window.Router.reload();
                };
                r2.querySelector('[data-yes]').onclick = function () {
                  UI.closeSheet();
                  window.Router.go('/' + full);
                };
              }
            });
          }, 60);
        }
      }
    });
  }
  window.newRepo = newRepo;

  function wrapSelection(ta, kind) {
    var s = ta.selectionStart, e = ta.selectionEnd, v = ta.value, sel = v.substring(s, e);
    var map = { bold: ['**', '**'], italic: ['*', '*'], code: ['`', '`'], quote: ['\n> ', ''], link: ['[](', ')'], 'list-unordered': ['\n- ', ''], tasklist: ['\n- [ ] ', ''] };
    var p = map[kind] || ['', ''];
    ta.value = v.substring(0, s) + p[0] + sel + p[1] + v.substring(e);
    ta.focus();
    ta.selectionStart = s + p[0].length; ta.selectionEnd = s + p[0].length + sel.length;
  }
  window.wrapSelection = wrapSelection;

  /** 把文本插到光标处（插图 / 插视频用） */
  function insertAtCursor(ta, text) {
    var s = ta.selectionStart, e = ta.selectionEnd;
    if (s == null || s < 0) s = e = ta.value.length;
    ta.value = ta.value.substring(0, s) + text + ta.value.substring(e);
    var pos = s + text.length;
    ta.focus();
    try { ta.setSelectionRange(pos, pos); } catch (err) { }
  }
  window.insertAtCursor = insertAtCursor;
})();
