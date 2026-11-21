#!/system/bin/sh
# Cài JDK Termux vào prefix của app và in đủ bằng chứng để phân tích chính xác.
#
# Chạy trong terminal của app (Terminal button trong OpenCode for Android):
#   sh "$HOME/OpenCodeForAndroid/docs/scripts/java-install-diagnose.sh"
#
# Không dùng root, không dùng apt/dpkg. Script chỉ gọi `pkg` bridge của app.
# Sau khi chạy, hãy copy TOÀN BỘ output gửi lại để đối chiếu với báo cáo
# docs/LINUX_RUNTIME_AND_TOOLCHAIN_REPORT.md.

PREFIX="${PREFIX:-/data/data/com.opencode.android/files/usr}"
HOME="${HOME:-/data/data/com.opencode.android/files/home}"
export PREFIX HOME

hr() { printf '\n===== %s =====\n' "$*"; }

hr "0. Thông tin môi trường"
id 2>&1
uname -a 2>&1 || true
printf 'PREFIX=%s\n' "$PREFIX"
printf 'HOME=%s\n' "$HOME"
printf 'PATH=%s\n' "$PATH"
printf 'LD_LIBRARY_PATH(truoc)=%s\n' "${LD_LIBRARY_PATH:-<unset>}"

hr "1. Trang thái truoc khi cài"
grep -E 'openjdk|aapt2|ca-certificates-java' "$PREFIX/.packages-installed" 2>/dev/null \
  || printf '(chưa có gói Java/aapt2 trong .packages-installed)\n'
ls -ld "$PREFIX/lib/jvm" 2>&1 || printf '(không có $PREFIX/lib/jvm)\n'
ls -l "$PREFIX/bin/java" "$PREFIX/bin/javac" 2>&1 || true

hr "2. Cài đặt openjdk-17 (bridge tự kéo ca-certificates-java + aapt2)"
printf 'Nếu lệnh báo "Bring OpenCode to the foreground", hãy mở app rồi chạy lại.\n'
pkg install -y openjdk-17 2>&1
printf 'pkg install exit=%s\n' "$?"

hr "3. Nạp lại env do app sinh"
if [ -r "$PREFIX/etc/opencode-env.sh" ]; then
  . "$PREFIX/etc/opencode-env.sh"
else
  printf 'KHÔNG đọc được %s/etc/opencode-env.sh\n' "$PREFIX"
fi
printf 'JAVA_HOME=%s\n' "${JAVA_HOME:-<unset>}"
printf 'LD_LIBRARY_PATH=%s\n' "${LD_LIBRARY_PATH:-<unset>}"
printf 'JAVA_TOOL_OPTIONS=%s\n' "${JAVA_TOOL_OPTIONS:-<unset>}"
printf 'PATH=%s\n' "$PATH"

hr "4. Symlink do configureJdk() tạo"
ls -l "$PREFIX/bin/java" "$PREFIX/bin/javac" 2>&1 || true
ls -l "$PREFIX"/lib/jvm/*/bin/java 2>&1 || true

hr "5. java -version"
java -version 2>&1
printf 'java exit=%s\n' "$?"

hr "6. javac -version"
javac -version 2>&1
printf 'javac exit=%s\n' "$?"

hr "7. So sánh: oc-run java -version (có patcher/pathmap)"
# oc-run đã tự chèn "--" khi gọi oc-gopatch; không truyền thêm "--" ở đây.
if command -v oc-run >/dev/null 2>&1; then
  oc-run java -version 2>&1
  printf 'oc-run java exit=%s\n' "$?"
else
  printf '(không có oc-run)\n'
fi

hr "8. Trust store / aapt2 / Gradle override"
ls -l "$PREFIX"/lib/jvm/*/lib/security/jssecacerts 2>&1 || true
ls -l "$PREFIX"/lib/jvm/*/lib/security/cacerts "$PREFIX/etc/tls/certs/java/cacerts" 2>&1 || true
keytool -list -cacerts -storepass changeit 2>&1 | head -5 || true
ls -l "$PREFIX/bin/aapt2" 2>&1 || true
grep -n 'aapt2FromMavenOverride' "$HOME/.gradle/gradle.properties" 2>/dev/null \
  || printf '(chưa có android.aapt2FromMavenOverride)\n'
printf 'ANDROID_HOME=%s\n' "${ANDROID_HOME:-<unset>}"

hr "9. Gợi ý đọc kết quả"
printf 'java exit=127  -> JDK chưa được cài/symlink (mục J1)\n'
printf 'java exit=159 / "Bad system call" -> SIGSYS (mục J5), thử oc-run ở mục 7\n'
printf 'JAVA_HOME=<unset> sau mục 3 -> env chưa refresh (mục J3)\n'
printf '"-version" bị coi như file -> thứ tự lib/server sai (mục J4)\n'
printf 'Nếu có SDK: ./gradlew --stop ; ./gradlew :app:assembleDebug --no-daemon --stacktrace\n'
printf 'Logcat: adb logcat -s OpenCodePackages OpenCodeBootstrap OpenCodeService\n'
