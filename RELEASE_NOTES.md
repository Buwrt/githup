# githup v1.2.12（返回上一层后，上一个页面不再赖在屏幕上）

这一版**没有新功能**，只修一个 bug：从搜索页点进一个仓库（或任何详情页），
加载还没完成就按返回 —— 顶栏已经变回「搜索」，屏幕中间却还留着上一个页面的内容。
两个页面缝在一起。

版本号只动第三位（1.2.11 → 1.2.12），按 README 的规矩属于**可选更新**：
装了 v1.2.11 的会收到提示，不更也能继续用。

---

## 一、这个画面是怎么来的

不是返回键的问题。**顶栏正确、内容没换**，这个形状本身就说明路由已经退回去了 ——
是内容被人从底下顶掉的。

每个页面的 `render` 都是同一套节奏：

```
先同步铺一层骨架  →  发请求  →  响应回来把整块内容写进 #view
```

问题出在最后一步。真实时序：

| 时刻 | 发生的事 |
|---|---|
| t0 | 搜索页点进仓库 → 画出仓库骨架，发出 `/repos/tisfeng/Easydict` |
| t1 | 你按了返回 → 路由退回搜索页，顶栏变回「搜索」，`#view` 换成搜索页 |
| t2 | **仓库的响应这时才落地** → 回调照旧 `host.innerHTML = ...` |
| t3 | 刚画好的搜索页被整个覆盖 → 缝合画面 |

回调不知道页面已经换了人。所以这 bug 只在**网络比手指慢**的时候出现 ——
网速好的时候响应早就回来了，你永远撞不上它。

顺带一提：搜索页自己早就有 `renderSeq` 防这个，所以它不会自己盖自己；
但那是补丁不是制度，其余页面全是裸奔的。

## 二、改法：给 Router 加一把「渲染纪元」令牌

`Router.render()` 每次换一把新令牌（`viewEpoch`），页面在 render 里把当前纪元
拍下来，异步回调落地前先对一下纪元 —— **对不上就整份丢弃**。

```js
// app.js · Router.render()
Router.viewEpoch = (Router.viewEpoch || 0) + 1;

// page-repo.js · P.repo.render()
var epoch = window.Router.viewEpoch;
return window.API.get('/repos/' + full, ...).then(function (r) {
  if (epoch !== window.Router.viewEpoch) return;   // 页面已换人，整份丢弃
  paint(r.data, true);
}).catch(function (e) {
  if (epoch !== window.Router.viewEpoch) return;   // 错误框一样会顶掉新页面
  host.innerHTML = UI.errorBox(e);
});
```

两个容易漏的点，都护住了：

- **错误框也要拦**。请求失败时那个「请检查网络后下拉重试」的框同样是整块写进
  `#view`，一样会把新页面顶掉。
- **改顶栏标题的回调也要拦**。议题页、用户页的回调里会调 `App.title()`，
  不拦的话内容没被覆盖、顶栏却被改回去了 —— 那是另一种缝合。

写子容器（`#tabbody`、`#nlist` 这类）的回调不需要这层保护：页面被清空后子容器
已经脱离文档，写了也看不见。

## 三、接入的页面

| 页面 | 文件 |
|---|---|
| 首页动态 | page-home.js |
| 仓库详情 | page-repo.js |
| 议题 / 提交 / Release / 运行详情 | page-detail.js |
| 用户主页 / Gist / 我的 | page-user.js |

另外顺手修了一处：Gist 页渲染时用了 `CSS.escape`，个别老 WebView 上没有这个 API，
缺了它整个 Gist 页会挂成错误框。加了字面量兜底。

## 四、验证

新建 jsdom 集成测试 41 项，用桩桥扣住响应来模拟「网络比返回键慢」：

| | 结果 |
|---|---|
| 修复后 | **41 / 41 全绿** |
| 反证（把 9 处防护全删掉，跑同一份测试） | **24 / 41，红 17 项** |

反证里 A1 精确复现了用户截图的画面 —— 迟到的仓库响应把搜索页顶掉，`#view` 里
冒出 `repo-head`。这条红了，才说明测试真的盯着这个 bug。

既有 14 个回归脚本无回退。

---

## 附：改动清单

| 文件 | 变化 |
|---|---|
| `app/src/main/assets/web/js/app.js` | Router：新增 `viewEpoch` 递增 + 说明竞态的注释 |
| `app/src/main/assets/web/js/page-home.js` | 首页动态接入 |
| `app/src/main/assets/web/js/page-repo.js` | 仓库详情接入 |
| `app/src/main/assets/web/js/page-detail.js` | 议题 / 提交 / Release / 运行详情接入 |
| `app/src/main/assets/web/js/page-user.js` | 用户主页 / Gist / 我的接入，另修 `CSS.escape` |

**Java / Kotlin 未改业务逻辑**（`GuardKeys.java` 由防护链脚本按新版本号重新生成）。
