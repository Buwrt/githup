#!/usr/bin/env bash
# githup 一键打包脚本
#
# 版本规则（从 1.2.3 起固定，以后照此类推）：
#   文件名 = githup-v<版本>.apk       例：1.2.3 -> githup-v1.2.3.apk
#   包内 versionName = 同一个版本     例：1.2.3
#   两者永远一致，改一个数字就够了
#
# 用法：
#   bash build-apk.sh            # 用 build.gradle 里现有的版本打包
#   bash build-apk.sh 1.2.4      # 三段式：文件名 githup-v1.2.4.apk，版本 1.2.4
#   bash build-apk.sh V1         # V 系列：文件名 githup-V1.apk，版本 V1（V2、V3 以此类推）
#
# 打包完成后，工作区里旧的 githup-v*.apk 会被清掉，只留最新这一个，
# 免得出现一堆名字相近的文件分不清哪个是新的。
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PRJ="$ROOT/github-mobile"
GRADLE_CFG="$PRJ/app/build.gradle"
API_JS="$PRJ/app/src/main/assets/web/js/api.js"
OUT="$ROOT"

export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
GRADLE=/opt/gradle-8.2/bin/gradle

NEW="$1"

# 1. 指定了版本就先把源码里的版本号改掉（build.gradle + 前端常量，两处同步）
# V 系列代号从 1.2.3 的代号往下加，保证 versionCode 一直是递增的，
# 否则手机上已装的新版本会拒绝安装旧代号（降级安装直接失败）。
BASE_CODE=10203

if [ -n "$NEW" ]; then
  if echo "$NEW" | grep -qE '^[Vv][0-9]+$'; then
    N=$(echo "$NEW" | tr -d 'Vv')
    CODE=$((BASE_CODE + N))
  elif echo "$NEW" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    MAJ=$(echo "$NEW" | cut -d. -f1)
    MIN=$(echo "$NEW" | cut -d. -f2)
    PAT=$(echo "$NEW" | cut -d. -f3)
    CODE=$((MAJ * 10000 + MIN * 100 + PAT))   # 1.2.3 -> 10203，保证版本越大代号越大
  else
    echo "版本号格式不对：要像 1.2.3（三段数字）或 V1 这样"; exit 1
  fi

  sed -i -E "s/versionCode [0-9]+/versionCode $CODE/" "$GRADLE_CFG"
  sed -i -E "s/versionName '[^']*'/versionName '$NEW'/" "$GRADLE_CFG"
  sed -i -E "s/APP_VERSION: '[^']*'/APP_VERSION: '$NEW'/" "$API_JS"
  echo "版本已改为 $NEW（versionCode $CODE）"
fi

# 2. 从 build.gradle 读回版本，文件名跟着它走
VER=$(grep -oE "versionName '[^']+'" "$GRADLE_CFG" | head -1 | sed "s/versionName '//;s/'//")
[ -n "$VER" ] || { echo "读不到 versionName"; exit 1; }
# 版本本身已经带 V 就不重复加小写 v：V1 -> githup-V1.apk，1.2.3 -> githup-v1.2.3.apk
if echo "$VER" | grep -qE '^[Vv]'; then NAME="githup-$VER.apk"; else NAME="githup-v$VER.apk"; fi
echo "文件名: $NAME"

# 3. 构建
cd "$PRJ"
"$GRADLE" assembleRelease --console=plain -q

# 4. 输出到工作区，并清掉同名的旧包
SRC="$PRJ/app/build/outputs/apk/release/app-release.apk"
[ -f "$SRC" ] || { echo "没找到构建产物"; exit 1; }
rm -f "$OUT"/githup-[vV]*.apk   # 大小写都清，只留最新这一个
cp -f "$SRC" "$OUT/$NAME"

echo
echo "完成: $OUT/$NAME"
ls -l "$OUT/$NAME"
sha256sum "$OUT/$NAME"
