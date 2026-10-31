# Termux daemon, Termux:API và cài thư viện ngoài `com.termux`

Tài liệu nghiên cứu cho **OpenCode for Android** (`com.opencode.android`, prefix riêng,
`targetSdk = 28`). Ngày: 2026-10-29.

Mục tiêu:

1. Giữ `opencode serve` sống nền tốt hơn (daemon/supervisor).
2. Đánh giá việc dùng **Termux:API** cho wake lock, job scheduler, notification.
3. Xem cách hai repo tham chiếu cài thư viện/gói Termux khi **không** dùng package
   `com.termux`, và áp dụng cho repo này.

Nguồn đã đọc:

- `termux/termux-app` (TermuxService, SystemEventReceiver, PhantomProcessUtils)
- `termux/termux-tools` (`termux-wake-lock`, `termux-wake-unlock`)
- `termux/termux-services` (`service-daemon`, `sv-enable`, `start-services.sh`)
- `termux/termux-api`, `termux/termux-api-package` (receiver, JobScheduler, `termux-api.c`)
- `termux/termux-packages` (`termux-services`, `termux-api` build.sh)
- `agnostic-apollo/Android-Docs` — phantom/cached/empty processes
- `manhduonghn/opencode-termux`
- `Sanuu7/AndroidHarness` (`AgentService`, `BgProcessStore`, `LinuxEnvironment`)

---

## 0. Kết luận trước

| Câu hỏi | Kết luận |
| --- | --- |
| Dùng `termux-services`/runit làm daemon? | **Không phải giải pháp chính.** runit chỉ là supervisor *bên trong* process app; app chết là cả cây chết. Chỉ hữu ích khi cần chạy nhiều service (sshd, cron) và tự restart khi một service crash. |
| Dùng Termux:API? | **Không gọi trực tiếp được** từ `com.opencode.android`: plugin chạy `sharedUserId="com.termux"`, `exported=false`, kiểm tra UID + chữ ký. Chỉ nên **port pattern** sang Kotlin. |
| `termux-wake-lock` là gì? | Không phải termux-api. Nó là script trong `termux-tools` gửi `am startservice -a com.termux.service_wake_lock` cho `TermuxService`; app Termux tự bật `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF` và xin tắt battery optimization. |
| Cài thư viện ngoài `com.termux`? | Repo này **đã có** kỹ thuật đúng (tải `.deb`, verify SHA-256, extract, rewrite path). Giữ `targetSdk 28` để tránh W^X; nếu nâng target phải thêm `termux-exec` preload hoặc linker shim. |
| Điểm yếu lớn nhất (trước P0) | `OpenCodeService.onTaskRemoved()` từng gọi `stopSelf()` → `onDestroy()` → `stopServer()`, nên **vuốt app khỏi recents là server bị kill**. Đã sửa: service + server tiếp tục chạy, kèm watchdog và BootReceiver. |

---

## 1. Hiện trạng của app

Đã có (tốt):

- `OpenCodeService`: foreground service `specialUse`, notification ongoing (Open/Stop),
  `START_STICKY`, `PARTIAL_WAKE_LOCK` (bật/tắt trong Settings), adopt server cũ qua
  `$HOME/.opencode/server.pid` + quét `/proc`, restart khi binary đổi.
- `onCreate()` tự chạy bootstrap rồi start server; `onStartCommand(null)` (hệ thống restart
  service sau khi giết app process) cũng start server ngay khi runtime đã sẵn sàng.
- UI đã có nút xin **ignore battery optimizations**
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- `targetSdk = 28` → không bị giới hạn start FGS từ background (Android 12) và
  `execve()` từ app data dir vẫn được phép (Android 10+).

Còn thiếu (trước P0/P1):

| Thiếu | Hệ quả |
| --- | --- |
| Không có `WifiLock` | Wi-Fi có thể ngủ/giảm hiệu năng khi màn hình tắt, kết nối LLM chậm hoặc đứt. |
| `onTaskRemoved()` → `stopSelf()` | Vuốt recents = tắt server + notification. Termux không làm vậy. |
| Không có boot receiver | Không tự chạy lại sau reboot (kể cả khi user muốn). |
| Không có watchdog | Nếu process con bị phantom killer/CPU killer giết, không có gì khởi động lại đến khi user mở app. |
| Không phân biệt exit code 9 (SIGKILL) | Không hiển thị lý do "bị Android kill" để user xử lý. |
| Wake lock không timeout, không toggle từ notification | Giống Termux, nhưng thiếu công tắc nhanh; PIN wake lock vô hạn có thể drain pin. |
| Không có hướng dẫn phantom-process killer | Android 12+ giết tiến trình con mà wake lock không cứu được. |

Đã bổ sung trong P0/P1: WifiLock, bỏ `stopSelf()` khi task bị remove, BootReceiver, watchdog
JobScheduler, restart mọi exit bất thường + backoff, heartbeat 60 giây trong service, và dialog
hướng dẫn OEM/phantom trong Settings.
Phần chưa làm: toggle wake lock ngay trên notification và timeout theo session (P2).

---

## 2. "Daemon" trên Android thực chất gồm những gì

Không có systemd/init cho app thường. Một "daemon" sống nền cần 4 tầng độc lập:

```
1. Process number     → foreground service (FGS) + notification ongoing
2. CPU sống           → PARTIAL_WAKE_LOCK (+ WifiLock)
3. Quyền bị giết      → ignore battery optimizations, OEM autostart, phantom-proc flag
4. Supervisor         → tự start lại server khi crash; boot receiver; JobScheduler watchdog
```

Thiếu tầng nào thì tầng dưới cũng vô nghĩa:

- FGS không có wake lock → CPU ngủ, socket timeout.
- Wake lock không có FGS → app bị coi là background, bị kill.
- Có FGS + wake lock nhưng bị phantom killer → process con chết, app vẫn sống.
- Có tất cả nhưng không có supervisor → chết một lần là im lặng.

### 2.1. `termux-services` (runit) hoạt động thế nào

`pkg install termux-services` (depends: `runit`, `coreutils`, `dpkg`):

- `$PREFIX/etc/profile.d/start-services.sh` (được login shell source) chạy
  `service-daemon start`.
- `service-daemon` chạy `runsvdir $SVDIR` ở nền (`start-stop-daemon`), với
  `SVDIR=$PREFIX/var/service`, log ở `$PREFIX/var/log/sv/<service>/current`.
- Mỗi service là thư mục `$PREFIX/var/service/<name>/` có `run` (và `log/run`).
- `sv-enable <name>` = xoá file `down` + `sv up`; `sv-disable` = tạo `down`.
- `sv` cho phép `up/down/restart/status`, runit tự restart service khi process con chết.

**Giới hạn quan trọng:**

- Toàn bộ runit là **con của process app** (Termux app hoặc app này). Khi Android
  kill app, cgroup chết theo → runit không restart được gì. runit chỉ giải quyết
  "service crash", không giải quyết "app bị kill".
- Daemon tự `daemonize()` (ví dụ `sshd`) thoát khỏi app, nhận `init` làm parent,
  nhưng lại **dễ bị phantom killer giết hơn** vì không còn gắn với app (tài liệu
  phantom process của Termux nói rõ điều này).
- `termux-services` kéo theo `dpkg` (cho `start-stop-daemon`), trong khi prefix của
  repo này cố ý **không có dpkg/apt**.

**Kết luận:** có thể thêm `runit` như một tiện ích cho user (`pkg install runit`),
nhưng **không dùng làm cơ chế giữ sống chính**. Supervisor chính nên viết bằng Kotlin
trong FGS, vì nó biết trạng thái bootstrap, port, binary, notification.

### 2.2. Cách Termux giữ app sống

`TermuxService`:

- Là FGS với notification ongoing, có action **Exit** và **Wake lock/Unlock**.
- `actionAcquireWakeLock()`:
  - `PowerManager.PARTIAL_WAKE_LOCK` tên `<app>:service-wakelock`, `acquire()` **không timeout**;
  - `WifiManager.WIFI_MODE_FULL_HIGH_PERF` cùng lúc;
  - nếu chưa tắt battery optimization → `requestDisableBatteryOptimizations()`.
- Không implement `onTaskRemoved()` → **vuốt recents không dừng service**.
- `termux-wake-lock` / `termux-wake-unlock` chỉ là:
  ```sh
  am startservice --user "$TERMUX__USER_ID" \
     -a com.termux.service_wake_lock \
     com.termux/com.termux.app.TermuxService
  ```
  Nên đây là **termux-tools**, không phải Termux:API.
- Bản thân Termux **không** auto-start sau reboot; plugin `Termux:Boot` mới làm việc đó
  (BOOT_COMPLETED → `RUN_COMMAND`). `SystemEventReceiver` của Termux chỉ reset bộ đếm
  shell sau boot, không start service.

### 2.3. Cách AndroidHarness làm

- `AgentService`: FGS type `dataSync`, `startForeground`, wake lock **có timeout 12h**,
  3 notification channel (progress low / result default / action high), action
  approve-deny-answer qua `RemoteInput`, `START_NOT_STICKY` (chỉ chạy khi có task).
- `BgProcessStore` + Shizuku: `spawnDetached()` chạy process **trong Shizuku user service**
  (UID 2000/0), ghi log ra file, lưu `bg-processes.json`, kill theo process group +
  quét `/proc/*/fd`. Process này **sống sót khi app chết** — đây là "daemon thật" duy nhất
  không cần root mà vẫn tách khỏi app.
- Resume card, message queue bền, auto-continue: giải quyết "chết giữa chừng" ở tầng UX.
- Không dùng runit, không dùng Termux:API.

Điểm đáng học nhất: **wake lock chỉ giữ khi đang chạy** (12h cap) thay vì bật vĩnh viễn,
và **tách process bằng Shizuku** là option nâng cao cho user có Shizuku/root.

### 2.4. Giới hạn Android 12+: phantom process & CPU killer

Từ Android 12, hệ thống theo dõi **mọi process con fork từ app** và:

- giết khi tổng số phantom process **toàn hệ thống > 32** (không phải 32/app);
- giết process dùng CPU quá mức, kể cả khi app đang foreground/foreground service.

Wake lock, FGS, `targetSdk` **không** chống được. `opencode serve` là 1-2 process con
nên hiếm khi chạm ngưỡng 32, nhưng có thể chết vì CPU khi chạy task nặng.

Cách xử lý (theo tài liệu của Termux):

- Android 14+: Settings → System → Developer options → **Disable child process restrictions**.
- Android 12L/13+ (adb/root):
  ```sh
  adb shell settings put global settings_enable_monitor_phantom_procs false
  ```
- Android 12 (adb/root):
  ```sh
  adb shell device_config put activity_manager max_phantom_processes 2147483647
  adb shell settings put global device_config_sync_disabled 1   # nếu GMS ghi đè
  ```
- Kiểm tra: `adb shell dumpsys activity settings | grep max_phantom_processes`.
- Log khi bị giết: `Killing PhantomProcessRecord ... Trimming phantom processes`
  hoặc `excessive cpu`; process nhận **signal 9**.

App nên: phát hiện exit code 9/`signal 9`, hiển thị giải thích + nút copy lệnh adb,
và tự restart server (có backoff) thay vì im lặng.

---

## 3. Termux:API — vì sao không dùng trực tiếp được

### 3.1. Cơ chế

- Package `termux-api` cung cấp binary `termux-api` + các script `termux-*`.
- Binary thử kết nối **abstract Unix socket** của app plugin trên Android < 14; nếu không
  được (hoặc luôn luôn, trên Android ≥ 14) thì gọi `$PREFIX/bin/am broadcast`. Lý do của
  nhánh ≥ 14: app plugin có thể bị freeze nhưng socket vẫn "connectable", `read()` sẽ treo;
  `am broadcast` đánh thức được app.
- Intent mang `api_method`, tên `socket_input`/`socket_output` (abstract socket mà plugin
  mở); stdin/stdout của process được nối với socket đó, không truyền FD trực tiếp.
- App `com.termux.api` có `TermuxApiReceiver` với `android:exported="false"`.
- Manifest plugin khai báo `android:sharedUserId="com.termux"` → **cùng UID, cùng chữ ký**
  với app Termux chính. README của Termux:API ghi rõ: phải ký cùng key.
- Phía client còn kiểm tra `SO_PEERCRED` (`cred.uid == getuid()`), nên kể cả khi sửa
  exported cũng không đơn giản là broadcast tới.

Hệ quả với app này:

- `com.opencode.android` khác UID và khác chữ ký → **không thể** gọi plugin chính thức.
- Cài Termux:API cùng app này cũng vô dụng (plugin không thấy `com.termux` để share UID).
- Package `termux-api` còn depends `termux-am` (bridge cần Termux app), mà repo này
  cố ý bỏ các wrapper `termux-tools`/`termux-am`.

### 3.2. Ba lựa chọn

| Lựa chọn | Chi phí | Đánh giá |
| --- | --- | --- |
| **Port native vào app** | Thấp, chỉ vài API cần | **Khuyến nghị.** Wake lock, WifiLock, notification, JobScheduler, battery status, boot đều là API Android chuẩn. |
| Fork Termux:API (GPLv3), đổi `applicationId`, bỏ `sharedUserId`, ký bằng key của mình, sửa check, build lại client | Cao: thêm 1 APK, 2 app phải cập nhật cùng nhau, GPL, Play policy | Chỉ đáng nếu cần sensor/camera/SMS/SAF/telephony. Không cần cho mục tiêu "giữ nền". |
| Giữ package `com.termux` như opencode-termux | Trung bình: thay thế Termux app, plugin phải cùng key, maintain fork GPL | Không phù hợp với `com.opencode.android`; sẽ mất khả năng cài song song. |

### 3.3. API nào thực sự hữu ích cho background

| Termux:API / Termux | Tương đương native trong app này | Ghi chú |
| --- | --- | --- |
| `termux-wake-lock` (termux-tools → TermuxService) | `PowerManager.PARTIAL_WAKE_LOCK` (đã có) + `WifiManager.WifiLock` | Thêm WifiLock; không cần API ngoài. |
| `termux-job-scheduler` | `JobScheduler` / `WorkManager` | Chu kỳ tối thiểu 15 phút; `persisted` cần `RECEIVE_BOOT_COMPLETED`. Dùng làm **watchdog**, không phải daemon. |
| `termux-notification` | `NotificationManager` + channel + action | Đã có notification cơ bản; thêm action Restart/Wake lock. |
| `termux-notification-list` | `NotificationListenerService` (quyền đặc biệt) | Chỉ cần nếu muốn đọc notification của app khác; bỏ qua. |
| `termux-battery-status` | `BatteryManager` sticky intent | Rất rẻ, có thể thêm để cảnh báo pin yếu. |
| `termux-toast` | `Toast` | Không cần. |
| `Termux:Boot` | `BOOT_COMPLETED` receiver | Nên thêm, kèm setting bật/tắt. |
| `KeepAliveService` (START_STICKY rỗng) | — | Vô dụng trên Android hiện đại; chỉ FGS mới có trọng số. |

---

## 4. Cài thư viện ngoài `com.termux`

### 4.1. Cách `manhduonghn/opencode-termux`: giữ nguyên `com.termux` + `apt`

- Là fork của Termux app, **vẫn là package `com.termux`**, nên dùng được toàn bộ
  `apt`, `pkg`, plugin và Termux:API.
- Bootstrap (`OpenCodeBootstrap.java`) chạy:
  ```sh
  apt-get install glibc-repo glibc-runner patchelf tar
  ```
  tải npm tarball `@opencode/cli-linux-<arch>`, extract, rồi:
  ```sh
  patchelf --set-interpreter $PREFIX/glibc/lib/ld-linux-*.so.* ./opencode
  ```
  **Chỉ patch interpreter**, không `--set-rpath` (sửa dynamic section của binary ~200MB
  gây SIGSEGV).
- Launcher `$PREFIX/bin/opencode` unset `LD_PRELOAD` (preload bionic của Termux không
  được vào process glibc), đặt `OPENCODE_DISABLE_AUTOUPDATE=1`.
- Ưu điểm: mọi package Termux hoạt động nguyên bản, không cần rewrite path.
- Nhược điểm: **thay thế app Termux**, plugin phải cùng chữ ký, phải maintain fork GPL,
  xung đột với Termux thật.

Đây là lựa chọn "dễ nhất" nhưng đánh đổi danh tính app. Repo này đã chọn hướng ngược lại.

### 4.2. Cách `Sanuu7/AndroidHarness`: prefix riêng + extract `.deb`

Luồng trong `LinuxEnvironment`:

1. Tải `Packages` index từ mirror Termux.
2. Resolve dependency closure, gồm `Provides:` (virtual package) và alternative `a | b`.
3. Tải `.deb`, verify **SHA-256** từ index.
4. Đọc `ar` → `data.tar.xz|zst|gz` → tar, extract vào prefix.
5. Rewrite từng entry:
   - symlink tuyệt đối `/data/data/com.termux/files/usr/...` → **relative** trong prefix;
   - shebang `#!/data/data/com.termux/files/usr/bin/...` → shebang mới trong prefix
     (hoặc `/system/bin/sh` cho script cần chạy bằng bionic);
   - bỏ wrapper bridge của `termux-tools` (`pm`, `cmd`, `am`, `termux-am`, …) để không
     shadow lệnh hệ thống;
   - patch `SHELL_PATH` trong ELF của `git` thành `/system/bin/sh`;
   - rewrite path cấu hình compiled-in: `etc/tls/cert.pem`, `etc/profile`,
     `bash.bashrc`, git templates; OpenSSL (`OPENSSLDIR`, `MODULESDIR`, `ENGINESDIR`)
     được chuyển bằng `OPENSSL_CONF`, `OPENSSL_MODULES`, `OPENSSL_ENGINES` vì các
     đường dẫn đó nằm trong `libcrypto.so` và không thể sửa khi thư viện đang chạy;
6. Xuất env cho mọi process con: `PATH`, `LD_LIBRARY_PATH`, `PREFIX`, `HOME`, `TMPDIR`,
   `SSL_CERT_FILE`, `CURL_CA_BUNDLE`, `NODE_EXTRA_CA_CERTS`, `OPENSSL_CONF`,
   `OPENSSL_MODULES`, `OPENSSL_ENGINES`, `GIT_EXEC_PATH`, `GIT_TEMPLATE_DIR`,
   `GIT_CONFIG_NOSYSTEM`.
7. Với `targetSdk >= 29` (AndroidHarness target cao): thêm `termux-exec` preload
   (`lib/libtermux-exec-ld-preload.so`, `TERMUX__PREFIX`, `TERMUX_APP__DATA_DIR`,
   `TERMUX_APP__LEGACY_DATA_DIR`) và/hoặc **linker shim** cho từng binary
   (`/system/bin/linker64 <binary>`), vì Android 10+ cấm `execve()` file trong app data.

### 4.3. Repo này đang làm gì

- Cùng kỹ thuật extract `.deb` + rewrite (`OpenCodePackages.kt`), thêm:
  - `pkg` CLI + `OpenCodePkgService` (FGS) để user cài package từ terminal;
  - **relocation glibc bằng cách thay chuỗi C tại chỗ**:
    `/data/data/com.termux/files/usr` (31 ký tự) → `/data/data/com.opencode.android`
    (31 ký tự). 31 byte đã dùng hết cho gốc sandbox nên không thể có một symlink cho cả prefix:
    mỗi thư mục cấp 1 của `$PREFIX` được symlink vào `<dataDir>` (`glibc`, `etc`, `bin`, `lib`,
    `share`, …). Nhờ vậy loader và `libc.so.6` tìm đúng `resolv.conf`, CA, v.v. mà không cần
    patchelf vào rodata. Chuỗi thay thế được phép **ngắn hơn** (phần đuôi của chuỗi cũ được
    NUL hoá), nên git `SHELL_PATH` dùng chung một bộ vá (`ElfStrings`); chuỗi dài hơn bị từ chối
    vì không được đổi offset của ELF. `app/build.gradle.kts` chặn build nếu `applicationId` (hoặc
    `applicationIdSuffix` của một build type) làm đường dẫn sandbox khác 31 ký tự.
  - patch `PT_INTERP` của binary OpenCode để nó tự chạy trực tiếp (quan trọng cho
    `opencode serve` self-spawn qua `/proc/self/exe`);
  - `targetSdk 28` nên **không cần** termux-exec/linker shim.
- Hạn chế: không có `apt`/`dpkg` (không chạy `postinst`), không remove package,
  package nào hardcode path trong rodata (khác độ dài) sẽ cần xử lý riêng.

#### 4.3.1. Vì sao "một số mạng chạy, một số mạng không"

`resolv.conf` của glibc là thứ quyết định. Trên nhiều mạng, Android trả về DNS
IPv6 trước (thường do router quảng bá), trong khi máy **không có route IPv6**
(`/proc/net/ipv6_route` rỗng, không có địa chỉ global). glibc thử nameserver theo
đúng thứ tự trong file: mỗi lookup phải chờ IPv6 timeout rồi mới fallback IPv4,
và nếu mạng chỉ có DNS IPv6 thì mọi lookup trả `EAI_AGAIN` dù IPv4 vẫn vào
Internet bình thường. Vì vậy app hiện tại:

1. Ghi **IPv4 trước**, IPv6 chỉ khi máy thật sự có địa chỉ global.
2. Dùng DNS của mạng đang hoạt động làm nguồn chính; chỉ thêm `1.1.1.1`,
   `8.8.8.8` khi mạng **không trả về resolver nào**, tối đa 3 nameserver
   (giới hạn `MAXNS` của glibc), `options timeout:1 attempts:1`. Không thay
   resolver của mạng bằng DNS công cộng, vì resolver của Wi-Fi/4G/VPN có thể
   là split-horizon hoặc có lọc riêng.
3. Ghi file reset lại mỗi khi **default network đổi** (Wi-Fi ↔ 4G, bật/tắt VPN)
   qua `ConnectivityManager.registerDefaultNetworkCallback`, nhưng bỏ qua nếu
   nội dung không đổi để không đánh thức server vô ích.
4. Ghi qua file tạm + `rename()` để server không đọc phải file dở dang.

### 4.4. Nếu muốn mở rộng

- `pkg install openssh` → `sshd -D` (không daemonize) nếu cần SSH vào máy; nhớ nó vẫn
  là process con, chết cùng app.
- `pkg install runit termux-services` → cần thêm `dpkg` (cho `start-stop-daemon`);
  chỉ nên coi là tiện ích.
- `nodejs`, `python`, `clang`, `git`, `gh` đã được thiết kế để chạy qua `pkg install`.
- Với package cần glibc (repo `glibc-repo`): hiện app chỉ cài package `glibc`; muốn
  `glibc-runner`/binary glibc khác phải xử lý relocation tương tự loader.

### 4.5. Nếu nâng `targetSdk` (Play Store buộc target mới)

Đây là thay đổi lớn, không chỉ là con số:

1. Android 10+ cấm `execve()` file trong app data → cần **termux-exec preload** hoặc
   **linker shim** cho mọi binary (như AndroidHarness).
2. Android 12+ giới hạn start FGS từ background; Android 14+ bắt buộc khai báo
   `foregroundServiceType` + permission; Android 15 giới hạn `dataSync` 6h/24h →
   dùng `specialUse` (đã khai báo) và tránh `dataSync`.
3. Android 13+ cần `POST_NOTIFICATIONS` runtime permission.
4. `BOOT_COMPLETED` vẫn được phép start FGS, nhưng một số type bị cấm từ Android 15
   (`dataSync`, `mediaProcessing`); `specialUse` không bị.
5. Play Store không chấp nhận target 28 cho bản cập nhật mới → nếu phát hành Play phải
   làm mục 1-3. Nếu chỉ sideload, giữ 28 là lựa chọn hợp lý và đơn giản nhất.

### 4.6. `pkg install` trên Android 13+: vì sao `am start-service` thất bại

Đây là lỗi có thật, gặp trên máy Android 13 (MIUI, API 33):

```text
$ pkg install -y openjdk
Installing openjdk ...
pkg: the app refused the request: Error: Can't start from invalid source: 10233
```

**Nguyên nhân** không phải quyền của app, mà là cách `am` gửi request:

- `am` là *shell command* chạy trong tiến trình của chính app (UID `10233`), nhưng
  `ActivityManagerShellCommand` luôn gửi `callingPackage = "com.android.shell"`.
- `ActivityTaskManagerService.assertPackageMatchesCallingUid()` thấy package
  `com.android.shell` không thuộc UID `10233` nên ném `SecurityException`, và
  `ActiveServices` in ra `Can't start from invalid source: <uid>`.
- Vì vậy **`am start-service`, `am start-foreground-service` và cả `am start-activity`**
  đều bị từ chối khi gọi từ shell của app. `am broadcast --user 0` thì **không**, vì
  broadcast không cần `callingPackage` khớp UID.

**Cách sửa** (đã triển khai):

1. `OpenCodePkgReceiver`: `exported="true"`, nhưng bảo vệ bằng permission
   `com.opencode.android.permission.PACKAGE_REQUEST` với `protectionLevel="signature"`.
   Chỉ app cùng chữ ký mới gửi được, app khác không thể lợi dụng.
2. Bridge `pkg` (Kotlin/Native, `cli-bridge/`) gửi **ordered broadcast**
   `com.opencode.android.pkg.REQUEST`:
   ```sh
   am broadcast --user "$USER_ID" -n "$APP/.OpenCodePkgReceiver" \
       -a com.opencode.android.pkg.REQUEST \
       --es action install --es session "$SESSION" --es packages "$packages"
   ```
   Receiver gọi `startForegroundService(OpenCodePkgService)` (cùng UID) rồi
   `setResultCode(1)`; bridge đọc `Broadcast completed: result=1` mới coi là app đã nhận,
   `result=0` hoặc `Error` thì báo lỗi rõ ràng thay vì treo.
3. Bridge chờ status file tối đa ~30 giây và nhắc "Bring OpenCode to the foreground" —
   broadcast vẫn đánh thức app, nhưng lần cài đầu cần runtime đã sẵn sàng.

Lưu ý: `USER_ID` được bridge sinh ra từ `getuid() / 100000`, không hardcode `0`,
nên multi-user vẫn đúng.

### 4.7. `pkg bundle`: cài nhanh bộ công cụ cho agent

Không thể "cài toàn bộ repo Termux": index `binary-aarch64` có **3.028 gói**, trong đó
nhiều gói xung đột nhau (nhiều phiên bản `python`, `node`, `openjdk`), có gói chạy
`postinst` cần `dpkg`/root, có daemon và tổng dung lượng hàng GB. Extract hết sẽ phá prefix
và không có cơ chế remove. Thay vào đó là các nhóm chọn lọc:

| Nhóm | Nội dung chính |
| --- | --- |
| `pkg bundle dev` | `clang make cmake ninja pkg-config autoconf automake libtool patchelf git gh ripgrep fd jq yq tree file less nano vim bat diffutils findutils sed gawk grep tar xz-utils zstd 7zip unzip zip curl wget openssh rsync tmux htop procps lsof ncurses-utils bash-completion sqlite` |
| `pkg bundle python` | `python python-pip python-cryptography python-numpy python-pillow` |
| `pkg bundle node` | `nodejs npm yarn pnpm` |
| `pkg bundle java` | `openjdk-17 ca-certificates-java` |
| `pkg bundle net` | `curl wget openssh rsync nmap dnsutils whois net-tools socat` |
| `pkg bundle all` | hợp của tất cả nhóm trên (lớn, nhưng vẫn không phải cả repo) |

Alias tên gói hay bị gõ sai:

| Người dùng gõ | Gói thật |
| --- | --- |
| `openjdk`, `java`, `javac`, `jdk` | `openjdk-17` |
| `node` | `nodejs` |
| `pip`, `pip3` | `python-pip` |

**JDK cần cấu hình thêm** vì Termux dùng `update-alternatives` trong `postinst`, mà prefix
này không chạy `postinst`:

- `openjdk-17` (96 MB deb, ~218 MB sau cài) nằm ở `$PREFIX/lib/jvm/java-17-openjdk`,
  không có `$PREFIX/bin/java`.
- `OpenCodePackages.configureJdk()` tự tạo symlink `bin/*` → `lib/jvm/.../bin/*` (thay cả
  symlink treo của gói cũ), chọn JDK mới nhất nếu có nhiều bản.
- `OpenCodeBootstrap` ghi `JAVA_HOME`, thêm `$JAVA_HOME/lib` + `$JAVA_HOME/lib/server` vào
  `LD_LIBRARY_PATH` (RUNPATH của JDK trỏ `/data/data/com.termux`), thêm `$JAVA_HOME/bin`
  vào `PATH` trong `etc/opencode-env.sh`; server cũng nhận `JAVA_HOME` trong env.
- `ca-certificates-java` chỉ là `Recommends` của `openjdk-17`, nên `pkg install openjdk`
  tự thêm gói này. Nó đặt `lib/jvm/java-17-openjdk/lib/security/jssecacerts` (Java ưu
  tiên file này hơn `cacerts`), nhờ vậy HTTPS từ Java/Gradle hoạt động; JDK cũng có sẵn
  `lib/security/cacerts`.
- `openjdk-17` kéo theo `alsa-plugins` → `pulseaudio` (vài MB) do dependency khai báo;
  không ảnh hưởng JVM nhưng làm lần cài lâu hơn.

Sau khi cài:

```sh
. "$PREFIX/etc/opencode-env.sh"   # hoặc mở terminal mới
java -version
javac -version
```

---

## 5. Kế hoạch đề xuất cho repo này

> **Trạng thái: P0, P1, P1.5 và P1.6 đã được triển khai** trong
> `OpenCodeService.kt`, `OpenCodeWatchdog.kt`, `OpenCodeBootReceiver.kt`,
> `OpenCodePkgReceiver.kt`, `OpenCodePkgService.kt`, `OpenCodePackages.kt`,
> `OpenCodeSettings.kt`, `OpenCodeWebActivity.kt`, `OpenCodeSessionTracker.kt`,
> `cli-bridge/`, `AndroidManifest.xml` và layout/strings.
> P2 vẫn để ngỏ.

### P0 — Sống sót khi vuốt recents (đã làm)

1. `OpenCodeService.onTaskRemoved()`: **đã bỏ `stopSelf()`**. Service khai báo thêm
   `android:stopWithTask="false"`, nên server + notification tiếp tục chạy như Termux.
2. Thêm action **Restart** vào notification (dùng `ACTION_RESTART` có sẵn).
3. `onDestroy()` vẫn dừng server khi user bấm **Stop** (giữ nguyên).
4. Chưa thêm setting tắt hành vi "giữ khi vuốt": hành vi mới là mặc định, đúng mục tiêu P0.

### P1 — Tầng "daemon" đầy đủ (đã làm)

5. `WifiLock` cạnh wake lock, cùng bật/tắt theo setting "Giữ CPU hoạt động":
   ```kotlin
   wifiLock = powerManager
       .createWifiLock(PowerManager.WIFI_MODE_FULL_HIGH_PERF, "opencode:foreground:wifi")
       .apply { setReferenceCounted(false); acquire() }
   ```
   Release trong `onDestroy()` và khi user tắt wake lock.
6. **`OpenCodeBootReceiver`** (`RECEIVE_BOOT_COMPLETED`, thêm `MY_PACKAGE_REPLACED` và
   `QUICKBOOT_POWERON`): chỉ start khi setting "Tự chạy khi khởi động máy" bật **và** runtime
   đã cài xong; luôn gọi lại `ensureScheduled()` cho watchdog.
7. **`OpenCodeWatchdogJobService`** (`JobScheduler`):
   - job `persisted`, periodic 15 phút, `NETWORK_TYPE_NONE` (chỉ mở socket local);
   - `onStartJob`: nếu port 49374 không mở → `startForegroundService(OpenCodeService)`;
   - có thêm một one-shot job khi user bật lại watchdog để kiểm tra sau ~5 giây;
   - `ensureScheduled()` idempotent, được gọi từ activity, service, boot receiver.
   - **Tôn trọng Stop:** bấm Stop trong notification đặt cờ `user_stopped`, hủy job watchdog
     và activity không tự start lại; mở app / bấm Retry / Restart mới xóa cờ.
8. **Phát hiện bị kill**: exit code `9` hoặc `137` → notification giải thích Android đã kill
   tiến trình (phantom/LMK), kèm nút **Khởi động lại**. Mọi exit bất thường khác (crash, thiếu
   thư viện, OOM cả cgroup) cũng được start lại thay vì chờ watchdog 15 phút. Backoff
   5s → 10s → 20s → 40s → 80s → 160s → 300s; chỉ reset khi server đã chạy ≥ 60 giây, nên một
   binary hỏng không tạo vòng lặp 5 giây.
   - **`onStartCommand(null)`**: khi Android restart service sau khi giết app process, service
     start server ngay nếu runtime đã sẵn sàng, thay vì trả `START_STICKY` rồi để port chết.
     Cờ `user_stopped` vẫn thắng: service tự `stopSelf()`.
   - **Heartbeat 60 giây**: kiểm tra port; nếu không có gì listen và không có process con đang
     chạy thì start lại. Chủ yếu cứu server được *adopt* (không có reader thread) và exit bị
     bỏ lỡ khi app bị freeze.
9. **OEM guidance**: nút "Hướng dẫn giữ chạy nền" trong Settings → Pin, gồm tắt tối ưu pin,
   autostart Xiaomi/Oppo/vivo/Samsung/Huawei và lệnh tắt phantom process killer.
10. **Chưa làm** (để P2): wake lock timeout có chủ đích / "chỉ giữ khi có session đang chạy".
    Hiện wake lock vẫn vô hạn khi server chạy, giống Termux.

### P1.5 — WebUI và terminal dùng chung một server (đã làm)

Mục tiêu: mở `opencode` trong terminal **không** spawn server thứ hai tranh port 49374, và
phiên đang chat trong WebUI được mở lại khi quay lại app.

11. **Đăng ký service:** sau khi server chạy (hoặc adopt server cũ), `OpenCodeService` ghi
    `$HOME/.local/state/opencode/service.json` với `{id, version, url, pid, password}`
    (`id = "latest"`, `url = "http://127.0.0.1:<port>"`, mode `0600`, ghi file tạm rồi rename).
    Đây đúng là file mà TUI/CLI của OpenCode dùng để discover background service; nó probe
    `/api/info` bằng Basic auth rồi so `pid` + `version`. File được xoá khi server dừng, chết
    hoặc service bị destroy; chỉ xoá khi `pid` trong file vẫn là server của app.
12. **Entry point `opencode`:** `$PREFIX/bin/opencode` là symlink trực tiếp tới binary
    đã patch trong `$HOME/.opencode/bin/opencode`. TUI/CLI tự discover server qua
    `service.json`; không còn wrapper chèn `--server`. `opencode upgrade` không được
    chuyển thành cập nhật app nữa, dùng nút Cập nhật trong Settings.
13. **Auto-resume:** route `/session/<id>` (do bridge JavaScript và `doUpdateVisitedHistory`
    báo về) được lưu vào `SharedPreferences` (`last_session_id`). Khi activity được tạo lại
    sau khi process chết, `onPageFinished` của trang chủ tự chuyển tới session đó. Việc quay
    về trang chủ **không** xoá `last_session_id`, giống TUI mở lại phiên gần nhất.
14. **`opencode service stop`:** server của app nhận SIGTERM (exit `143`). Service coi đây là
    Stop chủ động: đặt `user_stopped`, huỷ watchdog, xoá `service.json` và `stopSelf()`, nên
    watchdog không bật lại server sau vài phút.

**Helper `opencode-pty` cho terminal trên web:** OpenCode tự extract helper vào
`~/.cache/opencode/bin/opencode-pty/<version>-<hash>/opencode-pty`, nhưng file đó là ELF glibc với
`PT_INTERP=/lib/ld-linux-aarch64.so.1` — đường dẫn không tồn tại trên Android — nên `posix_spawn`
trả `ENOENT` và panel terminal báo "Couldn't create a terminal". Sửa file trong cache không có tác
dụng: mỗi lần server khởi động lại so checksum với sha256 nhúng sẵn rồi ghi đè. App cắt chính asset
đó từ CLI đã cài (Bun giữ nguyên, không nén), đối chiếu sha256 ghi cạnh asset,
`patchelf --set-interpreter` trỏ về loader glibc của prefix, cài vào
`$HOME/.opencode/bin/opencode-pty` và export `OPENCODE_PTY_BIN` cho server (đồng thời ghi vào
`etc/opencode-env.sh` cho terminal). OpenCode thấy biến này thì bỏ qua cả bước extract lẫn bước
validate cache. `$HOME/.opencode/.terminal-runtime` lưu fingerprint của CLI + version + sha256, nên
helper chỉ được build lại khi binary đổi. Riêng trên Android chỉ có vậy vẫn chưa đủ: daemon bind
unix socket dưới `${XDG_RUNTIME_DIR:-/tmp}/opencode-pty-<uid>`, mà `/tmp` không tồn tại nên nó chết
với `EACCES` (panel báo `ENOENT ... service.json`). Vì vậy server export thêm
`OPENCODE_PTY_SOCKET_DIR=$PREFIX/run` và thư mục này được tạo lại mỗi lần khởi động. Đường dẫn phải
ngắn: unix socket giới hạn 107 byte, daemon cộng thêm `/opencode-pty-<uid>/<32 hex>.sock` (57 byte),
nên không dùng được `$HOME/.opencode/tmp`.

**Thời gian khởi động đo trên máy (runtime đã cài):** `opencode --version` ≈0.24 s; `serve` mở TCP
sau ≈0.5 s và `/api/info` trả 200 sau ≈0.7 s; `POST /api/pair` ≈40 ms và `/auth/connect/<code>`
≈47 ms. Activity khởi động server và probe pairing song song (poll 700 ms), nên WebUI hiện sau
khoảng 1 s. Lần chạy đầu chậm là do tải runtime (~248 MB), không phải server.

**Server cũ không có biến môi trường mới:** tiến trình server giữ nguyên env lúc spawn, nên server
mồ côi do bản app cũ khởi động sẽ không có `OPENCODE_PTY_BIN` dù app đã cập nhật. Service ghi chữ
ký env vào `$HOME/.opencode/.server-env` sau mỗi lần spawn và so lại khi adopt; nếu khác (hoặc file
chưa có) thì kill rồi spawn server mới, thay vì chờ reboot.

### P1.6 — `pkg` dùng được trên Android 13+ (đã làm)

15. **Broadcast thay cho `am start-service`:** `OpenCodePkgReceiver` (signature permission
    `com.opencode.android.permission.PACKAGE_REQUEST`) nhận ordered broadcast của bridge
    `pkg`, gọi `startForegroundService(OpenCodePkgService)` trong cùng UID và trả
    `result=1`. Sửa đúng lỗi `Can't start from invalid source: 10233`, xem mục 4.6.
16. **Alias + bundle:** `openjdk|java|javac|jdk` → `openjdk-17`, `node` → `nodejs`,
    `pip` → `python-pip`; thêm `pkg bundle dev|python|node|java|net|all`, xem mục 4.7.
    Logic này nằm trong bridge Kotlin/Native (`cli-bridge/`), không còn shell script.
17. **JDK chạy được:** `configureJdk()` tạo symlink `bin/*` (gói `openjdk-17` chỉ có
    `lib/jvm/java-17-openjdk/bin/java`, không có `bin/java`); env có `JAVA_HOME`,
    `LD_LIBRARY_PATH` (`lib` + `lib/server`) và `PATH` cho JDK, cả trong terminal lẫn env
    của server. `openjdk-17` là tên gói thật (`openjdk` không tồn tại trong repo).
    `pkg install openjdk` tự thêm `ca-certificates-java` để có `jssecacerts`.
18. **Chưa kiểm chứng trên máy:** `java -version` trong prefix mới chỉ được suy luận từ
    cấu trúc gói + env, chưa chạy thật vì môi trường build hiện tại không có JDK/Android
    SDK và máy chưa cài APK mới. Sau khi cài APK: mở app một lần, chạy
    `pkg install openjdk`, rồi `java -version`.

### P1.7 — Giao diện Material You cho app và WebUI (đã làm)

19. **Native:** `Theme.Material3.DayNight.NoActionBar`, `OpenCodeApplication` gọi
    `DynamicColors.applyToActivitiesIfAvailable` nên Android 12+ lấy bảng màu từ hình nền; bản
    cũ dùng bảng seed cam trong `values/colors.xml` + `values-night/colors.xml`. Layout dùng
    `MaterialButton`, `MaterialSwitch`, `LinearProgressIndicator`, card bo tròn và màu theo
    theme attribute thay vì màu hard-code.
20. **WebUI:** `OpenCodeWebTheme` đọc các role Material 3 từ theme của activity rồi sinh CSS
    ghi đè token của OpenCode: các token ngữ nghĩa `--v2-*` (background, text, icon, border,
    overlay, state, agent), ramp `--v2-blue-*`, toàn bộ bộ tên cũ mà component OC-2 vẫn đọc
    (`--background-*`, `--text-*`, `--icon-*`, `--border-*`, `--surface-*`, màu diff) và màu
    syntax/markdown. CSS được đăng ký bằng `WebViewCompat.addDocumentStartJavaScript`, nên
    trang có màu đúng ngay lần paint đầu; sau `onPageFinished` và mỗi lần activity gắn lại
    WebView (xoay máy, đổi dark mode) script được eval lại trên document đang sống. Script cũng
    ghim `data-color-scheme` theo chế độ sáng/tối native để web app không tự chọn bảng màu
    khác, và nền WebView đặt bằng màu surface của app.

### P2 — Nâng cao

21. **Shizuku/root detached server** (như AndroidHarness `spawnDetached`):
    - chạy `opencode serve` trong Shizuku user service → sống sót khi app chết;
    - app chỉ là client HTTP tới `127.0.0.1`;
    - chỉ bật khi user có Shizuku/root, kèm cảnh báo bảo mật.
22. **runit/termux-services** như tiện ích: cho user chạy sshd/cron; tài liệu ghi rõ
    "chết cùng app", không quảng cáo là daemon thật.
23. **Live Updates** (Android 16) cho notification khi agent đang chạy (tùy chọn).
24. **Health check** định kỳ bằng `GET /api/info` thay vì chỉ `Socket.connect`, để phân
    biệt "port mở" và "server thật sự khoẻ".

---

## 6. Bảng quyết định nhanh

| Nhu cầu | Cách làm | Không nên |
| --- | --- | --- |
| Server sống khi màn hình tắt | FGS + wake lock (+ WifiLock) | Chỉ dựa vào wake lock |
| Server sống khi vuốt recents | Bỏ `stopSelf()` trong `onTaskRemoved` | Stop service khi task removed |
| Tự chạy sau reboot | `BOOT_COMPLETED` receiver + start FGS | Dùng runit |
| Tự restart khi bị kill | Watchdog JobScheduler + heartbeat 60s + restart mọi exit bất thường | Trông chờ `START_STICKY` một mình |
| WebUI + terminal cùng lúc | `service.json` (TUI tự attach) | Để TUI tự spawn server thứ hai |
| Mở lại app giữa chừng | Lưu `last_session_id`, mở `/session/<id>` | Xoá session khi về trang chủ |
| Chạy sshd/cron | `pkg install openssh`/`runit` (chấp nhận chết cùng app) | Coi runit là systemd |
| Thoát khỏi app hoàn toàn | Shizuku/root detached process | — |
| Gọi termux-api | Port native | Cài Termux:API, fork plugin |
| Cài thư viện ngoài `com.termux` | Extractor `.deb` + rewrite (đã có) | `apt`/`dpkg` vào prefix riêng |
| `pkg` báo `Can't start from invalid source` | Broadcast + receiver có signature permission | `am start-service` từ shell của app |
| Cài nhanh bộ công cụ | `pkg bundle dev\|python\|node\|java\|net\|all` | Cài cả 3.028 gói của index |
| Nâng targetSdk | termux-exec preload/linker shim + FGS type + notification permission | Chỉ đổi số trong Gradle |

---

## 7. Rủi ro / lưu ý

- **Pin**: wake lock vô hạn + server LLM là combo tốn pin; nên có toggle rõ ràng và
  trạng thái trong notification.
- **Phantom killer** không thể tắt bằng quyền app thường; đừng hứa "chạy mãi mãi".
  Chỉ có thể giảm khả năng bị giết và tự phục hồi.
- **OEM** (Xiaomi/Samsung/Oppo/Vivo) có killer riêng; FGS vẫn có thể bị dừng.
- **`targetSdk 28`** là điểm tựa kỹ thuật nhưng là nợ kỹ thuật nếu phát hành Play.
- **Termux:API** là GPLv3; fork sẽ ràng buộc giấy phép và làm tăng bề mặt bảo trì.
- **Shizuku** chạy UID 2000, có thể đọc/ghi ngoài sandbox; chỉ dành cho user nâng cao,
  phải cảnh báo rõ.
- **Broadcast receiver** của `pkg` là `exported="true"`; an toàn nhờ permission
  `signature` — đừng đổi thành `normal` hay bỏ permission, app khác sẽ start service được.
- **JDK** chiếm ~218 MB trong prefix; không có `postinst` nên mọi thứ phải do app tự nối
  (symlink, `JAVA_HOME`, `jssecacerts`). Gói `openjdk-17` kéo theo `pulseaudio` qua
  `alsa-plugins`, làm lần cài đầu lâu hơn.
- **Không có "cài hết repo"**: 3.028 gói Termux có xung đột và postinst; `pkg bundle all`
  chỉ là nhóm chọn lọc, và index vẫn có thể đổi giữa các lần cài.

---

## 8. Tài liệu tham khảo

- Termux app: <https://github.com/termux/termux-app>
- Termux services (runit): <https://github.com/termux/termux-services>
- Termux API: <https://github.com/termux/termux-api>
- Termux API package: <https://github.com/termux/termux-api-package>
- Phantom process killer: <https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md>
- Termux issue #2366: <https://github.com/termux/termux-app/issues/2366>
- opencode-termux: <https://github.com/manhduonghn/opencode-termux>
- AndroidHarness: <https://github.com/Sanuu7/AndroidHarness>
- AndroidHarness foreground service notes: `repowiki/03-foreground-service-execution-host.md`
- AndroidHarness background shell notes: `repowiki/22-background-shell-process-management.md`
