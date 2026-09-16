#!/usr/bin/env bash
# githup 一键打包脚本
#
# 用法：
#   bash build-apk.sh              用 build.gradle 里现有的版本打包
#   bash build-apk.sh 1.1.1        三段式：文件名 githup-v1.1.1.apk，版本 1.1.1
#   bash build-apk.sh V4           代号式：文件名 githup-V4.apk，版本 V4
#   bash build-apk.sh V4 1.1.1     文件名用代号、内部版本另填（本次 V4 就是这么来的）
#   bash build-apk.sh V6 1.1.1 1001003
#                                  第三个参数显式指定 versionCode。版本号需要往回
#                                  调时（如从 1.1.2 回到 1.1.1）必须用它：versionCode
#                                  只许变大不许变小，否则手机拒绝覆盖安装。Android
#                                  允许 versionName 与 versionCode 不联动，所以
#                                  「对外显示 1.1.1 + 内部号 1001003」完全合法。
#
# 版本号的规矩（x.y.z）：
#   第一位 —— 变大就强制更新（客户端会弹不可关闭的更新提示）
#   第二位 —— 功能更新，可更可不更
#   第三位 —— 修复更新，可更可不更
#
#
# ⚠️ 版本锁定规则（用户明确要求，勿擅自更改）：
#   版本号固定为 1.1.1，versionCode 固定为 1001001。
#   后续无论改什么代码，都不要再往上加版本号 —— 直接按本版重打包。
#   这么做的前提是：安装设备上已装版本 ≤ 1001001 才能覆盖安装。
#
# versionCode 的换算规则（保留以防将来需要打破锁定）：
#   三段式 x.y.z  ->  x*1000000 + y*1000 + z     例：1.1.1 -> 1001001
#   代号式 Vn     ->  10203 + n                  例：V3    -> 10206
#
# 打包完成后，工作区里旧的 githup-*.apk 会被清掉，只留最新这一个，
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

CODE_ARG="$1"      # 决定文件名的代号（没给第二个参数时，它同时也是版本号）
VER="$2"           # 可选：内部版本号，缺省就用代号
CODE_NUM="$3"      # 可选：显式 versionCode，缺省按版本号换算

BASE_CODE=10203    # V 系列的基点

# 1. 算出 versionCode
code_of() {
  local v="$1"
  if echo "$v" | grep -qE '^[Vv][0-9]+$'; then
    echo $((BASE_CODE + $(echo "$v" | tr -d 'Vv')))
  elif echo "$v" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    local maj min pat
    maj=$(echo "$v" | cut -d. -f1)
    min=$(echo "$v" | cut -d. -f2)
    pat=$(echo "$v" | cut -d. -f3)
    echo $((maj * 1000000 + min * 1000 + pat))
  else
    echo "版本号格式不对：要像 1.1.1（三段数字）或 V4 这样" >&2; exit 1
  fi
}

if [ -n "$CODE_ARG" ]; then
  [ -n "$VER" ] || VER="$CODE_ARG"
  if echo "$CODE_NUM" | grep -qE '^[0-9]+$'; then
    CODE="$CODE_NUM"
    echo "版本: $VER（versionCode 显式指定为 $CODE）"
  else
    CODE=$(code_of "$VER")
    echo "版本: $VER（versionCode $CODE）"
  fi
  sed -i -E "s/versionCode [0-9]+/versionCode $CODE/" "$GRADLE_CFG"
  sed -i -E "s/versionName '[^']*'/versionName '$VER'/" "$GRADLE_CFG"
  sed -i -E "s/APP_VERSION: '[^']*'/APP_VERSION: '$VER'/" "$API_JS"
  echo "版本已改为 $VER（versionCode $CODE）"
fi

# 2. 读回版本；文件名由第一个参数（代号）决定
BUILD_VER=$(grep -oE "versionName '[^']+'" "$GRADLE_CFG" | head -1 | sed "s/versionName '//;s/'//")
[ -n "$BUILD_VER" ] || { echo "读不到 versionName"; exit 1; }
[ -n "$VER" ] || VER="$BUILD_VER"

# 代号本身带 V 就不重复加小写 v：V4 -> githup-V4.apk，1.1.1 -> githup-v1.1.1.apk
if echo "$CODE_ARG" | grep -qE '^[Vv]'; then NAME="githup-$CODE_ARG.apk"
elif echo "$VER" | grep -qE '^[Vv]'; then NAME="githup-$VER.apk"
else NAME="githup-v$VER.apk"; fi
echo "文件名: $NAME（包内版本 $VER）"

# 3. 构建
cd "$PRJ"
"$GRADLE" assembleRelease --console=plain -q

# 4. 输出到工作区，并清掉旧的包，只留最新这一个
SRC="$PRJ/app/build/outputs/apk/release/app-release.apk"
[ -f "$SRC" ] || { echo "没找到构建产物"; exit 1; }
rm -f "$OUT"/githup-[vV]*.apk   # 大小写都清
cp -f "$SRC" "$OUT/$NAME"

echo
echo "完成: $OUT/$NAME"
ls -l "$OUT/$NAME"
sha256sum "$OUT/$NAME"
