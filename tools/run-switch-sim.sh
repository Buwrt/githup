#!/usr/bin/env bash
# 「明明在下却弹速度太慢」——新旧两版下载换道判定的对照验证。
#
# 做法：把当前工作树和 git HEAD（改动前）的 JsBridge 各编译一份，
#      交给 SwitchSim 用反射读真常量、跑同一批场景，打印对照表。
#
# 说明：这是一个**纯 Java 的单测**，不起模拟器、不装 APK。
#      只依赖 javac 和 android.jar（用来解析 android.* 符号）。
#
# 用法：bash tools/run-switch-sim.sh [OLD_REV]
#   OLD_REV 默认 HEAD（也就是本次改动提交之前的那版）

set -euo pipefail

OLD_REV="${1:-HEAD}"
SRC="app/src/main/java/com/hubmobile/app/JsBridge.java"
AJ="${ANDROID_HOME:-/opt/android-sdk}/platforms/android-34/android.jar"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ ! -f "$AJ" ]; then
  echo "找不到 android.jar：$AJ（可以 export ANDROID_HOME=... 指定）" >&2
  exit 1
fi

echo "== 取出改动前的源码树（$OLD_REV）=="
mkdir -p "$WORK/oldsrc"
git archive "$OLD_REV" app/src/main/java | tar -x -C "$WORK/oldsrc"

echo "== 编译两版 JsBridge =="
# BuildConfig 是 Gradle 生成的，本地补一个占位（与下载判定无关）；
# DEBUG_BUILD 是 githup 公开库里 SignCheck 用到的，一并写上。
mkdir -p "$WORK/stub/com/hubmobile/app"
cat > "$WORK/stub/com/hubmobile/app/BuildConfig.java" <<'EOF'
package com.hubmobile.app;
public final class BuildConfig {
    public static final boolean DEBUG = false;
    public static final boolean DEBUG_BUILD = false;
    public static final String APPLICATION_ID = "com.hubmobile.app";
    public static final String BUILD_TYPE = "release";
    public static final String VERSION_NAME = "1.1.5";
    public static final int VERSION_CODE = 1001005;
}
EOF

echo "== 编译当前（改后）版本 =="
javac -nowarn -encoding UTF-8 -cp "$AJ" -d "$WORK/new" \
      -sourcepath app/src/main/java \
      "$WORK/stub/com/hubmobile/app/BuildConfig.java" "$SRC"

# 旧源码要连根
javac -nowarn -encoding UTF-8 -cp "$AJ" -d "$WORK/oldcls" \
      -sourcepath "$WORK/oldsrc/app/src/main/java" \
      "$WORK/stub/com/hubmobile/app/BuildConfig.java" \
      "$WORK/oldsrc/$SRC"

echo "== 运行对照 =="
javac -nowarn -encoding UTF-8 -cp "$AJ" -d "$WORK/sim" tools/SwitchSim.java
java -Dfile.encoding=UTF-8 -cp "$WORK/sim" SwitchSim "$WORK/new" "$WORK/oldcls" "$AJ"
