/* ============================================================
 * onboarding.js — 新手指导（边做边学的高亮引导）
 * ------------------------------------------------------------
 * 设计要点：
 *
 * 1. 「挖洞」遮罩
 *    不用 SVG mask、也不用 clip-path —— 一个透明的 div 配一圈
 *    `box-shadow: 0 0 0 9999px` 就是遮罩，洞里能看到真实界面。
 *    老 WebView 也认 box-shadow，clip-path 在部分 ROM 上会锯齿。
 *
 * 2. 每一步自己声明在哪一页、高亮谁
 *    跨页的步骤会先 Router.go(route)，再轮询等目标元素出现。
 *    页面是异步渲染的（骨架同步、内容异步），所以必须轮询，
 *    直接 querySelector 会拿到 null。
 *
 * 3. 拿不到目标就退化成居中弹窗
 *    任何一个环节出问题（接口挂了、页面结构变了、选择器写错），
 *    都不能把用户卡在遮罩里。超时 2.5 秒直接当「没有目标」继续走。
 *
 * 4. 随时可跳过
 *    「跳过」放在气泡按钮行的最右边（不另占屏幕一角），系统返回键也等同跳过
 *    （在 App.handleBack 里挂的）。
 *    跳过和看完都会记状态，下次不再自动弹；设置里可以手动重看。
 *
 * 5. 关键步骤要「真的动手」—— 这是这一版最大的改动
 *    光看一遍记不住，尤其是长按这类没有任何视觉提示的功能：
 *    用户压根不知道那个按钮能长按。所以带 act 的步骤会：
 *      · 遮罩自己不吃点击（pointer-events:none），能点的只有气泡本身；
 *      · 在 document 上用**委托**监听对应手势（元素被重渲染也不会丢）；
 *      · 判定命中后不打断 —— 真实功能照常发生（长按翻译按钮会真的弹出菜单）；
 *      · 命中后遮罩先退到弹层背后（.behind），让用户把弹出来的东西看清楚，
 *        1.3 秒后关掉弹层、自动进入下一步。
 *    用委托而不是直接给元素挂监听：下拉刷新、点设置项都会 Router.reload()
 *    重建整片 DOM，挂在元素上的监听会随旧节点一起被扔掉。
 *
 * 6. 只允许点「这一步的目标」，其余点击一律吞掉
 *    放开点击是为了让人真的操作，代价就是可能点错地方跳走。
 *    所以捕获阶段有 guard：不在当前目标内的 click 直接拦掉并轻提示。
 *    展示型步骤（没有 act）保持原来的全拦策略，跟手点的风险不值得冒。
 * ============================================================ */
(function () {
  'use strict';

  var KEY = 'onboarded';      // Store 里的键
  /* 引导升级成「边做边学」：步骤数、内容和玩法都变了，
     版本号 +1 让已经看过旧版引导的老用户也重新走一遍。 */
  var VERSION = 3;   // 步骤表与机制重构，老用户也要重新走一遍

  /* ---------------- 步骤表 ----------------
     route   这一步要先跳到哪个页面（不填 = 停在当前页）
     target  要高亮的选择器（不填 = 居中弹窗，没有洞）
     round   true 强制用圆形洞（图标按钮这类）
     act     这一步要用户真的做一下（不填 = 看一眼点「下一步」）
       kind  tap 点一下 / long 按住不放 / drag 横向拖 / pull 顶部下拉
       sel   要操作的元素（不填 = 用 target）
       ms    长按判定毫秒数（默认 500）
       hint  气泡里那句「怎么做」的提示
       win   做对之后给的那句确认（顺手把功能再讲一遍） */
  /* 分组只是气泡左上角那行小字，不改变线性流程；
     目的是让人知道「现在讲到哪一类」，不至于 27 步一路黑走到底。 */
  var STEPS = [
    /* ── A 认识界面 ─────────────────────────────── */
    {
      group: window.I18n.t('认识界面'),
      title: window.I18n.t('欢迎使用 githup'),
      body: window.I18n.t("看动态、收通知、搜仓库、逛趋势、管 Release，都在这一个 App 里。") +
            window.I18n.t("<br>这段引导分五组：认识界面 → 隐藏手势 → 找内容 → 进阶功能 → 设置与收尾，共二十几步，每一步都讲清「在哪、怎么触发、有什么用」。") +
            window.I18n.t("<br>带「动手试试」的步骤做完会自动往下走；做不出来或找不到目标，点<b>「跳过这步」</b>即可。") +
            window.I18n.t("<br>随时点卡片右下角的<b>「跳过」</b>退出，之后在设置里能重看。")
    },
    {
      group: window.I18n.t('认识界面'),
      title: window.I18n.t('顶栏：你在哪'),
      body: window.I18n.t("左边是当前页面标题，进二级页面时最左边多出返回箭头。") +
            window.I18n.t("<br>这一栏固定在顶部不随内容滚动；标题右侧偶尔会出现副标题（比如仓库名）。"),
      target: '#appbar'
    },
    {
      group: window.I18n.t('认识界面'),
      title: window.I18n.t('右上角：三枚图标各管一件事'),
      body: window.I18n.t("从左到右通常是：<b>脉冲</b>（下载通道，只在下载管理页出现）、<b>收纳箱</b>（下载管理）、<b>地球</b>（整页翻译）。") +
            window.I18n.t("<br>三枚都没有文字，是最容易一直没被发现的入口。先点一下<b>收纳箱</b>试试。"),
      target: '#appbar-actions',
      act: {
        kind: 'tap',
        sel: '.dl-action',
        hint: window.I18n.t('点一下右上角那枚收纳箱图标（地球图标左边那枚）'),
        win: window.I18n.t("这就是下载管理，以后下 Release 包都在这儿看进度")
      }
    },
    {
      group: window.I18n.t('认识界面'),
      title: window.I18n.t('下载管理：任务与去向'),
      body: window.I18n.t("这里列出正在下载和已下载的任务：能看进度、速度、暂停/取消，失败会写明原因。") +
            window.I18n.t("<br>文件按来源分目录：Release 附件、仓库文件、议题附件各自分开；记录右侧标了实际存在哪个目录。"),
      route: '/downloads',
      target: '#dl-page'
    },
    {
      group: window.I18n.t('认识界面'),
      title: window.I18n.t('下载慢：可以自己挑通道'),
      body: window.I18n.t("这一行是<b>下载通道</b>：点开能看到每条通道的实测速度，默认「自动选择」挑最快的，也可以手动指定。") +
            window.I18n.t("<br>下载卡住时软件会自动换通道重试，但你可以直接来这里挑一条；「不可用」只是这一轮没连上。"),
      route: '/downloads',
      target: '#ch-open'
    },
    {
      group: window.I18n.t('认识界面'),
      title: window.I18n.t('底栏：五个主入口'),
      body: window.I18n.t("<b>首页</b>看关注动态，<b>通知</b>收 GitHub 消息，<b>探索</b>逛趋势榜，<b>搜索</b>找仓库/用户/代码，<b>我的</b>是主页、收藏夹和设置。") +
            window.I18n.t("<br>这枚胶囊是悬浮的；后面有两步专门讲它的拖动和长按手势。"),
      route: '/',
      target: '#tabbar'
    },

    /* ── B 隐藏手势 ─────────────────────────────── */
    {
      group: window.I18n.t('隐藏手势'),
      title: window.I18n.t('底栏可以「拖」着切页'),
      body: window.I18n.t("在胶囊上<b>左右拖动可跟手切换</b>：手指往哪边划，下面的指示条跟到哪边，松手吸附到最近一格。") +
            window.I18n.t("<br>比一格一格点快；拖的同时页面不会真的翻过去，松手才定。"),
      route: '/',
      target: '#tabbar',
      act: {
        kind: 'drag',
        hint: window.I18n.t('按住底栏胶囊，往左或往右拖出一段距离'),
        win: window.I18n.t("就是这个手感 —— 拖过头会吸附到最近一格")
      }
    },
    {
      group: window.I18n.t('隐藏手势'),
      title: window.I18n.t('页面顶部下拉 = 刷新'),
      body: window.I18n.t("页面滚到顶之后，<b>按住屏幕往下拖一段再松手</b>就能刷新当前页。") +
            window.I18n.t("<br>没有刷新按钮、也没有转圈提示，这个手势只能自己发现。"),
      route: '/',
      target: '#view',
      act: {
        kind: 'pull',
        sel: '#view',
        hint: window.I18n.t('在页面上按住，往下拖出一大段再松手'),
        win: window.I18n.t('刷新就是这么触发的，随时能用')
      }
    },
    {
      group: window.I18n.t('隐藏手势'),
      title: window.I18n.t('地球按钮：点一下翻译，长按出设置'),
      body: window.I18n.t("右上角那枚<b>地球图标</b>：点一下把本页英文翻成中文，再点一下还原原文。") +
            window.I18n.t("<br><b>长按它（约 1 秒）会打开翻译设置</b> —— 界面上没有任何提示，这是最容易漏掉的一个入口。"),
      target: '#tr-btn',
      round: true,
      act: {
        kind: 'long',
        ms: 500,
        hint: window.I18n.t('按住右上角那枚地球图标不放，大约 1 秒'),
        win: window.I18n.t("菜单弹出来了：自动翻译、目标语言、引擎、清缓存都在里面")
      }
    },
    {
      group: window.I18n.t('隐藏手势'),
      title: window.I18n.t('翻译菜单里逐项是什么'),
      body: window.I18n.t("<b>打开页面时自动翻译</b>：进页面自己翻；<b>重新翻译本页</b>：重跑一次；<b>关闭翻译并还原</b>：恢复原文。") +
            window.I18n.t("<br><b>翻译成</b>选目标语言；<b>翻译引擎</b>可选「自动」或指定一家 —— 自动模式是<b>按顺序逐个回退</b>（一家失败换下一家），不是几家一起翻。") +
            window.I18n.t("<br>在引擎列表里<b>长按某一条</b>，能用浏览器打开该平台的官网控制台去申请密钥；密钥只存本机，不填也能用免费口子。") +
            window.I18n.t("<br><b>清空译文缓存</b>清掉已翻过的句子，<b>重置引擎状态</b>清掉失败标记让被暂时跳过的引擎重新参与。"),
      target: '#tr-btn',
      round: true
    },
    {
      group: window.I18n.t('隐藏手势'),
      title: window.I18n.t('通知长按 = 多选批量处理'),
      body: window.I18n.t("通知列表里<b>按住任意一条不放</b>进入多选：底部浮出操作栏，可勾选、全选、批量删除或标为已读。") +
            window.I18n.t("<br>点一下是打开这条通知，长按才是多选，界面上没有角标提示。没登录或暂时没有通知时看不到列表，跳过这步即可。"),
      route: '/notifications',
      target: '.notif',
      act: {
        kind: 'long',
        ms: 500,
        hint: window.I18n.t('按住列表里任意一条通知不放'),
        win: window.I18n.t('底部这条操作栏就是多选，勾完可以批量处理')
      }
    },
    {
      group: window.I18n.t('隐藏手势'),
      title: window.I18n.t('发布版本长按：转正 / 转预览'),
      body: window.I18n.t("在仓库 → <b>发布</b>列表里<b>长按某一条</b>，菜单里有「转为正式版」「标记为预览版」，还能上传/删除附件。") +
            window.I18n.t("<br>这一条很关键：本 App 的更新检测<b>跳过预览版</b>，预览版不转正，已装的用户永远收不到更新提示。"),
      note: window.I18n.t('这一步是说明：需要进一个有 Release 的仓库才能实际操作')
    },

    /* ── C 找内容 ───────────────────────────────── */
    {
      group: window.I18n.t('找内容'),
      title: window.I18n.t('搜索：关键词与一键清空'),
      body: window.I18n.t("输入关键词，点右边<b>搜索</b>或按回车；框里的 <b>×</b> 一键清空。") +
            window.I18n.t("<br>直接粘一条 GitHub 仓库链接进来，会出现「直接打开」提示条，不用先切成关键词。"),
      route: '/search',
      target: '.search-bar'
    },
    {
      group: window.I18n.t('找内容'),
      title: window.I18n.t('搜索：先选类型再搜'),
      body: window.I18n.t("这排标签决定搜的是<b>仓库 / 用户 / 代码 / 议题</b>等哪一类，先选类型再搜命中率高很多。") +
            window.I18n.t("<br>不同类型下面的排序项也不同（仓库能按 Star、更新时间排）。"),
      route: '/search',
      target: '#tabs'
    },
    {
      group: window.I18n.t('找内容'),
      title: window.I18n.t('搜索：排序与筛选'),
      body: window.I18n.t("<b>输入关键词之后</b>才会浮出这一排：<b>排序</b>选按什么排，<b>筛选</b>按语言、Star 数、是否 fork 等收窄。") +
            window.I18n.t("<br>筛选条件会拼进真正发给 GitHub 的查询串，不是本地过滤。"),
      route: '/search',
      target: '.search-bar',
      note: window.I18n.t('提示：排序与筛选这一排要输入关键词之后才会浮出')
    },
    {
      group: window.I18n.t('找内容'),
      title: window.I18n.t('探索：时间范围与语言'),
      body: window.I18n.t("<b>今日 / 本周 / 本月</b>筛的是「这段时间内新创建的仓库里 Star 涨得快的」，不是历史 Star 总数榜。") +
            window.I18n.t("<br>下面那排<b>语言标签可以左右滑</b>，点一个就只剩那一种语言；两个条件会一起生效。"),
      route: '/explore',
      target: '.seg-wrap'
    },
    {
      group: window.I18n.t('找内容'),
      title: window.I18n.t('仓库页：标签页与一键打包'),
      body: window.I18n.t("点进任意仓库，顶部标签可在<b>代码 / 议题 / PR / Actions / 发布 / 贡献者</b>等之间切换；分支名可点，能换分支或标签。") +
            window.I18n.t("<br>有权限的仓库在 <b>Actions</b> 里可以「一键打包 APK」「手动触发构建」，构建日志和产物会回传到仓库。") +
            window.I18n.t("<br>仓库<b>设置</b>页还能改描述、Topics、可见性、协作者与归档。"),
      note: window.I18n.t('这一步是说明：随便点进一个仓库就能看到这些标签')
    },

    /* ── D 进阶功能 ─────────────────────────────── */
    {
      group: window.I18n.t('进阶功能'),
      title: window.I18n.t('收藏夹：跟官网 Lists 双向同步'),
      body: window.I18n.t("在「我的」里切到<b>收藏夹</b>，里面可以建<b>分类隔层</b>：＋新建、长按隔层改名/删除，把仓库收进不同列表。") +
            window.I18n.t("<br>登录后切进收藏夹会<b>自动和官网 Lists 双向同步</b>：官网建的这里可见，这里改的同步回官网，换设备不丢。") +
            window.I18n.t("<br>令牌权限不够时会自动退回本机存储，列表照常用，只是不同步。"),
      route: '/profile',
      target: '#fseg'
    },
    {
      group: window.I18n.t('进阶功能'),
      title: window.I18n.t('应用库：跟踪本机应用的更新'),
      body: window.I18n.t("「我的」里这行<b>应用库</b>：把手机上已装的应用关联到 GitHub 仓库 / Release，之后自动跟踪新版本、一键下载安装。") +
            window.I18n.t("<br>右侧有可更新数量角标。它藏在二级页里，不提示几乎没人发现 —— 明明有更新主界面却没动静，多半就是没进过这里。"),
      route: '/profile',
      target: '[data-p="/library"]'
    },
    {
      group: window.I18n.t('进阶功能'),
      title: window.I18n.t('数据概览：Star 与语言分布'),
      body: window.I18n.t("登录后可以看<b>数据概览</b>：收到的 Star 都落在哪些仓库、语言分布环形图、以及随时间的变化趋势。") +
            window.I18n.t("<br>数据全走同一套接口，令牌和加速通道与应用其余部分共用。"),
      route: '/stats'
    },
    {
      group: window.I18n.t('进阶功能'),
      title: window.I18n.t('头像与个人主页 ID'),
      body: window.I18n.t("议题 / PR / 提交 / 发布详情里的<b>头像都能点</b>，直接进对方主页。") +
            window.I18n.t("<br>个人主页用户名下面有一行<b>数字 ID</b>，点一下复制 —— 用户名能改，数字 ID 从注册起不变，确认「是不是同一个人」看它最准。"),
      note: window.I18n.t('这一步是说明：进任意带评论的页面都能试')
    },
    {
      group: window.I18n.t('进阶功能'),
      title: window.I18n.t('界面语言：跟随系统 / 中文 / English / 天道仙文'),
      body: window.I18n.t("设置里的<b>界面语言</b>可以跟随系统，或固定简体中文、English、天道仙文。") +
            window.I18n.t("<br>它只改界面文案，不会动仓库里的 README、议题正文这些用户内容；切换后整页会立刻重画。"),
      route: '/settings',
      target: '[data-s="lang"]'
    },
    {
      group: window.I18n.t('进阶功能'),
      title: window.I18n.t('两步验证器：不登录也能用'),
      body: window.I18n.t("设置里的<b>两步验证器</b>是个本机 TOTP 工具：扫码或粘贴恢复密钥添加账号，生成 6 位动态码。") +
            window.I18n.t("<br>密钥只存在本机、算法也在本机算，<b>不需要登录 GitHub</b>；回到前台通知栏提示会自动收起，退到后台再挂出来。"),
      route: '/settings',
      target: '[data-s="totp"]'
    },
    {
      group: window.I18n.t('进阶功能'),
      title: window.I18n.t('多账号：退出后还能一键切回来'),
      body: window.I18n.t("设置里的<b>切换账号</b>列出登录过的所有账号，可随时互换，也能添加新令牌。") +
            window.I18n.t("<br>退出登录只结束当前登录态，<b>令牌仍保存在加密存储里</b>，下次打开登录页一键登回；想彻底清除用登录页的「忘记此令牌」。"),
      route: '/settings',
      target: '#logout'
    },

    /* ── E 设置与收尾 ───────────────────────────── */
    {
      group: window.I18n.t('设置与收尾'),
      title: window.I18n.t('外观：主题、iOS 风格、字号、启动页'),
      body: window.I18n.t("<b>主题外观</b>：跟随系统 / 浅色 / 深色；<b>iOS风格</b>：开启时底栏是悬浮磨砂胶囊，关掉回到贴底朴素样式（老机型觉得卡可以关）。") +
            window.I18n.t("<br><b>代码字号</b>调代码阅读大小，<b>启动页</b>决定打开 App 默认停在哪个标签。"),
      route: '/settings',
      target: '[data-s="glass"]'
    },
    {
      group: window.I18n.t('设置与收尾'),
      title: window.I18n.t('维护：配额、缓存、更新、关于'),
      body: window.I18n.t("<b>API 配额</b>看 GitHub 还剩多少次请求；<b>清除缓存</b>清本地缓存；<b>检查更新</b>手动查新版；<b>关于</b>看版本号与源码指纹。") +
            window.I18n.t("<br>版本号第一位变了必须装新版才能继续用，后两位可选；同一个版本号换了安装包，App 靠 SHA-256 也能识别出来。"),
      route: '/settings',
      target: '[data-s="cache"]'
    },
    {
      group: window.I18n.t('设置与收尾'),
      title: window.I18n.t('想再看一遍引导：两个入口不一样'),
      body: window.I18n.t("<b>新手指导</b>：立刻重播一遍（现在就看）。<b>重置新手引导</b>：只抹掉「已看过」的记录，<b>下次打开 App</b> 自动重播。") +
            window.I18n.t("<br>右边显示「已完成 / 可重置」表示当前状态；显示「未开始 / 已是初始状态」时点重置不会有任何变化。") +
            window.I18n.t("<br>这一步只讲清楚区别，不要求你点 —— 引导还没看完时这两个入口本来就不该生效。"),
      route: '/settings',
      target: '[data-s="guideReset"]'
    },
    {
      group: window.I18n.t('设置与收尾'),
      title: window.I18n.t('就这些，开始用吧'),
      body: window.I18n.t("<b>刚才这些藏起来的：</b>") +
            window.I18n.t("<br>· 右上角：收纳箱=下载管理，地球=翻译，脉冲=下载通道") +
            window.I18n.t("<br>· 底栏可左右拖着切页，页面顶部下拉=刷新") +
            window.I18n.t("<br>· 地球按钮<b>长按</b>=翻译设置，引擎条目<b>长按</b>=去官网拿密钥") +
            window.I18n.t("<br>· 通知条目<b>长按</b>=多选，发布版本<b>长按</b>=转正/转预览") +
            window.I18n.t("<br>· 收藏夹同步官网 Lists、应用库跟踪更新、数据概览看分布") +
            window.I18n.t("<br>· 两步验证器不用登录，多账号退出后一键切回") +
            window.I18n.t("<br>想再看一遍，随时来 <b>设置 → 新手指导</b>。") +
            window.I18n.t("<br>登录后还能收通知、评论 Issue、Star 仓库 —— 去「我的」页登录即可。")
    }
  ];

  var S = {
    on: false, i: 0, startHash: '', waitTimer: null,
    win: false,          // 当前这步的动手任务是否已做对
    okTimer: null,       // 做对之后「让真实效果露个脸」的那 1.3 秒
    nagTimer: null,      // 迟迟没动手时的软提示
    nudged: false
  };
  var D = null;   // DOM 引用，ensureDom() 里建

  /* ---------------- DOM ---------------- */
  function ensureDom() {
    if (D) return D;
    var root = document.createElement('div');
    root.id = 'ob-root';
    /* 翻译脚本会遍历文本节点往外发请求，引导文案本来就是中文，
       标上 data-no-translate 免得白跑一趟（translate.js 的 SKIP_SEL 认这个）。 */
    root.setAttribute('data-no-translate', '1');
    root.innerHTML =
      '<div id="ob-hole"></div>' +
      '<div id="ob-ring"></div>';
    document.body.appendChild(root);

    /* 气泡特意挂在 body 上、而不是 #ob-root 里面：
       #ob-root 需要在弹层弹出时整体降到弹层背后（.behind），
       而 position:fixed 的元素自己就是一个层叠上下文，里面的子元素
       永远逃不出去 —— 弹层一出来气泡就会跟着被盖住，用户既看不到
       「✓ 就是这个」，也点不到里面的按钮。挂成兄弟节点后两者的
       z-index 各算各的，遮罩退下去、气泡照常浮在最上面。
       「跳过」现在是气泡按钮行里的一枚，跟着气泡走。 */
    var tip = document.createElement('div');
    tip.id = 'ob-tip';
    tip.setAttribute('data-no-translate', '1');
    tip.innerHTML =
      '<div id="ob-step"><span id="ob-step-text"></span><span id="ob-step-num"></span></div>' +
      '<div id="ob-progress"><i></i></div>' +
      '<div id="ob-title"></div>' +
      '<div id="ob-body"></div>' +
      '<div id="ob-hint" hidden></div>' +
      '<div id="ob-win" hidden></div>' +
      '<div id="ob-actions">' +
        '<button class="ob-btn" id="ob-prev" type="button" hidden>上一步</button>' +
        /* 「跳过」就放在按钮行最右边，不另占屏幕一角：原来那枚常驻右上角的
           胶囊会压住顶栏的图标（真机反馈「有点挡」），而它一条水平线上正好
           是页面的菜单/下载/翻译三个按钮 —— 引导本来就要求用户点到那里去。
           这不是一个需要随时能摸到的逃生口：系统返回键在任何一步都等同跳过。 */
        '<button class="ob-btn" id="ob-skip" type="button">跳过</button>' +
        '<button class="ob-btn" id="ob-next" type="button">下一步</button>' +
      '</div>';
    document.body.appendChild(tip);

    D = {
      root: root,
      tip: tip,
      hole: root.querySelector('#ob-hole'),
      ring: root.querySelector('#ob-ring'),
      title: tip.querySelector('#ob-title'),
      body: tip.querySelector('#ob-body'),
      hint: tip.querySelector('#ob-hint'),
      win: tip.querySelector('#ob-win'),
      num: tip.querySelector('#ob-step-num'),
      group: tip.querySelector('#ob-step-text'),
      bar: tip.querySelector('#ob-progress > i'),
      prev: tip.querySelector('#ob-prev'),
      next: tip.querySelector('#ob-next'),
      skip: tip.querySelector('#ob-skip')
    };

    D.next.onclick = function () { go(S.i + 1); };
    D.prev.onclick = function () { if (S.i > 0) go(S.i - 1); };
    D.skip.onclick = function () { quit(); };
    window.addEventListener('resize', function () { if (S.on) locate(); });
    window.addEventListener('orientationchange', function () {
      if (S.on) setTimeout(locate, 260);
    });
    return D;
  }

  /** 这一步要操作/高亮的选择器 */
  function selOf(step) {
    return (step.act && step.act.sel) || step.target || '';
  }

  /* ---------------- 点击守卫 ----------------
     动手步骤要放开点击，可放开的只有「这一步的目标」。
     其余 click 一律吞掉：引导期间跳到别的页面，人就找不回来了。 */
  function guard(e) {
    if (!S.on || !D) return;
    if (S.win) return;                       // 已经做对了：放开手，让人看真实效果
    var t = e.target;
    if (D.tip.contains(t) || t === D.skip || D.tip === t) return;
    /* 弹层里的 ✕ 必须能点：否则引导期间一个残留菜单都关不掉，
       后面的高亮全被它盖住。 */
    if (t && t.closest && t.closest('#sheet-root [data-close]')) return;
    var step = STEPS[S.i];
    if (step && step.act) {                  // 动手步骤：目标内的点击放行
      var sel = selOf(step);
      if (sel && t && t.closest && t.closest(sel)) return;
    }
    e.preventDefault();
    e.stopPropagation();
    nudge();
  }

  var nudgeAt = 0;
  function nudge() {
    var now = Date.now();
    if (now - nudgeAt < 2600) return;
    nudgeAt = now;
    try {
      if (window.UI && UI.toast) {
        UI.toast(STEPS[S.i] && STEPS[S.i].act
          ? window.I18n.t('先按提示做这一步，做完会自动往下走')
          : window.I18n.t('引导进行中，先看完或点卡片里的「跳过」'));
      }
    } catch (e) {}
  }

  /* ---------------- 轮询等元素 ----------------
     页面骨架是同步渲染的，但有的块要等接口回来才有，所以轮询。
     超时就返回 null —— 调用方会退化成居中弹窗，绝不把人卡住。 */
  function waitFor(sel, cb) {
    clearTimeout(S.waitTimer);
    if (!sel) return cb(null);
    var t0 = Date.now();
    (function poll() {
      var node = null;
      try { node = document.querySelector(sel); } catch (e) { node = null; }
      if (node) return cb(node);
      if (Date.now() - t0 > 2500) return cb(null);
      S.waitTimer = setTimeout(poll, 80);
    })();
  }

  /* ---------------- 定位洞和气泡 ---------------- */
  function locate() {
    if (!D) return;
    var step = STEPS[S.i];
    var sel = selOf(step);
    var node = sel ? document.querySelector(sel) : null;
    var r0 = node ? node.getBoundingClientRect() : null;

    /* 没有目标，或者目标小到看不见（比如右上角还没有任何按钮时
       #appbar-actions 是空的）—— 都退化成居中弹窗。
       给一个 8px 的门槛：高亮一个 2px 的空壳没有任何意义。 */
    if (!node || !r0 || r0.width < 8 || r0.height < 8) {
      D.root.classList.add('no-target');
      /* ⚠️ 必须把内联样式清掉。
         上一步写过 left/top/width/height，内联的优先级高于
         `#ob-root.no-target #ob-tip { left:50%; top:50% }` 那条规则，
         不清的话居中那两步的气泡会跑到屏幕外面去（欢迎页实测 x=-156）。 */
      D.tip.style.left = '';
      D.tip.style.top = '';
      [D.hole, D.ring].forEach(function (n) {
        n.style.left = ''; n.style.top = '';
        n.style.width = ''; n.style.height = '';
      });
      return;
    }
    D.root.classList.remove('no-target');

    /* 目标在可滚动区域里且不在视口内时，先滚进来再量。
       fixed 的顶栏/底栏调这个没有副作用。 */
    try {
      var vh0 = window.innerHeight;
      if (r0.top < 0 || r0.bottom > vh0) {
        node.scrollIntoView({ block: 'center', inline: 'nearest' });
      }
    } catch (e) {}

    var vw = window.innerWidth, vh2 = window.innerHeight;
    var r = node.getBoundingClientRect();
    var pad = 6;
    var x = Math.max(0, r.left - pad);
    var y = Math.max(0, r.top - pad);
    var w = Math.min(Math.max(24, r.width + pad * 2), vw - x);
    var h = Math.min(Math.max(24, r.height + pad * 2), vh2 - y);

    var round = !!step.round || (w <= 56 && h <= 56);
    [D.hole, D.ring].forEach(function (n) {
      n.classList.toggle('round', round);
      n.style.left = x + 'px';
      n.style.top = y + 'px';
      n.style.width = w + 'px';
      n.style.height = h + 'px';
    });

    /* 气泡：优先放洞下方，放不下就放上方，再不行就居中。
       顶部只留出状态栏那条 —— 「跳过」已经收进气泡里，不再占顶栏下方那 56px。 */
    var tipW = D.tip.offsetWidth, tipH = D.tip.offsetHeight;
    var gap = 12;
    var minTop = (parseFloat(getComputedStyle(document.documentElement)
      .getPropertyValue('--safe-t')) || 0) + 12;
    var top;
    if (vh2 - (y + h) >= tipH + gap + 12) top = y + h + gap;
    else if (y >= tipH + gap + 12) top = y - tipH - gap;
    else top = Math.max(minTop, Math.min(vh2 - tipH - 12, (vh2 - tipH) / 2));
    if (top < minTop) top = minTop;

    var left = x + w / 2 - tipW / 2;
    left = Math.max(12, Math.min(vw - tipW - 12, left));
    D.tip.style.left = left + 'px';
    D.tip.style.top = Math.max(minTop, Math.min(top, vh2 - tipH - 12)) + 'px';
  }

  /* ---------------- 动手任务 ----------------
     监听一律挂在 document 上做委托：下拉刷新、点设置项都会触发
     Router.reload() 把整片 DOM 换掉，挂在具体元素上的监听会随旧节点
     一起被回收，第二次就再也收不到事件了。委托 + closest 不受影响。 */
  var TASK = null;

  function clearTask() {
    if (TASK) {
      TASK.forEach(function (h) {
        try { document.removeEventListener(h[0], h[1], h[2]); } catch (e) {}
      });
      TASK = null;
    }
    clearTimeout(S.okTimer);
    clearTimeout(S.nagTimer);
    S.nagTimer = null;
  }

  /** 这一下是不是落在当前目标上 */
  function isHit(node, sel) {
    if (!node || !sel) return false;
    try { return !!(node.closest && node.closest(sel)); } catch (e) { return false; }
  }

  function pointOf(e) {
    return (e.touches && e.touches[0]) || e || {};
  }
  /** 目标是否真的可见可点（fixed 元素不能用 offsetParent 判） */
  function isVisible(node) {
    if (!node || node.nodeType !== 1) return false;
    try {
      var cs = window.getComputedStyle(node);
      if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) return false;
      var r = node.getBoundingClientRect();
      return r.width >= 8 && r.height >= 8;
    } catch (e) { return false; }
  }


  /**
   * 给这一步装上「做对了就往下走」的判定。
   * 目标元素此刻不在页面上时返回 false，调用方会退化成普通展示步骤
   * （比如没登录的通知页里一条通知都没有，长按自然无从谈起）。
   */
  function armTask(step) {
    clearTask();
    var act = step.act;
    if (!act) return false;
    var sel = selOf(step);
    if (!sel) return false;
    /* 元素「在 DOM 里」不等于「看得见、点得到」。
       以前只查 querySelector，路由跳错页面时 #tabbar 被 hidden，
       任务照样装上，气泡却让人去拖一个不存在的底栏 —— 永远做不出来。 */
    if (!isVisible(document.querySelector(sel))) return false;

    var T = [];
    var add = function (ev, fn, opt) {
      var o = opt === undefined ? true : opt;
      document.addEventListener(ev, fn, o);
      T.push([ev, fn, o]);
    };
    var passive = { capture: true, passive: true };

    if (act.kind === 'tap') {
      add('click', function (e) { if (isHit(e.target, sel)) succeed(act); });

    } else if (act.kind === 'long') {
      var hold = act.ms || 500, timer = null, sx = 0, sy = 0, moved = false;
      var down = function (e) {
        if (!isHit(e.target, sel)) return;
        var p = pointOf(e);
        sx = p.clientX || 0; sy = p.clientY || 0; moved = false;
        clearTimeout(timer);
        /* 判定比真实功能早一点点没关系：真实的长按（translate.js 520ms、
           UI.bindLongPress 500ms）本身就在同一时间量级上，用户看到的是
           「菜单弹出来了」，而不是「什么都没发生」。 */
        timer = setTimeout(function () { if (!moved) succeed(act); }, hold);
      };
      var mv = function (e) {
        var p = pointOf(e);
        if (Math.abs((p.clientX || 0) - sx) > 10 || Math.abs((p.clientY || 0) - sy) > 10) {
          moved = true; clearTimeout(timer);      // 手指挪开了：当成在滚页面
        }
      };
      var up = function () { clearTimeout(timer); };
      add('touchstart', down, passive);
      add('touchmove', mv, passive);
      add('touchend', up);
      add('touchcancel', up);
      add('mousedown', down);
      add('mousemove', mv);
      add('mouseup', up);
      add('mouseleave', up);

    } else if (act.kind === 'drag') {
      var x0 = null;
      add('pointerdown', function (e) { if (isHit(e.target, sel)) x0 = e.clientX; });
      add('pointermove', function (e) {
        if (x0 === null) return;
        if (Math.abs(e.clientX - x0) > 40) { x0 = null; succeed(act); }
      });
      add('pointerup', function () { x0 = null; });
      add('pointercancel', function () { x0 = null; });
      /* 老 WebView 没有 pointer 事件时退回 touch，别让这一步彻底做不了 */
      var tx0 = null;
      add('touchstart', function (e) { if (isHit(e.target, sel)) tx0 = pointOf(e).clientX; }, passive);
      add('touchmove', function (e) {
        if (tx0 === null) return;
        if (Math.abs(pointOf(e).clientX - tx0) > 40) { tx0 = null; succeed(act); }
      }, passive);
      add('touchend', function () { tx0 = null; });

    } else if (act.kind === 'pull') {
      var y0 = null;
      add('touchstart', function (e) { if (isHit(e.target, sel)) y0 = pointOf(e).clientY; }, passive);
      /* 阈值 75 比 app.js 里真实刷新的 70 略大：先让真刷新发生，
         再判这一步成功 —— 不然用户明明照着做了却没看到页面刷，会以为没生效。 */
      add('touchmove', function (e) {
        if (y0 === null) return;
        if ((pointOf(e).clientY || 0) - y0 > 75) { y0 = null; succeed(act); }
      }, passive);
      add('touchend', function () { y0 = null; });
    }

    TASK = T;
    /* 迟迟没做出来：给一句软提示，别让人对着屏幕猜。
       不做强制跳转 —— 有些人的机器上手势就是不太灵。 */
    S.nagTimer = setTimeout(function () {
      if (S.on && !S.win && window.UI && UI.toast) {
        UI.toast(window.I18n.t('做不出来也没关系，点「跳过这步」继续'), 3000);
      }
    }, act.kind === 'long' ? 9000 : 7000);
    return true;
  }

  /** 做对了：先让真实效果露个脸，再进下一步 */
  function succeed(act) {
    if (!S.on || S.win) return;
    S.win = true;
    clearTask();
    try { if (window.UI && UI.haptic) UI.haptic(); } catch (e) {}

    /* 遮罩退到弹层背后（.behind），气泡本身还在最上面 ——
       这样长按翻译按钮弹出来的菜单、多选浮出来的操作栏都能被看见。
       不然「做了半天什么都没看到」，这一下就白教了。 */
    D.root.classList.add('behind');
    D.tip.classList.add('win');
    D.win.hidden = false;
    D.win.textContent = '✓ ' + (act.win || window.I18n.t('就是这个'));
    D.hint.hidden = true;
    D.next.textContent = window.I18n.t('继续');

    S.okTimer = setTimeout(function () {
      if (!S.on) return;
      D.root.classList.remove('behind');
      try { if (window.UI && UI.closeSheet) UI.closeSheet(); } catch (e) {}
      go(S.i + 1);
    }, 1300);
  }

  /* ---------------- 走一步 ---------------- */
  function go(i) {
    if (i >= STEPS.length) return finish();
    /* 上一步长按弹出的菜单若没关掉，会一直盖在页面上，
       高亮框就会圈到菜单里的行而不是这一步的目标（真机截图实证）。 */
    try { if (window.UI && window.UI.closeSheet) window.UI.closeSheet(); } catch (e) {}
    S.i = i;
    var step = STEPS[i];

    /* 上一步的痕迹必须清干净：任务监听、成功态、退位用的 behind */
    clearTask();
    S.win = false;
    S.nudged = false;
    D.root.classList.remove('behind');
    D.tip.classList.remove('win');
    D.win.hidden = true;
    D.hint.hidden = true;

    if (step.route && window.Router) {
      var here = (location.hash || '#/').replace(/^#/, '').split('?')[0];
      if (here !== step.route) window.Router.go(step.route);
    }

    D.title.textContent = step.title;
    if (D.group) D.group.textContent = step.group || '';
    D.body.innerHTML = step.body;          // 内容是本文件里的常量，没有外部输入
    D.num.textContent = (i + 1) + ' / ' + STEPS.length;
    D.bar.style.width = ((i + 1) / STEPS.length * 100) + '%';
    D.prev.hidden = (i === 0);
    /* 重播一次气泡的进入动画：CSS animation 挂在元素上，只改内容不会重新播，
       强制回流一下才有效果（每步都有一点「冒出来」的感觉）。 */
    try {
      D.tip.style.animation = 'none';
      void D.tip.offsetWidth;
      D.tip.style.animation = '';
    } catch (e) {}

    waitFor(selOf(step), function () {
      if (!S.on) return;                   // 等待期间被关掉了
      locate();
      /* 目标没等到（接口没回、页面结构变了、未登录没数据）就退化成
         普通展示步骤：宁可这一步没教会，也不能把人卡在遮罩里。 */
      var armed = step.act ? armTask(step) : false;
      if (armed) {
        D.hint.hidden = false;
        D.hint.textContent = window.I18n.t('动手试试：') + step.act.hint + window.I18n.t('（做完自动进入下一步）');
        D.next.textContent = window.I18n.t('跳过这步');
      } else {
        if (step.note) { D.hint.hidden = false; D.hint.textContent = step.note; }
        D.next.textContent = (i === STEPS.length - 1) ? window.I18n.t('完成') : window.I18n.t('下一步');
      }
      D.next.classList.toggle('ghost', armed);
    });
  }

  /* ---------------- 开始 / 结束 ---------------- */
  function start(opts) {
    opts = opts || {};
    if (S.on) return;                      // 已经在跑，不重复启动
    ensureDom();
    S.on = true;
    S.i = 0;
    S.startHash = (location.hash || '#/');
    D.root.classList.add('show');
    document.addEventListener('click', guard, true);
    go(0);
    /* 手动从设置里进来时给个提示，让人知道随时能退 */
    if (opts.manual && window.UI && UI.toast) UI.toast(window.I18n.t('点卡片里的「跳过」可随时退出引导'));
  }

  function teardown() {
    S.on = false;
    S.win = false;
    clearTask();
    clearTimeout(S.waitTimer);
    document.removeEventListener('click', guard, true);
    if (D) {
      D.root.classList.remove('show', 'no-target', 'behind');
      D.tip.classList.remove('win');
      D.win.hidden = true;
      D.hint.hidden = true;
      [D.hole, D.ring].forEach(function (n) {
        n.style.width = '0px'; n.style.height = '0px';
      });
    }
  }

  /** 看完 / 跳过，都算「已经看过」，下次不再自动弹 */
  function finish() {
    markDone();
    var back = S.startHash && S.startHash !== '#' ? S.startHash : '#/';
    teardown();
    if (window.Router) {
      try { window.Router.replace(back.replace(/^#/, '')); } catch (e) { location.hash = back; }
    }
  }

  /** 返回键 / 点跳过：等同看完，也要回到进引导前的页面 */
  function quit() {
    markDone();
    var back = S.startHash && S.startHash !== '#' ? S.startHash : '#/';
    teardown();
    if (window.Router) {
      try { window.Router.replace(back.replace(/^#/, '')); } catch (e) { location.hash = back; }
    }
  }

  function markDone() {
    try { if (window.Store) window.Store.set(KEY, VERSION); } catch (e) {}
  }

  /* ---------------- 对外 ---------------- */
  window.Onboarding = {
    VERSION: VERSION,
    steps: STEPS,
    start: start,
    quit: quit,
    isActive: function () { return S.on; },
    isDone: function () {
      try { return window.Store ? (window.Store.get(KEY) || 0) >= VERSION : true; }
      catch (e) { return true; }
    },
    /** 首次启动自动弹：只看 Store，看过就不再打扰 */
    autoStart: function () {
      try { if (this.isDone()) return false; } catch (e) { return false; }
      var self = this;
      /* 等首屏画完再上遮罩，否则会盖在一个还没渲染好的页面上 */
      setTimeout(function () { if (!S.on) self.start(); }, 400);
      return true;
    },
    /** 重新看一遍（设置里的入口）：清掉状态再走一遍 */
    restart: function () {
      try { if (window.Store) window.Store.set(KEY, 0); } catch (e) {}
      start({ manual: true });
    },
    /**
     * 重置：只抹掉「已经看过」这个记录，回到刚装好的状态，**不立刻播放**。
     * 效果就是下次打开 App 时 autoStart() 会重新把引导走一遍。
     * 和 restart() 的区别：restart 是「现在就看」，reset 是「下次打开再看」。
     */
    reset: function () {
      try { if (window.Store) window.Store.set(KEY, 0); } catch (e) {}
      return true;
    }
  };
})();
