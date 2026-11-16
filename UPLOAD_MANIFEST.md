# 源码与 APK 上传清单 · Buwrt/githup

> 本文件由同步流程写入，用于直接核对「源码和 APK 到底传没传」。
> 同步时间：2026-11-16（北京时间）

## 这个库里有什么

| 类别 | 位置 | 状态 |
|---|---|---|
| 源码 | `app/src/main/`、`tools/`、`apk/`、工作流等（仓库共 208 个文件） | ✅ 已上传 |
| 本版 APK | `apk/githup-v1.2.20.apk`（本体）+ `apk/githup-v1.2.20.apk.sha256` | ✅ 已上传（文件树本体 + Release 附件同字节） |
| 校验清单 | `apk/githup-v1.2.20.apk.sha256`、`version.json` | ✅ 已上传 |
| Release 附件 | Releases → `v1.2.20` | ✅ 已上传 |

## 本版产物

- 版本 `1.2.20` / `versionCode 1002020` / 包名 `com.hubmobile.app`
- APK SHA-256：`e98952be5ed978c008bc85cfaf3653f2f8c4b5702850db6e465b809aff6ae82a`
- 源码指纹 `src_sha256`：`e2937fe297ffdcdc899123854a442c81f09cfd08c8e34e54a4bf9b992a751a91`
- 同步基线提交（本清单文件本身是随后一条）：`7d6aa3a53482089a7f46e6a436318d8ffca28b33`
- 官方签名证书未变，可直接覆盖升级

## 本轮内容

预览恢复旧版样式（纯卡片、按钮文字不变、保留代码高亮与图片）；切换议题分类时撤销上一次自动填入的标题前缀、正文骨架与标签，用户手写内容不受影响。

## 三库关系

- `Buwrt/githup`：公开底座库，源码 + 1.2MB 底座 APK
- `Buwrt/githup-release`：私有构建/签名库，与公开库同源码同包
- `Buwrt/2`：合并版（含 AI 引擎）。154MB 包**超过 GitHub 单文件 100MB 上限**，本体只能挂 Release 附件；文件树里放 `.url` 直链指针 + `.sha256`

## 为什么首页可能显示「几天前」

旧版 App 首页显示的是 GitHub 的 `updated_at`（仓库元数据变更：改描述、被点 Star），**push 代码不会更新它**；本版已改为 `pushed_at`（代码最后推送时间），下拉刷新即可看到「几分钟前」。真实推送时间以 `git log` 为准。

---
*以 `version.json` 为哈希权威；本文件不参与 APK 源码指纹计算。*
