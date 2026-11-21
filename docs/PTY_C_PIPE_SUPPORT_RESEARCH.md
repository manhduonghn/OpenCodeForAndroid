# Nghiên cứu: bổ sung chế độ `pipe` cho `opencode-pty` C để tool `shell` chạy được

**Ngày:** 2026-11-21
**Liên quan:** `docs/OPENCODE_SHELL_CAPABILITY_REPORT.md`, `docs/LINUX_RUNTIME_AND_TOOLCHAIN_REPORT.md`
**Phạm vi:** thiết kế/vá `native/pty-c/` (helper bionic do app tự build). Không đổi permission config — đã chứng minh quyền không phải vấn đề.

> **Cập nhật quan trọng so với báo cáo trước:** đã tìm thấy **spec giao thức chuẩn** trong mã nguồn OpenCode `v2.3.2` (đúng phiên bản CLI app đang dùng). Không còn phải đoán field hay response shape. Báo cáo trước ghi op `create` — **sai**, tên thật là **`create_pty`** (v7 `create` đã bị loại bỏ). Ở đây dùng tên chuẩn.

---

## 1. Kết luận điều tra

Tool `shell` của OpenCode không spawn tiến trình trực tiếp trong build Android này; nó dùng API **process daemon** qua helper PTY. Helper C hiện tại chỉ cài đặt nhánh **`create_pty`** (terminal tương tác), còn 4 op cần thiết cho lệnh không tương tác bị từ chối tại `native/pty-c/opencode-pty.c:1430-1436`:

```c
if (!strcmp(op, "wait")) { resp_error(c, "wait is only supported for pipes"); return; }
if (!strcmp(op, "create_pipe") || !strcmp(op, "signal") || !strcmp(op, "set_metadata")) {
    resp_error(c, "unsupported request");   // <-- lỗi ta gặp
    return;
}
```

Upstream định nghĩa rõ 4 op này là **"Pipe only"**. Nghĩa là helper của app đã "cắt" đúng nửa chức năng của protocol 9: nửa PTY thì có, nửa pipe thì không. Tool `shell` cần nửa pipe → `unsupported request`.

**Việc cần làm:** implement pipe process trong C helper. Đây là thay đổi có thể làm được bằng mã nguồn hiện có (`pty_core.c` đã có `fork/execve/waitpid`), không cần root, không cần SDK.

---

## 2. Spec chuẩn (nguồn: `anomalyco/opencode` tag `v2.3.2`)

| File | Đường dẫn |
| --- | --- |
| Định nghĩa op/response | `packages/process-daemon/src/protocol.rs` — `PROTOCOL_VERSION = 9`, `MAX_FRAME_BYTES = 8 MiB`, `OUTPUT_FRAME_TAG = 0` |
| Kiểu dữ liệu dịch vụ | `packages/process-daemon/src/service.rs` — `ProcessMode`, `ProcessInfo`, `PipeInfo`, `PipeMetadata`, `ProcessSignal` |
| Spawn/read pipe Unix | `packages/process-daemon/src/service/pipe.rs`, `.../pipe/unix.rs` |
| Dispatch server | `packages/process-daemon/src/daemon/server.rs` |

Raw URLs (đối chiếu trực tiếp):
- `https://raw.githubusercontent.com/anomalyco/opencode/v2.3.2/packages/process-daemon/src/protocol.rs`
- `https://raw.githubusercontent.com/anomalyco/opencode/v2.3.2/packages/process-daemon/src/service.rs`
- `https://raw.githubusercontent.com/anomalyco/opencode/v2.3.2/packages/process-daemon/src/service/pipe/unix.rs`
- `https://raw.githubusercontent.com/anomalyco/opencode/v2.3.2/packages/process-daemon/src/daemon/server.rs`

### 2.1 Request `create_pipe`

```json
{
  "token": "<32 hex>",
  "request": {
    "op": "create_pipe",
    "program": "sh",
    "args": ["-c", "true"],
    "cwd": "/data/data/com.opencode.android/files/home/OpenCodeForAndroid",
    "env": { "PATH": "...", "HOME": "...", "JAVA_HOME": "..." },
    "group_id": "g",
    "output_path": "/data/data/com.opencode.android/files/usr/tmp/<...>.out",
    "metadata": { "command": "true", "nested": { "a": [1] } }
  }
}
```

Ràng buộc từ `service.rs:321-340` và `protocol.rs` test `create_requests_take_only_their_own_fields`:

- `program`, `args`, `cwd`, `env`, `group_id`, `output_path`, `metadata` **đều bắt buộc**.
- `cwd` và `output_path` phải **tuyệt đối** (`bail!("cwd must be absolute")`).
- `metadata` phải là **JSON object**, tối đa **1 MiB** (`MAX_METADATA_BYTES`).
- `env` là map `string → string`; upstream dùng `env_clear()` → con **chỉ** nhận đúng `env` (`service/pipe.rs`: `.env_clear().envs(&request.env)`).
- `output_path` mở với `O_WRONLY|O_CREAT|O_TRUNC|O_NOFOLLOW` — **không đi theo symlink** ở thành phần cuối.
- stdin = `/dev/null`; **stdout và stderr chung một pipe**, giữ đúng thứ tự byte (`service/pipe.rs` header).
- Con chạy trong **process group riêng** (`process_group(0)` / `setpgid(0,0)`).

### 2.2 Các op còn lại

| Op | Request | Response |
| --- | --- | --- |
| `wait` | `{"op":"wait","id":3}` | **Một** `{"type":"exited",...}` khi con kết thúc (`server.rs:357-359` → `wait_for_exit`, streaming) |
| `signal` | `{"op":"signal","id":3,"signal":"SIGTERM"}` | `{"type":"ok"}`. Chỉ nhận `SIGTERM`, `SIGINT`, `SIGKILL` (`service.rs:62-69`) |
| `set_metadata` | `{"op":"set_metadata","id":3,"metadata":{...}}` | `{"type":"ok"}`; thay metadata đã lưu (`service.rs:421-431`) |
| `list` | `{"op":"list","mode":"pipe"}` | `{"type":"processes","processes":[...]}` — **phải trả các pipe đang có**, không được trả rỗng như hiện tại (`opencode-pty.c:1152-1156`) |

### 2.3 Response shape

`Response::Created { process }` / `Response::Process { process }` với `ProcessInfo` (`service.rs:86-132`):

```json
{
  "id": 3,
  "mode": "pipe",
  "pid": 12345,
  "command": ["sh", "-c", "true"],
  "cwd": "/...",
  "group_id": "g",
  "lifecycle": { "status": "running" },
  "output_head": 0,
  "output_tail": 0,
  "started_at": 1763712...,
  "exited_at": null,
  "pipe": {
    "output_path": "/.../out",
    "output_lines": 0,
    "metadata": { "command": "true" }
  }
}
```

- `pty` group **chỉ có khi** `mode == "pty"`; `pipe` group **chỉ có khi** `mode == "pipe"`.
- lifecycle exited: `{"status":"exited","exit_code":0}` hoặc `{"status":"exited","signal":"SIGTERM"}` (exit_code `null`).

`Response::Exited` (`protocol.rs`):

```json
{
  "type": "exited",
  "exit_code": 0,
  "final_offset": 1234,
  "output_lines": 7,
  "exited_at": 1763712...
}
```

với `signal`, `output_error` chỉ xuất hiện khi có. `output_lines` = số byte `\n` ghi vào file output.

### 2.4 Framing (đã đúng trong C hiện tại)

- 4 byte độ dài big-endian + JSON payload → `client_frame()` (`opencode-pty.c:448`).
- Output frame: `TAG_OUTPUT (0)` + `start` (8 byte BE) + `end` (8 byte BE) + bytes → `send_output()` (`:767-783`). Khớp `write_output_frame()` của Rust.
- `auth`: envelope `{"token":"...","request":{...}}` (`:1461-1470`).
- `ping` response phải có `"features":[]` (`:626`) — đúng spec `FEATURES = &[]`.

---

## 3. Gap analysis — C helper hiện tại vs spec

| Chức năng | Spec upstream | C hiện tại | Hệ quả |
| --- | --- | --- | --- |
| `create_pty` | có | có (`:1357-1429`) | Terminal web OK |
| `create_pipe` | có | `unsupported request` (`:1434`) | **Tool `shell` chết** |
| `wait` (pipe) | trả 1 `Exited` | luôn lỗi (`:1430`) | Không lấy exit code |
| `signal` | SIGTERM/INT/KILL | `unsupported request` | Không huỷ lệnh |
| `set_metadata` | thay JSON | `unsupported request` | Không cập nhật trạng thái |
| `list mode=pipe` | liệt kê pipe | trả `[]` cứng (`:1152`) | Client không thấy process |
| `ProcessInfo.mode` | `pty` hoặc `pipe` | hardcode `"pty"` (`:560`) | Sai shape nếu có pipe |
| `ProcessInfo.pipe` | group `pipe` | hardcode group `pty` (`:591-601`) | Sai shape |
| `Exited.output_lines` | pipe only | thiếu (`:836-846`) | Client không biết số dòng |
| Spawn stdin/stdout | `/dev/null` + 1 pipe chung | chỉ có PTY master | Cần `pipe_spawn()` mới |
| Output file | `output_path`, O_NOFOLLOW | không có | Cần thêm |
| Drain after exit | 1 s (`DRAIN_AFTER_EXIT`) | không có | Con cháu giữ pipe treo reader |

---

## 4. Thiết kế vá (đề xuất)

### 4.1 Cấu trúc dữ liệu — `opencode-pty.c`

Mở rộng `Terminal` (`:50-73`) thành "Process record" dùng chung:

```c
typedef enum { MODE_PTY = 0, MODE_PIPE = 1 } ProcessMode;

typedef struct {
    /* ... giữ nguyên các field hiện tại ... */
    ProcessMode mode;

    /* Chỉ dùng cho pipe */
    int pipe_fd;            /* read end, O_NONBLOCK; thay thế pty.master trong pump */
    char *output_path;      /* bản sao request.output_path */
    int output_fd;          /* O_WRONLY|O_CREAT|O_TRUNC|O_NOFOLLOW, 0600 */
    char *metadata;         /* JSON serialized, <= 1 MiB */
    uint64_t output_lines;
    char *output_error;     /* NULL khi không lỗi */
    int waiters_count;      /* số client đang chờ Exited */
    uint64_t exited_at_ms;
    uint64_t drain_deadline;/* exited_at + 1000 ms */
} Terminal;
```

Giữ `Pty pty` để tối ưu hoá ít thay đổi; với pipe thì `pty.master = -1`, `pty.pid` vẫn dùng làm pid, `pty.argv/argc` vẫn dùng để điền `command`.

### 4.2 `pty_core.h/.c` — thêm `pipe_spawn()`

```c
int pipe_spawn(const char *program, char *const argv[], char *const envp[],
               const char *cwd, const char *output_path,
               int *out_read_fd, pid_t *out_pid);
```

Thuật toán (bám `service/pipe/unix.rs:56-110`):

1. `pipe2(fds, O_CLOEXEC)` cho output; tạo thêm pipe cancel nếu muốn graceful shutdown (tuỳ chọn, nhưng nên có).
2. `open(output_path, O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW, 0600)` **trước khi fork** để path xấu fail sớm, không tạo process mồ côi.
3. `fork()`; trong con:
   - `setpgid(0, 0)` → process group riêng.
   - `chdir(cwd)`.
   - `open("/dev/null", O_RDONLY)` → `dup2` vào `STDIN_FILENO`.
   - `dup2(fds[1], STDOUT_FILENO)` **và** `dup2(fds[1], STDERR_FILENO)` (cùng write end).
   - Reset signal disposition (`SIGHUP/SIGINT/SIGQUIT/SIGTERM/SIGALRM/SIGCHLD → SIG_DFL`) và xoá signal mask — giống `reset_signals()` của Rust, tránh thừa kế handler của daemon.
   - `execve(program, argv, envp ? envp : environ)`; `_exit(127)` nếu lỗi.
4. Trong cha: đóng write end, đặt `O_NONBLOCK` trên read end, lưu pid/fd/argv.

**Lưu ý env:** `make_envp()` (`:948-986`) hiện đã merge 11 biến an toàn (`LD_LIBRARY_PATH, BASH_ENV, HOME, PATH, TMPDIR, LANG, TZDIR, SHELL, PREFIX, OC_PREFIX, OC_USER_HOME`) khi caller không ghi rõ, rồi ép `TERM/COLORTERM`. Giữ nguyên hành vi này cho **cả pipe** — nếu bỏ theo đúng `env_clear` của upstream thì `sh` trong prefix chết ngay vì thiếu `LD_LIBRARY_PATH` (`TERMUX_DAEMON_AND_API.md:556-561`). Caller-supplied vẫn thắng. Đây là điểm **khác biệt có chủ đích** so với upstream, cần ghi chú trong code.

### 4.3 Handler `create_pipe`

Tái dùng logic validate của `create_pty` (`:1358-1405`) nhưng:

```c
if (!strcmp(op, "create_pipe")) {
    const char *program = json_string(request, "program", "");
    const char *cwd = json_string(request, "cwd", "");
    const char *output_path = json_string(request, "output_path", "");
    Json *env = json_get(request, "env");
    Json *meta = json_get(request, "metadata");

    if (!*program || !*cwd || cwd[0] != '/' || !output_path || output_path[0] != '/')
        return resp_error(c, "program, cwd and output_path are required; paths must be absolute");
    if (!env || env->type != JSON_OBJECT || !meta || meta->type != JSON_OBJECT)
        return resp_error(c, "env and metadata must be objects");
    /* serialize meta -> Buf, reject > 1 MiB (MAX_METADATA_BYTES) */
    /* stat(cwd) + S_ISDIR như create_pty */
    /* resolve_program + make_argv + make_envp như create_pty */

    Terminal *t = xcalloc(1, sizeof(Terminal));
    t->mode = MODE_PIPE;
    t->id = g_next_id++;
    t->started_at = ptime_ms();
    t->cwd = xstrdup(cwd);
    t->group_id = xstrdup(json_string(request, "group_id", ""));
    t->output_path = xstrdup(output_path);
    t->metadata = <json serialized>;

    if (pipe_spawn(resolved, argv, envp, cwd, output_path,
                   &t->pipe_fd, &t->pty.pid)) { /* dọn rồi resp_error("failed to start pipe") */ }

    terminal_add(t);
    send_created(c, t);   // phải đã sửa write_process_info() ở 4.6
    return;
}
```

### 4.4 Pump output cho pipe

Tách `pump_pty()` (`:1478-1510`) thành `pump_pty()` + `pump_pipe()`:

- Đọc `t->pipe_fd` đến `EAGAIN`/EOF.
- **Ghi file trước**, đếm `\n` → `t->output_lines`. Nếu write lỗi: set `t->output_error`, ngừng ghi nhưng vẫn đọc để tránh block, và đảm bảo `Exited` mang `output_error`.
- Sau đó `replay_append()`, `broadcast_output()` như PTY (giữ nguyên offset semantics).
- Khi EOF: `pty_wait(&t->pty)`; nếu exited → set `t->exit_sent`, `exited_at = max(exited_at, started_at)`, `broadcast_exited(t)`, đóng fd.
- **Drain 1 s**: nếu hết EOF nhưng con chưa reaped (con cháu còn giữ write end), đặt `drain_deadline = now + 1000` và tiếp tục đọc đến hạn rồi đóng — giống `DRAIN_AFTER_EXIT`. Tránh treo vĩnh viễn với `cmd &`.
- Trong `event_loop()` (`:1520-1552`), thêm slot `SLOT_PIPE` và đưa `pipe_fd` vào `poll()` khi `mode == MODE_PIPE` (hiện đang đưa `pty.master` cho mọi terminal → với pipe là `-1`, poll bỏ qua).

### 4.5 `wait`, `signal`, `set_metadata`, `list`

- **`wait`**: lấy `t = terminal_find(request_id(request))`; kiểm tra `t->mode == MODE_PIPE` nếu không trả lỗi. Nếu đã `exit_sent` → `send_exited(c, t)` ngay. Nếu chưa → lưu `c` vào danh sách waiter của `t`, **không trả response lúc đó**, không đóng connection. Khi `broadcast_exited`, gửi `Exited` cho cả subscribers lẫn waiters, rồi xoá waiter. (Khớp `wait_for_exit` của Rust: response được stream trên chính connection đó.)
- **`signal`**: parse `signal` ∈ {`SIGTERM`, `SIGINT`, `SIGKILL`}; map sang số; gọi `kill(-t->pty.pid, sig)` rồi `kill(t->pty.pid, sig)` (process group + fallback như `pty_signal()` ở `pty_core.c:125-128`); trả `resp_ok(c)`. Không remove record.
- **`set_metadata`**: validate object ≤ 1 MiB, serialize, thay `t->metadata`, trả `resp_ok(c)`.
- **`list mode=pipe`**: thay đoạn trả `[]` cứng (`:1152-1156`) bằng vòng lặp `g_terminals` lọc `mode == MODE_PIPE`, gọi `write_process_info()` cho từng cái.

Ngoài ra, với pipe: `write`/`resize`/`control`/`input` phải trả error rõ ràng ("pipe has no terminal") thay vì ghi vào `pty.master == -1`; `get`/`snapshot`/`replay`/`subscribe` có thể dùng chung nhưng `snapshot` nên trả lỗi vì pipe không có màn hình.

### 4.6 Sửa shape response

- `write_process_info()` (`:559-602`): thay `"mode\":\"pty"` cứng bằng `t->mode == MODE_PTY ? "pty" : "pipe"`; nhánh `pty` chỉ emit khi PTY, nhánh `pipe` emit `{"output_path":..., "output_lines":..., "metadata":<raw>}` khi pipe; thêm `output_error` nếu có.
- `build_exited()` (`:836-846`): thêm `,"output_lines":N` và (nếu có) `,"output_error":"..."` cho pipe. `exit_code` = `null` khi bị signal (đã đúng).
- Cần tiện ích serialize JSON mới: `util.c/h` hiện chỉ có parser (`json_parse/json_get/...`), **không có json writer**. Thêm `int json_write(Buf *b, const Json *value)` đệ quy (null/bool/number/string/array/object) để (a) lưu `metadata` và (b) emit lại nguyên vẹn. Dùng `buf_json_string()` có sẵn (`:20`). Estimated ~60 LOC.

### 4.7 Build & deploy

```sh
# 1. build helper (NDK clang API 28, arm64 + x64)
sh native/pty-c/build.sh

# 2. đóng gói vào assets/native/<abi>/opencode-pty
# 3. reset stamp để app trích xuất lại
rm -f "$HOME/.opencode/.terminal-runtime-c"

# 4. restart server để lấy helper mới
#    (app: Stop/OpenCode service hoặc `opencode service stop`)
```

App tự verify bằng `opencode-pty --version` rồi export `OPENCODE_PTY_BIN` (`OpenCodeBootstrap.kt:2816-2835`, `RuntimeContract.kt:296-297`) — không cần sửa Kotlin nếu giữ nguyên tên file và version string `1.0.0 (android-c, protocol 9)` (`opencode-pty.c:1692`).

---

## 5. Kế hoạch kiểm chứng

### 5.1 Unit/protocol (có thể port từ Rust test)

Port 3 test trong `protocol.rs` sang C test nhỏ:
1. `create_pty` chấp nhận đủ field, thiếu `title/cols/rows/group_id` → lỗi.
2. `create_pipe` chấp nhận sample ở mục 2.1; thiếu `output_path/metadata/env` → lỗi; `metadata` không phải object → lỗi.
3. `op:"create"` (v7) → `unsupported request`.

### 5.2 Handshake trực tiếp với helper

```sh
# trong terminal app, sau khi cài helper mới
SOCK=$(find "$PREFIX/run" -name '*.sock' | head -1)
# gửi frame create_pipe bằng python/perl nếu có, hoặc dùng chính agent sau khi vá
```

### 5.3 End-to-end (mục tiêu cuối)

```sh
# Từ agent (không cần paste nữa):
shell echo hello
shell 'java -version'
shell 'sh "$HOME/OpenCodeForAndroid/docs/scripts/java-install-diagnose.sh"'
```

Tiêu chí xong: `create_pipe` trả `created` với `mode:"pipe"`; file `output_path` chứa output; `wait` trả `Exited` với `exit_code` đúng; `shell echo hello` không còn `unsupported request`.

---

## 6. Rủi ro & phương án dự phòng

| Rủi ro | Giảm thiểu |
| --- | --- |
| Sửa `write_process_info()` làm hỏng terminal web | Giữ nhánh PTY giữ nguyên byte-for-byte; chỉ thêm nhánh pipe; test `list mode=pty` + snapshot trước/sau |
| Con cháu giữ pipe → reader treo | Drain 1 s như upstream + cancel pipe |
| `env_clear` đúng upstream làm shell chết | Giữ merge 11 biến an toàn của app (§4.2) |
| SIGSYS khi con chạy binary tĩnh | Dùng `oc-run`/gopatch cho binary đó; JDK bionic đã chạy OK (`java -version` exit=0) |
| poll fd `-1` cho pipe | Tách slot `SLOT_PIPE`, chỉ đưa fd hợp lệ |
| Metadata lớn | Giới hạn 1 MiB, trả error trước khi spawn |
| Không build được ở đây (shell tool chưa chạy) | Dùng Terminal panel để chạy `build.sh`; hoặc phương án B2/B3 trong báo cáo trước |

---

## 7. Đề xuất triển khai

1. **Đã áp code và push lên `main`** (ngày 2026-11-21), gồm 5 file: `pty_core.h`, `pty_core.c`, `util.h`, `util.c`, `opencode-pty.c`. Các commit:
   - `3c8e0f9` — `pipe_spawn()` + `json_write()` (hạ tầng).
   - `9f05e27` — đủ 4 op protocol 9 (`create_pipe`, `wait`, `signal`, `set_metadata`), `pipe` mode trong `ProcessInfo`/`Exited`, `pump_pipe()`, lọc `list mode=pipe`.
   - `db57df3` — fix `-Werror` (cast `Buf.data` sang `const char *` trong `json_dup()`).
   CI **Debug build → Native helpers** đã biên dịch thành công cả 2 ABI; job build APK/unit test chạy sau đó. Xem `https://github.com/manhduonghn/OpenCodeForAndroid/actions/runs/44875755945`.
2. **Không đụng vào app đã cài.** Muốn thử nghiệm phải cài artifact debug APK từ Actions; helper mới nằm trong `assets/native/<abi>/opencode-pty`. Tool `shell` trong app hiện tại vẫn lỗi cho đến khi app được cập nhật.
3. Nếu CI báo lỗi mới, sửa tiếp trên `main` theo vòng lặp push → CI; không cần chạy lệnh tại đây (tool `shell` vẫn bị chặn bởi chính helper cũ).
4. Quyền OpenCode đã đầy đủ — đừng sửa `opencode.jsonc`; sửa config không ảnh hưởng tới `unsupported request`.

---

## 8. Tài liệu tham khảo

- Local: `native/pty-c/opencode-pty.c` (đặc biệt `:33-38, 55-73, 448, 559-602, 767-791, 836-859, 948-986, 1019-1020, 1068, 1150-1167, 1357-1441, 1478-1552, 1692`), `pty_core.c/.h`, `util.c/.h`.
- Local docs: `docs/OPENCODE_SHELL_CAPABILITY_REPORT.md`, `docs/TERMUX_DAEMON_AND_API.md:539-561, 684`, `docs/RUNTIME_PATH_HOOK.md:31, 236-247`.
- Upstream `v2.3.2`: `packages/process-daemon/src/{protocol.rs,service.rs,daemon/server.rs,service/pipe.rs,service/pipe/unix.rs}` (URL ở mục 2).
- `https://opencode.ai/docs/permissions/`, `https://opencode.ai/docs/tools/` — khẳng định đây không phải vấn đề quyền.
