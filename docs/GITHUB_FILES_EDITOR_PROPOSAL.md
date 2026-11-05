# Đề xuất: đồng nhất GitHub với Quản lý tệp và tiến tới sửa tệp trong app

Tài liệu này nối tiếp `docs/UI_REDESIGN_PROPOSAL.md`. Phần UI nền (token, toolbar, Files,
directory picker) đã triển khai; tài liệu này tập trung vào ba việc còn lệch:

1. Trang đăng nhập GitHub.
2. Quản lý repository GitHub.
3. Đường đi tới một trình sửa tệp native, dùng chung Git/commit/push với Files.

Mục tiêu cuối: người dùng không phải rời app để sửa một tệp text, và mọi thứ liên quan tới
Git/GitHub nằm trong cùng một mô hình điều hướng với Quản lý tệp.

> **Trạng thái:** Phase 1–5 đã được triển khai trong `app/src/main` (xem mục 8). Phần mô tả
> "hiện trạng" ở mục 1 và 2 là ảnh chụp trước khi triển khai, giữ lại để đối chiếu; các tệp
> `sheet_github.xml`/`sheet_git.xml` được nhắc ở đó đã bị xoá. Kiểm tra tĩnh đã chạy; build,
> lint và kiểm tra trên thiết bị (screenshot, TalkBack, font scale) chưa chạy được trong môi
> trường này.
>
> **Bước tiếp theo:** đề xuất nâng editor native hiện tại thành code editor có tô màu cú pháp
> (CodeMirror 6 trong WebView, fallback native) nằm ở
> [`EDITOR_CODE_HIGHLIGHTING_PROPOSAL.md`](EDITOR_CODE_HIGHLIGHTING_PROPOSAL.md). Phần nền của
> đề xuất đó đã được triển khai, gồm recovery snapshot, hạ cấp trang trí cho tệp lớn, xem trước
> Markdown offline, kiểm tra cú pháp nhẹ JSON/XML và gợi ý chế độ đơn giản cho TalkBack; build,
> unit test và QA thiết bị vẫn chưa chạy.

---

## 1. Hiện trạng đã đọc

### 1.1. Các thành phần liên quan

| Thành phần | Vai trò hiện tại |
| --- | --- |
| `FilesScreen.kt` | Màn hình overlay toàn màn hình; `MaterialToolbar`, breadcrumb, shortcut volume, danh sách `RecyclerView`, action sheet, clipboard, xoá/sửa tên. Mở tệp bằng `ACTION_VIEW` ra ứng dụng ngoài. |
| `activity_main.xml` (`files_panel`) | Shell của Files: toolbar, breadcrumb, shortcut, banner Git, summary, list, empty state. |
| `item_file.xml` | Row 72dp: icon 40dp trong tonal container, tên, metadata, overflow 48dp. |
| `sheet_file_actions.xml` | Action sheet của một mục: mở, mở dự án, đổi tên, sao chép, di chuyển, chia sẻ, thuộc tính, xoá. |
| `sheet_github.xml` | Một bottom sheet duy nhất chứa: cài git/gh, đăng nhập device flow, mã xác minh, clone URL, danh sách repo. Mở từ icon GitHub trên toolbar Files. |
| `sheet_git.xml` | Bottom sheet commit/push của repo chứa thư mục đang mở. |
| `OpenCodeGitHub.kt` | Device flow, kiểm tra đăng nhập, `gh repo list`, clone, `git status`, commit local, push. |
| `OpenCodeWebActivity.kt` | Giữ toàn bộ state GitHub (`github*`), render sheet bằng cách ẩn/hiện view, chạy tác vụ nền. |
| `OpenCodeSettings.kt` | Nhớ thư mục Files cuối, bật/tắt tự động commit sau khi sửa tệp. |
| `OpenCodeWebTheme.kt` | Inject token M3 vào Web UI; không liên quan trực tiếp nhưng là nguồn màu chung. |

### 1.2. Luồng hiện tại

```
Files toolbar
  └─ icon GitHub ──► BottomSheet sheet_github
                       ├─ chưa có git/gh  → nút Cài đặt + log cài
                       ├─ chưa đăng nhập → nút Đăng nhập + mã thiết bị + clone URL
                       └─ đã đăng nhập   → tài khoản + clone URL + danh sách repo (TextView)
                                            └─ chạm repo → điền URL vào ô clone
                                                 └─ Clone → luôn vào ~/HOME
                                                      └─ mở Files tại thư mục vừa clone

Files (trong repo)
  └─ banner Git ──► sheet_git
                      ├─ danh sách file thay đổi (TextView, tối đa 8 dòng)
                      ├─ commit message
                      ├─ Commit (local)
                      └─ Push (khi ahead)
```

Mở tệp text hiện tại:

```
Files → openFile() → Intent.ACTION_VIEW → app ngoài
     → quay lại app → onActivityResumed()
     → nếu bật "Tự động commit khi sửa file" → commit toàn bộ thay đổi
```

### 1.3. Điểm mạnh nên giữ

- Device flow đã tự viết, không phụ thuộc TTY của `gh`; token được đưa vào `gh auth login
  --with-token`.
- Clone/push đã xử lý CA bundle, token qua libcurl, credential helper không dùng được trong
  sandbox.
- Git status/commit/push đã tách luồng, commit local không tự push.
- Files đã có `RecyclerView`, `ListAdapter`, action sheet, breadcrumb, directory picker và
  empty/error state đúng hướng Material 3.
- Tự động commit sau khi sửa tệp đã có setting; chỉ thiếu trải nghiệm sửa trong app.

---

## 2. Vấn đề chính

| Khu vực | Hiện trạng | Hệ quả |
| --- | --- | --- |
| Điều hướng | GitHub là bottom sheet, không phải một destination; chỉ mở được từ icon trên toolbar Files. | Người dùng không biết repo nằm ở đâu, không quay lại được bằng back theo cùng quy tắc với Files/Settings. |
| Đồng nhất hình thức | Repo là `TextView` ghép chuỗi trong `LinearLayout`; row Files là `RecyclerView` + icon + metadata + overflow. | Hai màn hình cùng nói về "mục có thể mở" nhưng nhìn và thao tác khác nhau. |
| Form ẩn/hiện | `applyGithubState()` bật/tắt từng view trong một cột dài. | Layout nhảy khi cài xong, đăng nhập xong hoặc lỗi; trạng thái không rõ đâu là bước hiện tại. |
| Đăng nhập | Mã thiết bị hiện sau khi gọi mạng, không có đếm ngược, không có huỷ, lỗi thay bằng một dòng text. | Người dùng không biết mã còn hiệu lực bao lâu; đóng sheet vẫn có thể còn polling nền; lỗi khó thử lại. |
| Clone | Luôn clone vào `~/HOME`; không chọn đích; tên thư mục suy ra từ URL. | Repo dự án thật thường nằm trên bộ nhớ chia sẻ hoặc thẻ SD; trùng tên là clone thất bại. |
| Danh sách repo | `gh repo list` chỉ lấy `nameWithOwner`, `description`, `isPrivate`, `url`. | Không có ngày cập nhật, ngôn ngữ, trạng thái archive/fork, không biết repo đã clone trên máy chưa. |
| Hành động repo | Chạm repo chỉ điền URL; muốn clone phải cuộn xuống bấm Clone. | Không có "repo detail"; không có "mở trên GitHub", "sao chép URL", "mở thư mục local". |
| Git và GitHub | Hai sheet rời, hai mô hình trạng thái; banner Git không cho biết repo đã kết nối GitHub hay chưa. | Người dùng phải nhớ hai nơi; lỗi push chỉ báo "chưa đăng nhập". |
| Chuỗi điều hướng sai | `opencode_files_git_not_logged_in` vẫn ghi "Mở Cài đặt → GitHub", nhưng GitHub đã được chuyển khỏi Settings. | Chỉ dẫn không còn đúng, làm tăng cảm giác hai luồng không đồng bộ. |
| Sửa tệp | Chỉ mở bằng app ngoài; auto-commit khi quay lại, không có màn hình soạn thảo, không có dirty state, không kiểm tra file đã đổi trên đĩa. | Muốn sửa nhanh một dòng vẫn phải rời app; dễ commit nhầm thay đổi ngoài ý muốn. |
| Phản hồi | Log clone là `TextView` monospace 12 dòng cuối; lỗi cài đặt có thể đổ nguyên output. | Khó đọc trên màn hình nhỏ, không có action "Thử lại"/"Sao chép lỗi". |

### 2.1. Một lỗi cụ thể cần sửa ngay

`opencode_files_git_not_logged_in`:

> "Chưa đăng nhập GitHub. Mở Cài đặt → GitHub để đăng nhập."

Trong khi `activity_main.xml` không còn mục GitHub và entry duy nhất là icon trên Files.
Đề xuất đổi thành:

> "Chưa đăng nhập GitHub. Mở GitHub trong Quản lý tệp để đăng nhập."

và biến Snackbar thành action mở thẳng màn hình GitHub.

---

## 3. Nguyên tắc thiết kế

1. **Một ngôn ngữ điều hướng.** Mọi màn hình lớn là destination toàn màn hình trong overlay,
   có toolbar + back; bottom sheet chỉ dùng cho lựa chọn ngắn (directory picker, action sheet).
2. **Cùng một grammar cho row.** 40dp icon trong tonal container, title, metadata 1–2 dòng,
   trailing chip/overflow, chiều cao tối thiểu 72dp, touch target 48dp.
3. **State machine thay vì ẩn/hiện form.** Mỗi màn hình có các state rõ ràng
   (`Loading`, `Empty`, `Content`, `Error`, `SignedOut`, `Waiting`, `SignedIn`); mỗi state render
   một lần, không giữ các nhánh form chồng lên nhau.
4. **Local-first.** Không sửa trực tiếp nội dung repo qua GitHub API ở giai đoạn này. Mọi chỉnh
   sửa đi qua bản clone local, rồi commit/push bằng git đã có. Như vậy tái dùng toàn bộ logic
   token/CA/status và tránh trạng thái nửa vời giữa local và remote.
5. **Hành động rõ cấp bậc.** Một primary action cho mỗi state; hành động phá huỷ (đăng xuất, xoá,
   discard) có xác nhận; phản hồi ngắn bằng Snackbar có action.
6. **Không thêm dependency nặng.** Ưu tiên view/RecyclerView/EditText thuần. Syntax highlight và
   diff chỉ thêm sau nếu thật sự cần.

---

## 4. Kiến trúc thông tin mới

```
App shell
├─ WebView (giữ nguyên, không reload)
├─ Files (nguồn: Thiết bị)
│   ├─ File viewer / editor          ← mới
│   ├─ Thay đổi Git (commit/push)    ← thay sheet_git
│   └─ Nguồn: GitHub                 ← mới, thay sheet_github
│        ├─ Đăng nhập / tài khoản
│        ├─ Danh sách repository
│        └─ Repo detail → clone vào thư mục đã chọn
├─ Settings
└─ Boot / reconnect
```

### 4.1. "Nguồn" trong Files

Thêm một source bar ngay dưới breadcrumb:

```
┌─────────────────────────────────────────┐
│ ←  Tệp                                  │
│ ~ / storage / shared / my-project        │
│ [ Thiết bị ]  [ GitHub ]                 │
│ [HOME] [Bộ nhớ trong] [Thẻ SD]           │
└─────────────────────────────────────────┘
```

- `Thiết bị` là danh sách hiện tại.
- `GitHub` mở destination GitHub, dùng cùng toolbar và cùng row grammar.
- Trên GitHub, back quay về đúng thư mục local đang mở.
- Không thêm GitHub vào Settings để tránh hai entry cho cùng một việc. Nếu vẫn giữ shortcut
  trong Settings, nó phải mở đúng destination này, không mở một UI khác.

Lý do không trộn repo vào cùng danh sách file local: repo là đối tượng remote, có vòng đời
mạng, phân trang, tìm kiếm và clone; trộn sẽ làm hỏng các thao tác file-system hiện có
(sao chép, di chuyển, xoá hàng loạt). "Cùng shell, cùng row, khác nguồn" là mức đồng nhất
an toàn và dễ hiểu nhất.

### 4.2. State machine

```
GitHubScreen
├─ Checking
├─ Prerequisite      (chưa có git/gh)
├─ SignedOut
│   ├─ Idle
│   ├─ RequestingCode
│   ├─ Waiting(code, expiresAt)
│   └─ Error(retryable)
├─ SignedIn
│   ├─ RepoLoading / RepoEmpty / RepoContent / RepoError
│   └─ RepoDetail(repo, localPath?)
└─ Busy(operation, progress)
```

```
EditorScreen
├─ Loading
├─ ReadOnly(reason: binary | tooLarge | encoding | permission)
├─ Editing(clean)
├─ Editing(dirty)
├─ Saving
├─ Conflict(onDiskChanged)
└─ Error(message)
```

---

## 5. Đặc tả màn hình

### 5.1. GitHub — chưa có git/gh

```
┌──────────────────────────────────────────┐
│ ←  GitHub                                │
│                                          │
│  ┌ Yêu cầu ────────────────────────────┐ │
│  │ [git/gh]  Cần cài git & gh           │ │
│  │ Clone, commit và push cần hai công   │ │
│  │ cụ này trong môi trường của app.     │ │
│  │                                      │ │
│  │ [ Cài đặt git & gh ]                 │ │
│  │ ● Đang tải… 42%                      │ │
│  │ ▓▓▓▓▓▓▓░░░░░░░░░░░░                  │ │
│  └──────────────────────────────────────┘ │
│                                          │
│  Vì sao cần?                             │
│  App dùng git để quản lý phiên bản và gh │
│  để xác thực GitHub. Token được lưu      │
│  trong bộ nhớ riêng của app.             │
└──────────────────────────────────────────┘
```

- Chỉ một primary action: `Cài đặt git & gh`; khi đang cài, nút chuyển thành disabled và hiện
  progress determinate nếu `OpenCodePackages` báo phần trăm, indeterminate nếu chỉ có status.
- Log kỹ thuật nằm trong "Xem chi tiết" (monospace, 12sp), không phải nội dung chính.
- Lỗi: card error + "Thử lại" + "Sao chép lỗi". Không đổ nguyên output vào dòng status.
- Cài xong tự chuyển state `SignedOut` mà không làm layout nhảy: card được thay bằng account
  card ở cùng vị trí.

### 5.2. GitHub — chưa đăng nhập

```
┌──────────────────────────────────────────┐
│ ←  GitHub                                │
│                                          │
│  ┌ Đăng nhập GitHub ───────────────────┐ │
│  │ ● Chưa kết nối                       │ │
│  │ Đăng nhập để xem repo, clone và push.│ │
│  │                                      │ │
│  │ [ Đăng nhập GitHub ]                 │ │
│  └──────────────────────────────────────┘ │
│                                          │
│  Clone công khai                         │
│  Dán URL hoặc owner/repo                 │
│  [ https://github.com/owner/repo       ] │
│  [ Tải về máy ]                          │
└──────────────────────────────────────────┘
```

Sau khi bấm đăng nhập:

```
┌──────────────────────────────────────────┐
│ ←  GitHub                                │
│                                          │
│  ┌ Xác thực thiết bị ──────────────────┐ │
│  │ Bước 1  Sao chép mã                  │ │
│  │                                      │ │
│  │      ┌──────────────────────┐        │ │
│  │      │     ABCD-EFGH        │  [Sao chép] │
│  │      └──────────────────────┘        │ │
│  │                                      │ │
│  │ Bước 2  Mở GitHub và nhập mã         │ │
│  │ [ Mở github.com/login/device ]       │ │
│  │                                      │ │
│  │ ● Đang chờ xác thực…    còn 13:42    │ │
│  │                                      │ │
│  │ [ Huỷ ]                              │ │
│  └──────────────────────────────────────┘ │
│                                          │
│  Mã này không phải mật khẩu. Chỉ dùng    │
│  một lần cho phiên đăng nhập này.        │ │
└──────────────────────────────────────────┘
```

Hành vi:

- **Không tự lấy mã khi mở màn hình.** Người dùng bấm `Đăng nhập GitHub` mới gọi
  `requestDeviceCode()`; tránh mã hết hạn trong lúc người dùng đang đọc.
- Sau khi có mã: hiện mã, nút sao chép, nút mở liên kết, đếm ngược từ `expiresIn`.
- Polling chạy nền như hiện tại nhưng có cờ huỷ. `Huỷ` dừng polling và xoá mã khỏi màn hình.
- Rời màn hình (back) cũng huỷ, trừ khi người dùng chọn "Tiếp tục trong nền" (để phase sau).
- Hết hạn: card `Mã đã hết hạn` + `Lấy mã mới`, không giữ mã cũ.
- Lỗi mạng/quyền: card error + `Thử lại` + `Sao chép lỗi`.
- Thành công: chuyển sang `SignedIn`, Snackbar "Đã đăng nhập · octocat"; giữ hành vi restart
  service để export `GH_TOKEN`, nhưng hiển thị một progress row "Đang đồng bộ với OpenCode…"
  thay vì đổi status text.
- Không hiển thị mã bằng `MonoCode` 18sp trên nền input nếu mã dài; dùng chip monospace 20sp,
  letter-spacing vừa phải, selectable + nút copy.

### 5.3. GitHub — đã đăng nhập, danh sách repo

```
┌──────────────────────────────────────────┐
│ ←  GitHub                    [Tìm] [⋮]   │
│                                          │
│  ┌ Tài khoản ──────────────────────────┐ │
│  │ [@]  octocat          ● Đã kết nối   │ │
│  │ 128 repo · quyền đầy đủ              │ │
│  │ [Quản lý quyền]              [Đăng xuất] │
│  └──────────────────────────────────────┘ │
│                                          │
│  [ Tất cả ] [ Riêng tư ] [ Công khai ]   │
│                                          │
│  ┌ repo row ───────────────────────────┐ │
│  │ [repo]  owner/name        [Riêng tư] │ │
│  │ Mô tả ngắn của repository            │ │
│  │ Cập nhật 2 ngày trước · Kotlin       │ │
│  │                         [Tải về] [⋮] │ │
│  └──────────────────────────────────────┘ │
│  ┌ repo row ───────────────────────────┐ │
│  │ [repo]  owner/other      [Đã có máy] │ │
│  │ ...                                  │ │
│  └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

- Account card: monogram (chữ cái đầu của login) trong tonal container, không cần tải avatar.
  Nếu muốn avatar, thêm sau bằng `HttpURLConnection` + cache, không thêm thư viện ảnh.
- `Đã kết nối` là chip success cố định, không phụ thuộc Dynamic Color.
- `Quản lý quyền`: mở sheet giải thích các scope đang dùng (repo, workflow, packages, gist,
  user…). Nếu `needsScopeUpgrade()` thì chip warning "Cần cấp lại quyền" + CTA đăng nhập lại.
- Đăng xuất nằm trong overflow hoặc account card, có dialog xác nhận; sau đó quay lại
  `SignedOut`, xoá danh sách repo.
- Search/filter client-side trên dữ liệu đã tải; debounce 200ms. Server-side search để sau.
- Filter: Tất cả / Riêng tư / Công khai; sort: Mới cập nhật / Tên.
- Loading: 4–6 skeleton row cùng chiều cao 72dp, không chỉ một spinner giữa màn hình.
- Empty: icon repo + "Chưa có repository" + action `Làm mới`.
- Error: card lỗi có `Thử lại`, `Sao chép lỗi`; vẫn giữ account card.

Repo row dùng `RecyclerView` + `ListAdapter`/`DiffUtil`, `item_repo.xml` cùng cấu trúc
`item_file.xml`:

| Thành phần | Nội dung |
| --- | --- |
| Icon 40dp | `ic_git_repo` trong tonal container; archive/fork có badge nhỏ nếu cần |
| Title | `owner/name` (owner dùng `onSurfaceVariant`) |
| Meta | mô tả 1 dòng + "Cập nhật … · Ngôn ngữ" |
| Trailing | chip `Riêng tư`/`Đã có máy`, overflow |
| Chạm row | mở Repo detail, không điền URL |
| Overflow | Tải về máy, Mở trên GitHub, Sao chép URL |

`OpenCodeGitHub.listRepos()` mở rộng JSON:

```
nameWithOwner, description, isPrivate, url,
updatedAt, pushedAt, primaryLanguage, isFork, isArchived
```

`primaryLanguage` là object; lấy `.name`. `updatedAt`/`pushedAt` dùng để sắp xếp và hiển thị
"2 ngày trước". Không cần gọi thêm API cho mỗi row.

### 5.4. Repo detail

```
┌──────────────────────────────────────────┐
│ ←  owner/name                   [⋮]      │
│                                          │
│  [repo]  owner/name          [Riêng tư]  │
│  Mô tả đầy đủ của repository.            │
│  Cập nhật 2 ngày trước · Kotlin · main   │
│                                          │
│  [ Tải về máy ]   [ Mở trên GitHub ]     │
│                                          │
│  ┌ Trên máy ───────────────────────────┐ │
│  │ ~/storage/shared/projects/name       │ │
│  │ Nhánh main · 3 thay đổi · 1 chưa push│ │
│  │ [ Mở thư mục ]  [ Commit ]  [ Push ] │ │
│  └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

- Nếu chưa clone: primary `Tải về máy`.
- Nếu đã clone: primary `Mở thư mục`; card Git hiển thị trạng thái thật từ
  `OpenCodeGitHub.status()`.
- `Mở trên GitHub` dùng `Intent.ACTION_VIEW` tới `repo.url` (bỏ `.git`).
- Nếu repo đã archive: chip `Đã lưu trữ`, disable clone/push nếu GitHub từ chối; vẫn cho xem.
- Repo detail có thể là destination hoặc bottom sheet cao; khuyến nghị destination để cùng
  back stack với Files và đủ chỗ cho trạng thái Git.

### 5.5. Clone vào thư mục đã chọn

Thay vì luôn clone vào `~/HOME`:

1. `Tải về máy` mở `DirectoryPickerSheet` hiện có.
2. Thư mục mặc định: thư mục Files đang mở; nếu không có, `~/HOME`.
3. Hiển thị trước: đích đến, tên thư mục sẽ tạo, dung lượng trống.
4. Nếu tên đã tồn tại: cảnh báo và cho chọn tên khác; không để `git clone` fail với lỗi khó hiểu.
5. Khi clone:
   - Card tiến trình với `LinearProgressIndicator` (indeterminate vì git không báo %).
   - Log thu gọn "Xem chi tiết".
   - Nút `Huỷ` (destroy process) nếu `OpenCodeGitHub.clone` hỗ trợ callback/process handle.
6. Thành công:
   - Ghi nhớ mapping `repo → local path` (SharedPreferences JSON hoặc file trong `OC_HOME`).
   - Điều hướng Files local tới thư mục vừa clone.
   - Snackbar "Đã clone · Mở" và tuỳ chọn "Mở làm dự án".
7. Thất bại: giữ nguyên destination picker, hiện lỗi đã rút gọn + "Thử lại" + "Sao chép lỗi".

Kỹ thuật: đổi `OpenCodeGitHub.clone(repoUrl, onLine)` thành
`clone(repoUrl, destination: File, onLine)`; giữ overload cũ nếu muốn tương thích. Tên thư mục
lấy từ `nameWithOwner.substringAfterLast('/')`, không đoán từ URL; cho phép sửa trước khi clone.

### 5.6. Thay đổi Git (thay `sheet_git`)

```
┌──────────────────────────────────────────┐
│ ←  Thay đổi                              │
│  owner/name · nhánh main                 │
│  ● 3 tệp thay đổi · 1 commit chưa push   │
│                                          │
│  ┌ src/App.kt                 M    [›]  │
│  ├ README.md                  A    [›]  │
│  └ notes.txt                  ??   [›]  │
│                                          │
│  ┌ Commit ─────────────────────────────┐ │
│  │ Mô tả thay đổi                     │ │
│  │ [ Cập nhật từ OpenCode Android   ] │ │
│  │                                    │ │
│  │ Tự động commit khi lưu tệp   [on]  │ │
│  │ Chỉ tạo commit trên máy, không push│ │
│  │                                    │ │
│  │ [ Commit tất cả ]      [ Push ]    │ │
│  └────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

- Row thay đổi dùng `item_git_change.xml`: chip trạng thái (`M`, `A`, `D`, `??`, `R`), path
  (đậm), thư mục (muted), chạm để mở file trong editor/viewer.
- `RecyclerView` thay `TextView` 8 dòng; có "và N tệp khác" chỉ khi thực sự cần.
- `Commit tất cả` giữ hành vi `git add --all` + commit local như hiện tại. Staging chọn lọc là
  phase sau (không đổi UX hiện tại).
- `Push` chỉ hiện khi ahead > 0; nếu chưa đăng nhập, action mở thẳng GitHub screen.
- Busy: disable input/buttons, progress row; lỗi hiện banner trong card, không thay toàn bộ
  status bằng một câu dài.
- Sau commit: cập nhật banner Files + danh sách; Snackbar "Đã commit trên máy" + action `Push`.
- Sau push: Snackbar "Đã push lên GitHub"; refresh status.
- Banner Git ngoài Files thêm trạng thái kết nối:
  - Chưa đăng nhập: `● Chưa kết nối GitHub` + action `Kết nối`.
  - Đã đăng nhập: `● octocat` không cần chiếm chỗ; ưu tiên branch + changes.

### 5.7. Xem và sửa tệp trong app

#### Luồng mở tệp

```
Chạm file
├─ ảnh (png/jpg/webp/gif)        → ImageView toàn màn hình (zoom sau)
├─ text/code hợp lệ               → EditorScreen
├─ text quá lớn / encoding lạ     → Viewer readonly + "Mở bằng ứng dụng khác"
├─ binary/không hỗ trợ            → action sheet hiện có + "Mở bằng ứng dụng khác"
└─ thư mục                        → navigate như hiện tại
```

Giữ `Mở bằng ứng dụng khác` trong action sheet để không mất đường lui.

#### EditorScreen

```
┌──────────────────────────────────────────┐
│ ←  src/App.kt                    [Lưu]    │
│ ● Chưa lưu · main · trong repo           │
│                                          │
│  1  package com.example                  │
│  2                                       │
│  3  fun main() {                         │
│  4      println("Hello")                 │
│  5  }                                    │
│                                          │
│                                          │
│ UTF-8 · LF · Dòng 4, Cột 12 · 2.1 KB     │
└──────────────────────────────────────────┘
```

- Toolbar: back, tên tệp (ellipsize middle), action `Lưu`; overflow có `Lưu thành…` (phase sau),
  `Mở bằng ứng dụng khác`, `Thuộc tính`.
- Dòng trạng thái dưới toolbar: dirty indicator + tên nhánh nếu trong repo.
- Vùng soạn thảo: `EditText` monospace, `gravity=top|start`, `inputType=textMultiLine|
  textNoSuggestions`, không auto-cap, không gợi ý; nền `surface`, không card.
- Status bar cuối màn hình: encoding, newline, dòng/cột, dung lượng. Trên tablet có thể thêm
  cột line number (phase sau).
- Back khi dirty: dialog `Lưu thay đổi?` với `Lưu` / `Không lưu` / `Huỷ`. Không tự động lưu
  ngầm, trừ khi người dùng bật auto-save (phase sau).
- Xoay màn hình/background: giữ buffer trong controller của activity; nếu activity bị huỷ, ghi
  draft vào cache và hỏi khôi phục khi mở lại cùng tệp.
- IME: editor panel là con của root đã xử lý IME inset; vùng soạn thảo co lại, status bar nằm
  trên bàn phím, không bị che.

#### Lưu an toàn

1. Ghi ra file tạm cùng thư mục: `.<name>.tmp-<pid>`.
2. `fsync` (nếu dùng `FileOutputStream` + `FD.sync()`), giữ permission hiện có.
3. `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`; fallback move thường nếu filesystem
   không hỗ trợ atomic.
4. Nếu lỗi: giữ buffer, hiện lỗi, không xoá file gốc.
5. Sau khi lưu: cập nhật `lastModified`/hash, đánh dấu clean.

#### Phát hiện xung đột

Khi mở, lưu:

```
TextDocument(
  path, charset, newline, bom,
  lastModified, size, sha256,
  readOnlyReason
)
```

Trước khi lưu, đọc lại metadata + hash. Nếu khác:

- Dialog: "Tệp đã thay đổi trên đĩa" với `Tải lại` / `Ghi đè` / `Huỷ`.
- `Tải lại` bỏ buffer hiện tại (có xác nhận nếu dirty).
- `Ghi đè` mới ghi; mặc định focus `Huỷ` để tránh mất dữ liệu.

#### Giới hạn và an toàn

| Tình huống | Hành vi |
| --- | --- |
| Kích thước > 2 MiB | Read-only + gợi ý mở app ngoài |
| 1–2 MiB | Cho sửa nhưng cảnh báo hiệu năng |
| Có byte NUL trong 8 KiB đầu | Coi là binary, read-only |
| UTF-8 / UTF-8 BOM / UTF-16 | Hỗ trợ; hiển thị encoding ở status bar |
| Byte không decode được | Read-only + "Encoding không hỗ trợ" |
| CRLF / LF | Phát hiện và giữ nguyên khi lưu |
| Symlink | Sửa target sau khi resolve; cảnh báo nếu target ngoài vùng quen thuộc |
| Không có quyền ghi | Mở read-only, disable `Lưu` |
| Tệp đang được process khác dùng | Vẫn cho lưu; lỗi atomic move hiện dialog |

#### Tích hợp Git

- Nếu tệp nằm trong repo, sau khi lưu:
  - Nếu `OpenCodeSettings.gitAutoCommit()` bật: commit local với message
    `Cập nhật <tên tệp>` (dùng string hiện có), không push.
  - Snackbar: "Đã lưu · đã commit trên máy" + action `Push` (nếu ahead).
  - Nếu tắt auto-commit: Snackbar "Đã lưu" + action `Xem thay đổi` mở màn hình Git.
- `refreshGit()` cập nhật banner ngay; không cần đợi quay lại Files.
- Không push tự động trong mọi trường hợp.
- Nếu file ngoài repo: chỉ lưu, không hiện action Git.

#### Lựa chọn kỹ thuật editor

| Phương án | Ưu | Nhược | Khuyến nghị |
| --- | --- | --- | --- |
| A. `EditText` monospace thuần | Không dependency, IME tốt, nhẹ, dễ test; đủ để sửa code/text | Không syntax highlight, không line number mặc định | **MVP, chọn A** |
| B. WebView + CodeMirror bundle | Highlight, line number, search, folding | Thêm asset ~300–500KB, bridge phức tạp, IME/selection dễ lỗi trên Android | Phase sau, chỉ khi có nhu cầu thật |
| C. Giữ app ngoài | Không làm gì | Phải rời app, auto-commit mù, không kiểm soát conflict | Giữ làm fallback, không phải luồng chính |

Không thêm Gradle dependency cho editor. Nếu cần highlight, ưu tiên B với asset đóng gói sẵn
(không tải từ CDN) để app vẫn offline.

#### Undo/redo và tìm kiếm

- MVP: dựa vào undo của bàn phím/hệ điều hành; dialog back đã bảo vệ thay đổi.
- Phase sau: ring buffer snapshot (giới hạn 50, debounce ~1s) cho undo/redo trên toolbar;
  search trong file dùng `Editable` + highlight span, không cần thư viện.

---

## 6. Component và token dùng chung

| Component | Dùng cho | Ghi chú |
| --- | --- | --- |
| `MaterialToolbar` + subtitle | Files, GitHub, Repo detail, Git, Editor | Cùng `Widget.OpenCode.Toolbar` |
| `SourceBar` (chip Thiết bị/GitHub) | Files | `MaterialButton` outlined, cùng shortcut row |
| `InfoCard` (`Widget.OpenCode.Card`) | Account, prerequisite, repo detail, commit | Không elevation, shape 16dp |
| `StatusChip` | Đã kết nối, Riêng tư, Đã có máy, M, A | success/warning/neutral cố định; luôn có text |
| `ListRow` | File, repo, git change | 72dp, icon 40dp, trailing 48dp |
| `EmptyState` | Repo empty, Git clean, editor readonly | Icon + text + một action |
| `ErrorCard` | Cài đặt, login, repo list, clone, save | Có `Thử lại` + `Sao chép lỗi` |
| `ProgressRow` | Cài đặt, clone, commit, push, sync | `LinearProgressIndicator` + status 1 dòng |
| `Snackbar` | Mọi phản hồi ngắn | Action khi có thể (`Push`, `Mở`, `Thử lại`) |
| `BottomSheetDialog` | Directory picker, action sheet, quyền | Không dùng cho luồng nhiều bước |

Token đã có trong `dimens.xml`, `styles.xml`, `colors.xml`; không tạo bảng màu mới. Các role
`oc_success`, `oc_warning`, `oc_error` giữ cố định như hiện tại.

Quy tắc:

- Nội dung max width 640dp, canh giữa trên tablet; editor có thể full width nhưng status bar
  vẫn giới hạn 640dp.
- Touch target >= 48dp; row >= 72dp; body >= 14sp; metadata >= 12sp.
- Không dùng `bg_input` làm nền row; chỉ dùng cho input/log.
- Không dùng emoji; icon vector 24dp.
- Không đặt `textSize`/`fontFamily` trong layout mới; dùng `TextAppearance.OpenCode.*`.

---

## 7. Thay đổi kỹ thuật dự kiến

| File / vùng | Thay đổi |
| --- | --- |
| `activity_main.xml` | Thêm `github_panel` và `editor_panel` là overlay toàn màn hình, cùng cấp `files_panel`; thêm source bar trong `files_panel`. Giữ WebView attached. |
| `sheet_github.xml` | Xoá sau khi migrate; tách thành `github_panel` + các card state. |
| `sheet_git.xml` | Xoá; thay bằng `git_panel` + `item_git_change.xml`. |
| `item_repo.xml` (mới) | Row repo cùng grammar `item_file.xml`. |
| `item_git_change.xml` (mới) | Chip trạng thái + path + thư mục. |
| `GithubScreen.kt` (mới) | Controller + sealed state; dùng `backgroundExecutor`/`handler` hiện có; render theo state. |
| `GithubAdapter.kt` (mới) | `ListAdapter`/`DiffUtil` cho repo. |
| `GitChangesScreen.kt` (mới) | Danh sách thay đổi + commit/push; tái dùng logic trong `FilesScreen.showGitSheet()`. |
| `EditorScreen.kt` (mới) | Toolbar, EditText, status bar, dialog dirty/conflict, lưu. |
| `TextFile.kt` (mới) | `TextDocument`, đọc/ghi atomic, encoding/newline/hash, binary/large detection. |
| `OpenCodeGitHub.kt` | Thêm field repo (`updatedAt`, `pushedAt`, `primaryLanguage`, `isFork`, `isArchived`); `clone` nhận đích; mapping repo → local path; hàm `repoDetails()` nếu cần default branch; cờ huỷ login. |
| `OpenCodeWebActivity.kt` | Thay `openGithubSheet()` bằng `openGithub()`, thay state `github*` bằng controller; thêm `openEditor()`; back stack ưu tiên editor → git → github → files → settings. |
| `FilesScreen.kt` | `onOpenFile` chọn editor/viewer/external; thêm action `Nguồn`; banner Git mở `GitChangesScreen`; xoá `pendingExternalEdit` khỏi luồng chính (giữ cho fallback app ngoài). |
| `menu_files.xml` | Giữ icon GitHub nhưng đổi title "GitHub"; thêm item `Nguồn` nếu không dùng source bar. |
| `strings.xml` | Sửa `opencode_files_git_not_logged_in`; thêm string state/login/repo/editor; bỏ string không còn dùng sau migrate. |
| `OpenCodeSettings.kt` | Thêm lưu mapping clone → local path; có thể thêm `editorAutoSave` (phase sau). |
| Build | Không thêm dependency; giữ minSdk 26/targetSdk 28. |

### 7.1. Back stack

```
Editor (dirty?) ──back──► dialog Lưu/Không lưu/Huỷ
Editor ──back──► GitChanges (nếu mở từ đó) ──► Files
GitHub ──back──► Files (đúng thư mục cũ)
RepoDetail ──back──► GitHub list
Settings ──back──► Web UI
```

`OnBackPressedCallback` hiện có cần thêm nhánh cho editor/github/git trước `filesScreen`.
Không được để back thoát app khi editor còn dirty.

### 7.2. Lifecycle

- `GithubScreen` và `GitChangesScreen` dùng chung `backgroundExecutor`/`handler` của activity;
  kiểm tra `destroyed` trước khi post UI như code hiện tại.
- Login polling phải có `@Volatile cancelRequested` và kiểm tra trong vòng lặp; khi activity
  destroy, set cờ để thread dừng.
- Editor giữ buffer ở activity-scope controller; khi `onSaveInstanceState`, lưu path + dirty +
  selection (không lưu cả buffer lớn vào Bundle). Draft lớn ghi cache.
- `filesScreen.release()` hiện shutdown executor; editor dùng executor riêng hoặc chung, cần
  shutdown cùng lúc.

---

## 8. Lộ trình triển khai

### Phase 1 — Sửa điều hướng và vỏ màn hình (PR nhỏ, ít rủi ro) — ✅ Đã triển khai

- Thêm `github_panel` với toolbar/back, chuyển toàn bộ nội dung `sheet_github` vào destination.
- Repo list chuyển sang `RecyclerView` + `item_repo.xml`; row mở Repo detail thay vì điền URL.
- Clone dùng `DirectoryPickerSheet`, lưu mapping repo → path.
- Sửa string "Cài đặt → GitHub"; Snackbar lỗi push có action mở GitHub.
- Giữ nguyên toàn bộ logic `OpenCodeGitHub`; chỉ đổi presentation.

Đã triển khai: `GithubScreen.kt`, `panel_github.xml`, `item_repo.xml`; `sheet_github.xml` và
`sheet_git.xml` đã xoá; repo list dùng `ListAdapter`/`DiffUtil`; clone và chọn thư mục local
dùng chung `DirectoryPickerSheet`; mapping `owner/repo → path` nằm trong `OpenCodeSettings`.
Entry GitHub chỉ còn trong Files. Snackbar lỗi push ở cả Files và Git changes có action
`Kết nối` mở thẳng GitHub.

**Nghiệm thu:** mở GitHub từ Files, back về đúng thư mục; không còn bottom sheet; repo list
cuộn mượt với 100 repo; clone được vào thư mục chọn; không reload WebView.

### Phase 2 — Đăng nhập và tài khoản — ✅ Đã triển khai

- State machine `Checking/Prerequisite/SignedOut/Waiting/Error/SignedIn`.
- Không tự gọi mạng khi mở; đếm ngược `expiresIn`; huỷ polling; lấy mã mới khi hết hạn.
- Account card, scope card, logout confirm.
- Search/filter/sort repo; loading skeleton; error card.
- Refresh repo list; hiển thị "Đã có máy" từ mapping.

Đã triển khai: state machine `CHECKING/PREREQUISITE/SIGNED_OUT/WAITING/SIGNED_IN` (lỗi được
render trong state hiện tại thay vì state riêng); login có `beginLogin`/`cancelLogin` theo
session để callback cũ không thắng race; `Lấy mã mới` huỷ session cũ rồi xin mã mới; account
card, scope card, logout confirm, search/filter/sort, skeleton và error card đều có.

**Nghiệm thu:** login không nhảy layout; huỷ giữa chừng dừng polling; mã hết hạn có CTA mới;
lỗi mạng có thử lại; đăng xuất quay lại đúng state.

### Phase 3 — Git changes dùng chung — ✅ Đã triển khai

- Thay `sheet_git` bằng `git_panel`; row thay đổi; commit/push giữ nguyên hành vi.
- Banner Git hiển thị trạng thái kết nối GitHub; action mở GitHub khi chưa login.
- Tái dùng component trạng thái/thông báo của GitHub.

Đã triển khai: `GitChangesScreen.kt` + `panel_git.xml` + `item_git_change.xml`; banner trong
Files mở màn hình này; commit local mặc định stage tất cả, có toggle `Chỉ commit tệp đã stage`,
stage/unstage từng row và xem diff. Push vẫn là hành động riêng.

**Nghiệm thu:** commit local không push; push chỉ khi bấm; danh sách thay đổi cập nhật sau
commit/push; lỗi có chi tiết rút gọn + sao chép.

### Phase 4 — Editor MVP — ✅ Đã triển khai

- `TextFile` + `EditorScreen`; mở text trong app; lưu atomic; dirty dialog; conflict dialog.
- Giới hạn kích thước/binary/encoding; status bar.
- Auto-commit sau lưu theo setting; Snackbar có `Thay đổi` mở Git changes (push vẫn là hành
  động riêng).
- Giữ "Mở bằng ứng dụng khác" và auto-commit khi quay lại từ app ngoài.

Đã triển khai: `TextFile.kt` (UTF-8/BOM/UTF-16, giữ newline, ghi temp + fsync + atomic move,
so hash SHA-256 trước khi ghi) và `EditorScreen.kt` (dirty/conflict dialog, read-only cho
binary/lớn/encoding lạ/không có quyền, auto-commit local sau lưu, reload + auto-commit khi quay
lại từ app ngoài; content URI cấp quyền ghi khi mở từ editor). Snackbar mở thẳng màn hình Git
changes.

**Nghiệm thu:** sửa một file trong repo, lưu, thấy banner Git đổi; push được; tắt auto-commit
thì chỉ lưu; file ngoài repo không hiện action Git; không mất dữ liệu khi xoay màn hình.

### Phase 5 — Hoàn thiện — 🟡 Đã triển khai phần mã; kiểm tra thiết bị chưa chạy

- Image preview trong app; line number; undo/redo; search trong file.
- Diff view (nếu cần), staging chọn lọc, clone huỷ được giữa chừng.
- Tablet two-pane: repo list + detail; editor full width có gutter. Git changes giữ một cột vì
  commit card luôn nằm dưới danh sách.
- Screenshot test cho các state; kiểm tra dark/light/dynamic, font scale 200%, TalkBack.

Đã triển khai: `ImagePreviewScreen` (sample ảnh theo kích thước màn hình), gutter + undo/redo +
search trong `EditorScreen`, diff dialog, stage/unstage từng tệp, `CloneHandle` huỷ clone, và
GitHub two-pane khi `screenWidthDp >= 720` (Git changes giữ một cột). Editor còn có recovery
snapshot, xem trước Markdown, kiểm tra cú pháp nhẹ và mục "Trình sửa" trong Cài đặt. Chưa chạy
được screenshot test, dark/light/dynamic, font scale 200% và TalkBack vì môi trường hiện tại không
có Java/Gradle/thiết bị.

---

## 9. Rủi ro và câu hỏi cần chốt

1. **Editor native hay WebView?** MVP đã chọn A (`EditText`). Bước tiếp theo chọn hybrid:
   CodeMirror 6 trong WebView làm renderer chính, `EditText` làm fallback; xem
   [`EDITOR_CODE_HIGHLIGHTING_PROPOSAL.md`](EDITOR_CODE_HIGHLIGHTING_PROPOSAL.md).
2. **Có giữ entry GitHub trong Settings không?** Đề xuất không, để một entry duy nhất trong
   Files. Nếu giữ, nó phải mở cùng `GithubScreen`.
3. **Token và scope.** Bộ scope hiện tại rất rộng (`delete_repo`, `admin:org`, `workflow`…).
   Không đổi trong phase này để tránh ép đăng nhập lại; nhưng nên có card giải thích và cân
   nhắc giảm scope ở bản sau.
4. **Sửa trực tiếp trên GitHub.** Đề xuất không làm ở giai đoạn này; local-first rồi push.
   Nếu cần sửa nhanh một file remote, dùng "Mở trên GitHub" trước.
5. **Auto-save.** Đề xuất không bật mặc định; chỉ explicit save + dialog khi back. Auto-save có
   thể là setting riêng sau.
6. **Repo đã clone nhưng đổi tên/di chuyển.** Mapping có thể cũ; cần fallback quét `.git/config`
   hoặc cho người dùng "Chọn thư mục local" trong Repo detail.
7. **File ngoài vùng app quản lý.** Editor chỉ nên mở file đọc/ghi được; với SAF/URI không có
   đường dẫn thật, giữ external app.
8. **Undo/redo.** Nếu người dùng yêu cầu ngay, thêm ring buffer ở Phase 4 thay vì đợi Phase 5.

---

## 10. Tiêu chí nghiệm thu tổng

- GitHub là destination toàn màn hình, cùng toolbar/back/row với Files; không còn
  `sheet_github.xml`.
- Danh sách repo dùng `RecyclerView` + `ListAdapter`; không còn `TextView` ghép chuỗi hay
  `LinearLayout` chứa 100 row.
- Login có mã, nút sao chép/mở liên kết, đếm ngược, huỷ, thử lại; không nhảy layout giữa các
  state.
- Clone chọn được thư mục đích; báo dung lượng/tên/trùng tên trước khi chạy.
- Không còn string/UI chỉ sai "Cài đặt → GitHub".
- Git changes là màn hình riêng, dùng cùng row/status component; commit local và push tách
  bạch như hiện tại.
- Mở tệp text mở editor trong app; lưu atomic; có dirty dialog, conflict dialog, read-only cho
  binary/large/encoding lạ; auto-commit theo setting và không tự push.
- Không thêm dependency nặng; `minSdk 26`/`targetSdk 28` giữ nguyên; build và unit test hiện có
  vẫn chạy.
- Dark/light/dynamic, tablet 640dp, font scale 200%, TalkBack: row wrap, không cắt chữ, touch
  target >= 48dp.
- WebView không reload khi mở/đóng GitHub, Git changes hay Editor.
