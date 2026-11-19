# Môi trường phiên thống nhất: prefix → server → WebView

Tài liệu thiết kế + trạng thái triển khai cho **OpenCode for Android**. Thiết kế ở phần 1–8;
phần 9 ghi nhận những gì đã được hiện thực hoá trong working tree.

Mục tiêu: **không còn vá đơn lẻ**. Thay vì thêm một guard/script/config mới mỗi khi gặp lỗi,
app có một *hợp đồng môi trường* duy nhất, được mọi tầng cùng đọc và cùng xác nhận.
Triệu chứng cần loại bỏ hoàn toàn: `ClientError: Transport: Failed to fetch` và stack trace
SolidJS hiện trong WebView của phiên local.

Nguồn tham chiếu upstream (OpenCode V2):

- <https://opencode.ai/docs/troubleshooting/> — service là client/server; health chuẩn là
  `opencode api get /api/info`; log có `role=server`; không sửa `service.json`/DB lúc đang chạy.
- <https://opencode.ai/docs/api/> — `GET /api/info`, `POST /api/pair`,
  `GET /auth/connect/{code}`, `GET /api/session`, `GET /api/event`, `POST /api/pty` và nhóm
  persistent PTY. Các route session/PTY đều là *experimental*, nên hợp đồng phải chịu được
  việc response shape đổi.

---

## 1. Chẩn đoán: vì sao "vá đơn lẻ" không kết thúc được câu chuyện

### 1.1. Lỗi `Failed to fetch` thực sự ở đâu

Luồng hiện tại (`OpenCodeWebActivity.loadUi()` → `OpenCodeWebViewModel.BOOT_SCRIPT`):

1. `serverReachable()` chỉ mở TCP tới `127.0.0.1:49374` (`OpenCodeHttp.isPortOpen`).
   TCP mở **không** có nghĩa server đã sẵn sàng trả `/api/session`: port mở trước khi DB
   và location load xong ≈0.2–0.5 s.
2. `requestSessionToken()` mint `POST /api/pair`, redeem `/auth/connect/<code>`, rồi check
   `/api/info`. Nếu chưa được thì `pairAttempts` retry 6 lần.
3. `loadUi()` đặt `pageLoaded = true`, đưa `?auth_token=…` vào URL, giữ `statusPanel`
   (nền `?attr/colorSurface`, vẽ sau `web_container` → che kín WebView) và chỉ gỡ panel
   khi `BootBridge.ready()` — tức **một** API call đầu tiên thành công.
4. `BOOT_SCRIPT` bọc `fetch`, retry GET/HEAD same-origin `/api/` tới 6 lần (200 ms → 3 s).
   Sau khi hết lượt nó gọi `transportLost` **rồi `throw error`**. Chính lần `throw` cuối
   cùng đi vào query/error boundary của SolidJS.

Vì vậy có bốn cửa sổ stack trace vẫn lọt ra:

| Cửa sổ | Cơ chế hiện tại | Hệ quả |
| --- | --- | --- |
| Sau `showWebUi()` (đã gỡ panel), server restart | `transportLost` được `mainHandler.post` về activity, rồi mới `showBootPanel` | Trong vài frame WebView đang hiện, error boundary vẽ stack trước khi panel kịp phủ |
| 401/403 do token của server cũ | `authFailed` → `AUTH_RECOVERY_MS = 250 ms` mới pair lại | Stack hiện trong 250 ms; retry mãi không tự khỏi |
| WebView cũ không hỗ trợ document-start | `readinessFallback` probe `/api/session` rồi reveal; **không có wrapper fetch** | Mọi lỗi sau đó là lỗi thô |
| Request không phải `fetch` GET/HEAD (chunk dynamics, `EventSource /api/event`, PTY WebSocket) | Không được bọc | Error boundary tự vẽ, native không biết |

### 1.2. Các patch đang chồng lên nhau

| Patch | File | Vai trò | Vấn đề khi chồng |
| --- | --- | --- | --- |
| `BOOT_SCRIPT` retry | `OpenCodeWebViewModel` | che cold start | Quyết định retry **trong JS**, tách khỏi state native |
| `readinessFallback` | `OpenCodeWebActivity` | fallback WebView cũ | Probe `/api/session`, không phải health endpoint |
| `bootTimeout` 15 s | `OpenCodeWebActivity` | thoát spinner | Chồng lên retry của JS (tổng delay ≈9 s) |
| `transportWatchdog` 1.5 s | `OpenCodeWebActivity` | pair lại | Cùng mục đích với `generationWatch` 3 s |
| `generationWatch` | `OpenCodeWebActivity` | phát hiện server thay | Đọc `serverGeneration`, không đọc trạng thái health |
| `failUi` + `loadFailures > 3` | `OpenCodeWebActivity` | dừng reload vô hạn | Dừng ở UI, không dừng ở supervisor |
| `recoverWebView` / `hardRefreshWebUi` / `reconnectToRestartedServer` | `OpenCodeWebActivity` | 3 đường recovery | Mỗi chỗ tự lấy `sessionId` và tự reset 5 field |
| `serverEnvironmentCurrent` + `serverEnvironmentStamp` | `OpenCodeService` | quyết adopt server | So từng biến env, dễ lệch với `buildPackageEnvironment()` |
| `WATCHER_SCRIPT`, `LINK_SCRIPT`, theme script | `OpenCodeWebViewModel` | route/link/theme | 4 script document-start độc lập, không có phiên bản hợp đồng |

Kết luận: các patch này **đều đúng từng ca**, nhưng không có *nguồn chân lý* chung.
Mỗi ca mới lại nối thêm một Runnable hoặc một cờ. Đây là lý do phải đổi kiến trúc.

---

## 2. Nguyên tắc thiết kế

1. **Một hợp đồng, nhiều người dùng chung.** Một `RuntimeContract` duy nhất mô tả prefix, binary,
   helper, env, auth, port, health. Service shell, terminal, WebView và bridge cùng đọc.
2. **Health, không phải socket.** `READY` = `GET /api/info` trả 200 với credential đúng
   và `version` khớp binary đã cài. TCP mở chỉ là tín hiệu "đang STARTING".
3. **Generation là danh tính của server.** Mọi token, cookie, page, session ref đều gắn
   `generation`. Đổi generation là *chuyển state*, không phải lỗi transport.
4. **Ready barrier.** WebView chỉ được reveal khi hợp đồng `READY` được xác nhận liên tục.
   Không có trường hợp "đã reveal rồi mới phát hiện server chết".
5. **Native là authority, JS chỉ báo cáo.** Không để error boundary của web app tự xử lý
   lỗi hạ tầng. Script document-start chỉ report + giữ tối đa một retry cold-start.
6. **Hội tụ idempotent.** Mọi bước (alias, env, wrapper `rg`, PTY, cookie, pair) có thể chạy
   lại bất kỳ lúc nào; so nội dung thay vì tin timestamp.
7. **Không lưu secret trong manifest.** Password vẫn ở SharedPreferences + file `0600`;
   manifest chỉ chứa *tham chiếu*.
8. **Giữ nguyên nền tảng đã đúng:** `targetSdk 28`, không root/proot/chroot, relocation
   cùng độ dài, pathmap/gopatch, self-heal hai mức, wake lock + watchdog, CA/resolv.conf.

---

## 3. Kiến trúc đề xuất

```
┌─ A. RuntimeContract ────────────────────────────────────────────┐
│  $OC_HOME/runtime.json  (0600, atomic rename)                    │
│  sinh ra bởi: RuntimeEnv (Kotlin data class)                     │
│  → $PREFIX/etc/opencode-env.sh   (shell/terminal)                │
│  → ProcessBuilder.environment() (server)                         │
│  → contractHash                 (adopt/restart quyết định)       │
└──────────────────────────┬──────────────────────────────────────┘
                           │ đọc
┌─ B. RuntimeSupervisor ───▼──────────────────────────────────────┐
│  ABSENT → PREPARING → STARTING → HEALTHY(g)                      │
│                                  ↕ DEGRADED(g) → RESTARTING(g+1) │
│  STOPPED (user)                                                    │
│  publish RuntimeState {generation,state,health,pid,contractHash} │
└──────────────────────────┬──────────────────────────────────────┘
                           │ state + credential
┌─ C. SessionGateway ──────▼──────────────────────────────────────┐
│  pair(g) → token(g) → cookie(g) → SessionRef(g, id, title)       │
│  generation đổi ⇒ giữ id, bỏ token/cookie, mở lại route          │
└──────────────────────────┬──────────────────────────────────────┘
                           │ UiState
┌─ D. WebShell (ViewModel + Activity) ────────────────────────────┐
│  BOOTING → WAITING_API → READY → RECONNECTING → RECOVERING       │
│  1 bridge versioned `OpenCodeHostV1`, 1 document-start script     │
│  Activity chỉ render UiState; không còn 5 Runnable độc lập       │
└──────────────────────────────────────────────────────────────────┘
```

### Tầng A — `RuntimeContract`

File `$HOME/.opencode/runtime.json`, ghi qua file tạm + `rename()`, mode `0600`, schema version.
Nội dung đề xuất (bỏ secret):

```jsonc
{
  "schema": 1,
  "abi": "arm64-v8a",
  "paths": {
    "prefix": "/data/data/com.opencode.android/files/usr",
    "home": "/data/data/com.opencode.android/files/home",
    "ocHome": "/data/data/com.opencode.android/files/home/.opencode"
  },
  "binary": { "path": "...", "sha256": "...", "version": "..." },
  "launch": {
    "loader": "$PREFIX/glibc/lib/ld-linux-aarch64.so.1",
    "preload": "$PREFIX/lib/liboc-pathmap.so",
    "libraryPath": [ "$PREFIX/lib", "$PREFIX/glibc/lib" ],
    "argv": [ "serve", "--hostname", "127.0.0.1", "--port", "49374" ]
  },
  "helpers": {
    "pty": "$OC_HOME/bin/opencode-pty",
    "ptySocketDir": "$PREFIX/run",
    "ripgrep": "$HOME/.cache/opencode/bin/rg",
    "pathmap": "$PREFIX/lib/liboc-pathmap.so"
  },
  "http": { "host": "127.0.0.1", "port": 49374, "health": "/api/info" },
  "auth": {
    "user": "opencode",
    "passwordRef": "sharedPreferences:server_password",
    "pair": "/api/pair",
    "connect": "/auth/connect/"
  },
  "env": { "HOME": "...", "TMPDIR": "...", "BASH_ENV": "...", "...": "..." },
  "stamps": { "apk": "...", "packages": "...", "env": "...", "native": "..." }
}
```

- `RuntimeEnv` là data class Kotlin duy nhất. Ba nơi render từ nó: script shell
  (`RuntimeShellScript.render()` ← `buildPackageEnvironment()`), env của `ProcessBuilder`,
  và `contractHash`. Điều này xóa hẳn việc định nghĩa env 2–3 lần như hiện nay.
- `contractHash = sha256(nội dung contract không tính generation/password)`.
  Server ghi hash vào `$OC_HOME/.server-contract` lúc spawn. **Adopt rule mới**:
  pid file + `/proc` scan + `service.json` + `GET /api/info` 200 + hash khớp + password
  chấp nhận. Thay cho danh sách `expected` dài trong `serverEnvironmentCurrent()`.
- App update/gói update làm hash đổi → restart server **một lần**, có chủ đích, và UI
  biết qua `generation` thay vì tự đoán.

### Tầng B — `RuntimeSupervisor`

Gộp trách nhiệm của `OpenCodeService`, heartbeat 60 s, `OpenCodeWatchdog` và các retry
của activity. Chỉ supervisor được spawn/kill server.

| State | Điều kiện | Hành vi |
| --- | --- | --- |
| `ABSENT` | chưa có contract hoặc runtime chưa xong | chờ bootstrap, không mở port |
| `PREPARING` | self-heal/maintenance đang chạy | giữ native panel bước "Môi trường" |
| `STARTING` | đã spawn, `/api/info` chưa 200 | poll health mỗi 500 ms → 1 s |
| `HEALTHY(g)` | `/api/info` 200 + version khớp | publish `generation=g`, poll mỗi 15 s |
| `DEGRADED(g)` | health fail nhưng pid còn sống | publish ngay; giữ panel; backoff 5 s → 300 s |
| `RESTARTING(g+1)` | pid chết / hash lệch / explicit Restart | kill process group, đợi port rảnh, spawn lại |
| `STOPPED` | user bấm Stop | cờ `stoppedByUser` là state, watchdog/activity đều tôn trọng |

- `RuntimeStateHolder` là singleton trong app process: `{generation, state, health, pid,
  contractHash, reason}`. Activity, notification, WebShell cùng đọc; không còn việc
  activity tự gọi `startServerService()` trong `updateProgress()`.
- Activity re-pair khi **state** báo generation mới, không phải khi tự poll đồng hồ 3 s.
- `opencode service stop` từ terminal (SIGTERM/143) → supervisor chuyển `STOPPED`, xóa
  `service.json`, hủy watchdog — giữ nguyên hành vi hiện tại.

### Tầng C — `SessionGateway`

Một nơi duy nhất lo auth và danh tính phiên:

```kotlin
data class SessionRef(val generation: Int, val id: String?, val title: String)
class SessionGateway {
    fun attach(generation: Int): Credential      // pair → redeem → verify /api/info
    fun current(): SessionRef                    // route từ tracker, fallback lastSessionId
    fun onGenerationChanged(g: Int): SessionRef  // giữ id, bỏ token/cookie
}
```

- Cookie `opencode_session_<port>` và query `?auth_token=…` được set từ **một** hàm;
  không còn chỗ nào khác sửa cookie ngoài Gateway (hiện có ở `loadUi`,
  `reconnectToRestartedServer`, `hardRefreshWebUi`, `onNewWindow`).
- `SessionRef` là nguồn duy nhất cho: `onRenderProcessGone`, notification content intent,
  rotation, reconnect. Hiện tại logic "lấy id từ URL hoặc prefs" bị copy ở 4 chỗ.
- Pair thất bại → Gateway tự thử lại với backoff ngắn; **không** trả lỗi về WebView.
  Credential chỉ được đưa cho WebShell khi đã verify bằng `/api/info`.

### Tầng D — `WebShell`

Gộp `BOOT_SCRIPT`, `WATCHER_SCRIPT`, `LINK_SCRIPT` và theme registration thành một bridge
duy nhất, có version (`OpenCodeHostV1`) và nhận `generation`/`allowRetry` từ native, đọc
**đồng bộ từ bridge ngay tại document-start** (`HostBridge.contract()`) nên không bị mất
khi `loadUrl()` thay document. API tối thiểu:

```text
reportApi(ok | auth | http5xx | transport, generation)
reportRoute(sessionId)
reportFatal(kind)          // unhandledrejection / chunk load / event stream
```

Quy tắc:

- **Không retry vô hạn trong JS.** Giữ đúng 1 retry cho cold start (khi native đang
  `STARTING`); mọi quyết định còn lại thuộc supervisor. Điều này triệt tiêu cái
  `throw error` cuối cùng nuôi error boundary.
- Cài `window.onerror` + `unhandledrejection` ở document-start để *report*, không để
  stack trace tự render. Native luôn có panel phủ nếu state không `READY`.
- `UiState` trong ViewModel thay cho 5 Runnable: `BOOTING`, `WAITING_API`, `READY`,
  `RECONNECTING`, `RECOVERING`, `STOPPED`, `FAILED`. Activity chỉ `render(uiState)`.
- **Reveal rule** (tất cả phải đúng cùng lúc):
  `supervisor == HEALTHY(g)` ∧ `credential.generation == g` ∧ page có ≥1 API call 200
  ∧ route đã report. Thiếu một điều kiện → giữ panel native.
- Khi supervisor chuyển `DEGRADED → HEALTHY`, WebShell tự gọi nudge
  (`visibilitychange`/`focus`/`online`/`pageshow`) thay vì chỉ làm ở `onResume`.

---

## 4. Tiêu chí chấp nhận theo tình huống

| Tình huống | Hiện tại | Sau thiết kế |
| --- | --- | --- |
| Server restart đúng lúc page fetch `/api/session` | JS retry 6 lần rồi `throw` → stack | state `RESTARTING` publish ngay, JS dừng, panel phủ, pair lại `g+1`, mở lại `SessionRef.id` |
| 401 do token server cũ | 250 ms stack trước khi pair lại | Gateway verify trước khi trao credential; không bao giờ đưa token cũ cho page |
| Cold start port mở nhưng DB chưa load | TCP probe → page load → `/api/session` fail | `STARTING` chưa reveal; đợi `/api/info` 200 |
| WebView cũ không có document-start | fallback probe rồi reveal, không wrapper | vẫn fallback, nhưng reveal dùng cùng Ready rule + native health |
| Lỗi `EventSource /api/event` / PTY WebSocket | error boundary tự vẽ | bridge report `reportFatal`; native giữ panel/đánh dấu DEGRADED |
| Renderer bị kill | 4 chỗ copy logic lấy session | `SessionRef` duy nhất, state `RECOVERING` → pair lại |
| Xoay máy lúc đang boot | n Runnable tự re-apply | state nằm trong holder/ViewModel, activity chỉ render |
| Đổi mạng Wi-Fi/4G/VPN | refresh `resolv.conf`, server cũ có thể chết | hash/env đổi → `RESTARTING` có kiểm soát, UI biết qua generation |
| Bấm Stop trong notification | cờ `stoppedByUser` rải ở 4 chỗ | `STOPPED` là state chính thức, mọi guard dừng theo state |

**Definition of done cho triệu chứng đã chọn:** trong các kịch bản restart/pair/rotation/renderer,
logcat không có `Transport: Failed to fetch` hiển thị trên màn hình, và không có stack trace
SolidJS nào hiện ra — kể cả trong vài frame đầu.

---

## 5. Lộ trình di trú (không big-bang)

| Giai đoạn | Việc làm | Loại bỏ được |
| --- | --- | --- |
| **P0 — nền tảng** | `RuntimeEnv` data class → render env script + ProcessBuilder + hash; health `/api/info` trong supervisor; watchdog/heartbeat gọi `ensureHealthy()` | `serverEnvironmentCurrent()` list, `serverEnvironmentStamp()` thủ công, TCP-only readiness |
| **P1 — state** | `RuntimeStateHolder` + `SessionGateway`; gom `poll/bootTimeout/transportWatchdog/generationWatch/readinessFallback` thành 1 vòng | 5 Runnable chồng chéo, 4 chỗ lấy sessionId |
| **P2 — WebShell** | 1 bridge versioned thay 3 script; native truyền `generation`/`deadline`; bỏ JS retry vô hạn | `BOOT_SCRIPT` retry logic, khả năng `throw` về error boundary |
| **P3 — tuỳ chọn** | expose contract cho `oc-run`/CLI; document hoá schema | — |

Mỗi giai đoạn giữ nguyên giao diện native (boot card 3 bước) nên có thể phát hành riêng.

---

## 6. Kiểm chứng

**Unit/JVM**

- `RuntimeEnv` render identical script khi chạy 2 lần; hash đổi khi sửa 1 biến; output shell
  qua `sh -n`.
- State machine: mọi transition hợp lệ; `generation` tăng đúng 1 mỗi restart; `STOPPED`
  thắng mọi `ensureHealthy()`.
- `SessionRef.onGenerationChanged()` giữ id, xoá token.

**Device smoke (bắt buộc trước khi coi là xong)**

1. Mở web session, từ terminal chạy `opencode service restart`, quan sát giao diện.
2. Bấm Restart trên notification trong lúc agent đang chạy.
3. Vuốt app khỏi recents rồi mở lại (server adopt).
4. Xoay máy đúng lúc boot; khoá/mở màn hình; đổi Wi-Fi ↔ 4G.
5. Giết renderer WebView (`onRenderProcessGone`) rồi quay lại session.
6. Cài APK cũ hơn → chạy bản mới để ép contract hash đổi.

Tiêu chí cho từng ca: session mở lại đúng `ses_…`, không stack trace, không cần reload tay.
Logcat theo dõi: `OpenCodeRuntime` (supervisor), `OpenCodeGateway`, `OpenCodeWebShell`.
Giữ nguyên chính sách README: **không** tạo file log/diagnostics mới.

---

## 7. Rủi ro và giới hạn

- Không sửa được error boundary của upstream. Ta chỉ đảm bảo page **không bao giờ được
  reveal** trong trạng thái lỗi; đây là điểm mấu chốt.
- document-start script chạy trước bundle, nhưng WebView cũ không hỗ trợ → phải giữ fallback
  đến khi chắc chắn feature có mặt.
- Đổi contract hash khi update gây restart server 1 lần (cùng hành vi với logic current stamp
  hiện nay); cần giữ panel che trong lúc restart để không perceptible blink.
- Một số route API là experimental (`/api/event`, PTY); hợp đồng phải tolerate response shape
  mới, không parse cứng ngoài `GET /api/info`.
- Không đụng vào `service.json`/DB khi đang chạy — đúng khuyến cáo troubleshooting upstream.
- Không lưu password vào `runtime.json`; nếu cần debug, chỉ log *hash* của credential.

## 8. Quyết định cần duyệt trước khi triển khai

1. Chấp nhận restart server 1 lần khi `contractHash` đổi? (đề xuất: **có**)
2. Giữ 1 retry cold-start trong JS hay bỏ hẳn? (đề xuất: **giữ 1**, deadline do native cấp)
3. Làm P0+P1 cùng một đợt hay phát hành riêng từng giai đoạn? (đề xuất: **P0 trước**)

---

## 9. Trạng thái triển khai (working tree, chưa build/test)

Quyết định ở mục 8 đã được duyệt theo đề xuất: **có** restart 1 lần khi hash đổi, **giữ 1**
retry cold-start (native cấp `allowRetry`), làm **P0+P1+P2** cùng một đợt.

### Đã hiện thực

- **Tầng A — `RuntimeContract.kt` / `RuntimeEnv` (mới):** `RuntimeInputs` → một `env` map →
  ba renderer `spawnEnvironment()` / `shellExports()` / `toJson()` + `contractHash()` (sha256).
  Secret (`OPENCODE_SERVER_PASSWORD`, `GH_TOKEN`) chỉ được chèn lúc spawn, không bao giờ nằm
  trong `runtime.json` hay script.
- **Tầng A/P3 — `RuntimeShellScript.kt` + `OpenCodeBootstrap.runtimeInputs()`:** adapter Android
  duy nhất phân giải path; service build contract từ đó, `buildPackageEnvironment()` render script
  từ `shellExports()`. Script chỉ giữ hành vi thuần shell (wrapper `pkg`/curl/Android, `unset
  LD_PRELOAD`, cache npm/pip, PERL5LIB/RUBYLIB), không còn bản sao `export` nào của `env`.
  Trong `OpenCodeService`:
  - `buildContract()` là adapter Android duy nhất (đọc bootstrap, stamp APK/packages/native);
  - `writeRuntimeContract()` ghi `$OC_HOME/runtime.json` + `.server-contract` (hash) lúc spawn;
  - `serverEnvironmentCurrent()` giờ chỉ so `readHash(.server-contract) == contractHash()`,
    thay cho danh sách `expected` cũ; adopt vẫn bắt buộc `/api/info` 200 + password đúng.
- **Tầng B — `RuntimeState.kt` + service/watchdog:** `RuntimeStateMachine`/`RuntimeStateHolder`
  là nguồn chân lý. Service phát `BOOTSTRAP_STARTED`/`RUNTIME_READY`/`START_REQUESTED`/
  `STOP_REQUESTED`, `STARTING` lúc spawn, generation **tăng đúng 1** trong `markServerRunning()`,
  và `confirmHealthy()` chuyển `HEALTHY` chỉ sau `/api/info` 200. `checkServerAlive()` và
  `OpenCodeService.ensureHealthy()` (watchdog dùng) đều gọi `/api/info`, không còn TCP-only.
- **Tầng C — `SessionGateway.kt` (mới):** pair/redeem/verify/cookie/URL chỉ ở một class.
  `SessionRef` giữ `id`, xoá `token` khi đổi generation. Activity không còn tự sửa cookie hay
  đọc `serverGeneration()` theo đồng hồ.
- **Tầng D — `OpenCodeWebViewModel.kt` / `OpenCodeWebActivity.kt`:** gộp thành một
  `WEB_SHELL_SCRIPT` + một bridge `OpenCodeHostV1`; bỏ `throw error`, tối đa 1 retry GET/HEAD
  khi `allowRetry`, lọc `AbortError`/`TimeoutError`, report `reportFatal` cho chunk/event/PTY.
  Reveal rule trong `revealAllowed()` = `HEALTHY(g)` ∧ credential/cùng generation ∧ API 200
  (`webReady`) ∧ route đã report (`routeReported`). Boot card native 3 bước giữ nguyên.

### Kiểm chứng đã thêm

- `RuntimeStateMachineTest.kt` — transition, generation +1, `SessionRef` giữ id/bỏ token.
- `RuntimeContractTest.kt` — render lặp lại giống nhau, hash đổi khi sửa 1 biến, secret không
  xuất hiện trong `toJson()`/`shellExports()` nhưng có trong `spawnEnvironment()`, shell-quote,
  launch command; script sinh ra có đúng một `export` cho mỗi biến của contract, chỉ chứa helper
  thuần shell, và export cả path tuỳ chọn (JDK/SDK/OpenSSL/Git).

### Chưa làm được trong môi trường này

- Không chạy được Gradle/JUnit (không có SDK/JDK/Gradle cache; shell từ chối lệnh). Cần chạy
  trên máy có Android SDK:
  `./gradlew :app:testDebugUnitTest --no-daemon --stacktrace`, sau đó
  `./gradlew :app:assembleDebug --no-daemon --stacktrace`.
- Chưa chạy device smoke (mục 6) — tiêu chí "không stack trace, kể cả vài frame đầu" chỉ coi là
  xong sau khi chạy đủ 6 kịch bản trên thiết bị.

### P3 — đã xong

`buildPackageEnvironment()` không còn tự dựng env: nó gọi
`RuntimeShellScript.render(RuntimeEnv.build(OpenCodeBootstrap.runtimeInputs()))`, trong đó phần
`export` là `RuntimeContract.shellExports()`. Mọi path Android được phân giải một lần ở
`runtimeInputs()` và dùng chung cho server lẫn shell; xoá được bản sao thứ hai. `writePackageEnvironment()`
ghi `gitconfig` trước khi render script để `GIT_CONFIG_SYSTEM` trong contract khớp với trạng thái
mà shell sẽ thấy. Script chuyển sang "package environment v5", nên app update sẽ đổi hash và
restart server đúng một lần theo contract.
