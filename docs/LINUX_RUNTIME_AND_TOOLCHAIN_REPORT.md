# Báo cáo môi trường Linux runtime & điều tra toolchain build APK

**Ngày:** 2026-11-21
**Phạm vi:** runtime Termux-like của `com.opencode.android`, JDK/Gradle/AAPT2, môi trường C/C++ trên thiết bị, và giới hạn địa chỉ prefix 31 byte.
**Cách đọc:** `[XN]` = đã xác nhận bằng file trên máy/mã nguồn; `[GT]` = giả thuyết, cần chạy trên thiết bị để khẳng định.

> **Giới hạn kiểm chứng:** agent chỉ đọc/ghi file được; tool `shell` trả `unsupported request` do **capability gap của helper PTY** (`native/pty-c/opencode-pty.c:1435` từ chối `create_pipe`/`signal`/`set_metadata`), **không phải thiếu quyền** — phân tích đầy đủ tại `docs/OPENCODE_SHELL_CAPABILITY_REPORT.md`. Vì vậy phần lớn báo cáo này là điều tra tĩnh + ảnh chụp state thực tế của app; các lệnh kiểm chứng do người dùng chạy trong Terminal panel và paste output (mục 2.5). Đây cũng là lý do docs cũ (`TERMUX_DAEMON_AND_API.md:632-635`, `LIBRARY_FIXUPS.md:223`) để mục "JDK chạy được" ở trạng thái **chưa kiểm chứng trên máy**.

---

## 1. Ảnh chụp hiện trạng

### 1.1 Runtime đang chạy

| Mục | Giá trị | Bằng chứng |
| --- | --- | --- |
| ABI | `arm64` | `$OC_HOME/runtime.json:3` |
| Application id | `com.opencode.android` | `app/build.gradle.kts:74` |
| `targetSdk` | 28 (cố ý, để `execve()` trong app data) | `app/build.gradle.kts:87` |
| `versionCode` / `versionName` | **31** / 1.0.30 | `app/build.gradle.kts:88-89` |
| `PREFIX` | `/data/data/com.opencode.android/files/usr` | `OpenCodeBootstrap.kt:81` |
| `DATA_DIR` | `/data/data/com.opencode.android` (**31 ký tự**) | `OpenCodeBootstrap.kt:89` |
| `HOME` | `/data/data/com.opencode.android/files/home` | `OpenCodeBootstrap.kt:98` |
| OpenCode binary | `opencode-v2.3.2` | `runtime.json:12`, `$OC_HOME/.native-ready` |
| Loader | `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` | `runtime.json:17` |
| Pathmap preload | `$PREFIX/lib/liboc-pathmap.so` | `runtime.json:18` |
| Server port | 49374 | `runtime.json:4` |

Hai con số **31 dễ nhầm**: (1) độ dài `/data/data/<applicationId>` mà relocation yêu cầu, (2) `versionCode = 31` của APK. Chúng trùng nhau nhưng **không liên quan**; không có con số 32 nào trong cơ chế prefix (32 chỉ xuất hiện ở socket name 32 hex và ngưỡng 32 phantom process).

### 1.2 Gói đã cài trên máy này `[XN]`

`$PREFIX/.packages-installed` có 31 dòng: `libandroid-support, libiconv, ncurses, readline, bash, pcre2, libandroid-selinux, libc++, libgmp, ca-certificates, zlib, openssl, coreutils, linux-api-headers-glibc, glibc, grep, sed, libmpfr, gawk, findutils, diffutils, ripgrep, libnghttp2, libnghttp3, libngtcp2, libssh2, libcurl, libexpat, less, git, gh`.

**Không có:** `openjdk-*`, `aapt2`, `ca-certificates-java`, `clang`, `make`, `cmake`, `ninja`, `pkg-config`, `d8`, `apksigner`.

Hệ quả trực tiếp:
- `$PREFIX/bin` có `bash/git/gh/rg/pkg/oc-run/opencode/...` nhưng **không có** `java`, `javac`, `aapt2`, `cc`, `clang`, `make`, `cmake`, `pkg-config`.
- `$PREFIX/lib/jvm` **không tồn tại** → `OpenCodeBootstrap.jdkHome()` trả `null`.
- `$PREFIX/include` đã có header (openssl, curl, zlib, pcre2, gmp, mpfr, ncurses, expat, libssh2, ...) và `$PREFIX/lib/pkgconfig` đã có ~27 file `.pc`, nhưng **thiếu binary `pkg-config`** để tiêu thụ chúng.

### 1.3 Env script thực tế `[XN]`

`$PREFIX/etc/opencode-env.sh` (package environment v5) đang export:

```sh
export JAVA_TOOL_OPTIONS='-Duser.home=.../files/home -Djava.io.tmpdir=.../usr/tmp -Drobolectric.conscryptMode=OFF'
export LD_LIBRARY_PATH='/data/data/com.opencode.android/files/usr/lib'
export PATH='/data/data/com.opencode.android/files/usr/bin:/system/bin'
# KHÔNG có JAVA_HOME, KHÔNG có $JAVA_HOME/lib/server
```

`runtime.json:19-21` cũng ghi `libraryPath` chỉ gồm `$PREFIX/lib`. Đối chiếu `RuntimeContract.kt:308-316`: `lib/server` và `lib` của JDK chỉ được thêm khi `inputs.javaHome != null`. Nói cách khác **env được sinh ra ở trạng thái "chưa có JDK"** — đúng với danh sách gói ở trên.

### 1.4 Native/C assets `[XN]`

| Thành phần | Nơi build | ABI/liên kết |
| --- | --- | --- |
| `libopencode_pty.so` (terminal trong app) | Gradle + CMake/NDK `27.2.12479018`, `app/src/main/cpp/CMakeLists.txt` | bionic, C++17, chỉ bật khi có NDK |
| `opencode-pty` (terminal web) | `native/pty-c/build.sh`, NDK clang API 28, CI `native.yml` | bionic, pure C |
| `oc-bridge` (`pkg`) | Kotlin/Native `androidNativeArm64/X64` | bionic |
| `oc-run`, pathmap, gopatch | app/CI | glibc preload + Go static patch |

Cả bốn thành phần **được build ở CI/host, không build trên thiết bị**. Đây là điểm mấu chốt cho phần C/C++ ở mục 4.

---

## 2. Điều tra: "cài Java để build APK hoạt động không chính xác"

### 2.1 Kết luận ngắn

Triệu chứng chưa được mô tả cụ thể, nhưng có **một nguyên nhân đã xác nhận** và **năm rủi ro có cơ sở mã nguồn**. Nguyên nhân xác nhận: trên máy này JDK chưa hề được cài, nên mọi lệnh Java/Gradle đều không thể chạy — không phải lỗi patch ELF. Phần còn lại là các bẫy sẽ xuất hiện ngay sau khi cài JDK nếu không sửa env/alias.

### 2.2 Bảng nguyên nhân

| # | Mức | Nguyên nhân | Bằng chứng / cơ chế | Triệu chứng điển hình |
| --- | --- | --- | --- | --- |
| J1 | `[XN]` | **JDK chưa được cài** | `.packages-installed` không có `openjdk-*`; `bin/` không có `java`; env không có `JAVA_HOME` | `java: not found`, `./gradlew: JAVA_HOME not set` |
| J2 | `[XN]` — **đã kiểm chứng, rủi ro thấp** | **Alias gói `openjdk` → `openjdk-17` vẫn hợp lệ** | Tải index `Packages` chính thức `https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages` (21/11/2026, 3040 gói): `openjdk-17 17.0.21.1` (96.3 MB), `openjdk-21 21.0.13.1`, `openjdk-25 25.0.5.1`, đều kèm biến thể `-x`. `openjdk-17` tồn tại và được cập nhật 18/11/2026 → alias trong `Main.kt:304` không sai | Không còn nghi ngờ "package not found"; nhưng cần lưu ý J8 |
| J3 | `[XN]` cơ chế / `[GT]` triệu chứng | **`JAVA_HOME` và `lib/server` bị snapshot theo thời điểm sinh env** | `RuntimeInputs.javaHome = javaHome()` (`OpenCodeBootstrap.kt:1061`), `RuntimeEnv.build` chỉ thêm JDK khi khác null (`RuntimeContract.kt:263,276,309-312`) | Cài JDK xong mà shell cũ vẫn `JAVA_HOME=''` → `java` chết hoặc `java -version` bị hiểu sai |
| J4 | `[XN]` cơ chế | **Thứ tự `LD_LIBRARY_PATH` của JDK** | `libjli` re-exec `/proc/self/exe` nếu `$JAVA_HOME/lib/server` không đứng đầu (`LIBRARY_FIXUPS.md:93-96`). Env hiện tại chỉ có `$PREFIX/lib` | `-version` bị xem như tên file/class; log lạ từ libjli |
| J5 | `[XN]` — **đã loại trừ** | SIGSYS/seccomp của JVM | Cơ chế vẫn đáng biết (Android chặn `close_range`, `landlock...`, `clone3`), nhưng `java -version` thật **exit=0** trên máy → JVM không dính | Không còn là nguyên nhân (xem 2.5) |
| J6 | `[XN]` — **đã xong** | aapt2 / chuỗi AGP | `aapt2 16.0.0.4-4` đã cài, override `android.aapt2FromMavenOverride` đã ghi; `jssecacerts` vắng nhưng Termux dùng `cacerts` symlink sang `$PREFIX/etc/tls/certs/java/cacerts` | Không còn là nguyên nhân (xem 2.5) |
| J7 | `[XN]` thiết kế | **`JAVA_TOOL_OPTIONS` của người dùng bị ghi đè** | `javaToolOptions()` đọc `System.getenv` của **tiến trình app** (`OpenCodeBootstrap.kt:1101-1115`), rồi `RuntimeContract` export cứng giá trị đó (`RuntimeContract.kt:277`); comment "existing options are kept" chỉ đúng với env của app, **không đúng với shell** | Flag tự thêm trong terminal bị mất sau `. opencode-env.sh` |
| J8 | `[XN]` tài liệu cũ | **Docs ghi sai cấu trúc dependency/trust store của JDK** | `docs/TERMUX_DAEMON...:435-437` nói `ca-certificates-java` là *Recommends* và tạo `jssecacerts`; index thật để nó ở **Depends**, còn `Recommends` là `openjdk-17-x, resolv-conf`. `openjdk-17/build.sh` của Termux thay `cacerts` bằng symlink tới `$PREFIX/etc/tls/certs/java/cacerts` (đã kiểm chứng tồn tại) | Không gây lỗi cài/HTTPS; chỉ làm docs lỗi thời |

### 2.3 Chuỗi điều kiện để `java -version` chạy được

```text
pkg install openjdk-17
  → tên gói tồn tại trong index                 (J2)
  → extract + symlink bin/* bởi configureJdk()   (OpenCodePackages.kt:1333-1352)
  → ca-certificates-java + aapt2 được kéo       (J6)
  → maintenance viết lại opencode-env.sh         (J3)
  → shell MỚI/source lại env                     (J3)
  → JAVA_HOME + $JAVA_HOME/lib/server đầu LD_LIBRARY_PATH (J4)
  → JVM không bị SIGSYS, hoặc chạy qua oc-run    (J5)
  → java -version / javac -version / ./gradlew
```

Thiếu bất kỳ mắt xích nào cũng cho cảm giác "cài Java nhưng build APK vẫn lỗi" — đúng với mô tả của bạn.

### 2.4 Đề xuất sửa (chưa áp dụng)

1. **Alias: giữ nguyên.** Index chính thức xác nhận `openjdk-17` còn maintained (J2), nên `aliasFor()` hiện tại đúng. Chỉ cân nhắc cho phép chọn `openjdk-21/25` sau khi đã kiểm chứng JVM chạy được trên 17.
2. **Env Java tự lành:** `configureJdk()` sau khi tạo symlink phải gọi lại `writePackageEnvironment()` (hoặc đánh dấu contract stale) để `JAVA_HOME`/`lib/server` xuất hiện ngay; đồng thời hướng dẫn `. "$PREFIX/etc/opencode-env.sh"` trong mục Java của README.
3. **Shell giữ flag người dùng:** chuyển dòng export `JAVA_TOOL_OPTIONS` thành dạng nối `${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}` trong `RuntimeShellScript`, hoặc đọc biến từ shell khi render.
4. **Thêm proof binary Java:** mở rộng `BINARY_PROOF` (`OpenCodePackages.kt:138-142` mới chỉ có `lib/jvm/.../bin/java`) với `javac`, `aapt2`, `jssecacerts` để phát hiện cài thiếu.
5. **Kiểm chứng SIGSYS:** chạy cả `java -version` và `oc-run -- java -version`; nếu bản thường chết mà bản `oc-run` chạy, sinh wrapper `bin/java` → `oc-run -- <jdk>/bin/java "$@"` hoặc bổ sung policy SIGSYS cho JVM.

**Script chạy ngay để thay thế phỏng đoán bằng số liệu:** `docs/scripts/java-install-diagnose.sh`. Chạy trong terminal của app:

```sh
sh "$HOME/OpenCodeForAndroid/docs/scripts/java-install-diagnose.sh"
```

Script cài `openjdk-17`, nạp lại env, in `java -version` / `javac -version` / `oc-run java -version` kèm exit code, trạng thái symlink, `cacerts`, `aapt2` và Gradle override.
(Lưu ý đã sửa: `oc-run` tự chèn `--`, nên gọi `oc-run java -version`; viết `oc-run -- java -version` tạo `--` trùng và bị `oc-gopatch: --: executable file not found in $PATH`.)

### 2.5 Kết quả kiểm chứng thực tế (21/11/2026, Android 13 / aarch64)

Script đã được chạy trên thiết bị. Kết luận **đã chốt**, không còn là phỏng đoán:

| Mục | Kết quả | Đánh giá |
| --- | --- | --- |
| Cài đặt | `pkg install -y openjdk-17` exit=0; kéo đủ 30 dependency (gồm `alsa-lib → pulseaudio`, `libX11`, `abseil-cpp` cho aapt2) | J1/J2/J6 (cài) **xong, đúng** |
| Env sau `. opencode-env.sh` | `JAVA_HOME=.../java-17-openjdk`; `LD_LIBRARY_PATH` = `lib/server : lib : $PREFIX/lib`; `PATH` có `$JAVA_HOME/bin` | J3/J4 **không còn lỗi** sau khi nạp env |
| `java -version` | `openjdk version "17.0.21.1" 2026-11-17`, **exit=0** | JVM chạy trực tiếp, **không dính SIGSYS** → J5 loại trừ |
| `javac -version` | `javac 17.0.21.1`, **exit=0** | Compiler OK |
| `oc-run -- java -version` | exit=1, `oc-gopatch: --: executable file not found in $PATH` | **Lỗi của script diagnostic**, không phải lỗi app: wrapper `oc-run` (`OpenCodeBootstrap.kt:2707`) đã truyền `--`, gopatch (`main.go:58-60`) bỏ một `--`, nên user truyền thêm trở thành lệnh `--`. Đã sửa script; đề xuất hardening: `installOcRun()` bỏ một `--` thừa từ `$@` |
| `jssecacerts` | Không tồn tại — nhưng đây **không phải lỗi** | Tài liệu cũ lỗi thời: `openjdk-17/build.sh` của Termux `rm cacerts` rồi `ln -s $PREFIX/etc/tls/certs/java/cacerts`; file đích tồn tại và symlink đọc được (keystore nhị phân). Java dùng `cacerts` này → HTTPS/Gradle có CA hệ thống |
| `aapt2` | `bin/aapt2` 3,59 MB, override `android.aapt2FromMavenOverride` đã có trong `~/.gradle/gradle.properties` | J6 **xong** |
| `ANDROID_HOME` | `<unset>`; `$HOME/Android/Sdk`, `$HOME/android-sdk`, `$HOME/sdk`, `$PREFIX/opt/android-sdk`, `$PREFIX/share/android-sdk` đều không có SDK | **Đây mới là lý do build APK không chạy**: Java ổn, nhưng `sdkmanager`/Gradle không có SDK. Cần cài cmdline-tools + platform/build-tools rồi `pkg repair "$ANDROID_HOME"` để rewrite shebang |

**Kết luận cuối:** cài Java trong app này **hoạt động đúng**. Triệu chứng "build APK lỗi" không nằm ở JDK/aapt2/relocation 31 byte mà ở **thiếu Android SDK** (và, nếu có SDK, cần `./gradlew --stop` để daemon cũ nhặt env mới). Việc cần sửa trong code chỉ còn: (a) làm env tự refresh ngay trong shell hiện tại thay vì bắt user source, (b) bỏ `--` thừa trong `oc-run` cho thân thiện, (c) cập nhật docs về `cacerts` thay vì `jssecacerts`.

---

## 3. Vấn đề prefix 31 ký tự (điểm bạn nghi ngờ)

### 3.1 Cơ chế hiện tại `[XN]`

- Termux nhúng cứng `/data/data/com.termux/files/usr` (**31 byte**) vào rodata của mọi `.so`/binary.
- App thay byte-for-byte chuỗi đó bằng `/data/data/com.opencode.android` (**cũng 31 byte**) — `relocateLoader()` (`OpenCodeBootstrap.kt:3016-3058`), `relocateGlibc()` (`:3095-3158`), `patchFileStrings()` (`:3175-3193`).
- Vì thay cùng độ dài, **không offset ELF nào dịch chuyển**. Đây là lý do duy nhất của constraint 31.
- Đích thay là **data root**, không phải `$PREFIX`, nên mọi path biên dịch `…/files/usr/bin/bash` trở thành `<dataDir>/bin/bash`. Muốn path đó tồn tại phải tạo alias symlink cho **từng thư mục con cấp 1** của prefix (`ensurePathAliases()`, `:2967-2990`). Trên máy này data root đã có: `bin data etc glibc include lib libexec run share tmp`.
- Gradle `require(sandboxPath.length == 31)` (`app/build.gradle.kts:76-83`) và kiểm tra `applicationIdSuffix` (`:143-152`) biến điều kiện ngầm thành lỗi build ngay tại configuration time.

### 3.2 Điểm giòn

| # | Vấn đề | Tác động |
| --- | --- | --- |
| P1 | Khoá `applicationId` | Không build được fork/flavor/debug id khác; mọi thử nghiệm multi-id bị chặn ở Gradle |
| P2 | Alias "mỗi thư mục cấp 1" | Gói cài sau tạo thư mục cấp 1 mới (vd `opt/`, `man/`) thì compiled-in path trỏ vào data root bị hỏng cho đến khi chạy maintenance |
| P3 | Chỉ sửa đúng một spelling | `/data/user/0/...` (33 ký tự, thường là giá trị `context.dataDir` trên đa user) và dạng bỏ dấu `/` ở đầu không được xử lý; `rewriteTextPaths()` (`:2009-2017`) chỉ thay `PREFIX_TERMUX` + `HOME_TERMUX` |
| P4 | Replacement dài hơn bị **bỏ qua có cảnh báo** (`:3184-3188`) | Relocation có thể "thành công một phần" mà không fail build — rất dễ gây lỗi DNS/resolver kiểu `getaddrinfo` như README đã từng gặp |
| P5 | Thay byte mù trong rodata | Lý thuyết có thể sửa cả chuỗi không phải path nếu trùng pattern (rất thấp, chưa từng thấy) |
| P6 | Phụ thuộc symlink ở data root | Các tên `bin`, `etc`, `lib`... cạnh `cache/`, `databases/` của Android; đã có guard `SANDBOX_RESERVED_ENTRIES` nhưng vẫn là bề mặt dễ va chạm khi app mở rộng |

**Đánh giá:** constraint 31 **không sai về mặt kỹ thuật** — nó là một trade-off hợp lý để đổi lấy relocation không cần sửa ELF. Cái "không ổn" là nó bị **hardcode ở 3 nơi** (Gradle, constants Kotlin, alias logic) và **fail âm thầm** thay vì fail rõ ràng; nó cũng khoá luôn khả năng đổi applicationId.

### 3.3 Phương án

| Phương án | Mô tả | Ưu / Nhược | Đề xuất |
| --- | --- | --- | --- |
| **A. Giữ 31, làm data-driven** | Tính `TERMUX_PREFIX`, `DATA_DIR` từ `context.dataDir`; một hằng `RELOCATION_REQUIRES_EQUAL_LENGTH`; self-test kiểm tra mọi alias sau maintenance; P4 đổi thành throw | Rẻ, ổn định, nhưng vẫn cấm đổi id | **Làm ngay (P1)** |
| **B. Mở rộng ELF kiểu `gopatch`** | Dùng cơ chế append + sửa `p_offset`/`p_filesz` đã có trong `native/gopatch/patch.go:265-315` để thay chuỗi 31 byte bằng đường dẫn **tuỳ độ dài** (vd `$PREFIX` thật hoặc `/data/user/0/...`), rồi bỏ `require(... == 31)` | Bỏ hẳn constraint, hỗ trợ multi-user; nhược: phải test kỹ mọi ABI/ELF, file tăng vài chục byte, mất tính "in-place" | **P2, nếu cần đổi id hoặc hỗ trợ `/data/user/0`** |
| **C. Giảm patch, tăng pathmap** | Chỉ patch `PT_INTERP`; mọi path còn lại nhờ `liboc-pathmap.so` + `OC_PREFIX` | Hợp lý cho binary glibc, **không áp được cho toolchain bionic** (pathmap build bằng glibc, không preload vào clang bionic được) | Chỉ bổ trợ |
| **D. Alias FD độ dài cố định (đã triển khai)** | Patch chuỗi của `libandroid-posix-semaphore.so` thành `/proc/self/fd/9/` + 24 dấu `/` (đúng 40 byte); `pathmap`, `opencode-pty`, `oc-launcher` và JNI `pty.cpp` giữ FD 9 mở tới thư mục semaphore thật | Thoát constraint 31 cho trường hợp này mà không sửa ELF hay đổi applicationId; cần giữ descriptor qua `execve` (không đặt `FD_CLOEXEC`) | **Đã làm** |

> Ghi chú sửa lại: kết luận cũ cho rằng "không có đường dẫn ngắn nào app ghi được" chỉ đúng với đường dẫn **tĩnh**. Với `/proc/self/fd/N`, một descriptor mở tới thư mục thật cho phép thay toàn bộ prefix bằng alias mà vẫn giữ nguyên offset ELF. Đo thực tế: `TERMUX_TMP` 40 byte; `libandroid_sem_open()` copy đúng 40 byte rồi nối tên ở byte 40, nên replacement **phải dài đúng 40** (dấu `/` thừa được kernel gộp lại). Một alias 20 byte từng làm `Pool` fail `FileExistsError` vì tên bị NUL chặn — đã sửa. `patch()` trả `1` cho mọi prefix, kể cả `/data/user/10/...` (43 byte) vốn trước đó trả `0`. Cơ chế FD chỉ áp dụng cho semaphore; các relocation 31 byte còn lại vẫn theo phương án A.

### 3.4 Kiểm chứng hướng D [XN]

- Trước sửa, harness đo trực tiếp `patch()` trên thiết bị: root 31 byte → `patch=1` (vừa khít 40 byte), root 34 byte (`/data/user/10/...`) và 52 byte → `patch=0`.
- Sau sửa, `replacement()` trả alias 40 byte độc lập prefix; unit test `patchWorksForWorkProfileAndLongPackageNames` mô phỏng đúng cơ chế nối tên của library (`prefix + "mp-abcdef"`) với prefix 34/52 byte, kiểm tra `patch=1`, tên không bị mất và file giữ nguyên kích thước.
- Smoke test trên thiết bị sau `pkg install python`: thư viện vừa tải tự động được patch; `_multiprocessing.SemLock` tạo file qua alias; `mp.Semaphore`, `mp.Pool(2).map(...)` chạy OK.
- `RuntimeContractTest.semaphoreDirectoryFollowsTheInstallPathInEveryEnvironment` kiểm chứng `OC_SEMAPHORE_DIR` được export sang contract, shell script và spawn env, kể cả prefix work profile.
- `:app:testDebugUnitTest` trên thiết bị: **125 tests, 0 failures** (trước đó 123/1 fail chính vì ranh giới này).
- CI Debug build `44887151136`: success; native helper dựng cho `arm64-v8a`/`x86_64`, e2e pass, APK `OpenCodeForAndroid-20261121-0859-aba2e44-debug.apk`.
- [GT] Chưa có thiết bị cài thật ở work profile/package name dài để chạy Python `multiprocessing`; cơ chế mới được kiểm chứng bằng unit test, harness và cài đặt thật trên app release đang dùng root 31 byte.

### 3.5 Khuyến nghị

Làm A trước: gom hằng số relocation vào một nguồn, đổi cảnh báo P4 thành lỗi rõ ràng, thêm self-test alias vào `doMaintenance`, và ghi rõ trong README rằng 31 là hệ quả của in-place patch, không phải versionCode. Giữ B trong backlog như "lối thoát khi đổi applicationId".

---

## 4. Nghiên cứu môi trường C/C++ cho package Termux

### 4.1 Hiện trạng `[XN]`

- Prefix có header và file `.pc` của openssl, curl, zlib, pcre2, gmp, mpfr, ncurses, expat, libssh2, ... nhưng **không có compiler, không có `pkg-config`, không có `make`/`cmake`**.
- `pkg bundle dev` (`cli-bridge/.../Main.kt:78`, docs `TERMUX_DAEMON...:397`) đã liệt kê `clang make cmake ninja pkg-config autoconf automake libtool ...` — tức là **thiết kế đã có, nhưng chưa được cài mặc định và chưa được env hoá**.
- Công cụ native của app được build ở CI bằng NDK clang API 28 (`native/pty-c/build.sh:17-44`) và Kotlin/Native; không phụ thuộc compiler trên máy.

### 4.2 Khoảng trống cần lấp

| # | Khoảng trống | Hệ quả | Đề xuất |
| --- | --- | --- | --- |
| C1 | Không có meta bundle C/C++ được cài sẵn | `pkg install clang` rồi vẫn thiếu header `-dev`, `pkg-config`, `make` | Thêm `pkg bundle c` = `clang llvm make cmake ninja pkg-config autoconf automake libtool binutils` + `-dev` của lib đã có; giữ `dev` làm alias |
| C2 | Env không có biến biên dịch | `./configure`, `cmake`, `pkg-config` không thấy header/lib của prefix dù chúng có sẵn | Thêm (chỉ khi thư mục tồn tại) `CPATH=$PREFIX/include`, `LIBRARY_PATH=$PREFIX/lib`, `PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig`, `PKG_CONFIG_SYSROOT_DIR=$PREFIX`, `CMAKE_PREFIX_PATH=$PREFIX` |
| C3 | Contract export cứng, mất giá trị người dùng | Giống J7: user set `CFLAGS`/`PKG_CONFIG_PATH` bị ghi đè | Với nhóm biến "nối" (`CPATH`, `PKG_CONFIG_PATH`, `CMAKE_PREFIX_PATH`, `CFLAGS`, `LDFLAGS`) dùng append `${VAR:+$VAR:}` trong `RuntimeShellScript` thay vì export cứng |
| C4 | Xung đột `LD_LIBRARY_PATH` cho binary bionic | Đúng bug đã gặp ở `pkg`: bionic `clang/cc1/as/ld` nạp `libz`/`liblzma` của prefix → `cannot locate symbol ...` | Sinh wrapper `oc-cc`, `oc-cxx`, `oc-cmake`, `oc-pkg-config` dạng `#!/system/bin/sh` + `${PREFIX:?...}`, clear `LD_LIBRARY_PATH`/`LD_PRELOAD` trong lệnh (pattern `RuntimeShellScript.kt:45-51`) |
| C5 | `liboc-pathmap.so` là glibc | Không preload được vào toolchain bionic → không dùng pathmap để vá C/C++ bionic | Không dựa vào pathmap cho compiler; nếu cần map path, build thêm biến thể bionic bằng NDK clang |
| C6 | SIGSYS khi chạy `cc1`/`ld`/`collect2` | `Bad system call` như J5 | Test trên device; nếu dính, cho wrapper gọi qua `oc-run -- ...` hoặc mở rộng policy |
| C7 | Hai thế hệ libc | `clang` Termux sinh binary **bionic**; app lại có glibc runtime đã relocate. Không trộn `libc.so` linker script của glibc vào bionic linker (README đã ghi) | Tách rõ: toolchain mặc định = bionic; glibc chỉ dành cho binary có loader riêng. Ghi rõ trong docs, không hứa build glibc Linux bằng clang Termux |
| C8 | Chưa có smoke test | "Cài xong chạy được" mới chỉ là suy luận, giống JDK | Thêm script kiểm chứng ở mục 4.4 vào CI/docs |

### 4.3 Thiết kế đề xuất (tích hợp vào kiến trúc hiện tại)

```text
RuntimeInputs (OpenCodeBootstrap.kt)   ← resolve path C/C++ có điều kiện tồn tại
        ↓
RuntimeEnv.build (RuntimeContract.kt)  ← CPATH/LIBRARY_PATH/PKG_CONFIG_PATH/CMAKE_PREFIX_PATH
        ↓
RuntimeShellScript.render              ← append-only cho biến nối + wrapper oc-cc/oc-cxx/oc-pkg-config
        ↓
cli-bridge: pkg bundle c               ← cài đủ compiler + -dev + pkg-config
        ↓
OpenCodePackages.verify/repair         ← BINARY_PROOF: cc, c++, make, cmake, pkg-config
        ↓
smoke test trên device                 ← compile C, C++, pkg-config, cmake
```

Nguyên tắc thiết kế (theo `LIBRARY_FIXUPS.md:82-89,185`): **chỉ export biến trỏ tới path có thật**; wrapper launcher dùng `${PREFIX:?…}` để fail rõ ràng; không tạo bản sao env thứ hai ngoài contract.

### 4.4 Smoke test đề xuất (chạy trên thiết bị)

```sh
. "$PREFIX/etc/opencode-env.sh"

# C / C++
printf '#include <zlib.h>\nint main(void){return zlibVersion()[0]==1?0:1;}\n' > /tmp/oc_c.c
cc -O2 -Wall -Wextra "$PREFIX/include" -o /tmp/oc_c /tmp/oc_c.c -L"$PREFIX/lib" -lz
/tmp/oc_c && echo "C OK"

printf '#include <openssl/sha.h>\n#include <string>\nint main(){std::string s="ok";return s.size()==2?0:1;}\n' > /tmp/oc_cpp.cpp
c++ -std=c++17 -O2 -o /tmp/oc_cpp /tmp/oc_cpp.cpp -L"$PREFIX/lib" -lcrypto
/tmp/oc_cpp && echo "C++ OK"

pkg-config --cflags --libs openssl
cmake --version && make --version && ninja --version

# Java (đối chiếu mục 2)
pkg list-installed | grep -E 'openjdk|aapt2|ca-certificates-java'
java -version && javac -version
oc-run -- java -version        # phân biệt lỗi SIGSYS/path
grep -E 'JAVA_HOME|LD_LIBRARY_PATH' "$PREFIX/etc/opencode-env.sh"
```

---

## 5. Lộ trình đề xuất

| Ưu tiên | Việc | File dự kiến | Tiêu chí xong |
| --- | --- | --- | --- |
| P0 | ~~Xác nhận Java trên device~~ **ĐÃ XONG** (2.5): `java`/`javac` exit=0 | `docs/scripts/java-install-diagnose.sh` | Có log thật, các giả thuyết J1/J2/J5/J6 đã chốt |
| P0 | Bổ sung proof `aapt2` (bỏ `jssecacerts` khỏi docs) | `OpenCodePackages.kt`, docs | Phát hiện cài thiếu `aapt2` qua marker thay vì chỉ kiểm tra `java` |
| P0 | Refresh env ngay sau `configureJdk()` | `OpenCodePackages.kt`, `OpenCodeBootstrap.kt` | Không cần `. opencode-env.sh` thủ công trong shell hiện tại |
| P1 | Bỏ `--` thừa do wrapper `oc-run` truyền | `OpenCodeBootstrap.kt:2707` | `oc-run -- java -version` không còn báo `--: not found` |
| P0 | Hướng dẫn cài Android SDK (nguyên nhân thật của lỗi build APK) | README/docs | Có `ANDROID_HOME`, `pkg repair "$ANDROID_HOME"` chạy được |
| P1 | Giữ `JAVA_TOOL_OPTIONS` người dùng | `RuntimeContract.kt`, `RuntimeShellScript.kt` | Flag tự thêm không bị mất sau source env |
| P1 | Bundle C/C++ + env flags + wrapper | `Main.kt`, `RuntimeContract.kt`, `RuntimeShellScript.kt` | Smoke test C/C++ mục 4.4 đều OK |
| P1 | A: dọn hardcode 31 + self-test alias + fail rõ | `OpenCodeBootstrap.kt`, `app/build.gradle.kts` | Đổi `applicationIdSuffix` cho lỗi rõ thay vì relocation âm thầm |
| P1 | ~~D: alias FD cho semaphore~~ **ĐÃ XONG** (`2eadaed`, `aba2e44`) | `TermuxSemaphorePath.kt`, `native/*`, `app/src/main/cpp/pty.cpp` | `patch()` trả `1` cho prefix 34/52 byte; env export `OC_SEMAPHORE_DIR`; unit test xanh |
| P2 | B: ELF expansion kiểu gopatch cho các relocation 31 byte còn lại | `native/gopatch`, `OpenCodeBootstrap.kt` | Chạy được với id 32+ ký tự và `/data/user/0/...` ngoài semaphore |

---

## 6. Phụ lục — bằng chứng tham chiếu nhanh

- `app/build.gradle.kts:76-89,143-152` — constraint 31, versionCode 31, targetSdk 28.
- `app/src/main/kotlin/com/opencode/android/OpenCodeBootstrap.kt:81-95` — `PREFIX`, `DATA_DIR`, alias dirs.
- `…/OpenCodeBootstrap.kt:1044-1078,1101-1116,1152-1162` — runtime inputs, Java options, chọn JDK.
- `…/OpenCodeBootstrap.kt:2967-2990,3016-3058,3095-3158,3175-3193` — alias + relocation.
- `…/OpenCodeBootstrap.kt:2694-2719` — `oc-run -- <cmd>`.
- `app/src/main/kotlin/com/opencode/android/RuntimeContract.kt:259-316` — env, JDK library path.
- `app/src/main/kotlin/com/opencode/android/RuntimeShellScript.kt:16-58` — env v5, wrapper pattern.
- `app/src/main/kotlin/com/opencode/android/OpenCodePackages.kt:130-142,1333-1364` — proof binaries, `configureJdk`.
- `cli-bridge/src/nativeMain/kotlin/com/opencode/android/bridge/Main.kt:78,88,303-344` — bundle/alias Java.
- `docs/LIBRARY_FIXUPS.md:91-103,176-223` — JDK notes + đề xuất #4 "đổi thứ tự LD_LIBRARY_PATH".
- `docs/TERMUX_DAEMON_AND_API.md:388-445,622-635` — bundle, JDK, trạng thái chưa kiểm chứng.
- State trên máy: `$PREFIX/.packages-installed`, `$PREFIX/etc/opencode-env.sh`, `$PREFIX/bin`, `$PREFIX/lib/pkgconfig`, `$HOME/.opencode/runtime.json`, `$HOME/.opencode/.maintenance`, data root `/data/data/com.opencode.android`.
