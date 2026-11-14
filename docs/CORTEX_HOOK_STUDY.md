# Nghiên cứu `libcortex-hook.so` — kỹ thuật đáng áp dụng cho app này

Tài liệu bổ sung cho [`LIBRARY_FIXUPS.md`](LIBRARY_FIXUPS.md), lần này chỉ soi **một** dự án
tham chiếu: Cortex (`ssfuisu/cortex`), cụ thể là preload library `libcortex-hook.so` của nó.
Mục tiêu không phải mô tả lại Cortex, mà là **gap analysis**: app này đã có gì (mục 3), còn
thiếu gì so với Cortex (mục 4), nên port gì trước (mục 5–6), và cái gì không nên port
(mục 7).

## 0. Nguồn và giấy phép

| | |
| --- | --- |
| Repo | <https://github.com/ssfuisu/cortex> |
| Commit đọc | `2e95f52757079efba24673acd9797e7669297237` (2026-11-14) |
| Giấy phép | Apache-2.0 |
| Vị trí | `app/src/main/cpp/hook/` (20 file, 11 553 dòng) + `app/src/main/cpp/cortex-hook.c` (file gộp, 101 dòng) |
| Cách build | một translation unit: `cortex-hook.c` `#include` lần lượt 20 file `.c`, mỗi file khai báo hook bằng macro `EXPORT` |
| Giấy phép của app này | MIT |

Vì app này MIT còn Cortex Apache-2.0: **học ý tưởng và viết lại** thì không vướng gì; nếu
copy nguyên một đoạn code thì phải giữ header/NOTICE của Apache-2.0 và ghi rõ nguồn trong
file. Các đề xuất dưới đây được viết theo hướng tự viết lại trong `native/pathmap/pathmap.c`
(khoảng 1 500 dòng, đã có sẵn `map_path`, `preload_env`, `EXPORT`, SIGSYS policy), không
thêm translation unit mới.

## 1. Hai kiến trúc, cùng một ý tưởng

Cả hai đều **không** dùng ptrace/chroot: một `LD_PRELOAD` shim dịch đường dẫn ở mức libc và
một loader glibc được gọi tường minh.

| | Cortex | OpenCode for Android |
| --- | --- | --- |
| Hệ điều hành giả lập | Ubuntu 24.04 rootfs (FHS đầy đủ) | Termux prefix (không FHS) |
| Đường dẫn gốc | `/usr`, `/etc`, `/tmp`, `/var`, `/root`, … → `$CORTEX_ROOT` | `/data/data/com.termux/files/usr` + `/home` → `$PREFIX` / `$HOME` |
| Dịch đường dẫn | `rewrite_path()` theo bảng mount, loại trừ mount của Android | `map_path()` với đúng 2 tiền tố |
| Khởi động chương trình glibc | `execve` → `ld.so --preload libcortex-hook.so --library-path …` | `oc-run` → `ld.so --preload liboc-pathmap.so --library-path $PREFIX/lib:$PREFIX/glibc/lib` |
| Chương trình trong prefix | không sửa file, hook lo tất cả | sửa file: relocate chuỗi, PT_INTERP, RUNPATH, shebang |
| uid | fakeroot (uid 0 giả) | uid thật của app, không giả |
| Chương trình tĩnh | vá `svc #0` (C tĩnh + Go) | vá Go qua `native/gosys`; C tĩnh chưa |
| Bề mặt hook | 217 hàm export | 101 hàm export |

Điểm mạnh của cách app này đang làm: **không sửa hành vi của chương trình đã cài** (relocate
chuỗi cùng độ dài + patchelf là thay đổi tối thiểu, có self-heal), nên `opencode`, `node`,
`git`, `bash` chạy như native. Điểm yếu: mọi thứ **người dùng tải thêm** (binary GitHub
release, wheel có ELF, tool tự build) vẫn phải đi qua `oc-run`, và một số đường dẫn tuyệt đối
kiểu FHS vẫn trượt. Đó chính là khoảng trống mà hook của Cortex lấp.

## 2. Bản đồ 20 phần của hook Cortex

| File | Dòng | Nội dung | Liên quan tới app này |
| --- | ---: | --- | --- |
| `exec_probe.c` | 1 340 | đọc ELF, `PT_INTERP`, `DT_RUNPATH`/`DT_RPATH`, chọn `ld.so` theo `e_machine`, dựng `LD_LIBRARY_PATH` | **P0** |
| `exec.c` | 1 098 | `execve`/`execvp`/`posix_spawn`, shebang, `--argv0`, chuẩn bị trước `fork` | **P0** |
| `net.c` | 1 001 | DNS UDP thô khi `getaddrinfo` fail, redirect loopback, `getaddrinfo` wrapper | P2 |
| `identity.c` | 909 | `getuid`/`getgid` giả, passwd/group từ rootfs | không |
| `files.c` | 837 | `open`/`openat`/`openat2`, `/proc/self/exe`, `O_CREAT` ghi uid giả | **P0** (phần exe) |
| `ids.c` | 813 | bảng id giả, chuyển uid, set*id | không |
| `privileged.c` | 774 | `chroot`, `mount`, `umount` giả | không |
| `fileops.c` | 723 | `readlink`, `stat`, `access`, `inotify_add_watch`, `rename`, `mkdir` | **P0** (`inotify`) |
| `hardlinks.c` | 628 | bảng hardlink chia sẻ + copy thay thế | không (xem 7) |
| `sysv_shm.c` | 548 | SysV shared memory giả | không |
| `sockets.c` | 494 | `sockaddr_un` dịch + hash 108 byte, port thấp `+8000` | **P1** |
| `go_programs.c` | 465 | vá Go tĩnh, blob `gosys` | đã có |
| `paths.c` | 368 | `rewrite_path`, loại trừ mount Android, `/dev/shm` | **P0** |
| `static_programs.c` | 345 | vá call site `svc #0` cho C tĩnh | P2 |
| `setup.c` | 282 | `CORTEX_ROOT`, `CORTEX_REAL_EXE`, cert/TZ, Unix socket hash | **P0** |
| `memfd_locks.c` | 238 | khoá `memfd` cho phần mềm tin cậy | không |
| `signals.c` | 226 | SIGSYS, `signal`/`sigaction` | đã có |
| `env.c` | 222 | làm sạch môi trường cho tiến trình Android, `LD_PRELOAD` | **P1** |
| `syscalls.c` | 128 | `syscall()` wrapper, `clone3`/`rseq` → `ENOSYS` | đã có |
| `compression.c` | 114 | `lzma_cputhreads()` → 1 | P2 (tuỳ chọn) |

## 3. App này đã có gì (đối chiếu)

Đã có, không cần làm lại:

- Dịch 2 tiền tố Termux ở **101 entry point** (`native/pathmap/pathmap.c`): file, `stat`,
  `readdir`, `glob`, `ftw`, `xattr`, `exec*`, `posix_spawn`, `dlopen`, `chdir`, `realpath`,
  `getcwd` (unmap), `readlink` (unmap).
- SIGSYS policy trong constructor: `set*id` → 0, `clone3`/`rseq` → `ENOSYS`, `getgroups` → 1.
- `preload_env()` giữ `LD_PRELOAD`, `OC_PREFIX`, `OC_USER_HOME` khi exec một target glibc;
  **không** đụng target bionic/tĩnh (đúng hơn Cortex ở điểm này — xem 8.2).
- `native/gopatch`: phân loại `go`/`glibc`/`bionic`/`static`/`script`, `startGlibc` với
  `--preload`, `--library-path`, chạy script qua interpreter kể cả `#!/usr/bin/env`.
- Ở tầng APK: relocate chuỗi Termux trong ELF, patch `PT_INTERP`, patch RUNPATH bằng
  patchelf, sửa shebang, alias `$DATA_DIR/<dir>` → `$PREFIX/<dir>`, resolv.conf cho glibc,
  CA bundle, self-heal (`BINARY_PROOF`, `repair`).

Nghĩa là app này **mạnh hơn Cortex** ở phần "chương trình đã cài chạy trực tiếp", nhưng
**yếu hơn** ở phần "chương trình lạ chạy đúng như trên Linux thật".

## 4. Khoảng trống cụ thể

| # | Tình huống | Hiện tại | Cortex xử lý |
| --- | --- | --- | --- |
| G1 | `readlink /proc/self/exe`, `process.execPath`, `os.Executable()`, `current_exe()` | trả `…/glibc/lib/ld-linux-aarch64.so.1`, sai tên chương trình | `CORTEX_REAL_EXE` + hook 6 entry point |
| G2 | `./tool`: path tương đối khi exec | kernel tự resolve nên vẫn chạy, nhưng hook không absolutize được trước khi probe ⇒ không thể route (B); `execvp` đã tự tìm `PATH` (`find_program`) | `absolutize_exec_path()` cho bước route |
| G3 | Binary glibc tải từ GitHub (PT_INTERP `/lib/ld-linux-aarch64.so.1`), wheel có ELF | chỉ chạy được qua `oc-run` | exec wrapper tự đưa qua loader |
| G4 | RUNPATH `$ORIGIN/../lib` của binary lạ | không được thêm vào `--library-path` | đọc `DT_RUNPATH`, expand `$ORIGIN`/`$LIB` |
| G5 | Script `#!/usr/bin/env python3` (sau khi hết shebang Termux) | mất preload khi `env` exec tiếp, trừ khi qua `oc-run` | route `env` qua loader ⇒ preload sống tiếp |
| G6 | `/usr/bin/env`, `/usr/lib/...`, `/etc/ssl/certs`, `/tmp`, `/var/run`, `/dev/shm` | không map (chỉ có 2 tiền tố Termux) | map cả FHS |
| G7 | `inotify_add_watch` trên thư mục chưa tồn tại (Vite/Metro/chokidar cache) | `ENOENT`, watcher im lặng | `mkdir -p` rồi watch |
| G8 | `bind()/connect()` với `sockaddr_un` chứa đường dẫn Termux, hoặc path > 107 byte | không dịch; ssh-agent, tmux, mysqld, X11 fail | dịch + FNV-1a hash vào `tmp` |
| G9 | Server xin port < 1024 (`python -m http.server 80`) | `EACCES` | `+8000`, ghi file thông báo cho app |
| G10 | `system()`, `popen()` | exec `/system/bin/sh` (bionic) ⇒ mọi lệnh con mất preload | `clean_env_for_system` (không có shim) |

G10 và G5 khác bản chất: G5 sửa được bằng exec routing, G10 cần thêm shim bionic hoặc wrapper
`system()`.

## 5. P0 — bốn việc nên làm trước

### A. Ảo hoá `/proc/self/exe`

Vì sao đáng làm: đây là lỗi **âm thầm** phổ biến nhất của mô hình "chạy qua loader". Mọi
runtime đều hỏi đường dẫn của chính nó: Node (`process.execPath`), Python (`sys.executable`),
Rust (`current_exe`), Go (`os.Executable`), `npm`/`corepack` launcher, `git` helper. Nhận
`ld-linux` thì chúng spawn sai chương trình hoặc tính sai thư mục cài đặt.

Cách Cortex làm (`setup.c`, `files.c`): biến `CORTEX_REAL_EXE`; nếu không có thì đọc
`/proc/self/cmdline`, bỏ `argv[0]` (loader) và các option `--preload/--library-path/--argv0`,
lấy argument đầu tiên là file tồn tại. Sau đó hook `open`, `openat`, `openat2`, `readlink`,
`readlinkat`, `realpath` cho `/proc/self/exe`, `/proc/thread-self/exe` và
`/proc/<pid-của-mình>/exe` (nên thêm `stat`/`access` cho đủ bộ).

Cách áp dụng:

1. Thêm `OC_REAL_EXE` (đặt ở `startGlibc` trong `native/gopatch/run.go` và ở
   `OpenCodeBootstrap` khi start `opencode`) — target thật, không phải loader.
2. Trong constructor `pathmap_init`, đọc `OC_REAL_EXE`; nếu trống thì suy ra từ
   `/proc/self/cmdline` (chỉ khi `argv` bắt đầu bằng `ld-linux`).
3. Trong `readlink`/`readlinkat`: nếu path là `/proc/self/exe` (hoặc alias) và `g_real_exe`
   tồn tại, trả `g_real_exe` theo đúng ngữ nghĩa `readlink` (không NUL, cắt theo `size`).
4. Trong `open`/`openat`/`openat2`/`realpath` (và tuỳ chọn `access`/`stat`): thay path bằng
   `g_real_exe` (giữ nguyên `errno` nếu file biến mất).
5. Chỉ bật khi `g_real_exe[0] != '\0'` và `stat(g_real_exe)` thành công — sai thì rơi về
   hành vi cũ.

Kiểm thử: `node -p process.execPath`, `python3 -c 'import sys;print(sys.executable)'`,
`readlink /proc/self/exe`, một binary Rust tự in `current_exe()`, và `opencode` (Bun) khởi
động bình thường.

### B. Định tuyến exec: glibc ELF → loader

Đây là việc lớn nhất và giá trị nhất. Hiện `oc-run` là "cửa" duy nhất; sau khi làm B, mọi
`exec*` từ một tiến trình glibc đều tự định tuyến, nên `npm install` chạy postinstall,
`make`, `git` hook, `pip` console script, `cargo run` đều hoạt động mà không cần người dùng
biết `oc-run`.

Các mảnh cần có (đều đã có code mẫu trong `exec.c` + `exec_probe.c`):

1. **Chuẩn hoá path (chỉ cho bước route)**: `execve`/`execv` với path tương đối vẫn chạy
   được nhờ kernel, nhưng hook phải tự dựng bản tuyệt đối (ghép `getcwd`) để probe ELF;
   `execvp`/`execvpe` đã có `find_program` nên không cần tìm `PATH` lại. Giữ path gốc cho
   `argv[0]`/`$0` như Cortex làm.
2. **Probe**: đọc 64 byte header + program headers, lấy `PT_INTERP`, `e_machine`,
   `DT_RUNPATH`/`DT_RPATH` (`exec_probe.c` đã làm đủ, kể cả `$ORIGIN`, `$LIB`, `$PLATFORM`
   và giới hạn độ dài).
3. **Chọn loader**: tìm `ld-linux-*.so.*` khớp `e_machine` trong `$PREFIX/glibc/lib`
   (aarch64 và x86_64 nếu cần), không hard-code một tên.
4. **Dựng argv**: `ld.so [--preload $PREFIX/lib/liboc-pathmap.so] --library-path
   <runpath đã expand>:$PREFIX/lib:$PREFIX/glibc/lib[:$LD_LIBRARY_PATH] <target> <args…>`.
   `runpath` phải đứng **trước** để `$ORIGIN` thắng; đây là G4.
5. **Giữ `argv[0]`**: `oc-gopatch` hiện không dùng `--argv0` và ghi chú rằng argv[0] sẽ là
   path (basename vẫn đúng). Với exec routing, giữ nguyên `argv[0]` của caller là quan trọng
   (busybox applet, `sh -c 'exec -a …'`). glibc ≥ 2.33 hỗ trợ `--argv0`; nên dò khả năng
   bằng một lần chạy thử trong `gopatch`/lúc cài đặt và chỉ thêm khi hỗ trợ, hoặc chấp nhận
   mất `argv[0]` (an toàn hơn).
6. **Shebang**: nếu target là script, đọc dòng `#!`, route **interpreter** qua loader rồi
   thêm `argv` của script (`exec.c` xử lý cả `#!/usr/bin/env X` và `-S`). Chỉ cần thế là G5
   tự khỏi: `env` chạy dưới glibc + preload, đến lượt nó `execvp("python3")` lại đi qua
   wrapper của app này.
7. **Giới hạn đệ quy**: `depth` tối đa (Cortex dùng vài tầng) để `ld.so` không tự route
   chính nó; bỏ qua khi target chính là loader.
8. **Giữ môi trường**: dùng `preload_env()` sẵn có, nhưng thêm `OC_REAL_EXE` cho con.
9. **`posix_spawn`/`posix_spawnp`**: cùng logic, dựng `argv` mới trước khi gọi real
   (Cortex làm ở `exec.c`; app này đã export 2 hàm này nên chỉ cần sửa thân).

Rủi ro và cách chặn:

- Binary **bionic** (Android native, `termux-am`, `libandroid-*`) không được route — đã có
  `file_is_glibc()` để phân biệt.
- Binary **tĩnh** (không `PT_INTERP`) không route; `ENOEXEC` vẫn là `ENOEXEC`.
- `ld.so` in lỗi ra stderr khi thiếu lib: hành vi này đúng như Linux, nhưng nên có
  `OC_DEBUG` để log target/argv một lần.
- Biến `LD_PRELOAD` trong môi trường: nếu người dùng tự đặt, phải merge chứ không ghi đè
  (`merge_preload` đã có).

Kiểm thử: một binary glibc tải từ GitHub đặt trong `$HOME/bin` (không patch gì) chạy được
bằng cả `./tool`, `tool` (qua `PATH`), `env tool`; script `#!/usr/bin/env python3` in ra
`sys.executable`; `bash -c 'python3 -V'` (shell glibc, con được route); `make` một project
nhỏ gọi `python3`; `npm install` nếu `script-shell` của npm trỏ `$PREFIX/bin/sh` (npm dùng
`/bin/sh` cứng ở nhiều bản — đó là G10, không phải B); kiểm tra không hồi quy với `git`,
`node`, `opencode`.

### C. Map FHS đầy đủ

Vì sao: `$PREFIX` của app này **đã có dạng FHS** (`bin`, `lib`, `share`, `etc`, `tmp`,
`var`), nên `/usr` → `$PREFIX` là ánh xạ gần như miễn phí và làm chạy được rất nhiều thứ
viết cho Linux mà không cần sửa file:

| Từ | Đến | Ghi chú |
| --- | --- | --- |
| `/usr` | `$PREFIX` | `/usr/lib/...`, `/usr/share/...`, `/usr/bin/...` (shebang thì xem lưu ý dưới) |
| `/etc` | `$PREFIX/etc` | **không** ảnh hưởng resolver của glibc (đã relocate cứng về `$DATA_DIR/etc`) |
| `/tmp` | `$PREFIX/tmp` | nhiều tool ghi file tạm tuyệt đối |
| `/var` | `$PREFIX/var` | `run`, `log`, `lib` |
| `/root` | `$HOME` | chỉ khi `HOME` không được tôn trọng |
| `/home/<user>` | `$HOME` | tuỳ chọn, có thể bỏ |
| `/dev/shm` | `$PREFIX/tmp/shm` | POSIX shm, `multiprocessing` shared_memory |

Lưu ý: kernel **không** đi qua libc khi mở interpreter của shebang. Vì vậy map `/usr` không
làm `#!/usr/bin/python` chạy được; cái đó chỉ xảy ra khi exec routing (B) tự đọc dòng `#!`
và route interpreter. Ngược lại, một khi B đã đọc shebang thì việc map `/usr` lại rất có ích
cho interpreter tìm `site-packages`, `zoneinfo`, CA bundle… Hai việc bổ trợ nhau, không thay
thế nhau.

Thiết kế: giữ `map_path` nóng như hiện tại (một `strncmp` cho tiền tố Termux), thêm bảng
`{from, from_len, to, to_len}` duyệt sau khi trượt, có cờ `OC_FHS=0` để tắt. Vẫn phải loại
trừ các mount của Android (`/apex`, `/data`, `/dev` trừ `/dev/shm`, `/proc`, `/sys`,
`/system`, `/vendor`, `/product`, `/odm`, `/sdcard`, `/storage`) — Cortex có sẵn danh sách
trong `paths.c`, chép ý tưởng, không chép code.

Lưu ý quan trọng: **không map `/system` và không map `/bin/sh`** sang prefix. `system()` và
script Android phải tiếp tục nhận `/system/bin/sh` của bionic. Vì vậy `/bin` chỉ nên map
`/bin/sh`-less: hoặc bỏ `/bin`, hoặc map `/bin` → `$PREFIX/bin` nhưng **loại trừ** đúng
`/bin/sh` (Cortex không cần loại trừ vì rootfs của nó có `/bin/sh` glibc).

Kiểm thử: `python3 -c "import zoneinfo; zoneinfo.ZoneInfo('Asia/Ho_Chi_Minh')"` (cần
`/usr/share/zoneinfo`), `curl https://…` với `SSL_CERT_FILE` trỏ `/etc/ssl/certs`, ghi
`/tmp/x`, `multiprocessing.shared_memory`, đọc `/usr/share/...` từ một chương trình glibc.

### D. `inotify_add_watch` tự tạo thư mục

Cortex (`fileops.c`) làm đúng 4 dòng: map path → nếu `access(target, F_OK) != 0` thì
`mkdir -p` → gọi real. Với Vite/Metro/chokidar, watcher trên thư mục cache chưa tồn tại là
ca phổ biến khiến "dev server không reload" mà không có lỗi nào hiện ra. App này đã export
`inotify_add_watch` nên chỉ cần thêm nhánh này. Không sửa `ENOSPC` (giới hạn watch của
kernel) — cái đó phải hạ số file watched hoặc dùng polling.

## 6. P1 — làm sau P0

### E. Dịch `sockaddr_un` + hash 108 byte

Hiện không có hook nào cho `bind`/`connect`/`sendto`/`getsockname`/`getpeername`. Hệ quả:
`ssh-agent`, `tmux`, `gpg-agent`, `mysqld`, `emacs --daemon`, X11 đều tạo socket ở đường dẫn
Termux cứng hoặc quá dài. Cách làm (Cortex `sockets.c` + `setup.c`):

- `bind`/`connect`: nếu `AF_UNIX` và path tuyệt đối, map path rồi copy vào bản `sockaddr_un`
  cục bộ; abstract socket (byte đầu `\0`) giữ nguyên.
- Nếu path sau map ≥ 108 byte: FNV-1a 64 → `$PREFIX/tmp/.oc_sock_%016llx`. Hai phía tính
  cùng hash nên gặp nhau; phải `unlink` đường đã hash khi đóng listener (Cortex có
  `unlink_mapped_unix_socket`).
- `getsockname`/`getpeername`: unmap để chương trình in ra đường dẫn gốc (người dùng copy
  vào client).
- `sun_path` là mảng trong `sockaddr` của caller: **không** sửa tại chỗ nếu `addrlen` nhỏ
  hơn `sizeof(sockaddr_un)`; dùng bản copy.

Kiểm thử: `ssh-agent` + `ssh-add`, `tmux new -s t`, một server Python bind `$PREFIX/tmp/x.sock`,
và một path cố tình dài > 107 byte.

### F. Port thấp → `+8000`

Android không cho app bind < 1024. Cortex map `80→8080`, `443→8443`, `22→8022` trong `bind`,
và trong `connect` tới loopback thử port đã dịch nếu port gốc không có gì; đồng thời ghi
`run/cortex/moved-ports/PORT` để app báo cho người dùng. Với app này, giá trị là các tutorial
`python -m http.server 80`, `nginx` mặc định, `node server.js` port 80. Nên kèm thông báo
một lần (log/`OC_DEBUG` + file trạng thái cho UI), đừng im lặng.

### G. `system()`/`popen()` qua shell glibc

Đây là mảnh cuối để "mọi thứ chạy như Linux". Có hai hướng:

1. **Rẻ, an toàn**: không làm gì; tài liệu hoá như hiện tại (`LIBRARY_FIXUPS.md` mục 8.2).
2. **Đắt, đúng**: override `system()`/`popen()` để chạy `$PREFIX/bin/sh -c CMD` **qua
   loader + preload** (không phải `/system/bin/sh`). `system()` có ngữ nghĩa signal phức
   tạp (block `SIGCHLD`, ignore `SIGINT`/`SIGQUIT`, `waitpid`), nên phải viết cẩn thận và
   giữ fallback gọi real khi shell glibc không có. `popen` cần thêm `pipe` + `posix_spawn`
   với fd actions.

Hướng 3 (shim bionic) mà Cortex không làm: một `.so` NDK preload vào `/system/bin/sh` để nó
tự route con glibc. Nghe hấp dẫn nhưng phải strip `LD_PRELOAD`/`LD_LIBRARY_PATH` cho target
Android, thêm lại preload glibc cho target glibc, và chịu rủi ro ABI bionic — **không khuyến
khích** khi hướng 2 đã đủ.

### H. Ghi chú hardening: fork-safety của `preload_env`

`preload_env()` hiện `calloc` + cấp phát chuỗi **sau khi** tiến trình đã `fork()` (shell gọi
`execve` trong con). Trong tiến trình đa luồng, `fork` giữa lúc thread khác đang giữ lock của
malloc có thể làm con treo trong `calloc` — hiếm nhưng là loại bug không tái hiện được.
Cortex giải bằng `prepare_exec_in_parent()` + `rehearse_exec()` ở lần `fork` thứ 8. Cách nhẹ
hơn cho app này: cấp một pool tĩnh (ví dụ 64 KB) trong constructor và dựng env trong pool
bằng `memcpy`, không `malloc`; hoặc `pthread_atfork` để chuẩn bị sẵn bản env. Làm cùng lúc
với B vì B sửa đúng hàm này.

## 7. Không nên port

| Phần | Lý do |
| --- | --- |
| `identity.c`/`ids.c` (fakeroot) | App giữ uid thật; giả uid 0 sẽ làm hỏng quyền file thật và không cần cho OpenCode/toolchain. |
| `hardlinks.c` | Android chặn hardlink giữa các thư mục của app; muốn giả phải có bảng chia sẻ + IPC + xattr. Thay bằng `pnpm config set package-import-method=copy`, và tài liệu hoá. |
| `sysv_shm.c` | Không có phần mềm nào trong danh sách mục tiêu cần; rủi ro cao. |
| `memfd_locks.c` | Chỉ để chạy app tin cậy kiểu Chromium trong rootfs. |
| `privileged.c` (`chroot`/`mount` giả) | Không có nhu cầu container/LXC. |
| `compression.c` | `lzma_cputhreads()=1` chỉ nên là tuỳ chọn (tiết kiệm RAM khi giải nén lớn), không mặc định. |
| `usrmerge`/chủ sở hữu thư mục | Không có dpkg/X11/PHP nên không có ca nào cần. |
| Toàn bộ `paths.c` copy nguyên | Bảng mount của Ubuntu khác hoàn toàn layout Termux; chỉ học cấu trúc. |

## 8. Bài học thiết kế (áp dụng được ngay, không cần feature mới)

1. **Một translation unit + `EXPORT`**: mọi hàm `static` dùng chung, `-O2` inline được, một
   `.so` duy nhất. `pathmap.c` đã theo đúng mô hình này; giữ nguyên khi thêm feature.
2. **Hot path không cấp phát**: `map_path` hiện đúng (một `strncmp`); khi thêm bảng FHS phải
   giữ tính chất này — so sánh tiền tố trước, chỉ copy khi khớp.
3. **Cờ tắt bằng biến môi trường**: Cortex có `CORTEX_ROOT`, `CORTEX_REAL_EXE`; nên thêm
   `OC_FHS`, `OC_EXEC_ROUTE`, `OC_REAL_EXE`, `OC_SOCK`, `OC_LOW_PORTS` để một case hỏng có
   thể tắt mà không phát hành APK mới.
4. **`OC_DEBUG`**: log một lần cho mỗi đường dẫn/target đã dịch ra stderr. Rẻ, và là công cụ
   duy nhất để debug trên máy người dùng.
5. **Không đoán, hãy thử**: Cortex `stat` file trước khi trả `/proc/self/exe`; `rootfs_pair_is_merged`
   kiểm tra symlink usrmerge một lần. Cùng nguyên tắc: chỉ route khi `file_is_glibc()` thật
   sự đọc được `PT_INTERP`.
6. **Báo side effect qua file, không qua UI**: `run/.../listening/PORT` và
   `moved-ports/PORT` cho phép app hiển thị thông báo mà không buộc `.so` biết gì về app.
   App này đã có tiền lệ: `PTY_SOCKET_DIR`, marker files.
7. **Giới hạn tường minh**: `EXEC_ARG_MAX`, depth đệ quy, hash khi vượt 108 byte, cắt
   `PATH_MAX` như `snprintf`. Đừng để buffer tự do phình.
8. **Không đụng môi trường của target Android**: điểm mạnh của app này so với Cortex là dùng
   `--preload` cho tiến trình đầu thay vì `LD_PRELOAD` toàn cục, nên `system()` → bionic
   `sh` vẫn sạch. Giữ nguyên tính chất đó khi thêm exec routing (chỉ thêm preload cho target
   glibc, như `preload_env` đang làm).

## 9. Lộ trình và kiểm thử

Thứ tự đề xuất (mỗi bước là một PR nhỏ, có test):

1. **A** (`/proc/self/exe`) + `OC_REAL_EXE` + `OC_DEBUG`. Không phụ thuộc gì, ít rủi ro.
2. **C** (bảng FHS) sau cờ `OC_FHS`, mặc định bật sau khi chạy hết smoke test.
3. **B** (exec routing): chuẩn hoá path → probe → loader/argv → shebang → `posix_spawn`;
   kèm **H** (pool tĩnh cho `preload_env`) trong cùng đợt.
4. **D** (`inotify`), **E** (Unix socket), **F** (port thấp), cuối cùng là **G**.
5. **P2**: `system()`/`popen()` (nếu G), vá C tĩnh theo bảng `svc #0` (chỉ khi có nhu cầu
   thật), `lzma_cputhreads` như tuỳ chọn.

Kiểm thử:

- **Host test** (`native/pathmap/run-test.sh`, chạy được cả `x86_64` và `aarch64` qua qemu):
  thêm case vào `pathmap_test.c` cho bảng FHS (map + loại trừ `/system`), `absolutize`,
  dựng argv loader, `/proc/self/exe` (giả `OC_REAL_EXE`), socket hash (cùng input → cùng
  path, path ngắn giữ nguyên), `inotify` tự tạo thư mục. Test hiện có đã chạy nhị phân test
  bằng chính nó với môi trường sạch — dùng lại đúng mẫu đó cho exec routing.
- **Device smoke** (thủ công, ghi vào PR): các lệnh ở mục 5A–5D, 6E–6F, cộng `git status`,
  `node -v`, `opencode --version` để chắc không hồi quy.
- **Không hồi quy**: `npm install` trong một project có postinstall, `git clone` qua https,
  `python3 -m pip install` một wheel, `bun`/`opencode` khởi động, `bash -lc 'echo ok'`.

## 10. Tham chiếu

- Cortex: `app/src/main/cpp/hook/{setup,files,fileops,exec,exec_probe,paths,sockets,env,static_programs}.c`
  tại commit `2e95f52`.
- App này: `native/pathmap/pathmap.c` (`map_path` dòng 145, `preload_env` dòng 370,
  `inotify_add_watch` dòng 1074, `execve` dòng 1220, SIGSYS cuối file), `native/pathmap/pathmap_test.c`,
  `native/gopatch/run.go` (`startGlibc`), `native/gopatch/classify.go`.
- APK: `OpenCodeBootstrap.kt` (`PATHMAP_PATH`, `GLIBC_LIB_DIR`, `RESOLV_CONF_PATH`,
  `ensurePathAliases`, `relocateLoader`), `OpenCodePackages.kt` (`repair`, shebang).
- Tài liệu trước: [`LIBRARY_FIXUPS.md`](LIBRARY_FIXUPS.md) mục 8.1–8.2 (hooked functions và
  các giới hạn cố ý).
