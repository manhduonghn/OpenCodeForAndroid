# Cách vá cho các thư viện của prefix (nghiên cứu từ AndroidHarness và Cortex)

Tài liệu ghi lại kết quả nghiên cứu hai dự án tham chiếu và đề xuất hướng vá cho các
thư viện/nhị phân vận hành trong prefix của app:

- **AndroidHarness** (`Sanuu7/AndroidHarness`) — app coding agent chạy toolchain Termux
  trong private storage, phương pháp gần giống app này, kèm nhiều bản vá riêng đã được
  kiểm chứng trên thiết bị thật.
- **Cortex** (`ssfuisu/cortex`) — terminal Ubuntu 24.04 native trên Android bằng một
  thư viện `LD_PRELOAD` dịch đường dẫn (`libcortex-hook.so`), không ptrace, không root.

## 1. Hiện trạng của app đã làm gì

Các cơ chế đã có trong repo (đánh dấu để so sánh, không cần làm lại):

| Cơ chế | Vị trí trong code |
| --- | --- |
| Relocation cùng độ dài `com.termux/files/usr` (31) → `DATA_DIR` (31) trong rodata của ELF | `OpenCodeBootstrap.relocateLoader` / `relocateGlibc`, `ElfStrings` |
| Patch PT_INTERP về loader glibc của prefix + fallback `ld-linux --library-path` | `installBinary`, `repairNativeInterp` |
| Alias cấp 1: mọi thư mục top-level của prefix được symlink vào `DATA_DIR` vì không đủ độ dài cho 1 alias duy nhất | `ensurePathAliases` |
| RUNPATH patch bằng `patchelf` cho bash/patchelf/opencode/JDK | `patchRpath` |
| Symlink tuyệt đối của Termux → tương đối; shebang → `$PREFIX`; ELF của git mang `SHELL_PATH` được vá | `OpenCodePackages` (extractor + `repair`) |
| Self-heal: `BINARY_PROOF` + reinstall gói hỏng, glibc loader, cert.pem ngắn | `OpenCodePackages.brokenPackages`, `doMaintenance` |
| `etc/opencode-env.sh`: PATH/LD_LIBRARY_PATH, wrapper tách môi trường cho `pkg` và lệnh Android, GIT_EXEC_PATH/GIT_TEMPLATE_DIR/GIT_CONFIG_SYSTEM/GIT_ATTR_NOSYSTEM, OPENSSL_CONF/MODULES/ENGINES, SSL_CERT_FILE/CURL_CA_BUNDLE/NODE_EXTRA_CA_CERTS, JAVA_HOME, resolv.conf | `buildPackageEnvironment` |
| `etc/gitconfig` với `http.sslCAInfo` (Termux libcurl cắm cứng cert.pem của prefix cũ) | `writeGitConfig` |
| resolv.conf cho glibc (IPv6-only fallback, refresh theo mạng) | `refreshResolvConf` |
| profile/bashrc login shell | `ensureLoginProfile`, `hookBashrc` |

Kết luận nhanh: nền tảng của app đã bao phủ ~80% những gì AndroidHarness vá, theo đúng
cùng triết lý (không ptrace, sửa string cùng độ dài, self-heal). Phần dưới liệt kê những
góc còn thiếu và những cách làm tốt hơn đáng học.

## 2. AndroidHarness vá gì (dữ liệu lấy từ mã nguồn)

Nguồn: `LinuxEnvironment.kt` (~1900 dòng), `NetTls.kt`, `AndroidBuildToolchain.kt`,
`OpenCodePackages` tương đương của họ là extractor + repair bên trong `LinuxEnvironment`
và `TermuxShellPath`/`TermuxShebangs`/`TermuxLinkRewrite`.

### 2.1 TLS/CA (Bug 1 của họ)

- **Ship sẵn Mozilla CA bundle trong APK** (`assets/net/mozilla-ca-bundle.pem`), materialize
  vào prefix `etc/tls/cacert.pem` khi thiếu hoặc checksum lệch → TLS chạy ngay cả khi gói
  `ca-certificates` chưa cài hoặc trích xuất lỗi. App này hiện chỉ dựa vào gói + kiểm tra
  `cert.pem > 1024` byte — vốn đã bắt được lỗi, nhưng certificate vẫn cần mạng lần đầu.
- **Bộ biến môi trường đầy đủ**: `SSL_CERT_FILE`, `CURL_CA_BUNDLE`,
  `REQUESTS_CA_BUNDLE` (app mình chưa có biến này), `GIT_SSL_CAINFO`,
  `NODE_EXTRA_CA_CERTS`; nếu không có bundle thì hint `/system/etc/security/cacerts`
  (Java dùng `JAVAX_NET_SSL_TRUSTSTORE`, được cấp riêng khi có JDK).
- **OPENSSL_CONF**: cùng lý do với app, họ chỉ ra Node **không khởi động** khi file config
  trỏ về `/data/data/com.termux/...` mà Termux app có cài (EACCES thay vì missing) —
  khớp như app đã thấy và đã vá bằng `OPENSSL_CONF`.

### 2.2 Git re-rooting

- KV: `GIT_EXEC_PATH`, `GIT_TEMPLATE_DIR`, `GIT_CONFIG_GLOBAL`, `GIT_CONFIG_SYSTEM`
  (nếu file đọc được) hoặc `GIT_CONFIG_NOSYSTEM=1`, và
  **`GIT_ATTR_NOSYSTEM=1`** khi `etc/gitattributes` không đọc được — không có biến này,
  mỗi lần gọi git in 3–5 dòng warning `unable to access .../etc/gitattributes`.
  App đã có đủ (env script v2), gồm cả gitattributes.
- Họ patch **SHELL_PATH trong ELF của git** (`/data/data/com.termux/files/usr/bin/sh`
  → `/system/bin/sh`, cùng độ dài) vì tier Shizuku của họ deploy prefix ở nơi khác.
  Prefix của app là một, SHELL_PATH sau relocation đã trỏ đúng `$PREFIX/bin/sh`
  (cùng độ dài 31) — không cần làm gì thêm; chỉ lưu ý: nếu sau này app có tier
  thứ hai ở đường dẫn khác độ dài, đây là chỗ phải xử lý.

### 2.3 W^X / exec và môi trường runtime

- `libtermux-exec-ld-preload.so` cho bionic tier của họ; với glibc process họ **unset
  LD_PRELOAD** — app cũng làm y như vậy trong `opencode-env.sh` (`unset LD_PRELOAD` +
  wrapper `LD_LIBRARY_PATH= LD_PRELOAD= command pkg`).
- Bài học đáng lấy: nhận xét của họ rằng chỉ định `LD_PRELOAD` trỏ tới file không tồn tại
  còn tệ hơn **không set** — linker từ chối mọi binary động (`CANNOT LINK EXECUTABLE`),
  là lý do mọi nơi thiết lập `LD_PRELOAD` đều kiểm tra file tồn tại trước. Áp dụng cho
  script env của app: hiện `unset LD_PRELOAD` vô điều kiện là an toàn, nhưng các biến
  phụ thuộc (ví dụ `GIT_TEMPLATE_DIR`, `TZDIR`) nên theo cùng nguyên tắc "chỉ set khi
  có thật".

### 2.4 JDK/Java (quan trọng cho `openjdk-17`/`21` của Termux)

- **Thứ tự `LD_LIBRARY_PATH`: `libjli` re-exec `/proc/self/exe` nếu `lib/server` KHÔNG
  đứng đầu**, còn trong app tier `/proc/self/exe` là linker của Android nên một tham số
  `-version` bị hiểu là file chạy. App hiện để `$JAVA_HOME/lib:$JAVA_HOME/lib/server:…`
  (lib trước, server sau) — nên **đảo thành `lib/server` đầu tiên** (một dòng env script,
  rủi ro gần như bằng 0).
- `javaHome()` được suy luận hai cách cho hai tier; họ lưu ý Android alias
  `/data/user/0` ↔ `/data/data` và `libjli` so sánh chuỗi với library path đã load.
  Trong app chỉ có 1 tier nên không cần, nhưng nếu sau này dùng `/data/user/0` thì nhớ.
- **Launchers d8/r8/apksigner**: shell script của Termux cắm cả jar của prefix cũ;
  họ viết lại thành `#!/system/bin/sh` + `"\${PREFIX:?…}/share/java/d8.jar"` — pattern
  `${PREFIX:?}` làm script **tự lỗi rõ ràng** thay vì âm thầm chạy sai khi `PREFIX` chưa
  được export. Cách viết này đáng dùng cho mọi launcher script app sinh ra.

### 2.5 Tự phục hồi prefix (self-heal) — cấu trúc đáng tham khảo

Vòng repair chạy mỗi lúc app khởi động, từng bước đếm số và tóm tắt cho log/UI:

1. relink symlink Termux hỏng (`bin/`, `bin/applets/`, cả `lib/`).
2. purge/rewrite shebang Termux trong `bin/` (giữ ELF nguyên, chỉ sửa script).
3. patch SHELL_PATH trong ELF git.
4. repair launcher Java (d8/r8/apksigner).
5. gói "broken theo proof binary" → unmark + reinstall gộp vào vòng cài thường;
   **lỗi mạng không được hạ trạng thái Ready** (giữ Ready, lần sau thử lại).
6. drop staging marker khi có sửa để deploy phụ thuộc re-stage lại thay vì tin cache.

App có các mảnh tương đương rải trong `OpenCodePackages.repair` + `doMaintenance`, chỉ
thiếu (4) — chưa có gói build toolchain nào, nên mới quyết định tính kế hoạch khi thêm
gói `d8`/`apksigner`.

### 2.6 Cái khác đáng ghi

- **Locale**: `LANG=C.UTF-8` luôn được set (script env của app chưa set; glibc không có
  locale dir này sẽ warning trên node/gh/editors). Termux glibc thường đi kèm C.UTF-8,
  nên set mặc định + đắp được nếu trống.
- **`TZDIR`**: chỉ định `$PREFIX/share/zoneinfo` khi thiếu, tránh Java/Python/GNU time
  đoán mò `/usr/share/zoneinfo` (không có trên Android).
- **npm/pip cache ra ngoài prefix** (cùng ý tưởng `npm_config_cache`/`PIP_CACHE_DIR`
  của app — app đã có).
- **Scratch exec được**: với họ là `/data/local/tmp` (tier shell) + mirror app-private
  (tier app); app giữ `/data/data/…` (W^X chỉ gặp từ targetSdk 29+, app target 28) nên
  không sao chép, chỉ ghi nhận lý do.
- **Chỉ deploy khi hash khớp**: stage tarball gắn hash nội dung thay vì chỉ hash danh
  sách gói, tránh deploy một tarball cũ. App không có deploy 2 tầng; ý tưởng "hash
  nội dung thay vì danh sách" đáng nhớ nếu app sau này cache tarball bootstrap.

## 3. Cortex làm gì (bản đồ kỹ thuật)

Cortex chọn cách khác về bản chất: **không sửa binary**, mà dịch đường dẫn lúc chạy qua
một thư viện `libcortex-hook.so` được `LD_PRELOAD` vào mọi process glibc. 7200 dòng C
được tổ chức thành các part, mỗi part intercept một nhóm symbol libc:

| Part | Nội dung ứng dụng được |
| --- | --- |
| `paths.c` | `rewrite_path()`: rootfs (`/usr`, `/etc`, `/tmp`, `/var`, `/root`) → `$CORTEX_ROOT/...`; thư mục lẻ `/dev/shm` → `$ROOTFS/tmp/shm`; **loại trừ** các mount của Android (`/apex /data /dev /odm /proc /sys /system /sdcard`,…) đúng theo ký tự đầu thứ 2 — nhanh, không snprintf trong hot path |
| `setup.c` | Seed env lúc init của hook: `TZDIR`, `SSL_CERT_FILE/DIR`, `CURL_CA_BUNDLE`, `GIT_SSL_CAINFO`, `NODE_EXTRA_CA_CERTS` … tự phát hiện root bằng `dladdr` của chính hook; `/proc/self/exe` và `getcwd()` được trả lại dạng "mong muốn" |
| `exec.c` | `execve`/`posix_spawn` hook: ELF của rootfs được chạy qua `ld.so --library-path --preload`; script được spawn qua chính interpreter (nếu trả về kernel, `#!/bin/sh` sẽ chọn shell của Android — họ gặp crash thật); binary của Android được chạy với env sạch không preload |
| `files.c` | `open/openat/openat2` (kể cả `_FORTIFY_SOURCE` variants của GNU tar), `creat`, `fopen/freopen`, `mkstemp/mkstemps/tmpfile/mkdtemp` — tức là **`TMPDIR` được áp dụng cả cho những program không đọc biến này** |
| `fileops.c` | `access/faccessat(2)/eaccess`, `chmod/fchmod(at)`, `unlink(at)`, `mkdir/mkdirat` (**điều chỉnh mode để owner luôn rwx**: dpkg tạo thư mục mode 0 rồi chmod sau, nhưng chmod thất bại lại bị báo thành công do `root` giả — kết quả là "Permission denied" trong `/usr/share/applications`), `inotify_add_watch` (tự tạo thư mục config thiếu → hết crash Bun/Node), `readlink`, xattrs, `realpath`, `scandir` |
| `net.c` | DNS: đọc servers từ resolv.conf của prefix (refresh mỗi 5s), tự gửi truy vấn UDP khi glibc không tìm được gì, **redirect truy vấn tới 127.0.0.1/::1 sang server thật** (trên Android không gì listen ở loopback như vậy), hỗ trợ IPv6-only (fallback AAAA + public resolver) |
| `identity.c` | **fakeroot**: `getuid/geteuid/getgid/...` trả về 0 cho program của rootfs (app không thể root), `lckpwdf` giả (Android mount `/etc` ro → `EROFS`), `getpwnam`/`getgrnam` + `/etc/shadow` từ file của rootfs, `getgroups` trả lại `ngroups=1` đúng theo check của chương trình được fortify (sshd chết vì lệch số lượng) |
| `signals.c` + `syscalls.c` | Handler `SIGSYS` (Android seccomp chặn syscall): mở rộng từ syscall gì thì trả gì — `set*id` trả 0 (thành công giả), còn lại `-ENOSYS` để glibc retry theo đường cũ (`clone3` → `clone`) |
| `static_programs.c` | Binary tĩnh (`gcc -static`): quét và vá **call site `svc #0`** trong file, biến set_robust_list/clone3/rseq/set*id thành 0 hoặc `-ENOSYS`, chuẩn hoá kết quả như handler SIGSYS; kết quả nhớ qua xattr (1 lần quét) |
| `go_programs.c` + `gosys/` | Go không dùng libc → preload vô hiệu. Vá file Go (Go 1.19+, arm64): chèn code `gosys.c` (độc lập libc, không relocation) vào **padding giữa segments** của Go linker, nối nhánh `svc #0` tới nó; code dịch path + exec qua loader, đúng như hook cho chương trình C |
| `hardlinks.c`, `compression.c`, `sockets.c` | hardlink/symlink chroot-cross, zstd cho tar, tên unix socket quá 108 byte được hash vào `$ROOTFS/tmp/.ctx_sock_%016llx` một cách deterministic |

### 3.1 Điểm quan trọng nhất với app này

1. **`LD_PRELOAD` translation là "cách vá cho nhiều thư viện" thực sự**: mọi đường dẫn
   nhúng sai (`/usr`, `/etc`, `/tmp`…) dùng được mà không đụng vào file. Nhược điểm:
   cost phải trả ở từng call đi qua file path (libcortex-hook tự đo: `stat +3%`,
   `lstat` của symlink vẫn ~97%), và nhiều bề mặt phải giữ khớp nhau
   (`openat2`, `posix_spawn`, fortify variants…).
2. **Triết lý kết hợp**: Cortex shell là binary của Ubuntu **không use relocation** — toàn
   bộ work nằm ở hook. App này đã chọn relocation (an toàn & không có runtime cost),
   hai cách có thể **xen kẽ**: giữ relocation cho toolchain chính, dùng hook như một
   **lưới vét cho binary không nằm trong plan** (binary user tải thêm, static Go…).
3. **Chi tiết socket 108 byte**: app đã tự đụng vấn đề này với `$PREFIX/run` của
   `opencode-pty`; hash-truncated socket name như cortex làm có thể dùng ngay trong
   bridge/pty nếu sau này path hết fit.
4. **SIGSYS policy table** (`set*id` → 0, còn lại → ENOSYS) của cortex là bản tóm tắt
   gọn nhất về syscall Android chặn; nếu app sau này chạy glibc không phải build của
   Termux hoặc binary tĩnh, cần đúng các mặc định này.
5. Kỹ thuật Go patch khá mạo hiểm, đáng học nhưng không nên làm trước khi có nhu cầu
   thật: module mỏng, giữ kích thước file, ghi xattr "đã vá với version nào", có logic undo.

## 4. Đề xuất cho app (xếp theo thứ tự đáng làm)

### 4.1 Nhanh — env + data, không đụng native (thực hiện được ngay)

| # | Vá mới | Cụ thể |
| --- | --- | --- |
| 1 | Thêm `REQUESTS_CA_BUNDLE` (+ `PIP_CERT`) | python/pip một số đường đi ưu tiên cấu hình qua certifi/requests thay vì `SSL_CERT_FILE` |
| 2 | `LANG=C.UTF-8` mặc định | 1 dòng trong `buildPackageEnvironment()` |
| 3 | `TZDIR=$PREFIX/share/zoneinfo` khi có | tránh Java/Python tìm `/usr/share/zoneinfo` |
| 4 | JAVA: đổi thứ tự `LD_LIBRARY_PATH` | `$JAVA_HOME/lib/server` đứng **trước** `lib` (theo ghi chú libjli của AndroidHarness) |
| 5 | `GIT_SSL_CAINFO` biến env | App đã dùng `http.sslCAInfo` trong gitconfig; biến env giúp git chạy ngay cả khi không đọc được config |
| 6 | Hardening env: mọi biến chỉ set khi file/dir tương ứng tồn tại | cùng nguyên tắc với `LD_PRELOAD` của AndroidHarness: biến trỏ tới thứ không có còn tệ hơn biến không được set |

### 4.2 Trung bình — sửa cấu trúc nhỏ trong Kotlin

| # | Vá mới | Cụ thể |
| --- | --- | --- |
| 7 | **CA bundle như asset**: ship `cacert.pem` trong `app/src/main/assets/`, materialize vào `etc/tls/cert.pem` như `NetTls.ensureInstalled` của AndroidHarness (checksum SHA-256, idempotent) | TLS sáng ngay lần đầu, kể cả khi trích xuất gói lỗi |
| 8 | **Proof-binary mở rộng**: bảng `pkg → list file phải có` cho mỗi gói mới được thêm (hiện có `BINARY_PROOF`; chỉ cần thêm dòng khi cài thêm gói) | bắt gói thiếu file thay vì chỉ kiểm tra shell |
| 9 | **Launcher repair pattern**: nếu app sinh launcher script cho gói build/codegen, dùng `#!/system/bin/sh` + `${PREFIX:?...}` để fail rõ ràng khi env thiếu | học từ AndroidBuildToolchain.rewriteLauncher |
| 10 | **Bảng libc "blocked syscall"**: đối với glibc, phần chạy static không có; nhưng nếu sau này glibc bị sửa (Ubuntu) hoặc `apt` thành thuật toán chạy trong prefix, reuse SIGSYS-policy của cortex (`set*id=0`, còn lại `ENOSYS`) | tài liệu hóa trong docs, chưa cần code |

### 4.3 Dài hạn — chỉ khi có nhu cầu thật (cân nhắc kỹ)

| # | Ý tưởng | Giải thích |
| --- | --- | --- |
| 11 | **Preload path-translation** (tham khảo cortex, không sao chép) | Một `.so` nhỏ (glibc) `liboc-pathmap.so` map `/data/data/com.termux/.../X` → `$PREFIX/X` theo cách của `paths.c`; lợi: binary tải thêm/tự override phần path nhúng được mà không cần vá; hại: phải giữ hot-path đó nhanh (không snprintf) và cover đủ `openat2`, `posix_spawn`, `getcwd`, `/proc/self/exe`, unix socket; lưu ý: nó không giúp binary tĩnh |
| 12 | **Go static patch** (như `gosys.c`) | `gh`, `fzf`… không dùng libc nên preload vô hiệu; chỉ đáng làm khi người dùng thường tải Go binary trong terminal |

### 4.4 Bảng "thư viện → vấn đề → vá" (trạng thái hiện tại)

| Thư viện / binary | Vấn đề áp lên prefix | Điều kiện xuất hiện | App đã vá? |
| --- | --- | --- | --- |
| glibc loader + libc | PT_INTERP / thư viện theo com.termux | mọi pkg cài qua core | ✅ relocation + alias |
| OpenSSL (node/curl/gh) | config/modules path cũ | cài `openssl`, `nodejs`… | ✅ OPENSSL_CONF/MODULES/ENGINES |
| libcurl | CA file cứng cert.pem prefix cũ | mọi HTTPS | ✅ GIT_CONFIG sslCAInfo + SSL_CERT_FILE/CURL_CA_BUNDLE (đã đủ) |
| git | GIT_EXEC_PATH/SHELL_PATH/templates/system config/gitattributes | cài git | ✅ hết |
| bash | profile shebang + env unset LD_PRELOAD | mọi tty | ✅ |
| Python | prefix trong sysconfig; pip cache; REQUESTS | cài `python`/`pip` | ✅ relocation che phần lớn + `REQUESTS_CA_BUNDLE`/`PIP_CERT` |
| JDK | RUNPATH + lib/library-path thứ tự | cài `openjdk-*` | ✅ `JAVA_HOME` + `lib/server` đứng đầu `LD_LIBRARY_PATH` |
| Node | OPENSSL_CONF fatal nếu EACCES; xattrs/inotify `config` dir thiếu | cài nodejs → opencode | ⚠️ conf đã có; inotify mkdir là bản fix Bun của cortex, không cần cho node thường |
| dpkg/apt (nếu cài) | fakeroot, mode 0 mkdir, seccomp set*id | cài cùng `glibc` gói glibc | ❌ chưa cần — app dùng `pkg` bridge bionic riêng |
| Binary tĩnh / Go | syscall tự làm, path sai | kết quả tải thêm trong terminal | ✅ dùng `oc-run` (vá Go AArch64) hoặc `oc-run` + `liboc-pathmap.so` (glibc) |

## 5. Hướng kiểm chứng

Môi trường hiện tại không có JDK/SDK để build, vì vậy các bước xác minh khi triển khai:

- 4.1 #1–#5 chỉ sửa `buildPackageEnvironment()` → thiếu bộ test unit; kiểm tra bằng
  mở `etc/opencode-env.sh` được sinh ra và chạy `sh -n` cho syntax.
- 4.2 #7 (asset CA) cần xác minh SHA-256 khớp nội dung asset — `sha256sum` chạy được
  trên máy build, không phụ thuộc Android SDK. Hash hiện tại:
  `796f1389bfea13c0ed78f663b765baaff54a508e5483786bd6ece73c5e3e3cbe`; khi thay bundle
  phải cập nhật cả `CA_BUNDLE_SHA256` trong `OpenCodeBootstrap.kt`.
- 4.3 #11/#12 được xây và kiểm tra trong GitHub Actions (workflow `.github/workflows/native.yml`):
  biên dịch chéo `liboc-pathmap.so` cho aarch64/x86_64, chạy test pathmap native (x86_64 và
  aarch64 dưới qemu), build blob `oc-gosys`, build `oc-gopatch` tĩnh cho cả hai ABI, rồi chạy
  test đầu-cuối: một chương trình Go AArch64 chưa vá không thấy prefix, sau khi vá thì mở được
  path Termux, `getcwd` trả spelling Termux, `setuid` trả 0, `clone3` trả ENOSYS,
  `getgroups` trả 1 group rỗng, và `faccessat2`/`openat2`/`name_to_handle_at`/`chroot`
  trả ENOSYS; helper chạy được script `#!/bin/sh` và `#!/usr/bin/env`, nhận path Termux cho
  `run`/`classify`/`patch`; nhánh glibc
  được chạy qua loader thật với `--preload`. Không cần thiết bị thật để kiểm tra phần logic;
  phần Android/SELinux vẫn nên thử trên máy thật trước khi phát hành.

## 6. Trạng thái triển khai

Các mục 4.1, 4.2 và 4.3 đã được triển khai. Hai helper native của 4.3 được build trong CI
(workflow `native.yml`) và nhúng vào APK dưới `assets/native/<abi>/`; app trích xuất chúng
khi cài đặt/bảo trì. Một APK build cục bộ không có asset native vẫn chạy bình thường: `oc-run`
rót lệnh trực tiếp và các binary tải thêm chỉ chạy với path cũ.

| Mục | Trạng thái | Vị trí |
| --- | --- | --- |
| 4.1 #1 `REQUESTS_CA_BUNDLE` + `PIP_CERT` | ✅ | `buildPackageEnvironment`, env của server, `OpenCodeGitHub` |
| 4.1 #2 `LANG=C.UTF-8` mặc định | ✅ | env script, profile, installer, server, gh |
| 4.1 #3 `TZDIR` khi có `share/zoneinfo` | ✅ | env script, installer, server, gh |
| 4.1 #4 `lib/server` đứng đầu `LD_LIBRARY_PATH` | ✅ | env script, env của server |
| 4.1 #5 `GIT_SSL_CAINFO` | ✅ | env script, env của server, `OpenCodeGitHub` |
| 4.1 #6 chỉ export khi file/dir tồn tại | ✅ | toàn bộ env script + env của server/gh |
| 4.2 #7 CA bundle trong APK | ✅ | `assets/net/mozilla-ca-bundle.pem`, `OpenCodeBootstrap.ensureCaBundle` |
| 4.2 #8 proof-binary mở rộng | ✅ | `OpenCodePackages.BINARY_PROOF` (thêm JDK, d8, r8, apksigner) |
| 4.2 #9 launcher repair pattern | ✅ | `OpenCodePackages.JavaLaunchers` (d8/r8/apksigner) |
| 4.2 #10 bảng syscall bị chặn | ✅ tài liệu | mục 7 dưới đây |
| 4.3 #11 preload path translation | ✅ | `native/pathmap`, `OpenCodeBootstrap.installNativeTools`, `oc-gopatch run` |
| 4.3 #12 Go static patch | ✅ (AArch64) | `native/gosys`, `native/gopatch`, `oc-run` |

Ghi chú cho #7: asset được kiểm tra SHA-256 trước khi ghi, ghi nguyên tử vào
`etc/tls/cert.pem`, và **không** ghi đè một bundle hợp lệ đã có. Như vậy bundle mới hơn
của gói `ca-certificates` không bị thay bằng bản snapshot cũ của APK; asset chỉ dùng khi
file thiếu hoặc bị cắt cụt (ví dụ sau khi app bị kill giữa lúc trích xuất).

## 7. Bảng chính sách syscall Android cho binary glibc (4.2 #10)

Chưa cần code vì glibc hiện tại là build của Termux (đã relocation) và mọi process đều
đi qua loader của prefix. Bảng dưới đây là mặc định tham chiếu của Cortex nếu sau này app
chạy glibc ngoài Termux, binary tĩnh, hoặc thêm tier `apt`/`dpkg` thật:

| Syscall bị Android seccomp chặn | Hành vi thay thế | Lý do |
| --- | --- | --- |
| `setuid`, `setgid`, `setreuid`, `setregid`, `setresuid`, `setresgid`, `setfsuid`, `setfsgid` | trả `0` (thành công giả) | app không thể đổi uid; `dpkg`/`apt` tin rằng đã hạ quyền |
| `setgroups`, `getgroups` | `setgroups` → `0`; `getgroups` trả `ngroups=1` | chương trình fortify so sánh số group và chết nếu lệch (sshd) |
| `clone3` | `-ENOSYS` | glibc tự fallback sang `clone` |
| `rseq` | `-ENOSYS` hoặc `0` | kernel Android không đăng ký rseq cho process app |
| `set_robust_list` | `0` | glibc coi như đã đăng ký |
| syscall không có trong seccomp allowlist của Android (`faccessat2`, `openat2`, `name_to_handle_at`, `chroot`, `mount`, `io_uring_*`, …) | `-ENOSYS` | filter dùng `SECCOMP_RET_TRAP`; trả `ENOSYS` để libc dùng đường cũ thay vì `SIGSYS` |
| syscall khác bị chặn | `-ENOSYS` | để libc/gọi lại theo đường cũ thay vì `SIGSYS` |

Với binary tĩnh/Go, bảng này phải được áp ngay tại call site `svc #0` (Cortex làm bằng
`static_programs.c`/`gosys.c`); chỉ `LD_PRELOAD` không can thiệp được syscall do chương
trình tự phát.

## 8. Helper native của 4.3 (cách dùng và giới hạn)

APK có thêm ba thứ, được cài bởi `OpenCodeBootstrap.installNativeTools()`:

| File | Vai trò |
| --- | --- |
| `$PREFIX/lib/liboc-pathmap.so` | thư viện glibc `LD_PRELOAD`, map hai root Termux sang prefix của app |
| `$PREFIX/libexec/oc-gopatch` | binary Go tĩnh: phân loại ELF, chạy glibc qua loader + preload, vá Go tĩnh rồi chạy |
| `$PREFIX/bin/oc-run` | vỏ `#!/system/bin/sh` gọi `oc-gopatch run -- "$@"`, fallback chạy trực tiếp |

Người dùng gọi binary tải thêm qua `oc-run`, ví dụ:

```sh
oc-run ./gh --version
oc-run ./fzf
oc-run ./script.sh        # shebang được chạy bằng interpreter trong prefix
```

`oc-gopatch classify FILE` in ra `glibc`, `bionic`, `go`, `static`, `script`, `foreign` hoặc
`unknown`; `oc-gopatch patch SRC DST` ghi bản đã vá (dùng cho test/CI).

Cơ chế:

- **glibc**: chạy `$PREFIX/glibc/lib/ld-linux-*.so.* --preload liboc-pathmap.so
  --library-path $PREFIX/lib:$PREFIX/glibc/lib PROGRAM`. Thư viện hook các entry
  point open/stat/exec/getcwd/readlink/realpath/inotify/posix_spawn…, map
  `/data/data/com.termux/files/usr` → `OC_PREFIX` và `/data/data/com.termux/files/home` →
  `OC_USER_HOME`, đồng thời cài SIGSYS handler cho bảng ở mục 7. Hot path chỉ là `strncmp`,
  không cấp phát.
- **Go tĩnh (AArch64, Go ≥ 1.19)**: tìm đúng một chuỗi lệnh của
  `internal/runtime/syscall.Syscall6`, chép blob `oc-gosys` vào khoảng trống zero giữa hai
  `PT_LOAD` (hoặc cuối file nếu không đủ chỗ), nối `svc #0` → thunk → stub. Stub dịch các
  syscall path (`openat`, `newfstatat`, `statx`, `renameat2`, …) bằng scratch
  64 KB được nối vào `p_memsz` của segment dữ liệu, và áp cùng policy set*id/clone3/rseq.
  Các syscall không có trong seccomp allowlist của Android (`faccessat2`, `openat2`,
  `name_to_handle_at`, `chroot`, `mount`, `io_uring_*`, …) được trả `ENOSYS`
  ngay trong stub, để Go/glibc dùng fallback thay vì bị `SIGSYS`. Điều này cần thiết vì Go
  build cho `linux` (không phải `android`) thử `faccessat2` mỗi lần `exec.LookPath`.
  Bản đã vá được cache ở `~/.opencode/patched/<tên>-<hash>`; hash gồm cả nội dung blob
  `oc-gosys`, nên bản cache do APK cũ tạo ra không được dùng lại sau khi cập nhật.
- **Còn lại**: `exec` nguyên bản; script được chạy qua interpreter (kể cả `#!/usr/bin/env`).
  Bản thân helper cũng nhận path Termux cho `run`, `classify` và `patch`: nếu file tương ứng
  tồn tại trong prefix thì nó được map sang trước khi mở/exec, nhờ vậy
  `oc-run /data/data/com.termux/files/usr/bin/tool` hoạt động dù helper chưa được vá.

Đã kiểm tra trên máy thật (Android arm64) với đúng artifact của CI: file cài đặt trùng SHA-256
với `native-assets`; `classify` nhận đúng `go`, vá xong bản vá chạy được `version`; bản vá mở
được file qua path Termux và `run` exec được binary bionic qua path Termux; `oc-run` chạy
`glibc/bin/iconv` đọc file qua path Termux (preload); script có shebang
`/data/data/com.termux/files/usr/bin/sh` chạy được. Lần thử này phát hiện `faccessat2` bị
`SIGSYS` trên thiết bị (Go `linux` thử syscall đó trong `exec.LookPath`), nên stub và helper
đã được sửa để trả `ENOSYS`/tránh syscall đó. Lần thử tiếp theo phát hiện helper gốc chưa map
path Termux cho chính input của nó (`oc-run /data/data/com.termux/files/usr/bin/...` báo
không thấy file), nên `run`/`classify`/`patch` đã được bổ sung mapping đó. Một binary Go thật
(`yq` v4.54.1, build bằng Go 1.27.1) tải từ mạng cũng được vá và đọc/ghi `-i` qua path Termux;
cache được tạo lại theo blob mới thay vì dùng bản vá của APK cũ.

Đã cài thử bằng `pkg` trên máy thật: `tree jq fzf`, `python python-pip`,
`nodejs npm ripgrep fd bat sqlite openssh`, `openjdk-17 d8 apksigner` — cài xong chạy được
(`node -e`, `npm --version`, `rg`, `fd`, `bat`, `sqlite3`, `ssh -V`, `java -version`, `javac`,
`d8`, `apksigner`). Mạng là điểm yếu duy nhất: một lượt cài hỏng giữa chừng vì
`Unable to resolve host "packages.termux.dev"`, chạy lại lệnh thì tiếp tục và hoàn tất; các gói
đã trích trước đó vẫn dùng được. Script do `npm install -g` tạo ra có shebang
`#!/usr/bin/env node`, mà Android không có `/usr/bin`, nên chạy trực tiếp báo
`bad interpreter`; `oc-run cowsay` chạy ngay, còn để gõ thẳng tên lệnh thì cần thêm bước
`repair` (đã thêm lệnh `pkg repair`, và mỗi `pkg install` cũng chạy lượt repair này). Từ bản này
extractor/repair map shebang trỏ tới interpreter không tồn tại trên máy (`#!/usr/bin/env node`,
`#!/bin/bash`) sang `$PREFIX/bin`, giữ nguyên `/bin/sh` của Android.

Giới hạn có chủ ý:

- chỉ vá Go tĩnh **AArch64**; Go x86_64 có mẫu lệnh khác và chưa hỗ trợ (vẫn chạy, nhưng path
  Termux không được dịch);
- không dịch path trong `sockaddr_un` của `connect`/`bind`, `/proc/self/exe`, hay path nằm
  trong `argv`/biến môi trường của chương trình đích (path của chính chương trình thì có);
  các chương trình cần những thứ đó vẫn phải được relocation như gói cài qua `pkg`;
- binary tĩnh không phải Go (Rust musl, C static…) không được dịch path;
- patcher cần một khoảng zero đủ lớn giữa các segment hoặc cuối file; nếu không có, nó báo lỗi
  và `oc-run` chạy binary chưa vá thay vì từ chối;
- việc vá sửa file đích (bản cache), không sửa file người dùng tải về.
