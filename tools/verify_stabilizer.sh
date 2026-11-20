#!/bin/sh
# 稳像数值回归：直接用 Gradle 那套源码跑 JVM 测试，不需要真机。
#
#   tools/verify_stabilizer.sh [数据目录，默认 /tmp/djmdtest]
#
# 数据目录里要放 djmd.bin（ffmpeg 抽的第 4 条轨）和 sizes.txt（每包字节数）：
#   ffmpeg -v error -i CAM_xxx.OSV -map 0:3 -c copy -f data djmd.bin
#   ffprobe -select_streams 3 -show_entries packet=size -of csv=p=0 CAM_xxx.OSV > sizes.txt
set -e
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
SRC=app/src/main/java/com/osmo/viewer
OUT=build/stabtest

[ -f "$ANDROID_JAR" ] || { echo "找不到 $ANDROID_JAR"; exit 2; }
"$JAVA_HOME/bin/javac" -nowarn -d "$OUT" -cp "$ANDROID_JAR" \
    "$SRC/Quat.java" "$SRC/ViewState.java" "$SRC/OsvMeta.java" \
    "$SRC/AttitudeTrack.java" "$SRC/Diag.java" "$SRC/LogFileProvider.java" \
    tools/StabilizerTest.java

exec "$JAVA_HOME/bin/java" -cp "$OUT:$ANDROID_JAR" com.osmo.viewer.StabilizerTest "$@"
