# Cách vá cho các thư viện của prefix

Tài liệu ghi lại các bản vá, bài học và giới hạn của những thư viện/nhị phân vận hành trong
prefix của app. Nội dung được đúc kết từ việc kiểm chứng trên thiết bị thật và từ thiết kế
nội bộ của repo:

- **Sửa file** (relocation, `PT_INTERP`, RUNPATH, shebang) cho những gì đã biết trước.
- **Dịch đường dẫn lúc chạy** (`liboc-pathmap.so`) cho binary tải thêm, wheel có ELF và các
  đường dẫn FHS phát sinh sau khi cài.
- **Vá binary tĩnh/Go** (`native/gosys`, `native/gopatch`) cho chương trình không dùng libc.

## 1. Hiện trạng của app đã làm gì

Các cơ chế đã có trong repo (đánh dấu để tham chiếu, không cần làm lại):

| Cơ chế | Vị trí trong code |
| --- | --- |
| Relocation cùng độ dài `com.termux/files/usr` (31) → `DATA_DIR` (31) trong rodata của ELF | `OpenCodeBootstrap.relocateLoader` / `relocateGlibc`, `ElfStrings` |
| Patch PT_INTERP về loader glibc của prefix + fallback `ld-linux --library-path` | `installBinary`, `repairNativeInterp` |
| Alias cấp 1: mọi thư mục top-level của prefix được symlink vào `DATA_DIR` vì không đủ độ dài cho 1 alias duy nhất | `ensurePathAliases` |
| Exec/path translation qua loader glibc + `liboc-pathmap.so` cho OpenCode | `runOpenCodeBinary`, `OpenCodeService`, `oc-launcher` |
| Symlink tuyệt đối của Termux → tương đối; shebang → `$PREFIX`; ELF của git mang `SHELL_PATH` được vá | `OpenCodePackages` (extractor + `repair`) |
| Self-heal: `BINARY_PROOF` + reinstall gói hỏng, glibc loader, cert.pem ngắn | `OpenCodePackages.brokenPackages`, `doMaintenance` |
| Cache ripgrep của OpenCode: wrapper `$HOME/.cache/opencode/bin/rg` → `$PREFIX/bin/rg` (bionic), vì bản tải về là ELF glibc | `OpenCodeBootstrap.ensureRipgrepRuntime`, `CORE_PACKAGES` |
| `etc/opencode-env.sh`: PATH/LD_LIBRARY_PATH, wrapper tách môi trường cho `pkg` và lệnh Android, GIT_EXEC_PATH/GIT_TEMPLATE_DIR/GIT_CONFIG_SYSTEM/GIT_ATTR_NOSYSTEM, OPENSSL_CONF/MODULES/ENGINES, SSL_CERT_FILE/CURL_CA_BUNDLE/NODE_EXTRA_CA_CERTS, JAVA_HOME/JAVA_TOOL_OPTIONS, GRADLE_USER_HOME/ANDROID_USER_HOME, ANDROID_HOME + SDK PATH, resolv.conf | `buildPackageEnvironment` |
| `etc/gitconfig` với `http.sslCAInfo` (Termux libcurl cắm cứng cert.pem của prefix cũ) | `writeGitConfig` |
| resolv.conf cho glibc (IPv6-only fallback, refresh theo mạng) | `refreshResolvConf` |
| profile/bashrc login shell | `ensureLoginProfile`, `hookBashrc` |

Kết luận nhanh: nền tảng của app đã bao phủ phần lớn các ca đã gặp, theo đúng triết lý
(không ptrace, sửa string cùng độ dài, self-heal). Phần dưới liệt kê những góc còn thiếu và
những cách làm đáng giữ.

### 1.1. Công cụ đọc code (grep/sed/awk/find/ripgrep)

`CORE_PACKAGES` cài sẵn `grep`, `sed`, `gawk`, `findutils`, `diffutils` và `ripgrep` bản
bionic ngay từ lần cài đầu, nên `$PREFIX/bin` (đứng trước `/system/bin`) cung cấp GNU
grep/sed/awk/find. Nếu chỉ dựa vào toybox của Android thì `grep -P`, BRE `\|`, `sed \s`/`\b`
bị từ chối với "bad regex"/"Unknown option" — lỗi trông như file hỏng nhưng thực ra là
công cụ thiếu tính năng GNU.

Riêng OpenCode tải ripgrep 15.1.0 bản glibc vào `~/.cache/opencode/bin/rg` rồi tái sử dụng;
file đó có `PT_INTERP=/lib/ld-linux-aarch64.so.1` nên `grep`/`glob` báo
`ripgrep execution failed`. `ensureRipgrepRuntime` ghi một wrapper tại đúng path cache để
exec `$PREFIX/bin/rg`, và chạy lại ở mỗi maintenance (kể cả sau khi cache bị xoá hoặc
OpenCode tự cập nhật). Wrapper tự set `LD_LIBRARY_PATH` vì ripgrep cần `libpcre2-8.so`
của prefix.

## 2. Các bản vá đã kiểm chứng trên thiết bị

Phần này ghi lại những vấn đề thực tế gặp khi vận hành toolchain trong private storage và
cách xử lý đã chọn. Mỗi mục nêu hiện trạng của app, phần còn thiếu (nếu có) và lý do.

### 2.1 TLS/CA

- **Ship sẵn Mozilla CA bundle trong APK** (`assets/net/mozilla-ca-bundle.pem`), materialize
  vào prefix `etc/tls/cacert.pem` khi thiếu hoặc checksum lệch → TLS chạy ngay cả khi gói
  `ca-certificates` chưa cài hoặc trích xuất lỗi. Nếu chỉ dựa vào gói + kiểm tra
  `cert.pem > 1024` byte thì bắt được lỗi cắt cụt, nhưng certificate vẫn cần mạng lần đầu.
- **Bộ biến môi trường đầy đủ**: `SSL_CERT_FILE`, `CURL_CA_BUNDLE`,
  `REQUESTS_CA_BUNDLE`, `GIT_SSL_CAINFO`, `NODE_EXTRA_CA_CERTS`; nếu không có bundle thì hint
  `/system/etc/security/cacerts` (Java dùng `JAVAX_NET_SSL_TRUSTSTORE`, được cấp riêng khi có
  JDK).
- **OPENSSL_CONF**: Node **không khởi động** khi file config trỏ về
  `/data/data/com.termux/...` mà app Termux có cài (EACCES thay vì missing) — app đã vá bằng
  cách trỏ `OPENSSL_CONF` vào bản trong prefix.

### 2.2 Git re-rooting

- Cần: `GIT_EXEC_PATH`, `GIT_TEMPLATE_DIR`, `GIT_CONFIG_GLOBAL`, `GIT_CONFIG_SYSTEM`
  (nếu file đọc được) hoặc `GIT_CONFIG_NOSYSTEM=1`, và **`GIT_ATTR_NOSYSTEM=1`** khi
  `etc/gitattributes` không đọc được — không có biến này, mỗi lần gọi git in 3–5 dòng warning
  `unable to access .../etc/gitattributes`. Env script của app đã có đủ.
- Một tier deploy prefix ở đường dẫn khác (ví dụ shell/Shizuku) cần patch **SHELL_PATH trong
  ELF của git** (`/data/data/com.termux/files/usr/bin/sh` → shell có thật, cùng độ dài). Prefix
  của app là một, SHELL_PATH sau relocation đã trỏ đúng `$PREFIX/bin/sh` (cùng độ dài 31) —
  không cần làm gì thêm; nếu sau này có tier thứ hai ở đường dẫn khác độ dài, đây là chỗ phải
  xử lý.

### 2.3 W^X / exec và môi trường runtime

- Với glibc process phải **unset `LD_PRELOAD`** trước khi exec tool của hệ; app làm việc này
  trong `opencode-env.sh` (`unset LD_PRELOAD` + wrapper
  `LD_LIBRARY_PATH= LD_PRELOAD= command pkg`).
- Nguyên tắc: chỉ định `LD_PRELOAD` trỏ tới file không tồn tại còn tệ hơn **không set** —
  linker từ chối mọi binary động (`CANNOT LINK EXECUTABLE`). Vì vậy mọi nơi thiết lập
  `LD_PRELOAD` đều kiểm tra file tồn tại trước. Áp dụng cho script env của app: hiện
  `unset LD_PRELOAD` vô điều kiện là an toàn, nhưng các biến phụ thuộc (ví dụ
  `GIT_TEMPLATE_DIR`, `TZDIR`) nên theo cùng nguyên tắc "chỉ set khi có thật".

### 2.4 JDK/Java (quan trọng cho `openjdk-17`/`21` của Termux)

- **Thứ tự `LD_LIBRARY_PATH`: `libjli` re-exec `/proc/self/exe` nếu `lib/server` KHÔNG đứng
  đầu**, còn trong tier của app `/proc/self/exe` là linker của Android nên một tham số
  `-version` bị hiểu là file chạy. App để `$JAVA_HOME/lib/server` **đứng trước** `lib` trong
  `LD_LIBRARY_PATH`.
- `javaHome()` được suy luận cho tier hiện tại; nếu sau này dùng `/data/user/0` thay vì
  `/data/data`, nhớ rằng `libjli` so sánh chuỗi với library path đã load.
- **Launchers d8/r8/apksigner**: shell script của Termux cắm cả jar của prefix cũ; chúng được
  viết lại thành `#!/system/bin/sh` + `"\${PREFIX:?…}/share/java/d8.jar"` — pattern
  `${PREFIX:?}` làm script **tự lỗi rõ ràng** thay vì âm thầm chạy sai khi `PREFIX` chưa được
  export. Cách viết này dùng cho mọi launcher script app sinh ra.

### 2.5 Tự phục hồi prefix (self-heal)

Vòng repair chạy mỗi lúc app khởi động, từng bước đếm số và tóm tắt cho log/UI:

1. relink symlink Termux hỏng (`bin/`, `bin/applets/`, cả `lib/`).
2. purge/rewrite shebang Termux trong `bin/` (giữ ELF nguyên, chỉ sửa script).
3. patch SHELL_PATH trong ELF git.
4. repair launcher Java (d8/r8/apksigner).
5. gói "broken theo proof binary" → unmark + reinstall gộp vào vòng cài thường;
   **lỗi mạng không được hạ trạng thái Ready** (giữ Ready, lần sau thử lại).
6. drop staging marker khi có sửa để deploy phụ thuộc re-stage lại thay vì tin cache.

App có các mảnh tương đương rải trong `OpenCodePackages.repair` + `doMaintenance`, và đã có
(4) qua `JavaLaunchers`.

Lượt repair đầy đủ không chạy lại mỗi lần mở app: `doMaintenance` lưu fingerprint của prefix
(APK/versionCode, `.packages-installed`, mtime `bin/`/`lib/`/`glibc/lib`) trong
`$HOME/.opencode/.maintenance`. Khi fingerprint còn khớp và proof file của các gói vẫn đủ, app
chỉ chạy fast path (entry point, wrapper `rg`, env, resolver, marker) và bỏ qua bước quét
`bin/`, `lib/`, `lib/node_modules`, đọc lại loader/bash. Một `pkg install`, `pkg repair` hay app
update làm fingerprint đổi và kích hoạt lại lượt đầy đủ, nên self-heal vẫn giữ nguyên.

### 2.6 Cái khác đáng ghi

- **Locale**: `LANG=C.UTF-8` luôn được set (glibc không có locale dir này sẽ warning trên
  node/gh/editors). Termux glibc thường đi kèm C.UTF-8, nên set mặc định + đắp được nếu trống.
- **`TZDIR`**: chỉ định `$PREFIX/share/zoneinfo` khi thiếu, tránh Java/Python/GNU time đoán mò
  `/usr/share/zoneinfo` (không có trên Android).
- **npm/pip cache ra ngoài prefix**: `npm_config_cache`/`PIP_CACHE_DIR` (app đã có).
- **Scratch exec được**: app giữ `/data/data/…` (W^X chỉ gặp từ targetSdk 29+, app target 28)
  nên không cần mirror sang chỗ khác.
- **Chỉ deploy khi hash khớp**: stage tarball gắn hash nội dung thay vì chỉ hash danh sách gói,
  tránh deploy một tarball cũ. Ý tưởng "hash nội dung thay vì danh sách" đáng nhớ nếu app sau
  này cache tarball bootstrap.

## 3. Lớp dịch đường dẫn lúc chạy (bản đồ kỹ thuật)

Khi một binary không nằm trong kế hoạch relocation (tải thêm, wheel có ELF, tool tự build),
app dùng `liboc-pathmap.so`: một thư viện `LD_PRELOAD` dịch đường dẫn ở mức libc. Thiết kế
chi tiết ở [`RUNTIME_PATH_HOOK.md`](RUNTIME_PATH_HOOK.md); phần này tóm tắt các nhóm symbol và
quyết định chính.

| Nhóm | Nội dung |
| --- | --- |
| Dịch path | `map_path()`: hai root Termux → `$PREFIX`/`$HOME`; `map_fhs()`: `/usr`, `/etc`, `/tmp`, `/var`, `/root`, `/dev/shm`, `/bin` (trừ `/bin/sh`); loại trừ mount Android (`/apex /data /dev /odm /proc /sys /system /sdcard`,…). Hot path chỉ `strncmp`, không `snprintf` |
| Mở file | `open`/`openat`/`openat2` (kể cả `_FORTIFY_SOURCE` variants), `creat`, `fopen`/`freopen`, `mkstemp`/`mkstemps`/`tmpfile`/`mkdtemp` — `TMPDIR` được áp dụng cả cho chương trình không đọc biến này |
| Thao tác file | `access`/`faccessat`/`eaccess`, `chmod`/`fchmod(at)`, `unlink(at)`, `mkdir/mkdirat`, `inotify_add_watch` (tự tạo thư mục config thiếu), `readlink`, xattr, `realpath`, `scandir`, `glob`, `ftw`/`nftw`, `dlopen` |
| Exec | `execve`/`execv`/`execvp`/`execvpe`/`fexecve`, `execl*`, `posix_spawn`/`posix_spawnp`: ELF glibc chạy qua `ld.so --library-path --preload`; script chạy qua interpreter thật (kể cả `#!/usr/bin/env`); binary bionic/tĩnh nhận môi trường sạch, không preload |
| Socket | `bind`/`connect`/`sendto`/`sendmsg` map `sockaddr_un`; tên > 108 byte được hash determinant vào `$PREFIX/tmp/.oc_sock_%016llx`; `getsockname`/`getpeername` trả cách viết gốc; `accept`/`dup`/`close` theo dõi record |
| Port thấp | `bind` < 1024 → `+8000`, ghi marker cho UI; `connect` loopback retry sang port đã dịch |
| Shell | `system`/`popen`/`pclose` chạy shell glibc trong prefix qua `posix_spawn`, fallback `/system/bin/sh` |
| Syscall | SIGSYS handler: `set*id` → 0, `clone3`/`rseq`/syscall ngoài allowlist → `ENOSYS`; `getgroups` → 1 |
| Tĩnh/Go | `native/gosys` vá call site `svc #0` (Go một site `Syscall6`, C tĩnh mọi site) |

### 3.1 Điểm quan trọng nhất với app này

1. **`LD_PRELOAD` translation là "cách vá cho nhiều thư viện" thực sự**: mọi đường dẫn nhúng
   sai (`/usr`, `/etc`, `/tmp`…) dùng được mà không đụng vào file. Nhược điểm: cost phải trả ở
   từng call đi qua file path (`stat` tăng vài phần trăm) và nhiều bề mặt phải giữ khớp nhau
   (`openat2`, `posix_spawn`, fortify variants…).
2. **Triết lý kết hợp**: relocation cho toolchain chính (an toàn, không runtime cost), hook
   như một **lưới vét cho binary không nằm trong plan** (binary người dùng tải thêm, wheel có
   ELF, static Go/C).
3. **Chi tiết socket 108 byte**: app đã gặp vấn đề này với `$PREFIX/run` của `opencode-pty`;
   hash-truncated socket name dùng được ngay trong bridge/pty nếu sau này path hết fit.
4. **SIGSYS policy table**: `set*id` → 0, còn lại → `ENOSYS` là bản tóm tắt gọn nhất về syscall
   Android chặn; cần đúng các mặc định này cho glibc ngoài Termux và binary tĩnh.
5. Kỹ thuật Go/C patch mạo hiểm, chỉ làm khi có nhu cầu thật: module mỏng, giữ kích thước file,
   có magic chống vá lần hai và logic fallback khi không ghi được.

## 4. Đề xuất cho app (xếp theo thứ tự đáng làm)

### 4.1 Nhanh — env + data, không đụng native (thực hiện được ngay)

| # | Vá mới | Cụ thể |
| --- | --- | --- |
| 1 | Thêm `REQUESTS_CA_BUNDLE` (+ `PIP_CERT`) | python/pip một số đường đi ưu tiên cấu hình qua certifi/requests thay vì `SSL_CERT_FILE` |
| 2 | `LANG=C.UTF-8` mặc định | 1 dòng trong `buildPackageEnvironment()` |
| 3 | `TZDIR=$PREFIX/share/zoneinfo` khi có | tránh Java/Python tìm `/usr/share/zoneinfo` |
| 4 | JAVA: đổi thứ tự `LD_LIBRARY_PATH` | `$JAVA_HOME/lib/server` đứng **trước** `lib` (libjli re-exec `/proc/self/exe` khi `lib/server` không đứng đầu) |
| 5 | `GIT_SSL_CAINFO` biến env | App đã dùng `http.sslCAInfo` trong gitconfig; biến env giúp git chạy ngay cả khi không đọc được config |
| 6 | Hardening env: mọi biến chỉ set khi file/dir tương ứng tồn tại | cùng nguyên tắc với `LD_PRELOAD`: biến trỏ tới thứ không có còn tệ hơn biến không được set |

### 4.2 Trung bình — sửa cấu trúc nhỏ trong Kotlin

| # | Vá mới | Cụ thể |
| --- | --- | --- |
| 7 | **CA bundle như asset**: ship `cacert.pem` trong `app/src/main/assets/`, materialize vào `etc/tls/cert.pem` với checksum SHA-256, idempotent | TLS sáng ngay lần đầu, kể cả khi trích xuất gói lỗi |
| 8 | **Proof-binary mở rộng**: bảng `pkg → list file phải có` cho mỗi gói mới được thêm (hiện có `BINARY_PROOF`; chỉ cần thêm dòng khi cài thêm gói) | bắt gói thiếu file thay vì chỉ kiểm tra shell |
| 9 | **Launcher repair pattern**: nếu app sinh launcher script cho gói build/codegen, dùng `#!/system/bin/sh` + `${PREFIX:?...}` để fail rõ ràng khi env thiếu | học từ AndroidBuildToolchain.rewriteLauncher |
| 10 | **Bảng libc "blocked syscall"**: đối với glibc, phần chạy static không có; nhưng nếu sau này glibc bị sửa (Ubuntu) hoặc `apt` thành thuật toán chạy trong prefix, dùng SIGSYS policy nội bộ (`set*id=0`, còn lại `ENOSYS`) | tài liệu hóa trong docs, chưa cần code |

### 4.3 Dài hạn — chỉ khi có nhu cầu thật (cân nhắc kỹ)

| # | Ý tưởng | Giải thích |
| --- | --- | --- |
| 11 | **Preload path-translation** | Một `.so` nhỏ (glibc) `liboc-pathmap.so` map `/data/data/com.termux/.../X` → `$PREFIX/X` theo thiết kế ở [`RUNTIME_PATH_HOOK.md`](RUNTIME_PATH_HOOK.md); lợi: binary tải thêm/tự override phần path nhúng được mà không cần vá; hại: phải giữ hot-path đó nhanh (không snprintf) và cover đủ `openat2`, `posix_spawn`, `getcwd`, `/proc/self/exe`, unix socket; lưu ý: nó không giúp binary tĩnh |
| 12 | **Go static patch** (như `gosys.c`) | `gh`, `fzf`… không dùng libc nên preload vô hiệu; chỉ đáng làm khi người dùng thường tải Go binary trong terminal |
| 13 | **C static patch** (`static_programs.c`) | cùng lý do như #12 nhưng cho binary C tĩnh (`gcc -static`, Rust musl…); dùng chung blob `oc-gosys` và policy, chỉ khác cách nối tại mọi site `svc #0` |

### 4.4 Bảng "thư viện → vấn đề → vá" (trạng thái hiện tại)

| Thư viện / binary | Vấn đề áp lên prefix | Điều kiện xuất hiện | App đã vá? |
| --- | --- | --- | --- |
| glibc loader + libc | PT_INTERP / thư viện theo com.termux | mọi pkg cài qua core | ✅ relocation + alias |
| OpenSSL (node/curl/gh) | config/modules path cũ | cài `openssl`, `nodejs`… | ✅ OPENSSL_CONF/MODULES/ENGINES |
| libcurl | CA file cứng cert.pem prefix cũ | mọi HTTPS | ✅ GIT_CONFIG sslCAInfo + SSL_CERT_FILE/CURL_CA_BUNDLE (đã đủ) |
| git | GIT_EXEC_PATH/SHELL_PATH/templates/system config/gitattributes | cài git | ✅ hết |
| bash | profile shebang + env unset LD_PRELOAD | mọi tty | ✅ |
| Python | prefix trong sysconfig; pip cache; REQUESTS | cài `python`/`pip` | ✅ relocation che phần lớn + `REQUESTS_CA_BUNDLE`/`PIP_CERT` |
| Perl | `@INC` biên dịch cứng theo prefix Termux | cài `perl` (và script như `cowsay`) | ✅ `PERL5LIB` trỏ tới `lib/perl5/<version>` + `/<arch>-android` của prefix |
| Ruby | `$LOAD_PATH` biên dịch cứng; Ruby 4 không tự nạp RubyGems | cài `ruby`, chạy `gem` | ✅ `RUBYLIB` + `RUBYOPT=-rrubygems` |
| JDK | RUNPATH + lib/library-path thứ tự | cài `openjdk-*` | ✅ `JAVA_HOME` + `lib/server` đứng đầu `LD_LIBRARY_PATH` |
| Node | OPENSSL_CONF fatal nếu EACCES; xattrs/inotify `config` dir thiếu | cài nodejs → opencode | ⚠️ conf đã có; inotify mkdir đã có trong pathmap khi cần |
| dpkg/apt (nếu cài) | fakeroot, mode 0 mkdir, seccomp set*id | cài cùng `glibc` gói glibc | ❌ chưa cần — app dùng `pkg` bridge bionic riêng |
| Binary tĩnh (Go / C) | syscall tự làm, path sai | kết quả tải thêm trong terminal | ✅ `oc-run` vá Go và C tĩnh AArch64 tại `svc #0`; glibc động dùng `oc-run` + `liboc-pathmap.so` |

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
  được chạy qua loader thật với `--preload`. Cùng bộ assertion đó chạy thêm trên một fixture
  C tĩnh (`aarch64-linux-gnu-gcc -static -no-pie`) để kiểm tra vá `svc #0`, `fork()` qua
  `clone` không bị vá, và việc từ chối vá lần hai. Không cần thiết bị thật để kiểm tra phần logic;
  phần Android/SELinux vẫn nên thử trên máy thật trước khi phát hành.

## 6. Trạng thái triển khai

Các mục 4.1, 4.2 và 4.3 đã được triển khai. Các helper native của 4.3 và daemon terminal C được
build trong CI (workflow `native.yml`) và nhúng vào APK dưới `assets/native/<abi>/`; app trích xuất
chúng khi cài đặt/bảo trì. Một APK build cục bộ không có asset native vẫn chạy được shell: `oc-run`
rót lệnh trực tiếp và các binary tải thêm chỉ chạy với path cũ, nhưng panel Terminal trên web cần
riêng `opencode-pty` để mở shell.

| Mục | Trạng thái | Vị trí |
| --- | --- | --- |
| 4.1 #1 `REQUESTS_CA_BUNDLE` + `PIP_CERT` | ✅ | `buildPackageEnvironment`, env của server, `OpenCodeGitHub` |
| 4.1 #2 `LANG=C.UTF-8` mặc định | ✅ | env script, profile, installer, server, gh |
| 4.1 #3 `TZDIR` khi có `share/zoneinfo` | ✅ | env script, installer, server, gh |
| 4.1 #4 `lib/server` đứng đầu `LD_LIBRARY_PATH` | ✅ | env script, env của server |
| 4.1 #5 `GIT_SSL_CAINFO` | ✅ | env script, env của server, `OpenCodeGitHub` |
| 4.1 #6 chỉ export khi file/dir tồn tại | ✅ | toàn bộ env script + env của server/gh |
| 4.2 #7 CA bundle trong APK | ✅ | `assets/net/mozilla-ca-bundle.pem`, `OpenCodeBootstrap.ensureCaBundle` |
| 4.2 #8 proof-binary mở rộng | ✅ | `OpenCodePackages.BINARY_PROOF` (thêm JDK, d8, r8, apksigner, aapt2) |
| 4.2 #9 launcher repair pattern | ✅ | `OpenCodePackages.JavaLaunchers` (d8/r8/apksigner) |
| 4.2 #10 bảng syscall bị chặn | ✅ tài liệu | mục 7 dưới đây |
| Python `multiprocessing`: đường dẫn semaphore cắm cứng của Termux | ✅ | `TermuxSemaphorePath`, `OpenCodePackages.extractEntry` / `repair` |
| 4.3 #11 preload path translation | ✅ | `native/pathmap`, `OpenCodeBootstrap.installNativeTools`, `oc-gopatch run` |
| 4.3 #12 Go static patch | ✅ (AArch64) | `native/gosys`, `native/gopatch`, `oc-run` |
| P2 C tĩnh `svc #0` | ✅ (AArch64) | `native/gopatch/patchc.go`, `native/gosys/cstub.S`, `OC_STATIC_PATCH` |
| Web terminal: daemon C/bionic thay helper glibc theo phiên bản | ✅ | `native/pty-c`, `OpenCodeBootstrap.ensureTerminalRuntime`, `OPENCODE_PTY_BIN` |

Ghi chú cho #7: asset được kiểm tra SHA-256 trước khi ghi, ghi nguyên tử vào
`etc/tls/cert.pem`, và **không** ghi đè một bundle hợp lệ đã có. Như vậy bundle mới hơn
của gói `ca-certificates` không bị thay bằng bản snapshot cũ của APK; asset chỉ dùng khi
file thiếu hoặc bị cắt cụt (ví dụ sau khi app bị kill giữa lúc trích xuất).

Ghi chú cho `multiprocessing`: `libandroid-posix-semaphore.so` của Termux cắm cứng
`/data/data/com.termux/files/usr/tmp/sem.`, nên `sem_open` của Python chết với
`FileNotFoundError` (locks, queue, `Pool`, `ProcessPoolExecutor`). `TermuxSemaphorePath`
đổi chuỗi đó thành `/proc/self/fd/9/sem.` (19 byte, ngắn hơn 40 byte gốc nên không xê dịch
offset nào) và tạo thư mục `tmp` ở gốc sandbox — không phải `$PREFIX/tmp`. FD 9 được
`pathmap`, `opencode-pty`, `oc-launcher` và JNI `pty.cpp` mở sẵn tới thư mục đó và giữ qua
`execve`, nên alias hoạt động độc lập với application id, user id hay work profile. Giá trị thư mục
thật được export dưới tên `OC_SEMAPHORE_DIR` trong runtime contract, env script và env
của terminal. Việc vá chạy cả lúc trích xuất gói lẫn trong `pkg repair`, nên prefix cài
từ bản cũ được sửa mà không cần cài lại.

## 7. Bảng chính sách syscall Android cho binary glibc (4.2 #10)

Chưa cần code vì glibc hiện tại là build của Termux (đã relocation) và mọi process đều
đi qua loader của prefix. Bảng dưới đây là mặc định tham chiếu nếu sau này app
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

Với binary tĩnh/Go, bảng này phải được áp ngay tại call site `svc #0` (`native/gosys` xử lý cả
Go tĩnh lẫn C tĩnh); chỉ `LD_PRELOAD` không can thiệp được syscall do chương trình tự phát.

## 8. Helper native của 4.3 (cách dùng và giới hạn)

APK có thêm bốn file native. Ba file đầu được cài bởi `OpenCodeBootstrap.installNativeTools()`;
`opencode-pty` được cài riêng bởi `ensureTerminalRuntime()` vì nó phục vụ server web chứ không phải
lệnh `oc-run`:

| File | Vai trò |
| --- | --- |
| `$PREFIX/lib/liboc-pathmap.so` | thư viện glibc `LD_PRELOAD`, map hai root Termux sang prefix của app |
| `$PREFIX/libexec/oc-gopatch` | binary Go tĩnh: phân loại ELF, chạy glibc qua loader + preload, vá Go tĩnh và C tĩnh rồi chạy |
| `$PREFIX/bin/oc-run` | vỏ `#!/system/bin/sh` gọi `oc-gopatch run -- "$@"`, fallback chạy trực tiếp |
| `$HOME/.opencode/bin/opencode-pty` | daemon C/bionic của panel Terminal trên web, nói giao thức protocol 9 với server OpenCode |

`opencode-pty` được build từ `native/pty-c` bằng NDK clang, đóng gói tại
`assets/native/<abi>/opencode-pty` và chạy trực tiếp như một ELF bionic. Nó giữ nguyên
`OPENCODE_PTY_BIN`/`OPENCODE_PTY_SOCKET_DIR` và giao thức protocol 9 của OpenCode; đây là lý do
panel Terminal trên web không còn phụ thuộc vào asset glibc nhúng trong từng phiên bản CLI.

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
  --library-path $PREFIX/lib:$PREFIX/glibc/lib PROGRAM`. Thư viện hook open/openat/creat,
  stdio, stat/statx/statfs/statvfs, access, mkdir/rmdir/unlink/remove/rename/link/symlink,
  mknod/mkfifo, chmod/chown/truncate, utimensat/utimes/utime/lutimes/futimesat, xattr,
  readlink/realpath/canonicalize_file_name/getcwd, inotify, scandir/scandirat,
  glob, ftw/ftw64/nftw/nftw64, dlopen/dlmopen,
  execve/execv/execvp/execvpe/execveat/fexecve, execl/execlp/execle,
  posix_spawn/posix_spawnp và `posix_spawn_file_actions_addopen/addchdir_np`.
  Danh sách đầy đủ theo nhóm ở mục 8.1.
  Nó map `/data/data/com.termux/files/usr` → `OC_PREFIX` và
  `/data/data/com.termux/files/home` → `OC_USER_HOME`, đồng thời cài SIGSYS handler cho
  bảng ở mục 7. Hot path chỉ là `strncmp`, không cấp phát.
  - Khi exec/spawn một **ELF glibc** (hoặc script có interpreter glibc), thư viện chèn
    `LD_PRELOAD`, `OC_PREFIX`, `OC_USER_HOME` vào môi trường con nếu thiếu, kể cả khi
    chương trình gọi với `env -i`. Target bionic/tĩnh không bị chèn, vì nạp `.so` glibc vào
    bionic sẽ abort.
  - `OC_DEBUG=1` làm handler SIGSYS ghi `oc-pathmap: SIGSYS <nr> emulated|-> ENOSYS` bằng
    `write(2)` (không `printf`/`malloc` trong signal), và `oc-gopatch run` in
    `strategy=script|glibc|go|static|pass` ra stderr.
  - `openat2`/`faccessat2` vẫn có hook nhưng trên Android syscall bị seccomp trap, nên handler
    trả `ENOSYS` và libc tự fallback; "có hook" không có nghĩa là syscall chạy được.
  - Callback của `nftw`/`ftw` nhận path vật lý của prefix (không unmap), vì thư viện không
    thể thay con trỏ hàm của caller; `glob` thì unmap được vì kết quả nằm trong `gl_pathv`.
  - Script `#!/usr/bin/env ...` do một chương trình glibc exec sẽ đi qua `env` bionic, nên
    preload không được truyền tiếp; chạy script qua `oc-run` (như bình thường) thì không gặp
    giới hạn này.
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
- **C tĩnh (AArch64, glibc/musl)**: binary không có `PT_INTERP` cũng tự phát `svc #0`, nên
  patcher quét **mọi** site `svc #0` trong các `PT_LOAD` có cờ `X` và thay mỗi site bằng
  trampoline 5 lệnh `str x30,[sp,#-16]!` / `bl oc_c_stub` / `bti c` / `ldr x30,[sp],#16` /
  `b site+4`. `oc_c_stub` (trong cùng blob `oc-gosys`) lưu/khôi phục `x1`–`x18`, `x29`,
  `x30`, `NZCV`, đặt `x6 = x8` (số syscall là tham số thứ 7 của `oc_stub_entry`) rồi gọi
  stub chung; nhờ vậy mọi site dùng cùng một policy và cùng bảng dịch path mà không cần biết
  số syscall tại chỗ. Chỉ hai site được để nguyên: `clone` (220, đổi stack nên `x30` đã lưu
  sẽ bị đọc từ stack của tiến trình con) và `rt_sigreturn` (139, không trở lại sau `svc`);
  glibc nạp số `220` bằng `mov x8, #0xdc` tĩnh nên bộ giải mã nhận ra và bỏ qua, còn `139`
  không xuất hiện tĩnh (nằm ở restorer/vDSO). Blob được nối vào cuối file, segment cuối
  (theo vaddr) được nới `p_filesz` tới hết vùng trampoline và `p_memsz` tới hết scratch
  64 KB, và cờ `X` được bật; vùng BSS cũ `[off+filesz, need)` phải được zero trong file, nếu
  không section header tràn vào BSS làm glibc hỏng. Nếu segment cuối không kết thúc file hoặc
  không ghi được thì patch bị từ chối và `oc-run` chạy bản chưa vá. Bản đã vá mang magic
  `OCGOSYS1` nên không bị vá lần hai. Tắt bằng `OC_STATIC_PATCH=0`.
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

Bộ test mới của `liboc-pathmap.so` (chạy native trên máy arm64 với glibc của prefix, không
qua qemu) đã qua hết: `statx`, `utimensat`/`utimes`/`utime`, `statfs`/`statvfs`,
`getxattr`/`setxattr`, `euidaccess`, `canonicalize_file_name`, `remove`,
`mkfifo`/`mknod`, `scandir`, `glob` (kết quả được unmap về spelling Termux),
`ftw`/`ftw64`/`nftw`/`nftw64`, `dlopen` và `dlmopen` một plugin nằm trong prefix, và ca quan
trọng nhất — `execve` chương trình test với môi trường sạch (`chỉ có OC_TEST_CHILD=1`): con vẫn
thấy `oc_pathmap_active`, `OC_PREFIX`, `OC_USER_HOME` và
`stat("/data/data/com.termux/files/usr/child-check")` thành công.
`OC_DEBUG=1` cũng ghi đúng `oc-pathmap: SIGSYS 437 -> ENOSYS` khi gọi `openat2`.

Đã cài thử bằng `pkg` trên máy thật: `tree jq fzf`, `python python-pip`,
`nodejs npm ripgrep fd bat sqlite openssh`, `openjdk-17 d8 apksigner`,
`clang make cmake ninja pkg-config` — cài xong chạy được
(`node -e`, `npm --version`, `rg`, `fd`, `bat`, `sqlite3`, `ssh -V`, `java -version`, `javac`,
`d8`, `apksigner`, `clang hi.c -o hi` rồi chạy, `make 4.4.1`, `cmake 4.4.5`, `ninja 1.13.2`,
`pkg-config 0.29.2`). `pkg install yq` (binary Go trong repo, v4.54.1) cũng chạy trực tiếp
sau khi relocation — không cần `oc-run`, vì phần vá Go chỉ dành cho binary tải về ngoài repo.
Một project Android Java thật (WebView + HTTP proxy, build bằng script không Gradle) cũng build
trọn trên máy: `aapt2` 16.0.0.4-4 (2.20-android-16.0.0_r4), `d8` 9.2.4, `apksigner` 0.9,
`zip` 3.0, `javac` 21.0.13 và `python3` tạo ra APK 62 KB, ký V3, `aapt2 dump badging` đúng
package/version/minSdk/targetSdk; project không cần thư viện Java ngoài, chỉ `android-33.jar`.
Mạng là điểm yếu duy nhất: hai lượt cài hỏng giữa chừng (ở `libicu` và
`libllvm`) vì `Unable to resolve host "packages.termux.dev"`, chạy lại lệnh thì tiếp tục và
hoàn tất; các gói đã trích trước đó vẫn dùng được. App giờ thử lại 3 lần cho mỗi mirror với
backoff khi lỗi là DNS/connect/socket timeout; lỗi HTTP và checksum vẫn chuyển mirror ngay.
Script do `npm install -g` tạo ra có shebang
`#!/usr/bin/env node`, mà Android không có `/usr/bin`, nên chạy trực tiếp báo
`bad interpreter`; `oc-run cowsay` chạy ngay, còn để gõ thẳng tên lệnh thì cần thêm bước
`repair` (đã thêm lệnh `pkg repair`, và mỗi `pkg install` cũng chạy lượt repair này). Từ bản này
extractor/repair map shebang trỏ tới interpreter không tồn tại trên máy (`#!/usr/bin/env node`,
`#!/bin/bash`) sang `$PREFIX/bin`, giữ nguyên `/bin/sh` của Android.

Project Android dùng Gradle cần thêm ba thứ, giờ app tự đặt trong env và repair:

- `sdkmanager`/`avdmanager`/`apksigner` của SDK cũng mở đầu bằng `#!/usr/bin/env`; Android không
  có `/usr/bin`. `pkg repair "$ANDROID_HOME"` (và lượt maintenance khi app khởi động) quét
  `cmdline-tools/*/bin`, `tools/bin`, `build-tools/*` rồi đổi shebang sang `$PREFIX/bin/env`
  giống script của gói. Env script tự phát hiện SDK trong `$HOME/Android/Sdk`,
  `$HOME/android-sdk`, `$HOME/sdk`, `$PREFIX/opt/android-sdk` và export
  `ANDROID_HOME`/`ANDROID_SDK_ROOT`, thêm `cmdline-tools/latest/bin` + `platform-tools` vào `PATH`.
- JVM của Termux lấy `user.home` là home của app Termux, nên Gradle không tạo được thư mục
  metrics và Robolectric không tạo được lock file. Env script đặt `JAVA_TOOL_OPTIONS` gồm
  `-Duser.home=$HOME -Djava.io.tmpdir=$TMPDIR -Drobolectric.conscryptMode=OFF` (chỉ thêm flag
  còn thiếu, giữ option của người dùng); `GRADLE_USER_HOME=$HOME/.gradle`,
  `ANDROID_USER_HOME=$HOME/.android`. Server OpenCode export đúng bộ biến này cho terminal.
- AGP tải `aapt2` x86_64 từ Maven, không chạy trên ARM64. Gói `aapt2` của Termux được thêm vào
  `pkg bundle java` và tự cài cùng `openjdk-*`; app ghi block quản lý
  `android.aapt2FromMavenOverride=$PREFIX/bin/aapt2` vào `$HOME/.gradle/gradle.properties`
  (giá trị người dùng đã đặt được giữ nguyên). Không cần `-P` trên command line.

Robolectric 4.16.1 vẫn không có native runtime Linux ARM64; `conscryptMode=OFF` chỉ bỏ lỗi JNI
Conscrypt, test nào nạp `DefaultNativeRuntimeLoader` vẫn hỏng (giới hạn upstream). Gradle trên
máy ít RAM nên chạy `--max-workers=1` và đặt `org.gradle.jvmargs=-Xmx2g` (xem README).

`pkg install cowsay` (một script Perl) lộ thêm một lỗi: Perl biên dịch cứng `@INC` theo prefix
Termux nên báo `Can't locate Cwd.pm`. Env script giờ đặt `PERL5LIB` trỏ tới
`lib/perl5/<version>` và thư mục kiến trúc `<arch>-android` của prefix; `cowsay` chạy được.
Ruby 4.0.9 cũng vậy: `require "json"` và `gem` không tìm thấy file vì `$LOAD_PATH` trỏ về prefix
Termux, và bản Ruby 4 không tự nạp RubyGems. `RUBYLIB` + `RUBYOPT=-rrubygems` trong env script sửa
cả hai; `gem install colorize` cài vào prefix và `require "colorize"` chạy.

### 8.1 Hàm đã hook và hàm cố ý bỏ qua

Thư viện chỉ có thể dịch path tại các **entry point của libc mà chương trình gọi**; nó
không nhìn thấy syscall do chương trình tự phát, path nằm trong `argv`/biến môi trường, hay
path đã được kernel trả về qua một fd. Bảng dưới đây là hợp đồng hiện tại (117 symbol export,
trong đó `oc_pathmap_active` là marker; `_init`/constructor bị ẩn).

| Nhóm | Hàm đã hook |
| --- | --- |
| Mở / tạo file | `open`, `open64`, `openat`, `openat64`, `openat2`, `__open_2`, `__open64_2`, `__openat_2`, `__openat64_2`, `creat`, `creat64`, `fopen`, `fopen64`, `freopen`, `freopen64`, `opendir` |
| stat / metadata | `stat`, `stat64`, `lstat`, `lstat64`, `fstatat`, `fstatat64`, `statx`, `statfs`, `statfs64`, `statvfs`, `statvfs64` |
| Kiểm tra quyền | `access`, `eaccess`, `euidaccess`, `faccessat` |
| Thời gian | `utimensat`, `utimes`, `lutimes`, `futimesat`, `utime` |
| xattr | `getxattr`, `lgetxattr`, `setxattr`, `lsetxattr`, `listxattr`, `llistxattr`, `removexattr`, `lremovexattr` |
| Thao tác file / thư mục | `mkdir`, `mkdirat`, `mknod`, `mknodat`, `mkfifo`, `mkfifoat`, `rmdir`, `unlink`, `unlinkat`, `remove`, `rename`, `renameat`, `renameat2`, `chdir`, `chmod`, `fchmodat`, `chown`, `lchown`, `fchownat`, `truncate`, `truncate64`, `link`, `linkat`, `symlink`, `symlinkat` |
| Đường dẫn trả về | `readlink`, `readlinkat`, `realpath`, `canonicalize_file_name`, `getcwd`, `inotify_add_watch` |
| Duyệt thư mục | `scandir`, `scandir64`, `scandirat`, `scandirat64`, `glob`, `glob64`, `ftw`, `ftw64`, `nftw`, `nftw64` |
| Nạp thư viện | `dlopen`, `dlmopen` |
| exec / spawn | `execve`, `execv`, `execvp`, `execvpe`, `execveat`, `fexecve`, `execl`, `execlp`, `execle`, `posix_spawn`, `posix_spawnp`, `posix_spawn_file_actions_addopen`, `posix_spawn_file_actions_addchdir_np` |
| Shell | `system`, `popen`, `pclose` (chạy shell glibc trong `$PREFIX/bin` qua `posix_spawn`) |
| Unix socket | `bind`, `connect`, `sendto`, `sendmsg`, `getsockname`, `getpeername`, `accept`, `accept4`, `close`, `dup`, `dup2`, `dup3`, `close_range` (theo dõi/dọn tên hash) |

Các hàm trả path (`realpath`, `canonicalize_file_name`, `getcwd`, `glob`, `readlink`) được
**unmap tại chỗ** về spelling Termux trước khi trả cho caller; các hàm nhận path thì map
trước khi gọi hàm thật. `ftw`/`nftw` (và bản 64-bit) chỉ map path gốc, không map path trong
callback — xem lý do bên dưới.

Hàm **cố ý bỏ qua**:

| Hàm / cơ chế | Lý do |
| --- | --- |
| `fstat`, `fstat64`, `fchmod`, `fchown`, `fchdir`, `ftruncate`, `ftruncate64`, `futimens`, `fallocate`, `posix_fadvise`, `posix_fallocate`, `sendfile`, `splice`, `mmap` | nhận fd, không có path để dịch; fd đó đã được tạo qua một hook ở trên |
| `posix_spawn_file_actions_addfchdir_np`, `open_by_handle_at`, `name_to_handle_at` | fd/handle-based, không có path; `open_by_handle_at` còn cần quyền mà app không có |
| `mount`, `umount2`, `chroot`, `pivot_root`, `mount_setattr`, `open_tree`, `move_mount`, `fsopen`, `fsmount`, `fspick` | Android seccomp chặn và app không có `CAP_SYS_ADMIN`; dịch path không làm các call này chạy được |
| `syscall(...)` | variadic, nhận số hiệu syscall; hook sẽ phải chặn **mọi** syscall (kể cả syscall nội bộ của libc), tăng chi phí và nguy cơ đệ quy. Path do chương trình tự phát không dịch được; binary tĩnh AArch64 (Go, C) được xử lý riêng bằng `oc-gosys` |
| binary tĩnh AArch64 (Go, C tĩnh) | không nạp được `LD_PRELOAD`; đã vá tại mọi call site `svc #0` bằng `oc-gosys` (xem mục 8), không cần hook |
| binary tĩnh không phải AArch64 (Rust musl x86_64, C static x86_64, …) | `oc-gosys` mới hỗ trợ AArch64; vẫn chạy nhưng path Termux không được dịch |
| `wordexp` | nhận chuỗi lệnh, không phải path; dùng `/bin/sh` của bionic và không có nhu cầu route. `system`/`popen` thì đã có wrapper riêng (xem trên) |
| callback của `ftw`/`ftw64`/`nftw`/`nftw64` | thư viện không thể thay con trỏ hàm của caller, nên callback vẫn thấy path vật lý của prefix; chỉ path gốc được map |
| `exec` | bảng record Unix socket không theo dõi `exec`; một fd sống qua exec có thể mất cách viết gốc (đã theo dõi `dup`/`dup2`/`dup3`/`close_range`) |
| `updwtmp`, `setutent`/`pututline` | đường dẫn utmp/wtmp cố định hoặc rất hiếm dùng trong app Android; có thể thêm nếu có nhu cầu thật |
| path trong `argv`, biến môi trường, nội dung file cấu hình | không phải tham số của hàm libc và không có ý nghĩa chung; chỉ chương trình đích mới biết cách hiểu. Path của chính chương trình và `/proc/self/exe` thì đã được xử lý |

Các cơ chế mới bổ sung cho bảng trên:

- **FHS**: sau hai root Termux, `map_path` dịch `/usr` → `$PREFIX`, `/etc` → `$PREFIX/etc`,
  `/tmp` → `$PREFIX/tmp`, `/var` → `$PREFIX/var`, `/root` → `$HOME`,
  `/dev/shm` → `$PREFIX/tmp/shm`, `/bin` → `$PREFIX/bin` (trừ đúng `/bin/sh`). Tắt bằng
  `OC_FHS=0`.
- **Exec routing**: trong một tiến trình glibc, `execve`/`execvp`/`posix_spawn*` đọc ELF; nếu
  `PT_INTERP` là loader glibc nhưng không tồn tại trên máy, chương trình được chạy bằng
  `ld.so --preload liboc-pathmap.so --library-path <runpath>:$PREFIX/lib:$PREFIX/glibc/lib`.
  Script được exec qua interpreter đã resolve (kể cả `#!/usr/bin/env X`, bỏ qua `env`). Tắt
  bằng `OC_EXEC_ROUTE=0`. Con không phải glibc nhận môi trường đã bỏ `LD_PRELOAD` của
  pathmap và `OC_REAL_EXE`; con được route dùng `--preload` nên `LD_PRELOAD` cũng sạch.
- **`/proc/self/exe`**: khi tiến trình chạy dưới `ld.so`, `readlink`, `open`, `stat`,
  `access`, `realpath` của `/proc/self/exe` trả target thật, lấy từ `OC_REAL_EXE` hoặc dòng
  lệnh `/proc/self/cmdline`; `execve("/proc/self/exe")` cũng đổi sang chương trình thật để
  route/preload áp dụng lại thay vì chạy lại loader trần. `inotify_add_watch` tạo trước cây
  thư mục còn thiếu bằng `mkdir -p` (mode 0700) để watcher không im lặng.
- **Unix socket**: `bind`/`connect`/`sendto`/`sendmsg` map path tuyệt đối trong `sockaddr_un`
  sang bản copy (không sửa `sockaddr` của caller); abstract và path tương đối giữ nguyên. Nếu
  path sau map ≥ 108 byte, tên được thay bằng FNV-1a 64:
  `$PREFIX/tmp/.oc_sock_%016llx` (`bind` tự tạo `$PREFIX/tmp`). `getsockname`/`getpeername`
  trả lại cách viết gốc; `accept`/`accept4` copy record local của listener sang fd mới;
  `dup`/`dup2`/`dup3` copy cả record local+peer+tên hash sang fd mới (và `dup2`/`dup3` dọn
  record cũ của fd đích), nên fd trùng số vẫn giữ cách viết gốc; tên hash chỉ bị `unlink` khi
  pid hiện tại sở hữu listener, dev/ino vẫn khớp và **không còn record nào khác cùng tên hash**
  (nhờ vậy `dup` rồi `close` bản gốc không xoá tên khi bản sao còn sống); `close_range` dọn
  record của mọi fd trong khoảng khi syscall thành công và không có `CLOSE_RANGE_CLOEXEC`;
  `unlink`/`unlinkat`/`remove` cũng xoá tên hash.
- **Port thấp**: `bind()` dịch port `1..1023` thành `port + 8000` (IPv4/IPv6), in cảnh báo
  một lần cho mỗi port gốc và ghi `$PREFIX/run/moved-ports/PORT` (nội dung là port mới) cho
  UI. `connect()` tới loopback (`127/8`, `::1`) thử port đã dịch khi connect gốc trả
  `ECONNREFUSED`; `getsockname()` vẫn trả port thật. Phía app, `MovedPorts` parse marker và
  `MovedPortNotifier` thông báo/consume; tắt bằng `OC_LOW_PORTS=0`.
- **`system`/`popen`**: tìm `$PREFIX/bin/sh` rồi `$PREFIX/bin/bash` và chỉ nhận file glibc
  (`PT_INTERP`). Có shell thì dựng con bằng `posix_spawn()` của thư viện (route + preload áp
  dụng lại), `system()` giữ block `SIGCHLD` + ignore/reset `SIGINT`/`SIGQUIT`; `popen()` dùng
  `pipe` + fd actions và `pclose()` lấy pid từ bảng 32 record. Bảng có spinlock, và
  `pthread_atfork` child handler reset lock để `fork()` không làm con deadlock. Không có shell
  glibc thì gọi hàm thật (`/system/bin/sh`). Tắt bằng `OC_SYSTEM=0`.

Ngoài ra, ngay cả hàm **đã hook** vẫn có thể không chạy trên Android: `openat2` và
`faccessat2` bị seccomp trap, SIGSYS handler trả `ENOSYS` để libc fallback sang
`openat`/`faccessat`. Vì vậy "có hook" không đồng nghĩa với "syscall đó thực thi được".

### 8.2 Giới hạn có chủ ý

- chỉ vá binary tĩnh **AArch64**: Go tĩnh (một site `Syscall6`) và C tĩnh (mọi site `svc #0`);
  binary tĩnh x86_64 có mã lệnh khác và chưa hỗ trợ (vẫn chạy, nhưng path Termux không được
  dịch);
- vá C tĩnh bỏ qua `clone` (220) và `rt_sigreturn` (139), chỉ chạy khi segment cuối theo vaddr
  kết thúc file và ghi được, và từ chối bản đã mang magic `OCGOSYS1`;
- Unix socket chỉ dịch path tuyệt đối trong `bind`/`connect`/`sendto`/`sendmsg`; bảng record
  theo dõi `dup`/`dup2`/`dup3`/`close_range` nhưng **không** theo dõi `exec`, nên một fd sống
  qua `exec` có thể mất cách viết gốc; path đúng 108 byte không có NUL được giữ nguyên;
- không dịch path nằm trong `argv`/biến môi trường của chương trình đích (path của chính
  chương trình và `/proc/self/exe` thì có); các chương trình cần path trong argv vẫn phải
  được relocation như gói cài qua `pkg`;
- FHS chỉ là phép thay tiền tố: nó không tạo file, không đổi quyền và không làm cho một path
  tuyệt đối của Android (`/system`, `/data`, `/apex`, `/proc`, `/sys`…) trở nên ghi được;
  `/bin/sh` cố ý không map để script Android giữ shell bionic (system/popen chỉ dùng shell
  glibc trong prefix khi có, xem dưới);
- exec routing chỉ áp dụng cho ELF có `PT_INTERP` là loader glibc **không tồn tại**. Binary đã
  có interpreter trỏ sẵn vào loader của prefix, binary bionic và binary tĩnh chạy đúng như cũ.
  `execveat`/`fexecve` chỉ map path, không route (route cần tên file để đọc ELF);
- script không có shebang, shebang trỏ tới interpreter không resolve được, hoặc vượt quá
  `PATH_MAX` vẫn để kernel xử lý và có thể trả `ENOEXEC`;
- `inotify_add_watch` chỉ tạo thư mục khi path **đã được map** sang prefix/home; lỗi tạo
  thư mục bị bỏ qua và hàm thật vẫn trả lỗi của nó (không che `ENOSPC`/`EACCES`);
- port thấp chỉ dịch `1..1023` (port 0 ephemeral giữ nguyên), không đổi `getsockname()` nên
  caller thấy port thật; retry trong `connect()` chỉ áp dụng cho loopback và khi connect gốc
  trả `ECONNREFUSED` (connect non-blocking trả `EINPROGRESS` không retry); marker
  `$PREFIX/run/moved-ports/PORT` được ghi lại mỗi lần bind thành công, còn stderr chỉ cảnh
  báo một lần cho mỗi port gốc trong tiến trình;
- `system`/`popen` chỉ route khi `$PREFIX/bin/sh` hoặc `$PREFIX/bin/bash` là ELF glibc; nếu
  shell biến mất giữa lúc kiểm tra và `posix_spawn`, `system()` rơi về hàm thật với `ENOENT`.
  `popen` giữ tối đa 32 stream cùng lúc (bảng record cố định); quá giới hạn thì đóng stream,
  `waitpid` con và trả `EMFILE`. Bảng dùng spinlock; `pthread_atfork` reset lock trong con nên
  `fork()` không để lại deadlock, bảng record kế thừa vẫn nguyên vẹn và con có thể tạo record
  mới. `system()`/`popen()` không dùng `/bin/sh` đã map, nên `OC_FHS=0` không ảnh hưởng tới việc
  chọn shell;
- `preload_env` dựng môi trường trong arena static TLS 16 KB mỗi thread; môi trường lớn hơn
  sẽ nở một vùng `mmap` ẩn danh (64 KB → 16 MB) chứ **không** quay lại `malloc`, nên cả nhánh
  lớn cũng an toàn sau `fork`; `test_exec_large_env` dùng 6 000 biến để đi nhánh `mmap`;
- `OC_FHS=0` tắt bảng FHS, `OC_EXEC_ROUTE=0` tắt exec routing, `OC_LOW_PORTS=0` tắt dịch port
  thấp, `OC_SYSTEM=0` tắt wrapper shell, `OC_STATIC_PATCH=0` tắt vá C tĩnh, `OC_REAL_EXE` chỉ có
  tác dụng khi tiến trình chạy dưới loader; không có biến nào ảnh hưởng tới hai root Termux;
- binary tĩnh AArch64 (Go hoặc C) được dịch path; binary tĩnh x86_64 và các kiến trúc khác thì
  không;
- patcher cần một khoảng zero đủ lớn giữa các segment hoặc cuối file; nếu không có, nó báo lỗi
  và `oc-run` chạy binary chưa vá thay vì từ chối;
- việc vá sửa file đích (bản cache), không sửa file người dùng tải về.
