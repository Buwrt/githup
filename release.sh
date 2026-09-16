#!/usr/bin/env bash
# githup 发版脚本
#
# 用法：
#   bash release.sh 1.2.0 "修了 issue 列表偶尔不刷新的问题"
#   bash release.sh 1.2.0 "支持 xxx" V5        # 文件名还想用代号时加第三个参数
#
# 一条命令走完这些事：
#   1. 改版本号（build.gradle / 前端常量）并打包 APK
#   2. 把 APK 放进 apk/ 目录
#   3. 更新根目录的 version.json（更新检测的备用来源）
#   4. 提交并推送 main
#   5. 打 tag v<版本> 并推送
#
# 第 5 步会触发 .github/workflows/publish-release.yml：
# 由 Actions 用仓库自己的 GITHUB_TOKEN 创建 Release 并上传 APK 附件。
#
# 为什么不直接用 API 发 Release：
#   外部令牌通常只有仓库的读/拉取权限，调 REST 的 Release 写接口会 403，
#   但 git 推送仓库内容是允许的。于是把「写 Release」这件事交给仓库自己的
#   Actions 去做 —— 它的令牌天然有 contents:write，不需要额外授权。
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PRJ="$ROOT/github-mobile"

VER="$1"
NOTES="$2"
CODE="$3"
[ -n "$VER" ] || { echo "用法: bash release.sh <版本号> [更新说明] [文件名代号]"; exit 1; }
[ -n "$CODE" ] || CODE="$VER"

bash "$ROOT/build-apk.sh" "$CODE" "$VER" >/dev/null

APK_SRC="$ROOT/githup-$CODE.apk"
[ -f "$APK_SRC" ] || APK_SRC="$(ls "$ROOT"/githup-[vV]*.apk | head -1)"
[ -f "$APK_SRC" ] || { echo "没找到打包产物"; exit 1; }

mkdir -p "$PRJ/apk"
rm -f "$PRJ"/apk/*.apk
cp -f "$APK_SRC" "$PRJ/apk/"
echo "APK 已同步到 apk/ 目录: $(basename "$APK_SRC")"

python3 - "$PRJ/version.json" "$VER" "$NOTES" <<'PY'
import json, sys, os, hashlib

path, ver, notes = sys.argv[1], sys.argv[2], sys.argv[3]
apk_dir = os.path.join(os.path.dirname(path), 'apk')
apks = sorted(os.listdir(apk_dir))
apk = [f for f in apks if f.endswith('.apk')]
name = apk[-1] if apk else ('githup-v%s.apk' % ver)

d = {}
if os.path.exists(path):
    with open(path, encoding='utf-8') as f:
        try: d = json.load(f)
        except Exception: d = {}

sha = hashlib.sha256(open(os.path.join(apk_dir, name), 'rb').read()).hexdigest()
size = os.path.getsize(os.path.join(apk_dir, name))

d.update({
    'version': ver,
    'apk': 'apk/' + name,
    'name': 'githup v' + ver,
    'published': __import__('datetime').date.today().isoformat(),
    'size': size,
    'sha256': sha
})
if notes:
    d['notes'] = notes

with open(path, 'w', encoding='utf-8') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write('\n')
print('version.json 已更新: version=%s size=%d' % (ver, size))
print('  sha256=%s' % sha)
PY

cd "$PRJ"
git add -A
MSG="发布 v$VER"
[ -n "$NOTES" ] && MSG="$MSG：$NOTES"
if git diff --cached --quiet; then
  echo "没有需要提交的改动"
else
  git -c user.name=aeroheaven -c user.email=aeroheaven@users.noreply.github.com \
      commit -q -m "$MSG"
  git push origin main
  echo "已推送 main"
fi

git tag -f -a "v$VER" -m "githup v$VER" 2>/dev/null || \
git -c user.name=aeroheaven -c user.email=aeroheaven@users.noreply.github.com \
    tag -f -a "v$VER" -m "githup v$VER"
GIT_TERMINAL_PROMPT=0 git push -f origin "v$VER"
echo
echo "tag v$VER 已推送，Actions 会自动创建 Release 并上传 APK"
echo "稍后可在这里查看：https://github.com/Buwrt/githup/releases"
