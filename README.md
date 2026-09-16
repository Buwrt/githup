# githup

Android 上的第三方 GitHub 客户端。用 WebView 承载一套纯前端单页应用，原生层只负责网络、文件、下载安装等 Web 做不了的事 —— APK 只有 **230 KB** 左右，却覆盖了浏览仓库、看 Issue / PR、查 Actions、发 Release、上传文件，甚至云端打包 APK 这些事。

更新源是本仓库，App 内自带版本检测：[Releases](https://github.com/Buwrt/githup/releases)。

---

## 一句话了解它能干什么

| 场景 | githup 能做的 |
|---|---|
| 通勤路上要看代码 | 仓库文件树、README 渲染、代码高亮（浅色 / 深色两套高亮主题） |
| 有人 @ 我 | 通知列表、未读角标、一键全部标已读、批量操作 |
| Review PR | Issue / PR 列表筛选、详情、评论、合并状态、diff 高亮 |
| CI 挂了 | Actions 运行列表、日志、下载构建产物 ZIP 并自动解压出 APK 安装 |
| 发版本 | 创建 Release、上传附件（APK 等），或让 Actions 云端打包 APK |
| 摸鱼 | Trending 仓库榜，按日 / 周 / 月和语言筛选 |

---

## 功能一览

### 登录
- Personal Access Token 登录，页面里直接跳到带好权限的 Token 创建页，不用自己勾 scope
- Token 只存在设备本机（系统 Keystore 保护的 SharedPreferences），退出登录即从本机移除

### 首页 / 通知 / 探索 / 搜索
- **首页**：活动时间线、仓库快捷入口、"我的主页"
- **通知**：未读角标、全部标为已读、批量选择处理
- **探索**：Trending 仓库榜，可切换日 / 周 / 月，按编程语言过滤
- **搜索**：仓库、用户/组织的结果检索

### 个人主页
- 仓库 / Star / 关注者 / 正在关注 列表（带筛选与排序）
- 关注 / 取关、编辑个人资料、复制主页链接、在浏览器打开

### 仓库
- **关于区**：简介、Star / Watching / Fork、Releases、Packages、语言占比
- **代码浏览**：文件树逐级展开、README 自动渲染、代码高亮、图片预览、原始文件查看
- **Issue / PR**：打开/关闭筛选、标签、评论、创建与关闭
- **Actions**：工作流列表、单次运行详情、**构建产物直接下载并解压安装 APK**
- **Releases**：版本列表、资产下载
- **Commits / Contributors / Branches / Tags / Stargazers / Watchers / Forks**
- **仓库设置**：改简介、改可见性（公开 ⇄ 私有，带方向校准的二次确认）、归档 / 取消归档、删除仓库

### 写操作
- **新建仓库**：表单实时预览克隆地址，创建成功后弹出可复制的克隆链接
- **上传文件**：从设备任意目录选文件上传到仓库指定路径
- **创建 Release**：填版本号与说明，附带本地文件（APK 等）

### 云端打包 APK
- 「一键打包」向导：给任意 Android 仓库自动生成 `.github/workflows/build-apk.yml`
- **本机生成签名**：内置 `keytool` 的纯 Java 实现（`KeyTool.java`），口令 → keystore → Base64，**不需要电脑上装 JDK**
- 自动写入仓库 Secrets，触发工作流，构建完成后回到 App 下载产物并解压安装

### 设置
- 主题外观：跟随系统 / 浅色 / 深色
- 代码字号：12 / 13 / 15 / 17 px
- 启动页：默认打开哪个标签页
- API 配额：实时查看剩余次数与重置时间
- 清除缓存
- **检查更新**：手动检查是否有新版本，见下面的更新机制

---

## 更新机制（1.1.1 起内置）

版本号遵循 `x.y.z`，每一位有明确含义，**更新策略由「从高位往下第一个出现差异的那一位」决定**：

| 变化位 | 例子 | 行为 |
|---|---|---|
| 第一位 x（大版本） | `1.1.1` → `2.0.0` | **强制更新**。弹层不可关闭：没有关闭按钮、点遮罩无效、返回键也无法绕过，必须更新才能继续用 |
| 第二位 y（功能） | `1.1.1` → `1.2.0` | 可选更新。弹窗给「立即更新 / 稍后提醒 / 跳过此版」 |
| 第三位 z（修复） | `1.1.1` → `1.1.2` | 可选更新，同上 |

其它规则：

- **比不出新就不打扰**：服务端版本相同或更旧时静默跳过（方便你自己编译降级包）
- **6 小时节流**：启动后延迟 4 秒静默检查一次，6 小时内不重复打扰
- **跳过只对该版本生效**：跳过的版本会被记下来，但大版本永远会拦
- **数据来源一（优先）**：本仓库的 `GET /repos/Buwrt/githup/releases/latest`，取最新一个正式版（跳过 draft 与 prerelease），从它的 `assets` 里挑 APK 附件
- **数据来源二（备用）**：仓库根目录的 [`version.json`](version.json)。Release 不存在、取不到 APK 附件、或网络异常时自动改读这个文件，所以**即使从没发过 Release，更新检测照样可用**
- **下载安装**：走系统 `DownloadManager`，下载完自动拉起系统安装器（`installApk`），不需要自己去下载目录里找文件

`version.json` 长这样，发新版时改 `version` / `apk` / `notes` 三处即可：

```json
{
  "version": "1.1.1",
  "apk": "apk/githup-V4.apk",
  "notes": "这版改了什么"
}
```

相关实现：`app/src/main/assets/web/js/updater.js`，界面入口在「设置 → 检查更新」。

> 小提示：版本信息识别依赖 Release 的 **tag**，发布时 tag 必须是 `v1.1.1` 这种三段号（也可以不带 `v`）。老代号包（V1/V2/V3）会被按 `0.0.N` 处理，因此升到 `1.1.1` 属于大版本变化，会走强制更新 —— 这是故意的，代号版该退场了。

---

## 目录结构

```
github-mobile/
├── app/
│   ├── build.gradle                     版本、签名、编译配置
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/hubmobile/app/
│       │   ├── MainActivity.java        启动 WebView，注入 NativeBridge
│       │   ├── JsBridge.java            原生能力总入口（HTTP / 文件 / 下载 / 安装 …）
│       │   ├── Http.java                HttpURLConnection 封装（支持二进制 body）
│       │   ├── FilePick.java            系统文件选择器 + 读取
│       │   ├── KeyTool.java             纯 Java 生成 keystore（免 JDK）
│       │   ├── ApkProvider.java         给安装器临时授予 APK 读权限
│       │   ├── SecurePrefs.java         Token 的安全存储
│       │   └── WebViewActivity.java     应用内浏览器
│       └── assets/web/                  前端单页应用
│           ├── index.html
│           ├── css/app.css
│           ├── js/
│           │   ├── api.js               REST 封装 + 原生桥通信
│           │   ├── app.js               路由、主题、返回键、下拉刷新
│           │   ├── ui.js                Toast / Sheet / Confirm / 图片查看器
│           │   ├── md.js                Markdown 渲染（marked + highlight.js）
│           │   ├── updater.js           ← 本次新增：版本检测与更新
│           │   ├── page-home.js         登录 / 首页 / 通知 / 探索 / 搜索
│           │   ├── page-repo.js         仓库全页 + 云端打包向导
│           │   ├── page-detail.js       文件 / 提交 / Issue / PR / Actions 详情
│           │   └── page-user.js         个人主页 + 设置
│           └── vendor/                  marked / highlight.js / DOMPurify（本地离线）
├── apk/githup-V4.apk                    预编译好的安装包
├── gradlew, gradle/                     Gradle Wrapper（已指向国内镜像）
├── build-apk.sh                         一键打包脚本
└── preview/                             功能截图
```

## 自己编译

两种方式，任选其一。

### 方式一：一键脚本

```bash
bash build-apk.sh              # 按现有版本打包
bash build-apk.sh 1.1.2        # 文件名 githup-v1.1.2.apk，版本 1.1.2
bash build-apk.sh V4 1.1.1     # 文件名用代号，内部版本另填
```

脚本会同步改三处（`app/build.gradle` 的 `versionCode` / `versionName`、前端 `APP_VERSION` 常量），
构建完成后输出到上层目录，并清掉旧的包，只留最新这一个。

### 方式二：Gradle

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease      # 产物在 app/build/outputs/apk/release/
```

签名：`build.gradle` 里默认输出可被后续 `apksigner` 处理的 Release 构建，自行配置 signingConfig 即可打包。
`.gitignore` 已排除 `*.keystore`，签名文件不会被误提交。

### 版本号的换算

`versionCode` 必须单调递增，否则手机上已有的新版本会拒绝安装旧包：

```
x.y.z   ->   x*1000000 + y*1000 + z      例：1.1.1 -> 1001001
Vn      ->   10203 + n                   例：V3    -> 10206
```

两个体系互不冲突，1.1.1（1001001）比所有 V 系列代号都大，从 V3 直接升级安装没问题。

## 安装要求

- Android **7.0**（API 24）及以上，`targetSdk 34`
- 首次安装未知来源 APK 时，系统会要求授权「允许来自此来源的应用」
- 用到的权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`VIBRATE`、`READ_MEDIA_*`（上传文件）、`REQUEST_INSTALL_PACKAGES`（下载 APK 后直装）

## 截图

`preview/` 下放了 50 张真实截图，覆盖登录、首页、仓库、Issue、PR、Actions、Release、搜索、个人主页、设置、深色模式等界面。

## 声明

本应用是个人学习目的的第三方客户端，与 GitHub, Inc. 没有隶属关系。所有数据都通过 GitHub 官方公开 REST API 获得，Token 只保存在你自己的设备上。

- 图标：[GitHub Octicons](https://github.com/primer/octicons)（MIT）
- 代码高亮：[highlight.js](https://github.com/highlightjs/highlight.js)（BSD-3-Clause）
- Markdown：[marked](https://github.com/markedjs/marked)（MIT）
- HTML 净化：[DOMPurify](https://github.com/cure53/DOMPurify)（Apache-2.0 / MPL-2.0）
