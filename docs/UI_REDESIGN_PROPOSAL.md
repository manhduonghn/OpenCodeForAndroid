# Đề xuất thiết kế lại giao diện OpenCode for Android

Tài liệu đề xuất cho giao diện native của app. Mục tiêu: **đẹp, đồng nhất, dễ bảo trì** và không
làm mất bản sắc Material You / tiếng Việt hiện có. Phase 0–4 đã được triển khai trong
`app/src/main`; Phase 5 đã chạy các kiểm tra tĩnh và chờ kiểm tra trên thiết bị thật.

Phần GitHub/editor nối tiếp tài liệu này nằm ở
[`GITHUB_FILES_EDITOR_PROPOSAL.md`](GITHUB_FILES_EDITOR_PROPOSAL.md). Đề xuất nâng editor
native thành code editor có tô màu cú pháp nằm ở
[`EDITOR_CODE_HIGHLIGHTING_PROPOSAL.md`](EDITOR_CODE_HIGHLIGHTING_PROPOSAL.md).

## 1. Phạm vi

Giao diện native gồm:

- App shell: top bar, system bars, trạng thái kết nối, chuyển màn hình.
- Màn hình khởi động / cài đặt / kết nối lại.
- Cài đặt (cập nhật, pin, bộ nhớ, tệp, GitHub).
- Quản lý tệp và chọn thư mục dự án.
- Dialog, snackbar, notification.
- Lớp phủ theme lên Web UI (`OpenCodeWebTheme`) — giữ nguyên hướng tiếp cận, chỉ đồng bộ token.

Không nằm trong phạm vi: giao diện bên trong OpenCode Web UI (do upstream quyết định), trừ phần
token màu/typography đã inject.

## 2. Hiện trạng

### Điểm mạnh đã có

- Material 3 + `DynamicColors` trên Android 12+, fallback bảng màu cam OpenCode rõ ràng.
- Web UI được inject token M3 (`OpenCodeWebTheme`), nên native và web đã có cùng nguồn màu.
- Boot gate giữ panel native tới khi API đầu tiên thành công, có 3 bước và timeout.
- Chuỗi tiếng Việt đầy đủ, có dark theme.

### Vấn đề chính

| Khu vực | Hiện trạng | Hệ quả |
| --- | --- | --- |
| Typography | Nhiều `textSize` rời: 25, 26, 20, 18, 17, 14, 13, 12, 11sp; `sans-serif-medium` đặt tay | Không theo type scale, phân cấp không ổn định, chữ 11–12sp khó đọc |
| Top bar | `LinearLayout` tự dựng 64dp, icon 44dp | Không phải `MaterialToolbar`, touch target dưới 48dp, khó thêm subtitle/status/overflow |
| Card | `bg_card` hình 20dp + `LinearLayout` | Không dùng `MaterialCardView`, thiếu state/elevation/ripple chuẩn |
| Chip / trạng thái | `TextView` + drawable tự tạo | Không có semantics, không đổi theo trạng thái, dễ lệch màu |
| Cài đặt | Một `NestedScrollView` dài, 5 nhóm card, form GitHub nhét inline | Khó quét, form ẩn/hiện làm layout nhảy, không có progressive disclosure |
| Tệp | `FileManagerDialog` dùng `ListView` + `android.R.layout.simple_list_item_1` | Không theo M3, item 2 dòng chật, action phải mở dialog thứ hai |
| Chọn thư mục | Dialog + `ListView`, icon emoji `📁` | Emoji lệch style, thiếu breadcrumb/shortcut, khó dùng trên tablet |
| Màu ngữ nghĩa | `oc_ok`/`oc_warn` hardcode; web map success → `tertiary` | Success có thể thành hồng/tím khi Dynamic Color; native và web lệch nhau |
| Chữ phụ | Nhiều chỗ dùng `colorOutline` cho body/hint | Độ tương phản thấp, khó đọc |
| Edge-to-edge | Tô màu status/navigation bar, chưa xử lý insets | Trên máy có gesture bar / cutout dễ lệch, không tận dụng chiều cao |
| Tablet | Nội dung kéo full chiều ngang, boot panel full bleed | Dòng chữ quá dài, bố cục loãng |
| Motion | Chuyển panel tức thời | Mất cảm giác liên tục, khó hiểu khi quay lại |

## 3. Ba hướng thiết kế

### Hướng A — Material You thuần (khuyến nghị)

Dynamic Color cho toàn bộ surface/accent, chỉ giữ cam OpenCode cho logo và seed fallback. Native
và web dùng chung token. Ưu điểm: đồng nhất với hệ điều hành, ít rủi ro, dễ đạt "đẹp" trên mọi
máy. Nhược điểm: bản sắc thương hiệu nhạt hơn.

### Hướng B — Thương hiệu OpenCode

Cố định cam OpenCode, không theo wallpaper; dùng surface tối kiểu code editor. Ưu điểm: nhận diện
mạnh. Nhược điểm: xung đột với Material You, phải tự kiểm tra contrast cho mọi theme.

### Hướng C — Hybrid

Surface theo Dynamic Color, accent/CTA theo cam OpenCode, log/code dùng surface tối riêng. Đẹp
nhưng phức tạp nhất và dễ tạo hai hệ màu song song.

**Khuyến nghị: A + một điểm nhấn thương hiệu nhỏ** (logo và wordmark cố định, phần còn lại theo
Material You). Lý do: app đã có Dynamic Color và web theme đồng bộ; đây là con đường ngắn nhất
tới "đồng nhất" mà vẫn đẹp trên mọi thiết bị.

## 4. Design system

### 4.1. Màu

Giữ nguyên role M3, bổ sung role ngữ nghĩa cố định (không lấy từ `tertiary`):

| Role | Light | Dark | Dùng cho |
| --- | --- | --- | --- |
| success | `#146C2E` | `#6BD586` | server đang chạy, đã kết nối |
| successContainer | 12% success | 16% success | chip/nền trạng thái |
| warning | `#7A5900` | `#F2CF76` | đang khởi động, cần chú ý |
| warningContainer | 12% warning | 16% warning | chip/nền |
| error | `colorError` | `colorError` | lỗi, hành động xoá |
| info | `colorPrimary` | `colorPrimary` | thông tin, liên kết |

Quy tắc:

- Nền: `surface`; card: `surfaceContainerLow`; input: `surfaceContainerHighest`;
  dialog/sheet: `surfaceContainerHigh`; divider: `outlineVariant`.
- Chữ: `onSurface` cho chính, `onSurfaceVariant` cho phụ, `outline` **chỉ** cho metadata rất nhỏ
  hoặc disabled — không dùng cho hint/body.
- Không dùng màu làm tín hiệu duy nhất: trạng thái luôn kèm nhãn hoặc icon.
- Web token: map success/warning sang bảng ngữ nghĩa ở trên thay vì `tertiary/secondary`.

### 4.2. Typography

Dùng type scale M3, khai báo `TextAppearance` trong theme, không đặt `textSize` trong layout:

| Style | Size/Line | Weight | Dùng cho |
| --- | --- | --- | --- |
| headlineSmall | 24/32 | 400 | tiêu đề màn hình |
| titleLarge | 22/28 | 400 | top app bar |
| titleMedium | 16/24 | 500 | tiêu đề card, row chính |
| bodyLarge | 16/24 | 400 | nội dung chính, input |
| bodyMedium | 14/20 | 400 | mô tả, supporting text |
| labelLarge | 14/20 | 500 | button, chip |
| labelMedium | 12/16 | 500 | nhãn nhỏ, metadata |
| labelSmall | 11/16 | 500 | overline — hạn chế dùng |

- Không dùng 11sp cho nội dung; tối thiểu 12sp cho metadata, 14sp cho body.
- Monospace chỉ cho đường dẫn, log, mã xác thực GitHub.
- Cho phép font scale 200%; layout phải wrap, không cắt chữ.

### 4.3. Spacing — lưới 4dp

`4 / 8 / 12 / 16 / 24 / 32 / 48dp`.

- Lề màn hình: 16dp (tablet: max width 640dp, căn giữa).
- Padding card: 16dp; khoảng cách card: 12dp; section: 24dp.
- Row cài đặt: min 56dp (hai dòng: 72dp); touch target >= 48dp.
- Top bar: 64dp medium; icon 24dp trong vùng chạm 48dp.

### 4.4. Shape

- extraSmall 4, small 8, medium 12, large 16, extraLarge 28.
- Card 16dp; dialog/sheet 28dp; input 12dp (filled) hoặc 4dp top; button/chip full.
- Bỏ bán kính 20dp rời rạc; dùng shape token của theme.

### 4.5. Elevation & motion

- Tonal surface là chính, elevation 0; top bar nổi nhẹ khi scroll; dialog/sheet theo M3.
- Motion 150–250ms: fade-through khi boot → web; shared-axis X khi mở Cài đặt/Tệp; slide-up cho
  bottom sheet. Tôn trọng "giảm chuyển động" của hệ thống.

### 4.6. Icon

- Material Symbols outlined, 24dp, stroke đồng nhất; bỏ emoji.
- Icon trạng thái trong top bar là chấm 8dp + nhãn, không chỉ màu.
- Launcher/wordmark giữ bản sắc; trong app dùng mark đơn sắc 28dp.

## 5. Kiến trúc thông tin & điều hướng

Giữ **single activity** và **WebView được giữ nguyên** (không move/reload). Các màn hình native là
overlay toàn màn hình, không thay thế WebView, để không mất phiên đang chat.

```
┌──────────────────────────────────────────┐
│ App shell (activity_main)                │
│  Top app bar: mark · OpenCode · status   │
│               refresh · settings         │
│  ┌────────────────────────────────────┐  │
│  │ WebView (retained)                 │  │
│  └────────────────────────────────────┘  │
│  Overlay: Boot / Settings / Files        │
│  Sheet: Directory picker / More actions  │
└──────────────────────────────────────────┘
```

- Top bar: title "OpenCode", subtitle "Đang chạy · 127.0.0.1:49374" hoặc tên project; chấm trạng
  thái; action refresh + settings. Thêm overflow "Thêm" cho: Quản lý tệp, Khởi động lại server,
  Dừng server, Sao chép địa chỉ, Giới thiệu.
- Cài đặt: destination toàn màn hình, top bar có back; nội dung là danh sách nhóm, không phải
  chuỗi card dài.
- Tệp: destination toàn màn hình từ Cài đặt hoặc overflow.
- Chọn thư mục: bottom sheet (tablet: sheet rộng tối đa 640dp).
- Dialog xác nhận: `MaterialAlertDialogBuilder`; action sheet: `BottomSheetDialog`; phản hồi ngắn:
  `Snackbar` (có action khi cần), hạn chế Toast.

## 6. Đặc tả màn hình

### 6.1. Khởi động

```
        [logo 56]
        OpenCode
   Đang chuẩn bị môi trường

   ● Môi trường        ✓
   ○ Server cục bộ     đang chạy
   ○ Giao diện         chờ

   [progress / indeterminate]
   Dòng trạng thái ngắn, 1 dòng
   [Xem chi tiết]  [Thử lại]  [Cài đặt]
```

- Bỏ kiểu splash quảng cáo (tagline, icon 80dp) → panel "system boot" gọn, có stepper thật.
- Mỗi bước có 3 trạng thái: chờ / đang chạy / xong / lỗi; icon check/spinner/error.
- Progress determinate khi cài đặt; indeterminate khi chờ server.
- Log kỹ thuật đặt trong expandable "Xem chi tiết" (monospace, 12sp), không đẩy nội dung chính.
- Lỗi: icon error, tiêu đề rõ, mô tả ngắn, action "Thử lại", "Sao chép lỗi", "Mở cài đặt".
- Tablet: card max 480dp, căn giữa; nền surface, không full-bleed.

### 6.2. Shell / Web UI

- Top bar medium 64dp, title 22sp, subtitle 12–14sp một dòng, ellipsize.
- Chấm trạng thái 8dp cạnh subtitle: xanh = server chạy, vàng = đang khởi động, đỏ = lỗi.
- Refresh là icon button 48dp; settings là icon button 48dp; overflow chứa hành động ít dùng.
- Mất kết nối: banner Material (không che toàn bộ) với "Đang kết nối lại…" + "Thử lại"; chỉ dùng
  overlay reconnect khi web app không còn dữ liệu. Giữ cơ chế hiện tại nhưng đổi presentation.
- Edge-to-edge: `WindowCompat.setDecorFitsSystemWindows(false)`, insets cho top bar và đáy danh
  sách; status/navigation bar trong suốt, icon sáng/tối theo theme.
- Back: ưu tiên lịch sử WebView, sau đó mới thoát; khi overlay mở thì đóng overlay trước.

### 6.3. Cài đặt

```
← Cài đặt
┌ OpenCode ───────────────────────────────┐
│ Phiên bản 1.0.19                  ›     │
│ Kiểm tra cập nhật            Đã mới nhất│
│ Tự động kiểm tra cập nhật        [switch]│
│ Cập nhật ngay (khi có)         [button] │
├ Máy chủ ────────────────────────────────┤
│ ● Đang chạy · cổng 49374         [chip] │
│ Giữ CPU hoạt động                [switch]│
│ Tự chạy khi khởi động            [switch]│
│ Watchdog giữ server sống         [switch]│
│ Bỏ tối ưu pin                Đã bỏ tối ưu│
│ Khởi động lại server                    │
│ Dừng server                    (đỏ)     │
├ Không gian làm việc ────────────────────┤
│ Quyền bộ nhớ             Đã cấp một phần│
│ Chọn thư mục dự án                ›     │
│ Quản lý tệp & dự án               ›     │
│ ~/storage/shared → /storage/...   (chip)│
├ GitHub ─────────────────────────────────┤
│ Tài khoản                Chưa kết nối   │
│ Kết nối GitHub                          │
│ Cài GitHub CLI (nếu thiếu)              │
│ Clone repository                  ›     │
├ Thông tin ──────────────────────────────┤
│ Phiên bản app 1.0.19                    │
│ Phiên bản OpenCode 2.2.5                │
│ Nhật ký server                    ›     │
│ Giấy phép                         ›     │
└─────────────────────────────────────────┘
```

- Row chuẩn: leading icon (nếu cần), title, supporting text, trailing switch/chevron/chip.
- Trạng thái luôn ở trailing dạng chip hoặc text ngắn; không nhét đoạn mô tả dài vào row.
- Form GitHub (mã xác thực, URL clone, danh sách repo) tách sang sheet riêng, không ẩn/hiện inline.
- Hành động phá huỷ (Dừng server, Xoá) dùng màu error + dialog xác nhận.
- Nội dung max width 640dp trên tablet; section header sticky nếu danh sách dài.
- Tìm kiếm cài đặt: cân nhắc phase sau nếu số mục tăng.

### 6.4. Tệp & dự án

- Top bar: "Tệp & dự án", back, action làm mới.
- Header: "12 mục · 1,2 GB · còn trống 8,4 GB" + sort (Tên / Kích thước / Mới nhất).
- Item 72dp: icon folder/repo/file trong tonal container; title; metadata "Git repo · 320 MB ·
  2 ngày trước"; overflow (Mở dự án, Sao chép đường dẫn, Xoá).
- Empty state: icon + "Chưa có tệp nào" + button "Chọn thư mục".
- Xoá: dialog xác nhận, nút xoá màu error; sau khi xoá cập nhật list + snackbar có "Hoàn tác" nếu
  khả thi (phase sau).
- Dùng `RecyclerView` + `ListAdapter`/`DiffUtil`; không dùng `ListView`.
- Tablet: list + preview panel (tên, đường dẫn, dung lượng, action) nếu đủ rộng.

### 6.5. Chọn thư mục

- `BottomSheetDialog` bo 28dp, kéo để mở rộng.
- Breadcrumb dạng chip: `~ / storage / shared / project` (bấm để nhảy).
- Shortcut: Home, ~/storage/shared, thẻ SD, thư mục gần đây.
- Danh sách folder 56dp, icon folder, tên; bấm để mở.
- Ô nhập đường dẫn trong expandable "Nhập đường dẫn"; validate và hiển thị lỗi ngay dưới.
- Nút chính "Chọn thư mục này"; phụ "Huỷ".
- Không dùng emoji; icon vector 24dp.

### 6.6. Dialog, snackbar, notification

- Dialog: title 24sp, body 14sp, action text button; dialog xoá có icon error và nút đỏ.
- Action sheet: danh sách row 56dp có icon; item xoá màu error.
- Snackbar: đáy màn hình, 4–10s, action "Thử lại"/"Hoàn tác"; không chồng lên bottom bar.
- Notification: icon đơn sắc, title/body theo cùng format; action Mở / Khởi động lại / Dừng; nhóm
  theo server; channel name tiếng Việt.

## 7. Component inventory

| Component | Thay cho | Ghi chú |
| --- | --- | --- |
| `MaterialToolbar` | top bar tự dựng | title + subtitle + menu |
| `MaterialCardView` (filled) | `bg_card` | shape 16dp, không elevation |
| `MaterialSwitch` | đã dùng | giữ |
| `MaterialButton` | đã dùng | phân cấp filled / outlined / text rõ ràng |
| `MaterialChip` | `bg_chip` TextView | trạng thái, filter, breadcrumb |
| `TextInputLayout` + `TextInputEditText` | `EditText` + `bg_input` | label, helper, error |
| `MaterialAlertDialogBuilder` | đã dùng | thống nhất action order |
| `BottomSheetDialog` | dialog action | chọn thư mục, action sheet, form GitHub |
| `RecyclerView` | `ListView` | settings, files, repos |
| `Snackbar` | Toast | phản hồi ngắn, có action |
| `LinearProgressIndicator` | đã dùng | determinate khi có % |

## 8. Accessibility & responsive

- Touch target >= 48dp; icon button hiện tại 44dp phải nâng lên 48dp.
- Contrast >= 4.5:1 cho body; không dùng `outline` cho chữ phụ.
- Trạng thái không chỉ dựa vào màu; có text/icon.
- TalkBack: contentDescription cho icon, thứ tự đọc đúng, không đọc icon trang trí.
- Font scale 200%, display size lớn: row wrap, không cắt; test cả tiếng Việt có dấu.
- RTL: dùng start/end (đa số đã đúng), kiểm tra icon back/chevron.
- Tablet/foldable: max width 640dp; sheet max 640dp; files có thể 2 pane.
- Reduced motion: tôn trọng `Settings.Global.ANIMATOR_DURATION_SCALE`.

## 9. Lộ trình triển khai

### Phase 0 — Nền tảng token (PR nhỏ, ít rủi ro)

- Thêm `res/values/dimens.xml`, `res/values/styles.xml` (TextAppearance, ShapeAppearance,
  widget styles), semantic colors + night.
- Chuẩn hoá icon 24dp; bỏ hardcoded textSize/fontFamily trong layout.
- Thêm style `Widget.OpenCode.Toolbar`, `Widget.OpenCode.Card`, `Widget.OpenCode.Chip`.

### Phase 1 — App shell & boot

- `activity_main.xml`: `MaterialToolbar`, edge-to-edge, status dot, overflow.
- Boot panel: stepper, log expandable, error card; giữ nguyên boot gate.
- `OpenCodeWebActivity`: insets, bindings mới, transition fade.

### Phase 2 — Cài đặt

- Tách `settings_panel` thành màn hình destination với `RecyclerView` + item layouts.
- Nhóm OpenCode / Máy chủ / Không gian làm việc / GitHub / Thông tin.
- Chuyển form GitHub sang bottom sheet; status dùng chip; snackbar cho phản hồi.
- Giữ nguyên logic trong `OpenCodeWebActivity` bằng cách tách helper/adapter.

Đã triển khai: panel được nhóm theo 5 section có tiêu đề, dùng token spacing/typography,
thêm hàng Khởi động lại / Dừng server và card Thông tin (phiên bản app, phiên bản OpenCode).
`settings_panel` vẫn là `NestedScrollView` với các id cũ, nên toàn bộ binding hiện tại giữ nguyên.
Form GitHub đã được thay bằng destination toàn màn hình `github_panel` (`GithubScreen.kt`), mở
từ nguồn GitHub trong Files; entry GitHub trong Cài đặt đã bỏ. Việc chuyển toàn bộ Cài đặt sang
`RecyclerView` + item layouts được giữ lại như bước tối ưu sau, vì panel hiện tại đã đúng nhóm, đúng token và không còn layout nhảy.

### Phase 3 — Tệp & chọn thư mục

- Thay `FileManagerDialog` bằng màn hình Files (`RecyclerView`).
- Thay `DirectoryPickerDialog` bằng `BottomSheetDialog` + breadcrumb + shortcut.
- Xoá `ListView`/`simple_list_item_1`/emoji.

Đã triển khai:

- `FilesScreen.kt` là overlay toàn màn hình trong `activity_main.xml`, giữ WebView attached.
  `RecyclerView` + `ListAdapter`/`DiffUtil`, quét HOME ở background, hiển thị dung lượng,
  trạng thái rỗng/lỗi, nút làm mới trên toolbar.
- Mỗi mục mở `BottomSheetDialog` (`sheet_file_actions.xml`) với mở dự án / sao chép đường dẫn /
  xoá; xoá có dialog xác nhận, kết quả báo bằng Snackbar.
- `DirectoryPickerSheet.kt`: `BottomSheetDialog` có breadcrumb bấm được (rút gọn path sâu bằng
  dấu `…`), chip lối tắt HOME / bộ nhớ trong / từng thẻ SD, ô nhập đường dẫn (`~` hỗ trợ),
  danh sách thư mục đọc được, trạng thái rỗng/lỗi và nút chọn.
- Đã xoá `FileManagerDialog.kt`, `DirectoryPickerDialog.kt`; không còn `ListView`,
  `simple_list_item_1` hay emoji trong UI native.

### Phase 4 — Đồng bộ còn lại

- Dialog/snackbar/notification; icon; motion; empty/error states.
- Đồng bộ success/warning vào `OpenCodeWebTheme` — đã làm cùng Phase 1.
- Kiểm tra dark/light/dynamic, tablet, font scale, TalkBack.

Đã triển khai:

- Form GitHub chuyển vào destination toàn màn hình `github_panel` (`GithubScreen.kt`), mở từ
  nguồn GitHub trong Files; các trạng thái cài đặt / đăng nhập / clone / danh sách repo giữ
  nguyên logic cũ. `sheet_github.xml` và `sheet_git.xml` đã bị xoá; xem
  [`GITHUB_FILES_EDITOR_PROPOSAL.md`](GITHUB_FILES_EDITOR_PROPOSAL.md).
- Toast còn lại trong Activity đổi sang Snackbar (`showSnack`), có action khi phù hợp.
- Nút xác nhận Dừng server dùng màu error; empty/error state của Files và picker có text rõ ràng.
- `OverlayMotion` thêm fade ngắn cho Cài đặt/Files, không detach WebView.
- Bỏ toàn bộ `android:textSize`/`android:fontFamily` khỏi `activity_main.xml`; dùng
  `TextAppearance.OpenCode.*` (thêm `MonoSmall`, `MonoCode`), input clone dùng `TextInputLayout`.
- Nội dung Cài đặt/Files giới hạn 640dp và canh giữa trên tablet/landscape; boot card 480dp.
- Icon button quan trọng nâng lên 48dp; breadcrumb có `contentDescription` cho TalkBack.

Phần kiểm tra dark/light/dynamic, font scale 200%, TalkBack và so sánh contrast được liệt kê ở
Phase 5; các kiểm tra này cần thiết bị/emulator nên chưa chạy trong môi trường build hiện tại.

### Phase 5 — QA & chốt

- Screenshot test cho các trạng thái chính (boot, lỗi, settings, files, picker).
- So sánh light/dark/dynamic; kiểm tra contrast bằng tool.
- Đo cold start: không thêm dependency nặng; ưu tiên view/RecyclerView thuần.

Đã chạy được trong môi trường không có thiết bị:

- Kiểm tra tĩnh: không còn `textSize`/`fontFamily` đặt tay trong layout, không còn `ListView`/
  `simple_list_item_1`/emoji, mọi `R.id`/`R.string`/`R.layout`/`R.drawable` được tham chiếu đều
  tồn tại, `git diff --check` sạch.
- Build + unit test trên GitHub Actions là bước biên dịch chính (máy local không có JDK/Android SDK).
- Không thêm dependency mới: Files/picker dùng `RecyclerView`, `BottomSheetDialog`, `ListAdapter`
  sẵn có; cold start không bị ảnh hưởng bởi thư viện nặng.

Chưa chạy (cần thiết bị/emulator): screenshot test, đo contrast bằng tool, kiểm tra TalkBack,
font scale 200%, xoay màn hình và so sánh light/dark/dynamic trực quan.

## 10. Tiêu chí nghiệm thu

- Không còn `textSize`/`fontFamily` đặt tay trong layout mới; toàn bộ dùng TextAppearance.
- Không còn `ListView`, `android.R.layout.simple_list_item_1`, emoji trong UI.
- Mọi icon button >= 48dp; body >= 14sp; contrast đạt 4.5:1.
- Top bar, card, chip, input, dialog dùng component Material tương ứng.
- Cài đặt chia nhóm rõ; không có form ẩn/hiện làm layout nhảy.
- Boot → web không chớp trắng, không lộ stack trace; lỗi có action.
- WebView không bị reload khi mở/đóng Cài đặt hay Tệp.
- Dynamic Color bật/tắt đều đẹp; dark/light đồng nhất với web.
- Tablet: nội dung không vượt 640dp; sheet không full width.

## 11. Quyết định & câu hỏi còn lại

1. Đã chốt hướng **A**: Material You động cho surface/accent, màu thương hiệu cố định chỉ dùng cho
   logo/seed. Các phase 0–4 triển khai theo hướng này.
2. Cài đặt có cần tìm kiếm ngay không, hay để sau?
3. Có cần màn hình "Nhật ký server" riêng (xem log, sao chép) không?
4. Có cần onboarding lần đầu (quyền bộ nhớ, pin, chọn thư mục) hay giữ dialog hiện tại?
5. Có chuyển nốt `settings_panel` sang `RecyclerView` + item layouts không, hay giữ
   `NestedScrollView` đã nhóm section như hiện tại?
