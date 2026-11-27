# Báo cáo: vì sao agent không dùng được lệnh shell, và cách lấy lại quyền đó

**Ngày:** 2026-11-21
**Cập nhật:** 2026-11-21 (phiên sau) — **lỗi `unsupported request` đã được vá**; xem mục 0. Các mục 1–6 giữ nguyên phần phân tích gốc, chỉ bổ sung ghi chú về trạng thái hiện tại.
**Câu hỏi:** "Tại sao bạn không toàn quyền sử dụng và thực hiện lệnh shell trong môi trường, cách để bạn toàn quyền sử dụng?"
**Kết luận ngắn (tại thời điểm điều tra):** **Đây không phải lỗi xin quyền (permission).** OpenCode V2 mặc định đã `allow *`; không có file cấu hình nào thu hẹp quyền. Lệnh shell bị chặn ở **lớp năng lực (capability)**: tool `shell` đi qua helper PTY do chính repo này tự build, và helper đó **từ chối các op cần thiết để chạy lệnh không tương tác** bằng chuỗi `unsupported request`. Ngoài ra còn một lớp nữa không thể gỡ bằng cấu hình: **sandbox Android** (uid `u0_a403`, SELinux `untrusted_app_27`).

> **Không còn đúng nữa:** script Java giờ có thể do agent tự chạy; không cần bạn chạy trong Terminal panel rồi paste output. Lý do lịch sử của cách làm đó là capability gap của helper PTY, đã được sửa (mục 0).

Quy ước: `[XN]` = có bằng chứng trực tiếp (mã nguồn/log/file); `[GT]` = suy luận có cơ sở, cần thử nghiệm để chốt.

## 0. Cập nhật: tool `shell` đã hoạt động đầy đủ

`[XN]` Bản vá nửa pipe của giao thức 9 đã vào repo và được cài trên máy:

| Mốc | Bằng chứng |
| --- | --- |
| `3c8e0f9` | thêm `pipe_spawn` và JSON writer (`native/pty-c/pty_core.c`, `util.c`) |
| `9f05e27` | implement `create_pipe`, `wait`, `signal`, `set_metadata` trong `native/pty-c/opencode-pty.c` |
| `db57df3` | sửa cast cho `-Werror` |
| `0e195a5` | ghi lại tài liệu nghiên cứu (báo cáo này và `PTY_C_PIPE_SUPPORT_RESEARCH.md`) |

Code hiện tại: `create_pipe` ở `opencode-pty.c:1553`, `wait` ở `:1638`, `signal` ở `:1665`, `set_metadata` ở `:1688`. Nhánh `unsupported request: <op>` ở `:1713` giờ chỉ dành cho op không được định nghĩa; thông báo `wait is only supported for pipes` ở `:1645` chỉ áp cho PTY tương tác, đúng thiết kế upstream.

Helper đã cài: `~/.opencode/bin/opencode-pty --version` → `opencode-pty 1.0.0 (android-c, protocol 9)`, stamp `2 31 arm64-v8a …`. CLI: `opencode v2.3.2`.

Kiểm chứng trực tiếp trong harness Android (không cần paste output nữa):

```text
$ sh -c 'printf "out\n"; printf "err\n" >&2; exit 7'; echo "exit=$?"
out
err
exit=7
$ echo abc | grep -q b; echo "pipeline=$?"
pipeline=0
$ (sleep 0.01; echo bg-ok) & wait; echo "wait=$?"
bg-ok
wait=0
$ pkg list-installed | head -1  → exit 0
$ opencode api get /api/info   → exit 0 (127.0.0.1:49374)
```

Hệ quả:

- **Lớp B (năng lực)** đã đóng; phương án B3 (`bạn chạy rồi paste`) không còn cần thiết.
- **Lớp A (quyền)** vẫn như cũ: không có file cấu hình nào trong repo/home, base policy vẫn `allow *`.
- **Lớp C (Android)** không đổi: tiến trình vẫn là app thường (`u0_a404`, `untrusted_app_27` trong phiên hiện tại), không root, không `apt`. Đây là giới hạn hệ điều hành, không phải lỗi tool.
- Cấu hình OpenCode V2 không cần sửa để khôi phục shell: docs `https://opencode.ai/docs/tools/` và `/permissions/` xác nhận `shell` là tool lõi, quyền mặc định đã mở.

---

## 1. Có 3 lớp khác nhau, đừng gộp lại

| Lớp | Cái gì quyết định | Trạng thái hiện tại | Có sửa bằng `opencode.jsonc` được không |
| --- | --- | --- | --- |
| **A. Quyền tool OpenCode** | `permissions` / `policies`, file `opencode.json(c)` | `[XN]` **Đã mở sẵn** (base policy `{action:"*",resource:"*",effect:"allow"}`), không có config file nào trong repo/home | Không cần; sửa cũng không đổi triệu chứng |
| **B. Đường dẫn thực thi shell của tool** | helper `opencode-pty` / cách harness spawn lệnh | `[XN]` helper trả `unsupported request` cho `create_pipe`, `signal`, `set_metadata` — **đã sửa, xem mục 0** | **Không** — phải sửa code/build (đã làm) |
| **C. Hệ điều hành Android** | uid, SELinux, targetSdk, quyền app | `[XN]` `u0_a403`, `untrusted_app_27`, Android 13 | **Không** — chỉ root/host khác mới thoát |

Lỗi đã gặp nằm ở **B**, không phải A hay C — và B đã được sửa (mục 0). Lớp C vẫn là giới hạn cứng của Android.

---

## 2. Bằng chứng

### 2.1 Không phải từ chối quyền `[XN]`

- Tài liệu chính thức `https://opencode.ai/docs/permissions/` nêu rõ base policy của **mọi agent**:

  ```jsonc
  [
    { "action": "*", "resource": "*", "effect": "allow" },
    { "action": "read", "resource": "*.env", "effect": "ask" },
    { "action": "read", "resource": "*.env.*", "effect": "ask" },
    { "action": "read", "resource": "*.env.example", "effect": "allow" },
  ]
  ```

  Nếu bị từ chối quyền, kết quả phải là luồng `ask` (hiện dialog **Allow once / Allow always / Reject**) hoặc thông báo `deny`/`permission required` — **không phải** `unsupported request`.

- Tìm kiếm config: không có `opencode.json` / `opencode.jsonc` nào trong workspace lẫn `$HOME`; cũng không có thư mục `.opencode/` chứa cấu hình. Nghĩa là không có rule `deny` nào được thêm vào.

- `[GT]` nhưng rất chắc: nếu là `ask` thì tôi đã nhận được request approval trong harness; thực tế tool trả lỗi ngay lập tức với nội dung lệnh làm prefix (`echo hello: unsupported request`) — đây là kiểu lỗi **thực thi không được hỗ trợ**, không phải lỗi phê duyệt.

### 2.2 Chuỗi lỗi khớp chính xác với helper PTY của repo này `[XN]`

Chuỗi `unsupported request` chỉ xuất hiện ở một nơi trong toàn repo (ngoài báo cáo trước đó của tôi):

```c
// native/pty-c/opencode-pty.c:1430-1441
if (!strcmp(op, "wait")) {
    resp_error(c, "wait is only supported for pipes");
    return;
}
if (!strcmp(op, "create_pipe") || !strcmp(op, "signal") || !strcmp(op, "set_metadata")) {
    resp_error(c, "unsupported request");          // <-- dòng 1435, khớp 100% với log
    return;
}
Buf message = {0};
buf_printf(&message, "unsupported request: %s", op); // dòng 1439 (có ": <op>")
```

Log của tôi là `unsupported request` **không có hậu tố `: <op>`** → khớp đúng nhánh dòng 1435, tức là một request thuộc nhóm `create_pipe` / `signal` / `set_metadata`.

Helper này là bản thay thế do app tự build, nói giao thức **protocol 9** của web terminal (`opencode-pty.c:4-8`) và chỉ cài đặt một tập op hạn chế:

- Có: `list`, `get`, `write`, `resize`, `control`, `input`, `snapshot`, `read_rows`, `rename`, `replay`, `subscribe`, `terminate`, **`create_pty`**, `close`, `kill`, `handoff` (`opencode-pty.c:1150-1167, 1253, 1357, 1430`). Tên op thật là `create_pty` (op `create` của v7 đã bị upstream loại bỏ) — xác nhận từ spec `v2.3.2`.
- **Không có**: `create_pipe`, `signal`, `set_metadata` — đây là nhóm op mà một tool `shell` chạy lệnh rồi **thu stdout/stderr + mã exit** cần dùng (chế độ `pipe`, đối lập với `pty` tương tác).

Cơ chế đã được ghi trong docs của repo:
- `RUNTIME_PATH_HOOK.md:31`: `native/pty-c/` là daemon C/bionic cho web terminal, **không đi qua preload glibc**.
- `TERMUX_DAEMON_AND_API.md:539-547`: app không dùng helper glibc của CLI mà tự build `opencode-pty`, export `OPENCODE_PTY_BIN` cho server; "nói cùng giao thức framed" — tức là server/tool có thể gửi op mà helper chưa implements.
- `SESSION_RUNTIME_ENVIRONMENT.md:141`: helper là một phần của runtime contract.

`[GT]` — cần kiểm chứng thêm: tool `shell` của harness trong bản Android này được dẫn tới `create_pipe` trên helper (thay vì spawn trực tiếp `/system/bin/sh`). Suy luận dựa trên (1) khớp chuỗi chính xác, (2) `create_pipe` đúng là op chạy lệnh không tương tác, (3) `wait` của helper cũng báo "only supported for pipes" → nhánh pipe vốn chưa hoàn thiện.

### 2.3 Code Mode không đi vòng được `[XN]`

`execute` (Code Mode) trong môi trường này chỉ có catalog `opencode` với 8 tool (session/worktree/MCP resources/models) + `fetch`. **Không có `shell` trong catalog**, không có import filesystem, không có timer. Docs `https://opencode.ai/docs/tools/` cũng nói Code Mode chỉ gọi lại các tool trong catalog — và `execute` bị deny thì mới gỡ chế độ Code Mode. Vì vậy không thể dùng `execute` để lách lớp B.

### 2.4 Lớp Android: "toàn quyền" có giới hạn cứng `[XN]`

Từ output bạn paste ở bước cài Java:

```text
uid=10403(u0_a403) gid=10403(u0_a403)
context=u:r:untrusted_app_27:s0:c147,c257,c512,c768
Linux localhost 5.15.194-android13-... aarch64 Android
```

Dù mở mọi quyền OpenCode, tiến trình vẫn là app thông thường:

- Không có `root`/`su`; không đọc được `/data/data/com.termux` hay data app khác; không `apt`, chỉ có bridge `pkg` của app.
- SELinux `untrusted_app` chặn nhiều syscall/đường dẫn; bionic chặn `clone3`, `close_range`, `landlock_create_ruleset` → cần SIGSYS handler (`RUNTIME_PATH_HOOK.md:236-247`) hoặc `oc-run`/gopatch cho binary tĩnh.
- `targetSdk = 36`: `execve()` trong app data bị chặn, nên runtime dùng linker shim/loader đóng gói trong APK (`docs/TARGET_SDK_36_EXEC_SHIM.md`). Quyền của agent vẫn bị giới hạn bởi sandbox Android.
- Prefix Termux-like được cài **không root** qua broadcast receiver có signature permission (`OpenCodePkgReceiver`, `TERMUX_DAEMON_AND_API.md:369-370, 618-619`).

Nói cách khác: **OpenCode grant quyền cho agent, nhưng Android không grant quyền cho OpenCode.**

---

## 3. Cách để "toàn quyền" — theo từng lớp

### Mức A — Quyền OpenCode (dễ, nhưng không sửa được lỗi hiện tại)

Tạo `opencode.jsonc` ở gốc repo (hoặc config global theo `https://opencode.ai/docs/config/`):

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "permissions": [
    { "action": "*", "resource": "*", "effect": "allow" }
  ]
}
```

Hoặc khi hiện dialog phê duyệt, chọn **Allow always** để lưu rule dạng `shell: <prefix> → allow`. Lưu ý:

- Base policy vốn đã `allow *` → với triệu chứng này **không đổi gì**.
- Rule sau cùng thắng; `policies` (Console/quản trị) có thể **hard-deny** và override cả config project — nếu có policy như vậy thì mới là lý do quyền bị thu.
- Nên dùng allowlist hẹp thay vì `*` cho `shell`, vì docs cảnh báo shell mang toàn bộ quyền filesystem/process/network của user host.

### Mức B — Sửa năng lực shell (đây mới là việc cần làm)

**Phương án B1 (khuyến nghị nếu muốn agent tự chạy lệnh): bổ sung op cho helper PTY.**

1. Trong `native/pty-c/opencode-pty.c`, implement `create_pipe`: spawn `/system/bin/sh -c <cmd>` (hoặc `$SHELL`) ở chế độ pipe, đóng gói stdout/stderr thành frame tag-0, trả `exit_code` trong response; implement luôn `wait` cho pipe (hiện báo "only supported for pipes" ở dòng 1431) và `signal`/`set_metadata` tối thiểu.
2. Giữ nguyên framing protocol 9 (4-byte big-endian length + JSON envelope) để server không phải đổi.
3. Build lại bằng `native/pty-c/build.sh` (NDK clang API 28), đóng gói `assets/native/<abi>/opencode-pty`, xoá/reset stamp `$HOME/.opencode/.terminal-runtime-c` để `ensureTerminalRuntime()` trích xuất bản mới.
4. Kiểm chứng: `opencode-pty --version` → `1.0.0 (android-c, protocol 9)`, rồi thử `shell echo hello` từ agent.

**Phương án B2 (ít xâm lấn hơn nếu harness cho phép cấu hình): đổi đường dẫn spawn của tool `shell`** từ "gửi `create_pipe` tới PTY" sang spawn trực tiếp (`ProcessBuilder` trong app / `posix_spawn` của server) với env từ `RuntimeEnv` (`PATH`, `LD_LIBRARY_PATH`, `BASH_ENV`, `HOME`, `TMPDIR`, `PREFIX`, `OC_PREFIX`, `OC_USER_HOME` — danh sách an toàn đã có ở `opencode-pty.c:972` và `TERMUX_DAEMON_AND_API.md:556-561`). Cần giữ `LD_LIBRARY_PATH` ngay từ lần exec đầu nếu không bash báo `libandroid-support.so not found`.

**Phương án B3 (làm ngay, không sửa code): giữ nguyên vòng lặp hiện tại** — tôi soạn script, bạn chạy trong Terminal panel, paste output. Đây là cách đã chốt được Java 17.0.21.1 exit=0 trong báo cáo trước.

### Mức C — Quyền hệ thống (chỉ khỏi bằng môi trường khác)

| Mục tiêu | Cách | Khả thi ở đây |
| --- | --- | --- |
| Root/su, apt thật, đọc data app khác | Root device / `adb root` / custom ROM | Không (app chạy `u0_a403`, chữ ký app thường) |
| Shell tool đầy đủ, không vá PTY | Chạy OpenCode trên Linux host / WSL / Termux bản quyền đầy đủ, rồi `cd` vào repo qua ADB/sync | Có, nhưng rời khỏi thiết bị |
| Cho phép lệnh tuỳ ý từ agent mà không root | Thêm bridge trong app (pattern `OpenCodePkgReceiver`: ordered broadcast + signature permission) nhận lệnh và thực thi trong tiến trình app | Có, nhưng mở rộng bề mặt bảo mật — cần allowlist |

---

## 4. Khuyến nghị

1. **Đừng tốn thời gian sửa `opencode.jsonc`** cho trường hợp này — quyền đã mở sẵn, lỗi là `unsupported request` dòng 1435 (cũ).
2. ~~Nếu mục tiêu là để tôi tự chạy `pkg install`, `./gradlew`, smoke test C/C++: làm **B1**, kèm test pipe/exit code.~~ **ĐÃ LÀM:** B1 đã được triển khai qua `3c8e0f9`/`9f05e27`/`db57df3` và kiểm chứng ở mục 0.
3. ~~Nếu chỉ cần kết quả nhanh: dùng **B3** như đã làm với Java.~~ **Không còn cần** — agent tự chạy lệnh được.
4. Khi đọc lại báo cáo Java cũ, ghi rõ lý do lịch sử là "capability gap của PTY helper, không phải thiếu quyền"; lý do đó đã không còn sau bản vá.
5. Script `docs/scripts/java-install-diagnose.sh` giờ có thể chạy trực tiếp từ agent. Tuy nhiên ở phiên cài đặt hiện tại JDK/SDK đã bị reset (xem mục 0 của `LINUX_RUNTIME_AND_TOOLCHAIN_REPORT.md`), nên cần `pkg install -y openjdk-17` trước khi chạy script.

---

## 5. Kiểm chứng nhanh để tự phân loại trong tương lai

| Thử | Kết quả nếu là A (quyền) | Kết quả nếu là B (năng lực) | Kết quả nếu là C (OS) |
| --- | --- | --- | --- |
| `shell echo hello` | Dialog Allow/Reject hoặc `denied` | `unsupported request` (trước bản vá); **hiện tại chạy bình thường** | `permission denied` / `EACCES` / `operation not permitted` |
| Xem `opencode.jsonc` | Có rule `deny` | Không có rule nào | Không liên quan |
| `id`, `context=` từ terminal | Bình thường | Bình thường | `u0_a...`, `untrusted_app`, không root |
| `oc-run java -version` | — | Chạy được (sửa script xong) | SIGSYS nếu binary tĩnh không được patch |

---

## 6. Tài liệu tham khảo

- `https://opencode.ai/docs/permissions/` — base policy, thứ tự rule, `ask`/`allow`/`deny`, `policies`, approvals.
- `https://opencode.ai/docs/tools/` — `shell`, `execute` (Code Mode), permission của từng tool.
- `https://opencode.ai/docs/config/` — vị trí `opencode.jsonc`.
- `native/pty-c/opencode-pty.c:4-8, 972, 1150-1167, 1253, 1430-1441` — protocol 9, env an toàn, tập op, nhánh `unsupported request` **trước bản vá**; sau bản vá xem `:1553,1638,1665,1688,1713` (mục 0).
- `docs/RUNTIME_PATH_HOOK.md:31, 236-247` — helper bionic, SIGSYS policy.
- `docs/TERMUX_DAEMON_AND_API.md:369-370, 539-561, 618-619, 684` — `OPENCODE_PTY_BIN`, socket dir, env kế thừa, broadcast signature permission, targetSdk.
- `docs/SESSION_RUNTIME_ENVIRONMENT.md:121-155` — runtime contract và helper PTY.
- Output thiết bị của `docs/scripts/java-install-diagnose.sh` (uid/context/Java) — bằng chứng lớp C và kết quả Java.
