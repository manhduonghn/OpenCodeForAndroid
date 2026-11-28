# Lớp dịch đường dẫn lúc chạy (`liboc-pathmap.so`)

Tài liệu bổ sung cho [`LIBRARY_FIXUPS.md`](LIBRARY_FIXUPS.md). Nó mô tả **thiết kế nội bộ**
của preload library `native/pathmap/pathmap.c`: vì sao app cần một lớp dịch đường dẫn ở mức
libc, nó che những khoảng trống nào, các quyết định đã chốt, và những giới hạn cố ý.

## 0. Vì sao cần lớp này

App chạy chương trình glibc của prefix bằng cách gọi loader tường minh (`ld.so --preload …
--library-path …`). Cách đó giữ nguyên file trên đĩa, nhưng để lại ba loại lệch:

1. **Đường dẫn tuyệt đối cứng trong chương trình.** Một binary tải từ GitHub hay một wheel có
   ELF vẫn mang `/lib/ld-linux-aarch64.so.1`, `/usr/lib/...`, `/etc/ssl/certs`, `/tmp`. Những
   đường dẫn đó không tồn tại trên Android.
2. **Chương trình khởi động chương trình khác.** `exec*`, `posix_spawn`, `system`, `popen`,
   script `#!…` đều có thể rời khỏi thế giới glibc nếu không được định tuyến lại.
3. **Danh tính tiến trình.** Runtime nào cũng hỏi đường dẫn của chính nó; khi chạy qua loader,
   câu trả lời là `ld-linux`, không phải tên chương trình.

`liboc-pathmap.so` là một `LD_PRELOAD` nhỏ, biên dịch từ **một translation unit** duy nhất,
export các entry point libc cần thiết và chuyển tiếp phần còn lại cho hàm thật. Nó không
ptrace, không chroot, không sửa uid.

## 1. Kiến trúc tổng thể

| Thành phần | Vai trò |
| --- | --- |
| `native/pathmap/pathmap.c` | Preload library: dịch path, định tuyến exec, xử lý socket/port, policy SIGSYS |
| `native/gopatch/` | Chọn cách chạy một file: glibc qua loader, script qua interpreter, Go/C tĩnh qua bản vá |
| `native/gosys/` | Blob và stub cho chương trình tĩnh/Go không dùng libc |
| `native/pty-c/` | Daemon `opencode-pty` C/bionic của web terminal (không đi qua preload glibc) |
| `OpenCodeBootstrap.kt` | Relocate chuỗi Termux trong ELF, patch `PT_INTERP`/RUNPATH, alias thư mục, self-heal |

Hai lớp bổ trợ nhau:

- **Sửa file** (APK, lúc cài/self-heal) xử lý những gì đã biết trước: chuỗi Termux trong
  rodata, interpreter, RUNPATH, shebang. Đây là thay đổi tối thiểu và không ảnh hưởng hành vi
  chương trình.
- **Dịch lúc chạy** (preload) xử lý những gì đến sau: binary người dùng tải thêm, wheel có ELF,
  tool tự build, đường dẫn FHS, socket, port thấp.

Mỗi tính năng của preload có một cờ môi trường để tắt riêng, nhờ vậy một ca hỏng có thể được vô
hiệu mà không phát hành APK mới.

## 2. Dịch đường dẫn

### 2.1 Hai tiền tố Termux

`map_path()` là hot path: nó so sánh tiền tố trước, chỉ copy khi khớp, và không cấp phát.

- `/data/data/com.termux/files/usr/...` → `$PREFIX/...`
- `/data/data/com.termux/files/home/...` → `$HOME/...`

Hai gốc được copy một lần trong constructor từ `OC_PREFIX` / `OC_USER_HOME`. Sau khi trượt hai
tiền tố, `map_fhs()` mới được xét; `OC_FHS=0` tắt bảng FHS.

Các hàm trả path (`getcwd`, `realpath`, `readlink`, …) làm chiều ngược lại: một path thật của app
được **unmap** về spelling Termux để chương trình cũ thấy lại gốc nó được biên dịch cùng. Điều này
không được áp dụng cho server OpenCode: CLI lấy workspace root từ `getcwd()`, nên nếu nó thấy
`/data/data/com.termux/files/home` (thư mục của app khác, không tồn tại ở đây) mọi tra cứu location
sẽ fail với `Location not found`. Vì vậy `RuntimeContract.serverProcessEnvironment()` bỏ
`OC_USER_HOME` khỏi môi trường của server (giữ nguyên `HOME` thật); `oc-gopatch` và `oc-exec` thêm
lại hint này cho tiến trình con, nên phần còn lại của runtime vẫn dịch path bình thường.

Route glibc của server cũng xoá `LD_LIBRARY_PATH` trước khi chạy `liboc_ld.so`, và tiến trình con
bionic không có shell profile để đặt lại. `oc-run` vì thế đặt mặc định `$PREFIX/lib` khi biến này
trống (hoặc prepend nếu đã có) trước khi gọi patcher; `oc-gopatch` lại loại bỏ nó cho con glibc.
Nếu thiếu bước này, `git` trong tab **Thay đổi** chết với `libpcre2-8.so not found`, và adapter VCS
của CLI âm thầm trả danh sách rỗng.

### 2.2 Bảng FHS

Prefix của app đã có dạng FHS (`bin`, `lib`, `share`, `etc`, `tmp`, `var`), nên `/usr` →
`$PREFIX` gần như miễn phí và làm chạy được nhiều phần mềm viết cho Linux mà không cần sửa file:

| Từ | Đến | Ghi chú |
| --- | --- | --- |
| `/usr` | `$PREFIX` | `/usr/lib/...`, `/usr/share/...`, `/usr/bin/...` |
| `/etc` | `$PREFIX/etc` | Không ảnh hưởng resolver của glibc (đã relocate cứng về `$DATA_DIR/etc`) |
| `/tmp` | `$PREFIX/tmp` | Nhiều tool ghi file tạm tuyệt đối |
| `/var` | `$PREFIX/var` | `run`, `log`, `lib` |
| `/root` | `$HOME` | Chỉ khi `HOME` không được tôn trọng |
| `/home/<user>` | `$HOME` | Tuỳ chọn |
| `/dev/shm` | `$PREFIX/tmp/shm` | POSIX shm, `multiprocessing` shared_memory |
| `/bin` | `$PREFIX/bin` | **Loại trừ `/bin/sh`** (xem dưới) |

Các mount của Android không bao giờ bị map: `/apex`, `/data`, `/dev` (trừ `/dev/shm`), `/proc`,
`/sys`, `/system`, `/vendor`, `/product`, `/odm`, `/sdcard`, `/storage`.

**`/bin/sh` phải giữ nghĩa bionic.** Kernel không đi qua libc khi mở interpreter của shebang,
nên map `/usr` không làm `#!/usr/bin/python` chạy được; việc đó do định tuyến exec đọc dòng `#!`.
Ngược lại, script Android phải tiếp tục nhận `/system/bin/sh`, vì vậy `/bin` hoặc bị bỏ, hoặc map
`$PREFIX/bin` nhưng loại trừ đúng `/bin/sh`. `system()`/`popen()` không dựa vào phép map này:
chúng gọi thẳng shell glibc khi có, còn `/bin/sh` vẫn nguyên nghĩa cho mọi caller khác.

## 3. Định tuyến exec

Đây là phần lớn nhất và giá trị nhất. Trước khi có nó, `oc-run` là "cửa" duy nhất; sau khi có,
mọi `exec*` từ một tiến trình glibc đều tự định tuyến, nên `npm install` chạy postinstall,
`make`, `git` hook, `pip` console script, `cargo run` hoạt động mà người dùng không cần biết
`oc-run`. `OC_EXEC_ROUTE=0` tắt toàn bộ.

Các mảnh, theo đúng thứ tự trong `route_build()`:

1. **Chuẩn hoá path cho bước route.** `execve`/`execv` với path tương đối vẫn chạy được nhờ
   kernel, nhưng hook phải dựng bản tuyệt đối (ghép `getcwd`) để probe ELF; `execvp`/`execvpe`
   đã có `find_program` nên không tìm `PATH` lại. Path gốc vẫn được giữ cho `argv[0]`.
2. **Probe.** Đọc ELF header và program headers, lấy `PT_INTERP`, `e_machine`,
   `DT_RUNPATH`/`DT_RPATH`, expand `$ORIGIN`/`$LIB`/`$PLATFORM`, có giới hạn độ dài.
3. **Chọn loader.** Tìm `ld-linux-*.so.*` khớp `e_machine` trong `$PREFIX/glibc/lib`; không
   hard-code một tên.
4. **Dựng argv.** `ld.so [--preload $PREFIX/lib/liboc-pathmap.so] --library-path
   <runpath đã expand>:$PREFIX/lib:$PREFIX/glibc/lib[:$LD_LIBRARY_PATH] <target> <args…>`.
   `runpath` đứng **trước** để `$ORIGIN` thắng.
5. **Giữ `argv[0]`.** Với busybox applet hay `sh -c 'exec -a …'`, giữ `argv[0]` của caller là
   quan trọng. glibc ≥ 2.33 hỗ trợ `--argv0`; khả năng này được dò một lần và chỉ dùng khi có.
6. **Shebang.** Nếu target là script, đọc dòng `#!`, route **interpreter** qua loader rồi thêm
   `argv` của script (xử lý cả `#!/usr/bin/env X` và `-S`). Nhờ vậy `env` chạy dưới glibc +
   preload, và đến lượt nó `execvp("python3")` lại đi qua wrapper.
7. **Giới hạn đệ quy.** `depth` tối đa để `ld.so` không tự route chính nó; bỏ qua khi target
   chính là loader.
8. **Giữ môi trường.** Dùng `preload_env()` sẵn có và thêm `OC_REAL_EXE` cho con.
9. **`posix_spawn`/`posix_spawnp`.** Cùng logic, dựng argv mới trước khi gọi hàm thật.

Chặn rủi ro:

- Binary **bionic** (Android native, `termux-am`, `libandroid-*`) không bị route; `file_is_glibc()`
  phân biệt bằng `PT_INTERP` thật sự đọc được.
- Binary **tĩnh** (không `PT_INTERP`) không route; `ENOEXEC` vẫn là `ENOEXEC`.
- `LD_PRELOAD` do người dùng đặt được merge, không ghi đè.
- `OC_DEBUG=1` log target/argv một lần ra stderr.

## 4. Ảo hoá `/proc/self/exe`

Đây là lỗi **âm thầm** phổ biến nhất của mô hình "chạy qua loader". Mọi runtime đều hỏi đường
dẫn của chính nó: Node (`process.execPath`), Python (`sys.executable`), Rust (`current_exe`),
Go (`os.Executable`), `npm`/`corepack` launcher, `git` helper. Nhận `ld-linux` thì chúng spawn
sai chương trình hoặc tính sai thư mục cài đặt.

Thiết kế:

1. `OC_REAL_EXE` được đặt ở `startGlibc` (`native/gopatch/run.go`) và ở `OpenCodeBootstrap` khi
   start `opencode` — luôn là target thật, không phải loader.
2. Trong constructor, đọc `OC_REAL_EXE`; nếu trống thì suy ra từ `/proc/self/cmdline`, bỏ
   `argv[0]` (loader) và các option `--preload/--library-path/--argv0`, lấy argument đầu tiên
   là file tồn tại.
3. `readlink`/`readlinkat` trả `g_real_exe` theo đúng ngữ nghĩa `readlink` (không NUL, cắt theo
   `size`) cho `/proc/self/exe`, `/proc/thread-self/exe` và `/proc/<pid-của-mình>/exe`.
4. `open`/`openat`/`openat2`/`realpath` thay path bằng `g_real_exe`, giữ nguyên `errno` nếu file
   biến mất.
5. Chỉ bật khi `g_real_exe[0] != '\0'` và `stat(g_real_exe)` thành công; sai thì rơi về hành vi
   cũ.

Kiểm thử: `node -p process.execPath`, `python3 -c 'import sys;print(sys.executable)'`,
`readlink /proc/self/exe`, một binary Rust tự in `current_exe()`, và `opencode` (Bun) khởi động
bình thường.

## 5. Socket Unix và tên quá dài

`ssh-agent`, `tmux`, `gpg-agent`, `mysqld`, `emacs --daemon`, X11 đều tạo socket ở đường dẫn
Termux cứng hoặc quá dài.

- `bind`/`connect`/`sendto`/`sendmsg`: nếu `AF_UNIX` và path tuyệt đối, map path rồi copy vào
  bản `sockaddr_un` cục bộ; abstract socket (byte đầu `\0`) và path tương đối giữ nguyên.
  `sockaddr` của caller không bao giờ bị sửa.
- Nếu path sau map ≥ 108 byte: FNV-1a 64 → `$PREFIX/tmp/.oc_sock_%016llx`, và `bind` tạo
  `$PREFIX/tmp` trước. Hai phía tính cùng hash nên gặp nhau.
- `getsockname`/`getpeername` trả lại cách viết gốc nhờ bảng 64 record theo fd; `accept`/`accept4`
  copy record của listener sang fd mới; `dup`/`dup2`/`dup3` copy cả local + peer + tên hash, và
  dọn record cũ của fd đích khi bị ghi đè.
- `close` chỉ unlink tên hash khi pid hiện tại sở hữu record, dev/ino của path vẫn khớp và
  **không còn record nào khác cùng tên hash**, nên fork, một listener mới bind cùng hash, hoặc
  một bản `dup` còn sống đều không bị xoá nhầm. `close_range` dọn mọi record trong khoảng khi
  syscall thành công và không có `CLOSE_RANGE_CLOEXEC`.
- `unlink`/`unlinkat`/`remove` cũng xoá tên hash và trả 0 khi socket hash đã bị xoá dù kernel
  không tìm thấy đường path vật lý.

Giới hạn: `exec` không cập nhật bảng, nên một fd sống qua `exec` có thể mất cách viết gốc; path
đúng 108 byte không có NUL được giữ nguyên; bảng đầy thì rơi về unmap thường.

Kiểm thử: `test_unix_sockets` trong `native/pathmap/pathmap_test.c` — stream với cách viết
Termux và FHS, hash + `close`/`unlink`/`dup`/`dup2`/`dup3`/`close_range`, datagram qua
`sendto`/`sendmsg`, abstract passthrough.

## 6. Port thấp → `+8000`

Android không cho app bind port < 1024. `bind()` dịch port `1..1023` thành `port + 8000` (IPv4 và
IPv6, giữ nguyên address); thành công thì ghi `$PREFIX/run/moved-ports/PORT` chứa port mới và
in một dòng stderr **một lần cho mỗi port gốc** trong tiến trình.

`connect()` tới loopback (`127/8`, `::1`) chỉ thử port đã dịch khi connect gốc trả `ECONNREFUSED`,
rồi phục hồi errno nếu cả hai thất bại. `OC_LOW_PORTS=0` tắt cả hai.

Phía app, `MovedPorts` đọc/parse marker và `MovedPortNotifier` đăng một notification (xoá marker
sau khi đăng thành công); service quét lúc khởi động, trong heartbeat 60 giây và activity quét
lại ở `onResume`.

Giới hạn: không dịch port 0 (ephemeral), không đổi `getsockname()` (caller thấy port thật), và
retry không áp dụng cho connect non-blocking trả `EINPROGRESS`.

Kiểm thử: `test_low_ports` (bind, marker, connect retry, chọn cặp port trống bằng raw syscall)
và `MovedPortsTest` cho parser/consume.

## 7. `system()`/`popen()` qua shell glibc

Nếu để nguyên, `system()`/`popen()` exec `/system/bin/sh` (bionic), nên mọi lệnh con mất
preload. Có hai hướng:

1. **Rẻ, an toàn**: không làm gì, tài liệu hoá giới hạn.
2. **Đắt, đúng**: override `system()`/`popen()` để chạy `$PREFIX/bin/sh -c CMD` **qua loader +
   preload**.

Chọn hướng 2. `glibc_shell()` tìm `$PREFIX/bin/sh` rồi `$PREFIX/bin/bash`, chỉ nhận file chạy
được có `PT_INTERP` glibc; không có thì mọi wrapper rơi về hàm thật (shell bionic).

`system()` và `popen()` dựng con bằng chính `posix_spawn()` của thư viện, nên route/preload áp
dụng lại cho shell và lệnh con. `system()` giữ hợp đồng signal (`SIGCHLD` bị block,
`SIGINT`/`SIGQUIT` ignore ở cha và reset về default ở con). `popen()` giữ bảng 32 record
`FILE* → pid` để `pclose()` lấy đúng trạng thái, và `pclose()` chỉ gọi hàm thật khi stream không
có record. Bảng record dùng spinlock; constructor đăng ký `pthread_atfork` child handler để
`fork()` khi thread khác đang giữ lock không làm con treo. `OC_SYSTEM=0` tắt cả ba wrapper.

Kiểm thử: `test_glibc_shell` (popen đọc `/usr/share/...` qua `cat`, popen ghi bằng redirection,
`system("exit 7")`, và một `fork()` mà cả cha lẫn con đều dùng được `popen`).

## 8. `inotify_add_watch` tự tạo thư mục

Với Vite/Metro/chokidar, watcher trên thư mục cache chưa tồn tại là ca phổ biến khiến dev server
không reload mà không có lỗi nào hiện ra. `inotify_add_watch` map path → nếu `access(target,
F_OK) != 0` thì `mkdir -p` → gọi hàm thật.

Không sửa `ENOSPC` (giới hạn watch của kernel); ca đó phải hạ số file watched hoặc dùng polling.

## 9. Môi trường cho tiến trình con

`preload_env()` giữ `LD_PRELOAD`, `OC_PREFIX`, `OC_USER_HOME` và (khi cần) `OC_REAL_EXE` cho
một target glibc; **không** đụng target bionic/tĩnh — loading preload vào một tiến trình Android
sẽ làm nó chết vì ABI.

`fork` trong tiến trình đa luồng có thể làm con treo nếu thread khác đang giữ lock của malloc.
Vì vậy `preload_env()` không `malloc` sau `fork`: nó dựng cả mảng con trỏ và các chuỗi trong một
arena `__thread` 16 KB (`tls_model("initial-exec")`) bằng bump allocator + `memcpy`; hết chỗ thì
dùng một vùng `mmap` ẩn danh 64 KB → 16 MB. Arena thuộc static TLS nên thread tạo trước `fork`
đã có sẵn block và con kế thừa nguyên trạng. `free_preload_env()` nhận ra con trỏ nằm trong arena
(không `free`) hoặc trong vùng `mmap` (header magic, `munmap` theo kích thước ghi trong header)
và giải phóng đúng cách. `file_is_glibc_depth()` dùng `open_raw()` để không đi qua hook.

Kiểm thử: các case exec dùng arena; một môi trường rất lớn đi nhánh `mmap`.

## 10. Policy SIGSYS và chương trình tĩnh

Bionic chặn một số syscall mà glibc/Go gọi. Constructor cài một `SIGSYS` handler:

- `set*id`/`setgroups` → giả thành công (app giữ uid thật, nhưng nhiều chương trình chỉ cần
  syscall không trả lỗi).
- `clone3`/`rseq` và các syscall ngoài allowlist → `ENOSYS`, để libc rơi về đường cũ.
- `fork()` qua `clone` không bị vá.

Với chương trình **tĩnh** hoặc **Go** (không đi qua libc), preload vô hiệu. `native/gopatch`
vá file: chèn blob `gosys` vào padding giữa các segment, nối nhánh `svc #0` tới stub, hoặc với C
tĩnh thì trampoline mỗi call site (`patchc.go`). `OC_STATIC_PATCH=0` tắt bản vá.

## 11. Bài học thiết kế

1. **Một translation unit + `EXPORT`**: mọi hàm `static` dùng chung, `-O2` inline được, một `.so`
   duy nhất. Giữ nguyên mô hình này khi thêm feature.
2. **Hot path không cấp phát**: `map_path` chỉ `strncmp`; thêm bảng FHS không được phá tính chất
   đó — so sánh tiền tố trước, chỉ copy khi khớp.
3. **Cờ tắt bằng biến môi trường**: `OC_FHS`, `OC_EXEC_ROUTE`, `OC_REAL_EXE`, `OC_SOCK`,
   `OC_LOW_PORTS`, `OC_SYSTEM`, `OC_STATIC_PATCH` để một ca hỏng có thể tắt mà không phát hành
   APK mới.
4. **`OC_DEBUG`**: log một lần cho mỗi path/target đã dịch ra stderr. Rẻ, và là công cụ duy nhất
   để debug trên máy người dùng.
5. **Không đoán, hãy thử**: chỉ route khi `file_is_glibc()` thật sự đọc được `PT_INTERP`; chỉ
   trả `/proc/self/exe` khi `stat` target thành công.
6. **Báo side effect qua file, không qua UI**: `run/moved-ports/PORT` cho phép app hiển thị thông
   báo mà không buộc `.so` biết gì về app. App đã có tiền lệ: `PTY_SOCKET_DIR`, marker files.
7. **Giới hạn tường minh**: `EXEC_ARG_MAX`, depth đệ quy, hash khi vượt 108 byte, cắt `PATH_MAX`.
   Đừng để buffer tự do phình.
8. **Không đụng môi trường của target Android**: dùng `--preload` cho tiến trình đầu thay vì
   `LD_PRELOAD` toàn cục. `system()`/`popen()` chỉ route khi prefix có shell glibc; không có thì
   vẫn là `/system/bin/sh` sạch.

## 12. Kiểm thử

- **Host test** (`native/pathmap/run-test.sh`, chạy được cả `x86_64` và `aarch64` qua qemu): bảng
  FHS (map + loại trừ `/bin/sh`), `inotify` tự tạo thư mục, Unix socket
  (`bind`/`connect`/`sendto`/`sendmsg`, hash 108 byte + `close`/`unlink`, abstract passthrough),
  port thấp (`bind` → `+8000`, marker, connect retry), `system`/`popen` qua shell glibc (kể cả
  `fork()` rồi `popen` ở cả cha và con), và exec routing: fixture `route_child.c` với `PT_INTERP`
  không tồn tại + `$ORIGIN/lib`, script `#!/usr/bin/oc-interp`, `/proc/self/exe` qua loader (có
  và không có `OC_REAL_EXE`), môi trường sạch của con và một môi trường 2 100 biến để đi nhánh
  heap của `preload_env`. Trên aarch64/qemu các case exec và shell bị bỏ (`OC_TEST_NO_EXEC=1`) vì
  qemu thay `/proc/self/exe` bằng chính nó và không chạy được shell của host; case port thấp vẫn
  chạy.
- **End-to-end native** (`native/test/run.sh`): fixture Go tĩnh và fixture C tĩnh AArch64
  (`aarch64-linux-gnu-gcc -static -no-pie`) dưới `qemu-aarch64`; kiểm tra path Termux, `getcwd`
  unmap, policy set*id/clone3/getgroups, các syscall ngoài allowlist trả `ENOSYS`, `fork()` qua
  `clone` không bị vá, và việc từ chối vá lần hai (magic `OCGOSYS1`). Khối C tĩnh bị bỏ khi thiếu
  cross compiler.
- **Device smoke** (thủ công, ghi vào PR): các case ở mục 4–7, cộng `git status`, `node -v`,
  `opencode --version` để chắc không hồi quy.
- **Không hồi quy**: `npm install` trong một project có postinstall, `git clone` qua https,
  `python3 -m pip install` một wheel, `bun`/`opencode` khởi động, `bash -lc 'echo ok'`.

## 13. Tham chiếu mã nguồn

- `native/pathmap/pathmap.c` — `map_path`, `map_fhs`, `preload_env`, exec routing,
  `/proc/self/exe`, `inotify_add_watch`, socket/port wrapper, SIGSYS ở cuối file.
- `native/pathmap/pathmap_test.c`, `native/pathmap/route_child.c` — host test và fixture.
- `native/gopatch/run.go` (`startGlibc`, `startScript`), `classify.go`, `patch.go`/`patchc.go`.
- `native/gosys/gosys.c`, `native/gosys/cstub.S`.
- `native/pty-c/opencode-pty.c` (protocol 9), `pty_core.c`, `screen.c`, `util.c`.
- `OpenCodeBootstrap.kt` (`PATHMAP_PATH`, `GLIBC_LIB_DIR`, `RESOLV_CONF_PATH`,
  `ensurePathAliases`, `relocateLoader`), `OpenCodePackages.kt` (`repair`, shebang).
- [`LIBRARY_FIXUPS.md`](LIBRARY_FIXUPS.md) — bảng vá thư viện và các giới hạn cố ý.
