# Đề xuất kỹ thuật: mở rộng trình sửa tệp và tô màu cú pháp

Tài liệu này nối tiếp [`GITHUB_FILES_EDITOR_PROPOSAL.md`](GITHUB_FILES_EDITOR_PROPOSAL.md)
(Phase 4–5 đã có editor native) và [`UI_REDESIGN_PROPOSAL.md`](UI_REDESIGN_PROPOSAL.md)
(token Material You, typography, toolbar). Mục tiêu là nâng `EditorScreen` từ một
`EditText` có gutter thành một trình sửa mã thực sự: tô màu cú pháp, số dòng, gấp mã,
tìm/thay thế, nhiều con trỏ, tự động hoàn thành và các tiện ích cấp tệp — nhưng vẫn giữ
`TextFileIO` là nguồn chân lý duy nhất về encoding, BOM, newline và ghi atomic.

> **Trạng thái (cập nhật 2026-11-05):** triển khai nền tảng Phase 0–2 và một phần Phase 3 đã
> được đưa vào cây mã nguồn (chưa commit). Chưa chạy build Android/Gradle trong môi trường này và
> chưa có QA thiết bị. Phần còn lại: xác minh build, kiểm thử thiết bị, recovery snapshot bền
> vững, tối ưu hiệu năng tệp lớn, và các tính năng nâng cao chưa đấu nối đầy đủ.
>
> **Đã triển khai (mã nguồn):** `tools/editor/` (CodeMirror 6, esbuild, code splitting),
> `app/src/main/assets/editor/` (bundle offline đã sinh), `EditorRenderer` +
> `CodeMirrorEditorRenderer` + `NativeEditorRenderer`, `EditorBridge`, `EditorTheme`/
> `EditorThemeBuilder`, `OpenCodeWebTheme.tokens()`, `EditorLanguageRegistry`,
> `EditorScreen` refactor (renderer, save/conflict/auto-commit giữ nguyên), layout/menu editor
> (WebView host + native fallback, ngôn ngữ/đi tới dòng/tệp gần đây/word wrap/chế độ đơn giản),
> `OpenCodeSettings` cho tùy chọn editor, `AndroidManifest` `adjustResize`, `checkEditorAssets`.
>
> **Chưa hoàn tất / cần xác minh:** biên dịch APK, QA trên thiết bị/WebView thật, fallback
> readiness timeout theo thiết bị chậm, snapshot recovery khi nền/kill process, giới hạn/đường
> đi tệp rất lớn, unit test registry/protocol, cài gói npm reproducible hoàn toàn trong CI (đã
> dùng registry npm; có thể cần commit lockfile/pinning bổ sung).
>
> Tài liệu được viết sau khi đọc mã nguồn Acode; mọi tên tệp/API của Acode đều kèm đường dẫn để
> đối chiếu.
> **Mốc tham chiếu Acode:** nhánh `main`, commit
> [`6962af1e805b8996e743df97cad4df3ecef687e5`](https://github.com/Acode-Foundation/Acode/commit/6962af1e805b8996e743df97cad4df3ecef687e5)
> (2026-10-29), `package.json` phiên bản `1.13.5`, đọc ngày 2026-11-05.
>
> **Ràng buộc giữ nguyên:** Kotlin, `minSdk 26`, `targetSdk 28`, local-first, Git/GitHub thao
> tác trên bản clone local (không sửa nội dung repo qua GitHub API), Files giữ mô hình điều
> hướng toàn màn hình hiện có.

---

## 1. Mục tiêu và phạm vi

### 1.1. Mục tiêu

| # | Mục tiêu | Cách đo |
| --- | --- | --- |
| 1 | Mở tệp text trong app và thấy màu cú pháp đúng ngôn ngữ | `.kt`, `.java`, `.xml`, `.json`, `.md`, `.js`, `.py`, `.sh`, `.gradle`… được nhận diện và tô màu trong < 300 ms với tệp ≤ 100 KB |
| 2 | Sửa tệp an toàn như hiện tại | Không đổi encoding/BOM/kiểu newline; phát hiện xung đột bằng SHA-256; ghi atomic |
| 3 | Có trải nghiệm code editor | Số dòng, active line, bracket matching, auto-indent, auto-close, undo/redo, search/replace, fold, multi-cursor |
| 4 | Không làm hỏng đường lui | Tệp lớn/nhị phân/encoding lạ/không có quyền vẫn chỉ đọc; WebView lỗi hoặc quá cũ tự quay về editor native |
| 5 | Đồng bộ giao diện | Dùng đúng palette Material You và token `--syntax-*` / `--markdown-*` đã có trong `OpenCodeWebTheme` |
| 6 | Mở rộng cấp tệp | Tệp gần đây, phiên mở nhiều tệp (tab), xem trước Markdown; không đổi mô hình Git local-first |

### 1.2. Không nằm trong phạm vi giai đoạn này

- Sửa trực tiếp tệp trên GitHub qua API. Mọi thay đổi vẫn đi qua clone local → commit local →
  push thủ công.
- LSP server đầy đủ (Kotlin/Java language server) và refactor/rename toàn dự án. Acode có LSP
  client ([`src/cm/lsp/`](https://github.com/Acode-Foundation/Acode/tree/main/src/cm/lsp)) nhưng
  nó cần runtime Java/Node và một kiến trúc tiến trình riêng; quá lớn cho bước này.
- Terminal, SFTP/SSH, plugin marketplace, theme marketplace.
- Đồng bộ đám mây, đa thiết bị.
- Thay đổi `minSdk`/`targetSdk` hoặc thêm runtime JavaScript vào tiến trình native.

### 1.3. Ràng buộc kỹ thuật

1. **Kotlin là chủ sở hữu tệp.** JavaScript chỉ nhìn thấy chuỗi Unicode đã decode. Nó không
   biết BOM, không biết charset, không tự ghi đĩa.
2. **Không có Node/Java/Gradle trong môi trường phát triển hiện tại.** Bản build CodeMirror
   phải được tạo sẵn và commit vào `app/src/main/assets/editor/`; Gradle không chạy npm.
3. **Không thêm network.** Trang editor là asset local, không gọi mạng, không tải CDN.
4. **Không phá WebView của OpenCode.** Editor dùng một WebView riêng, không dùng chung
   `OpenCodeWebViewModel` đang chạy OpenCode UI.
5. **APK vẫn cài được trên API 26.** WebView trên API 26 có thể cũ nếu người dùng không cập
   nhật; phải có phép thử khả năng và fallback.

---

## 2. Acode đã làm gì

### 2.1. Kiến trúc tổng quan

Acode là ứng dụng Cordova: phần native Android chỉ là vỏ, toàn bộ UI và editor chạy trong
WebView. Từ phiên bản 1.13.x, editor dùng **CodeMirror 6**, không còn Ace. Các tầng chính:

| Tầng | Vị trí | Vai trò |
| --- | --- | --- |
| Vỏ ứng dụng | `src/main.js`, `www/index.html` | Cordova, file system, dialog, settings |
| Editor | `src/cm/` | CodeMirror 6: extension, mode, theme, LSP, command |
| Trang | `src/pages/` | File browser, settings, markdown preview, problems |
| Theme | `src/cm/themes/`, `src/theme/`, `src/palettes/` | Highlight style + màu UI |
| Cài đặt | `src/settings/editorSettings.js` | Font, wrap, tab, autocomplete, lint, renderer |

Các package CodeMirror 6 trong `package.json` (rút gọn):

```json
"@codemirror/autocomplete": "^6.20.3",
"@codemirror/commands": "^6.11.0",
"@codemirror/lang-css": "^6.3.1",
"@codemirror/lang-html": "^6.4.12",
"@codemirror/lang-java": "^6.0.2",
"@codemirror/lang-javascript": "^6.2.5",
"@codemirror/lang-json": "^6.0.2",
"@codemirror/lang-markdown": "^6.5.2",
"@codemirror/lang-python": "^6.2.1",
"@codemirror/lang-xml": "^6.1.0",
"@codemirror/lang-yaml": "^6.1.3",
"@codemirror/language": "^6.13.1",
"@codemirror/language-data": "^6.5.2",
"@codemirror/legacy-modes": "^6.5.3",
"@codemirror/lint": "^6.9.7",
"@codemirror/search": "^6.7.1",
"@codemirror/state": "^6.7.6",
"@codemirror/view": "^6.43.14",
"@lezer/highlight": "^1.2.5"
```

Nguồn: [`package.json`](https://github.com/Acode-Foundation/Acode/blob/main/package.json).

### 2.2. Extension stack

[`src/cm/baseExtensions.ts`](https://github.com/Acode-Foundation/Acode/blob/main/src/cm/baseExtensions.ts)
là bản "basicSetup" tự chỉnh của Acode. Nó bật:

- `history()` — undo/redo.
- `highlightSpecialChars()`, `drawSelection()`, `dropCursor()`.
- `foldGutter()` + `codeFolding()` + extension gấp theo indentation.
- `EditorState.allowMultipleSelections.of(true)`, `rectangularSelection()`,
  `crosshairCursor()` — nhiều con trỏ và chọn theo cột.
- `indentOnInput()`, `closeBrackets()`, `bracketMatching()`.
- `highlightActiveLine()` + `highlightActiveLineGutter()`.
- `highlightSelectionMatches()` — tô các lần xuất hiện của vùng chọn.
- `syntaxHighlighting(defaultHighlightStyle, { fallback: true })`.
- `keymap` cho Tab/autocomplete và `tooltips()` giới hạn trong vùng editor.

[`src/cm/mainEditorExtensions.ts`](https://github.com/Acode-Foundation/Acode/blob/main/src/cm/mainEditorExtensions.ts)
lắp các nhóm extension theo tính năng: emmet, base, command keymap, theme, pointer cursor,
shift-click selection, multi-cursor, touch selection, search, read-only, option. Theme cố định
chiều cao: `& { height: 100% }`, `.cm-scroller { height: 100%; overflow: auto }`.

Các extension riêng đáng chú ý (đều MIT, có thể tham khảo/port có ghi nguồn):

| Tệp | Chức năng |
| --- | --- |
| `indentGuides.ts` | Vẽ đường indent, giới hạn 500 dòng/40 cấp để không chậm |
| `rainbowBrackets.ts` | Tô ngoặc theo cấp, có bảng màu dark/light |
| `searchMatchHighlighter.ts` | Chỉ tô match quanh vùng đang render, không quét cả tệp |
| `editorReadOnly.ts` | Chặn input/transaction khi read-only, vẫn cho chọn/copy |
| `indentationFolding.ts` | Gấp theo indentation cho ngôn ngữ không có parser |
| `lineNumberSelection.ts` | Chọn cả dòng từ gutter |
| `scrollPastEnd.ts` | Cho phép cuộn quá dòng cuối |
| `punctuationWrapping.ts` | Wrap dấu câu đúng kiểu code |

### 2.3. Nhận diện ngôn ngữ

[`src/cm/modelist.ts`](https://github.com/Acode-Foundation/Acode/blob/main/src/cm/modelist.ts)
xây registry mode từ:

- `extensions`: chuỗi dạng `js|mjs|cjs`.
- `filenameMatchers`: RegExp cho tên đặc biệt (`Dockerfile`, `Makefile`, `.gitignore`…).
- `aliases`: tên thay thế (`js`, `javascript`).

Điểm đáng học:

- **Độ ưu tiên theo độ đặc hiệu.** Pattern bắt đầu bằng `^` (tên chính xác) được điểm 1000+;
  extension được điểm theo độ dài. Nhờ vậy `.d.ts` thắng `.ts`, `Dockerfile` thắng `*`.
- **Cache theo tên tệp.** `resolvedByFileName` giới hạn 2000 entry để không phình bộ nhớ.
- **Cache index.** `modeIndex` được dựng lại khi thêm/bớt mode.
- **Fallback "Text".** Luôn có mode plain text cho `txt|text|log|plain|lock|pub|gitkeep|keep`
  và `Makefile`.

[`src/cm/supportedModes.ts`](https://github.com/Acode-Foundation/Acode/blob/main/src/cm/supportedModes.ts)
đăng ký toàn bộ `languages` từ `@codemirror/language-data`, chuyển extension/filename của
CodeMirror thành pattern của modelist, bổ sung các mapping thực dụng:

- Shell: `zsh`, `bashrc`, `zprofile`, `bash_history`…
- JSON: `prettierrc`, `eslintrc`, `babelrc`, `bun.lock`.
- YAML: `yarn.lock`.
- TOML: `Cargo.lock`, `poetry.lock`.
- Protobuf: `pb`.
- Properties/INI: `gitconfig`, `gitmodules`, `editorconfig`, `npmrc`.

Đáng chú ý: Acode **cố tình bỏ** một số ngôn ngữ khỏi `language-data` để plugin
`acode.additional.langmodes` đăng ký bằng Lezer grammar. Danh sách hiện tại:

```ts
const PLUGIN_PROVIDED_LANGUAGES = new Set([
  "clojure", "clojurescript", "edn", "gas", "kotlin",
  "latex", "stex", "lua", "swift",
]);
```

Nghĩa là **Kotlin không có sẵn trong lõi Acode**; nó đến từ plugin. Với app của chúng ta,
có thể dùng `kotlin` trong `@codemirror/legacy-modes/mode/clike` (đã kiểm tra: package
xuất `export const kotlin = clike({...})`) để có tô màu ngay, rồi nâng lên Lezer grammar sau
nếu cần độ chính xác cao hơn.

### 2.4. Theme và highlight tag

[`src/cm/themes/index.js`](https://github.com/Acode-Foundation/Acode/blob/main/src/cm/themes/index.js)
định nghĩa theme bằng `HighlightStyle.define([{ tag, color, fontStyle, ... }])`. Mỗi theme có
một `config` màu (background, foreground, keyword, string, number, comment, function,
variable, type, class, constant, operator, invalid) và một mảng extension.

Danh sách theme dựng sẵn trong `src/cm/themes/`: aura, ayu dark, catppuccin, cobalt, dracula,
github dark/light, gruvbox dark, material palenight, monokai, noctis lilac, nord, solarized
dark/light, tokyo night/day, tomorrow night/bright, vscode dark, one dark.

[`src/cm/highlightTags.ts`](https://github.com/Acode-Foundation/Acode/blob/main/src/cm/highlightTags.ts)
định nghĩa thêm tag `memberProperty` là con của `propertyName` để tô thuộc tính JavaScript
(`obj.foo`) khác method, mà không ảnh hưởng key JSON/YAML hay thuộc tính CSS. Đây là ví dụ tốt
về mở rộng tag mà không phá theme cũ.

### 2.5. Cài đặt editor

[`src/settings/editorSettings.js`](https://github.com/Acode-Foundation/Acode/blob/main/src/settings/editorSettings.js)
cho thấy bộ cài đặt người dùng thực tế cần:

- Hiển thị: `fontSize`, `lineHeight`, `editorFont`, `linenumbers`, `relativeLineNumbers`,
  `textWrap`, `wrappingIndent` (`none|same|indent|deepIndent`), `showSpaces`, `rtlText`.
- Soạn thảo: `tabSize`, `softTab`, `indent`, `autoCloseTags`, `autoRenameTags`,
  `autosave`, `formatOnSave`.
- Trợ giúp: `languageCompletion`, `liveAutoCompletion`, `localWordCompletion`,
  `useEmmet`, `lintGutter`, `rainbowBrackets`, `indentGuides`, `fadeFoldWidgets`,
  `colorPreview`, `shiftClickSelection`.
- Renderer: `editorRenderer` (`dom|canvas`), `canvasBackend` (`auto|webgl2|webgpu`),
  `stickyScroll`, `stickyScrollMaxLines`, `canvasGutterMarkers`, `cursorBlinkRate`.

Không phải cái nào cũng cần cho app này, nhưng danh sách này là checklist tốt để chọn mức
tính năng cho từng phase.

### 2.6. LSP, lint, format và canvas renderer

- `src/cm/lsp/` là một LSP client khá đầy đủ: quản lý kết nối, worker transport, diagnostics,
  code actions, definition, references, rename, formatting, inlay hints, document symbols.
  Acode cài runtime riêng cho từng ngôn ngữ (`src/cm/lsp/runtimes/`, `servers/`).
- `@codemirror/lint` + `lintGutter` được nối vào diagnostics.
- Prettier được dùng cho format (`prettier`, `prettier-plugin-java`) và "format on save".
- [`src/cm/createEditorView.ts`](https://github.com/Acode-Foundation/Acode/blob/main/src/cm/createEditorView.ts)
  có hai renderer: `dom` (mặc định) và `canvas` (`@acode.app/canvas-view`, cần WebGL2, opt-in).
  Code canvas tự vẽ selection/cursor/active line/gutter để giảm số DOM node.

**Kết luận cho app này:** LSP và canvas là giai đoạn sau, không đưa vào MVP. Nhưng thiết kế
bridge và lớp renderer nên chừa chỗ để thêm sau mà không đổi hợp đồng lưu tệp.

### 2.7. Bài học lấy / không lấy

| Lấy | Không lấy ngay |
| --- | --- |
| CodeMirror 6 + Lezer, extension theo nhu cầu | Canvas renderer/WebGL2 |
| `language-data` cho đa số ngôn ngữ, lazy load | LSP runtime và server catalog |
| Registry extension + filename + alias có độ ưu tiên | Plugin marketplace |
| `HighlightStyle` dựng từ palette, không hard-code theme | 20 theme dựng sẵn |
| `searchMatchHighlighter` chỉ quét vùng nhìn thấy | Emmet, color preview |
| `indentGuides`, `rainbowBrackets` bản nhẹ | Sticky scroll, WebGPU |
| Read-only chặn ở transaction + input | Autosave mặc định |

---

## 3. Hiện trạng của app

### 3.1. Editor native hiện tại

`app/src/main/kotlin/com/opencode/android/EditorScreen.kt` là một controller activity-scoped:

- `EditText` (`R.id.editor_text`) + `TextView` gutter (`R.id.editor_gutter`) cuộn đồng bộ.
- `TextWatcher` đẩy snapshot vào `ArrayDeque` cho undo/redo, giới hạn `MAX_UNDO`/
  `MAX_UNDO_CHARS`.
- Search bar native: `TextInputLayout` + `TextInputEditText`, đếm match, prev/next.
- Toolbar: Lưu, Tìm, Hoàn tác, Làm lại, Mở bằng ứng dụng khác, Thuộc tính.
- Status bar: trạng thái, ví dụ `UTF-8 · LF · 12:4 · trong repo`.
- Dirty dialog khi back; conflict dialog khi hash đổi; read-only chip.
- `show(file)` chạy `TextFileIO.load` trên executor, `applyDocument` trên UI thread.
- `onActivityResumed()` reload nếu người dùng vừa sửa bằng app ngoài và buffer không dirty;
  nếu dirty thì buffer thắng, hash check vẫn bảo vệ khi lưu.
- `release()` dừng executor, xoá buffer.

`app/src/main/res/layout/panel_editor.xml` là panel toàn màn hình gồm toolbar, search bar,
`FrameLayout` chứa gutter + `EditText` + placeholder, status bar.

### 3.2. `TextFileIO` — hợp đồng bất biến

`app/src/main/kotlin/com/opencode/android/TextFile.kt` là nơi duy nhất đọc/ghi tệp text:

| Khái niệm | Hành vi hiện tại |
| --- | --- |
| Encoding | UTF-8 (có/không BOM), UTF-16LE/BE có BOM; file không BOM và không phải UTF-8 được decode Windows-1252 để đọc nhưng **read-only** |
| BOM | Nhớ và ghi lại đúng BOM |
| Newline | Phát hiện kiểu chiếm ưu thế (`\r\n`, `\n`, `\r`) và chuẩn hoá khi ghi |
| Kích thước | `MAX_EDITABLE_BYTES = 2 MiB`; lớn hơn → read-only, `text = ""` |
| Nhị phân | Phát hiện byte NUL/không decode được → read-only |
| Quyền | `file.canWrite()` false → read-only |
| Ghi | Ghi temp cùng thư mục, fsync, giữ permission, atomic move |
| Xung đột | So SHA-256 của byte trên đĩa với `document.hash` trước khi ghi; lệch → `TextSave.Conflict` |
| `force` | Cho phép ghi đè sau khi người dùng xác nhận |

**Không được thay đổi các hành vi này.** CodeMirror chỉ thay lớp hiển thị/soạn thảo.

### 3.3. Theme token đã có

`OpenCodeWebTheme.kt` đã sinh stylesheet cho OpenCode Web UI từ palette Material You, trong đó
có sẵn:

```text
--syntax-comment      --syntax-keyword       --syntax-string
--syntax-primitive    --syntax-property      --syntax-type
--syntax-constant     --syntax-operator      --syntax-punctuation
--syntax-variable     --syntax-object        --syntax-success
--syntax-warning      --syntax-critical      --syntax-info
--markdown-heading    --markdown-text        --markdown-link
--markdown-link-text  --markdown-code        --markdown-block-quote
--markdown-emph       --markdown-strong      --markdown-horizontal-rule
--markdown-list-item  --markdown-list-enumeration
--markdown-image      --markdown-image-text  --markdown-code-block
```

Đây là nguồn màu lý tưởng cho CodeMirror. Tuy nhiên hàm `buildCss()` đang là `private` và
ghép chuỗi CSS trực tiếp; cần tách phần palette/token thành API dùng chung (xem 7.1).

### 3.4. Cài đặt hiện có

`OpenCodeSettings.kt` dùng `SharedPreferences("opencode_cfg")` với các key kiểu chuỗi/boolean.
Editor hiện chưa có key riêng; các setting mới sẽ theo cùng pattern:

```kotlin
fun editorRenderer(context: Context): String
fun setEditorRenderer(context: Context, value: String)
fun editorFontSize(context: Context): Int
fun editorWordWrap(context: Context): Boolean
fun editorTabSize(context: Context): Int
fun editorSoftTab(context: Context): Boolean
```

### 3.5. Giới hạn cần giải quyết

| Vấn đề | Hệ quả |
| --- | --- |
| `EditText` không có syntax highlighting | Tệp code chỉ là chữ một màu; khó đọc cấu trúc |
| Gutter tự vẽ bằng `TextView` | Không có fold, không có marker lỗi, không có line selection |
| Không có fold/multi-cursor/bracket matching | Trải nghiệm khác biệt rõ so với editor quen thuộc |
| Search chỉ là `indexOf` trên chuỗi | Không regex, không whole-word, không replace |
| Undo là snapshot toàn bộ chuỗi | Tốn bộ nhớ với tệp lớn; không gộp theo transaction của editor thật |
| Không có nhận diện ngôn ngữ | Không thể chọn mode, không thể tô màu |
| Không có tệp gần đây/phiên nhiều tệp | Mỗi lần mở là một lần đi lại từ Files |
| Font/line-height/tab cố định trong XML | Không theo setting, không theo font scale tốt |

---

## 4. Các phương án kiến trúc

### 4.1. Phương án A — tô màu native bằng `Spannable`

Dùng `EditText` + `TextWatcher`, mỗi lần gõ chạy một tokenizer (Kotlin hoặc regex) và
`setSpan(ForegroundColorSpan)` cho từng token.

**Ưu điểm**

- Không thêm WebView, không thêm asset JS.
- IME, TalkBack, chọn text, clipboard, undo native hoạt động như hiện tại.
- Không có bridge, không có vấn đề đồng bộ.

**Nhược điểm**

- Phải tự viết tokenizer cho từng ngôn ngữ, hoặc port grammar; sai và khó bảo trì.
- `Spannable` trên tệp 100 KB+ rất tốn: mỗi lần gõ có thể phải re-token và tạo hàng nghìn span.
- Không có parser tree nên không có fold/bracket matching/autocomplete tử tế.
- Không tái dùng được hệ sinh thái CodeMirror/Lezer.
- Kết quả thường chỉ đạt mức "regex highlight", không phải code editor thật.

**Kết luận:** chỉ phù hợp làm fallback tối giản hoặc cho chế độ trợ năng, không phải hướng
chính.

### 4.2. Phương án B — CodeMirror 6 trong WebView

Nhúng một trang HTML local chứa CodeMirror 6; Kotlin giao tiếp qua
`addJavascriptInterface` và `evaluateJavascript`.

**Ưu điểm**

- Có ngay parser Lezer, highlight chính xác, fold, bracket matching, multi-cursor,
  autocomplete, search/replace.
- Theme bằng `HighlightStyle`, dễ map sang Material You.
- Thư viện MIT, được Acode và nhiều editor dùng; tài liệu tốt.
- Cùng một codebase JS có thể chạy trên WebView và trình duyệt để kiểm thử.

**Nhược điểm**

- Phải đóng gói asset và giữ phiên bản; APK tăng dung lượng.
- Bridge Kotlin ↔ JS là bề mặt lỗi mới (đồng bộ text, version, lifecycle).
- IME/contenteditable trên Android WebView có thể lỗi với một số bàn phím; cần test kỹ.
- TalkBack trong contenteditable thường kém hơn `EditText`.
- WebView cũ trên API 26 có thể không chạy bundle hiện đại.

**Kết luận:** đây là hướng đúng để có "code editor thật", nhưng phải có fallback.

### 4.3. Phương án C — hybrid: CodeMirror là renderer chính, native là fallback

- Trang CodeMirror 6 trong một WebView riêng, chỉ làm nhiệm vụ hiển thị và soạn thảo.
- `TextFileIO` vẫn là nơi đọc/ghi duy nhất.
- `EditorScreen` trở thành controller: chọn renderer, quản lý state, toolbar, search, dialog.
- Nếu WebView thiếu khả năng, crash renderer, hoặc người dùng bật "chế độ đơn giản"/
  trợ năng → dùng `EditText` hiện tại.

Đây là phương án đề xuất.

### 4.4. So sánh

| Tiêu chí | A. Native span | B. CodeMirror | C. Hybrid |
| --- | --- | --- | --- |
| Chất lượng highlight | Thấp–trung bình | Cao | Cao |
| Công sức ban đầu | Trung bình (nhiều tokenizer) | Trung bình–cao | Trung bình–cao |
| Công sức dài hạn | Cao (mỗi ngôn ngữ một tokenizer) | Thấp (Lezer) | Thấp |
| Fold/multi-cursor/autocomplete | Không có sẵn | Có sẵn | Có sẵn |
| IME/TalkBack | Tốt nhất | Rủi ro | Rủi ro + fallback |
| Dung lượng APK | +0 | +1.5–2.5 MB (ước tính) | +1.5–2.5 MB |
| Bộ nhớ | Thấp | +30–80 MB WebView | +30–80 MB |
| Rủi ro kỹ thuật | Thấp | Trung bình | Trung bình, có đường lui |
| Tái dùng hệ sinh thái | Không | Có | Có |

### 4.5. Quyết định

Chọn **C**. Lý do:

1. Mục tiêu là "đúng code editor", không chỉ "đổi màu chữ". CodeMirror cho parser tree, fold,
   bracket, completion, search/replace — những thứ gần như không thể làm tốt bằng span.
2. CodeMirror 6 đã được chứng minh trong Acode trên Android WebView, kể cả bàn phím và màn
   hình cảm ứng.
3. Rủi ro WebView được kiểm soát bằng fallback native đã có, cùng một feature flag.
4. `TextFileIO` không đổi, nên rủi ro mất dữ liệu không tăng.

---

## 5. Kiến trúc đề xuất

### 5.1. Sơ đồ tầng

```text
┌──────────────────────────────────────────────────────────────────┐
│ OpenCodeWebActivity (single activity, configChanges)             │
│                                                                  │
│  ┌────────────────────────────┐   ┌───────────────────────────┐  │
│  │ EditorScreen (controller)  │   │ OpenCode WebView (giữ nguyên)│
│  │  - document / dirty        │   └───────────────────────────┘  │
│  │  - renderer selection      │                                  │
│  │  - toolbar / search / dialog                                  │
│  └─────────────┬──────────────┘                                  │
│                │                                                 │
│      ┌─────────┴──────────┐                                      │
│      │                    │                                      │
│  ┌───▼──────────────┐  ┌──▼──────────────────────────────┐       │
│  │ NativeEditorView │  │ EditorWebView (lazy, riêng)     │       │
│  │ EditText + gutter│  │  index.html + editor.js (CM6)   │       │
│  │ (fallback)       │  │  window.OpenCodeEditor.*        │       │
│  └──────────────────┘  └──────────────┬──────────────────┘       │
│                                       │ JS bridge                │
│                          ┌────────────▼─────────────┐            │
│                          │ EditorBridge (@Javascript│            │
│                          │ Interface) + protocol    │            │
│                          └────────────┬─────────────┘            │
│                                       │                          │
│                          ┌────────────▼─────────────┐            │
│                          │ TextFileIO (không đổi)   │            │
│                          │ load/save/hash/encoding  │            │
│                          └──────────────────────────┘            │
└──────────────────────────────────────────────────────────────────┘
```

### 5.2. Trách nhiệm từng tầng

| Tầng | Làm gì | Không làm gì |
| --- | --- | --- |
| `EditorScreen` | Chọn tệp, gọi `TextFileIO`, quản lý dirty/conflict, toolbar, search UI, dialog, auto-commit | Không giữ `EditText`/`WebView` trực tiếp nữa; uỷ quyền cho renderer |
| `EditorRenderer` (interface) | `load`, `snapshot`, `setReadOnly`, `setTheme`, `setSettings`, `find`, `undo`, `redo`, `focus`, `dispose` | Không đọc/ghi tệp |
| `NativeEditorRenderer` | Bọc code hiện tại của `EditorScreen` | Không thay đổi hành vi cũ |
| `CodeMirrorEditorRenderer` | Tạo WebView, nạp asset, gửi/nhận JSON, theo dõi readiness | Không biết charset/BOM |
| `EditorBridge` | Validate message, chuyển thread, version, giới hạn kích thước | Không chứa logic UI |
| `EditorLanguageRegistry` | Tên tệp → language id + chunk | Không tải grammar |
| `EditorThemeBuilder` | Palette Material You → JSON theme | Không tự quyết định màu ngoài palette |
| `TextFileIO` | Encoding/BOM/newline/hash/atomic save | Không biết CodeMirror |

### 5.3. Interface renderer

```kotlin
interface EditorRenderer {
    val view: View

    fun load(document: TextDocument, language: EditorLanguage, state: EditorViewState?)
    fun snapshot(reason: SnapshotReason, callback: (EditorSnapshot) -> Unit)
    fun setReadOnly(reason: Int?)
    fun setTheme(theme: EditorTheme)
    fun setSettings(settings: EditorSettings)
    fun setLanguage(language: EditorLanguage)
    fun find(query: String, options: FindOptions)
    fun findNext(forward: Boolean)
    fun clearFind()
    fun undo()
    fun redo()
    fun gotoLine(line: Int)
    fun focus()
    fun dispose()
}
```

`EditorScreen` không còn gọi `editor.text` trực tiếp. Nó gọi `renderer.snapshot(...)` khi cần
lưu hoặc khi đóng, và nhận `onDirtyChanged`/`onCursorChanged` từ renderer.

### 5.4. State machine của tài liệu

```text
        show(file)
            │
            ▼
      ┌──────────┐   TextFileIO.load   ┌──────────────┐
      │ Loading  │ ──────────────────► │  ReadOnly    │ (large/binary/encoding/permission)
      └────┬─────┘                     └──────────────┘
           │ Ok & editable
           ▼
      ┌──────────┐  user edit   ┌──────────┐
      │  Clean   │ ───────────► │  Dirty   │
      └────┬─────┘              └────┬─────┘
           │ save                    │ save
           ▼                         ▼
      ┌──────────┐              ┌──────────┐
      │  Saving  │ ───────────► │ Conflict │
      └────┬─────┘   hash lệch  └────┬─────┘
           │ Ok                      │ reload / overwrite / cancel
           ▼                         ▼
      ┌──────────┐              Clean / Dirty
      │  Clean   │
      └──────────┘
```

Quy tắc:

- **Dirty** được tính bên trong renderer bằng so sánh `state.doc` với `savedDoc` (CodeMirror
  `Text` là cây chia sẻ nên `.eq()` rẻ), không phải so chuỗi mỗi lần gõ.
- **Version** là số nguyên tăng mỗi transaction đổi doc; **requestId** tăng mỗi lần yêu cầu
  snapshot. Kết quả lưu cũ bị bỏ nếu `requestId` không khớp. Nếu người dùng gõ tiếp trong lúc
  đang ghi, `savedDoc` được đặt thành document của snapshot (không phải document hiện tại), nên
  buffer vẫn dirty đúng và lần lưu sau vẫn an toàn.
- **Base hash** là `TextDocument.hash` tại lần load/save gần nhất. `TextFileIO.save` tự kiểm
  tra lại; renderer không được tự quyết định ghi đè.
- **Read-only** vẫn cho chọn/copy và tìm kiếm, nhưng chặn transaction đổi doc (giống
  `editorReadOnly.ts` của Acode).

### 5.5. Luồng mở tệp

```text
FilesScreen.openFile(file)
  └─ EditorScreen.show(file)
       ├─ generation++ ; renderer.reset()
       ├─ IO: TextFileIO.load(file)
       ├─ IO: OpenCodeGitHub.findRepoRoot(file)
       ├─ UI: applyDocument()
       │    ├─ nếu readOnly vì large/binary → placeholder + nút mở app ngoài
       │    ├─ nếu readOnly vì encoding/quyền → renderer.load(text, readOnly=true)
       │    └─ nếu editable
       │         ├─ EditorLanguageRegistry.resolve(file)
       │         ├─ EditorThemeBuilder.build(activity)
       │         ├─ renderer.load(document, language, savedState)
       │         └─ renderer.setSettings(OpenCodeSettings...)
       └─ UI: cập nhật toolbar/status/menu
```

`generation` vẫn giữ vai trò cũ: nếu người dùng mở tệp khác trong lúc load, kết quả cũ bị bỏ.

### 5.6. Luồng lưu

```text
Toolbar Save
  └─ EditorScreen.save()
       ├─ renderer.snapshot(SAVE, requestId)  ──►  JS: gửi text + version + selection
       ├─ nếu requestId không khớp → bỏ qua snapshot cũ
       ├─ IO: TextFileIO.save(document, text, force)
       ├─ Ok(hash)
       │    ├─ document = document.copy(hash = hash)
       │    ├─ renderer.confirmSaved(version, hash)
       │    ├─ JS tính lại dirty (có thể vẫn dirty nếu người dùng gõ tiếp)
       │    └─ auto-commit local nếu setting bật
       ├─ Conflict
       │    └─ dialog Tải lại / Ghi đè / Huỷ
       └─ Failure → snackbar, giữ dirty
```

Điểm quan trọng: **không gửi toàn bộ text qua bridge mỗi lần gõ**. Chỉ gửi metadata
(`dirty`, `line`, `column`, `selection`, `version`) với tần suất giới hạn; text chỉ đi qua
bridge khi load/save/snapshot khôi phục.

### 5.7. Luồng quay lại từ app ngoài

Giữ nguyên logic hiện tại:

- Nếu buffer **không dirty**: `show(file, force = true)` để nạp nội dung mới, sau đó auto-commit
  nếu setting bật.
- Nếu buffer **dirty**: giữ buffer; hash check khi lưu sẽ phát hiện xung đột.
- Sau khi `renderer.load` lại, gửi kèm `selection`/`scroll` cũ nếu tệp không đổi.

---

## 6. Đóng gói CodeMirror

### 6.1. Nguyên tắc

1. **Không tải CDN.** Trang editor phải chạy offline và không phụ thuộc mạng.
2. **Không chạy npm trong Gradle.** Build Android vẫn chỉ là Gradle; asset JS được tạo ở
   máy phát triển hoặc một CI job riêng, rồi commit.
3. **Pin phiên bản chính xác.** Dùng version cụ thể (không `^`) trong `package.json` của
   `tools/editor`, khoá bằng `package-lock.json`, và ghi lại trong tài liệu.
4. **Có nguồn và giấy phép.** Mọi package CodeMirror/Lezer là MIT; chép `LICENSE` vào
   `app/src/main/assets/editor/licenses/`.

### 6.2. Cấu trúc asset

```text
app/src/main/assets/editor/
├── index.html
├── editor.css
├── editor.js                 # core bundle: state, view, commands, language,
│                             # search, autocomplete, highlight, bridge
├── chunks/
│   ├── lang-javascript.js    # JS/TS/JSX/TSX
│   ├── lang-html.js
│   ├── lang-css.js
│   ├── lang-sass.js
│   ├── lang-less.js
│   ├── lang-json.js
│   ├── lang-markdown.js
│   ├── lang-xml.js
│   ├── lang-yaml.js
│   ├── lang-python.js
│   ├── lang-java.js
│   ├── lang-cpp.js
│   ├── lang-go.js
│   ├── lang-rust.js
│   ├── lang-sql.js
│   ├── lang-php.js
│   ├── legacy-clike.js       # Kotlin, C, C++ (fallback), C#, Scala, Dart
│   ├── legacy-shell.js
│   ├── legacy-properties.js  # .properties, .ini, .gitconfig
│   ├── legacy-toml.js
│   ├── legacy-dockerfile.js
│   ├── legacy-diff.js
│   └── legacy-groovy.js      # Gradle Groovy
└── licenses/
    ├── CodeMirror-LICENSE
    └── Lezer-LICENSE
```

Nguồn JS/TS (không được đóng gói vào APK) nằm ở:

```text
tools/editor/
├── package.json
├── package-lock.json
├── build.mjs                 # esbuild: core + từng chunk
├── src/
│   ├── main.ts               # khởi tạo EditorView, bridge
│   ├── bridge.ts             # window.OpenCodeEditor / native calls
│   ├── languages.ts          # lazy import map
│   ├── theme.ts              # HighlightStyle từ JSON palette
│   ├── settings.ts           # Compartment cho readOnly/theme/settings
│   └── extensions/           # indent guides, rainbow brackets (tuỳ chọn)
└── README.md
```

### 6.3. Build và kiểm tra trôi

`tools/editor/build.mjs` dùng esbuild:

- `entryPoints: ["src/main.ts"]`, `bundle: true`, `format: "esm"`, `target: ["chrome90"]`.
- `splitting: true`, `outdir: "../../app/src/main/assets/editor"`.
- Mỗi ngôn ngữ là một entry hoặc một `import()` tĩnh để esbuild tách chunk.
- `minify: true`, `sourcemap: false` cho bản phát hành; có thể bật sourcemap ở debug.
- Không dùng `eval`; CSP `script-src 'self'`.

CI có thể thêm bước (không bắt buộc ở Phase 0):

```yaml
- name: Verify editor bundle
  run: |
    cd tools/editor
    npm ci
    npm run build
    git diff --exit-code -- ../../app/src/main/assets/editor
```

Nếu bước này quá nặng, thay bằng kiểm tra hash của `editor.js` trong một file
`app/src/main/assets/editor/BUNDLE_SHA256` và so sánh trong một unit test đọc asset.

### 6.4. Dung lượng dự kiến

| Thành phần | Minified | Gzip (APK) |
| --- | --- | --- |
| Core CM6 + bridge | ~280 KB | ~95 KB |
| 12–15 language chunks | ~1.4–2.2 MB | ~450–700 KB |
| Legacy modes (clike, shell, properties, toml, dockerfile, diff, groovy) | ~250 KB | ~80 KB |
| Tổng | **~2.0–2.8 MB** | **~0.6–0.9 MB** |

Đây là ước tính; con số thực phải đo sau khi bundle. Ngân sách đề xuất: **không quá 3 MB
trước nén, không quá 1 MB trong APK**. Nếu vượt, cắt bớt ngôn ngữ ít dùng (PHP, SQL, Rust)
hoặc gộp chunk.

### 6.5. Tải trang

Dùng `WebViewAssetLoader` (đã có `androidx.webkit:webkit:1.18.0`):

```kotlin
private val assetLoader = WebViewAssetLoader.Builder()
    .addPathHandler("/editor/", AssetsPathHandler(context))
    .build()

webView.webViewClient = object : WebViewClientCompat() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = true // không cho điều hướng ra ngoài
}
webView.loadUrl("https://appassets.androidplatform.net/editor/index.html")
```

Ưu điểm so với `file:///android_asset`:

- Origin `https://appassets.androidplatform.net` ổn định, không bị coi là opaque origin.
- `fetch`/`import()` tương đối hoạt động nhất quán.
- Có thể chặn mọi request không phải `/editor/` ngay trong `shouldInterceptRequest`.

### 6.6. Cấu hình WebView

```kotlin
settings.javaScriptEnabled = true
settings.domStorageEnabled = true          // chỉ cho setting cục bộ của editor
settings.databaseEnabled = false
settings.allowFileAccess = false
settings.allowContentAccess = false
settings.allowFileAccessFromFileURLs = false
settings.allowUniversalAccessFromFileURLs = false
settings.javaScriptCanOpenWindowsAutomatically = false
settings.setSupportMultipleWindows(false)
settings.mediaPlaybackRequiresUserGesture = true
settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
settings.blockNetworkLoads = true          // không có request mạng
settings.cacheMode = WebSettings.LOAD_NO_CACHE // asset local, tránh cache cũ sau update
WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
```

Lưu ý: `blockNetworkLoads = true` có thể chặn cả `https://appassets.androidplatform.net`?
Trong Android, `blockNetworkLoads` chặn request mạng thật qua network stack; asset loader được
xử lý trong `shouldInterceptRequest` nên vẫn hoạt động. Cần kiểm tra trên thiết bị; nếu không,
dùng `shouldInterceptRequest` trả `404` cho mọi URL ngoài `/editor/` thay vì
`blockNetworkLoads`.

CSP trong `index.html`:

```html
<meta http-equiv="Content-Security-Policy"
      content="default-src 'none';
               script-src 'self';
               style-src 'self' 'unsafe-inline';
               img-src 'self' data:;
               font-src 'self' data:;
               connect-src 'none';
               base-uri 'none';
               form-action 'none';
               frame-ancestors 'none'">
```

CodeMirror chèn `<style>` động, nên cần `style-src 'unsafe-inline'`; không cấp
`'unsafe-eval'`.

---

## 7. Theme Material You cho CodeMirror

### 7.1. Tách palette dùng chung

Hiện `OpenCodeWebTheme.buildCss(p: Palette, dark: Boolean)` là private và tự ghép CSS. Đề xuất
tách:

```kotlin
object OpenCodeWebTheme {
    data class Tokens(
        val syntax: Map<String, String>,   // comment, keyword, string, ...
        val markdown: Map<String, String>,
        val editor: Map<String, String>,   // background, gutter, caret, selection, ...
    )

    fun tokens(context: Context): Tokens
    fun buildCss(context: Context): String = buildCss(palette(context), isDark(context))
}
```

`EditorThemeBuilder` gọi `OpenCodeWebTheme.tokens(context)` rồi serialize JSON. Như vậy màu
native Web UI và màu editor không bao giờ lệch nhau. Nếu sau này có theme tùy chỉnh, chỉ cần
thay implementation của `tokens()`.

### 7.2. Bảng map tag → token

| Lezer tag | Token CSS | Ghi chú |
| --- | --- | --- |
| `comment`, `lineComment`, `blockComment`, `docComment` | `--syntax-comment` | |
| `keyword`, `controlKeyword`, `definitionKeyword`, `modifier`, `operatorKeyword`, `self` | `--syntax-keyword` | |
| `string`, `special(string)`, `regexp`, `character`, `attributeValue` | `--syntax-string` | |
| `number`, `integer`, `float`, `bool`, `null`, `atom` | `--syntax-primitive` | |
| `propertyName`, `attributeName`, `labelName` | `--syntax-property` | |
| `typeName`, `className`, `namespace`, `tagName` | `--syntax-type` | |
| `variableName`, `definition(variableName)`, `local(variableName)` | `--syntax-variable` | |
| `function(variableName)`, `function(propertyName)`, `macroName` | `--syntax-function` (mới) | Thêm token mới, suy từ `p.tertiary` |
| `operator` | `--syntax-operator` | |
| `punctuation`, `bracket`, `separator`, `squareBracket` | `--syntax-punctuation` | |
| `meta`, `annotation` | `--syntax-comment` | hoặc token riêng nếu cần |
| `invalid` | `--syntax-critical` | |
| `heading1..6`, `heading` | `--markdown-heading` | |
| `strong` | `--markdown-strong` | |
| `emphasis` | `--markdown-emph` | |
| `link`, `url` | `--markdown-link` | |
| `monospace`, `code` | `--markdown-code` | |
| `quote` | `--markdown-block-quote` | |
| `list` | `--markdown-list-item` | |
| `contentSeparator`, `horizontalRule` | `--markdown-horizontal-rule` | |
| `image` | `--markdown-image` | |

Màu nền/khung:

| Thuộc tính editor | Nguồn |
| --- | --- |
| `background` | `colorSurface` |
| `gutterBackground` | `colorSurfaceContainerLow` |
| `gutterText` | `colorOnSurfaceVariant` |
| `caret` | `colorPrimary` |
| `selection` | `colorSecondaryContainer` (alpha ~0.35) |
| `activeLine` | `colorSurfaceContainerHighest` (alpha ~0.5) |
| `searchMatch` | `colorTertiaryContainer` |
| `searchMatchSelected` | `colorPrimaryContainer` |
| `bracketMatch` | `colorOutline` |
| `indentGuide` | `colorOutlineVariant` |
| `invalid` | `colorError` |
| `foldPlaceholder` | `colorSurfaceContainerHigh` |

### 7.3. JSON gửi sang JS

```json
{
  "protocol": 1,
  "dark": true,
  "colors": {
    "background": "#111318",
    "surface": "#1A1C22",
    "gutter": "#16181E",
    "gutterText": "#8A8F9A",
    "caret": "#D0BCFF",
    "selection": "#4A445866",
    "activeLine": "#FFFFFF0D",
    "searchMatch": "#633B48",
    "searchMatchSelected": "#7D5260",
    "bracketMatch": "#938F99",
    "indentGuide": "#49454F",
    "invalid": "#F2B8B5"
  },
  "syntax": {
    "comment": "#8A8F9A",
    "keyword": "#D0BCFF",
    "string": "#EFB8C8",
    "primitive": "#D0BCFF",
    "property": "#CCC2DC",
    "type": "#EFB8C8",
    "function": "#EFB8C8",
    "constant": "#CCC2DC",
    "operator": "#CAC4D0",
    "punctuation": "#CAC4D0",
    "variable": "#E6E1E5",
    "invalid": "#F2B8B5"
  },
  "markdown": {
    "heading": "#D0BCFF",
    "text": "#E6E1E5",
    "link": "#D0BCFF",
    "code": "#EFB8C8",
    "quote": "#938F99",
    "emph": "#CCC2DC",
    "strong": "#D0BCFF",
    "rule": "#49454F",
    "listItem": "#D0BCFF",
    "listEnumeration": "#CCC2DC",
    "image": "#D0BCFF"
  }
}
```

Giá trị trên chỉ là ví dụ; giá trị thật lấy từ palette động.

### 7.4. Đổi theme khi hệ thống đổi màu

- `OpenCodeApplication` đã bật `DynamicColors`. Khi wallpaper/theme đổi, Activity thường được
  recreate (trừ khi `configChanges` chặn `uiMode`; manifest hiện không khai báo `uiMode`, nên
  đổi dark/light sẽ recreate).
- `EditorScreen` phải giữ buffer qua recreation. Nếu renderer là WebView, không thể giữ nguyên
  WebView khi Activity recreate. Giải pháp:
  1. Lưu snapshot (text + selection + scroll) vào controller trước khi `onDestroy`.
  2. Sau recreate, tạo lại WebView và `load` snapshot, không đọc lại đĩa nếu buffer dirty.
  3. Hoặc thêm `uiMode` vào `configChanges` và tự gọi `onConfigurationChanged` để chỉ đổi theme;
     cách này nhanh hơn nhưng phải kiểm tra lại toàn bộ app (đặc biệt Web UI OpenCode).
- Đề xuất chọn cách 1 (an toàn, không đổi hành vi toàn app), và đo thời gian restore.

### 7.5. Font và typography

- Font mặc định: `monospace` của hệ thống; cho phép chọn font trong setting sau.
- `fontSize`: theo `TextAppearance.OpenCode.Editor` hiện tại, nhân với `fontScale` của hệ
  thống; CodeMirror nhận `fontSize` dạng px/rem, không hard-code.
- `lineHeight`: 1.4–1.5 cho code; cấu hình được.
- `tabSize`: mặc định 4; `softTab` mặc định true (chèn space).
- Không bật ligature mặc định (dễ gây nhầm `=>`, `!=`).
- `letterSpacing: 0`.

---

## 8. Hợp đồng bridge Kotlin ↔ JavaScript

### 8.1. Nguyên tắc

1. **Một chiều cho lệnh, hai chiều cho sự kiện.** Kotlin gọi JS qua `evaluateJavascript`;
   JS gọi Kotlin qua `@JavascriptInterface`.
2. **JSON là định dạng duy nhất.** Không truyền chuỗi phân tách bằng ký tự.
3. **Có số protocol.** `EDITOR_PROTOCOL = 1`; lệch → fallback native.
4. **Mọi method JS→Kotlin chạy trên thread của WebView, không phải main thread.** Bridge phải
   `activity.runOnUiThread { ... }` hoặc đẩy sang executor.
5. **Giới hạn kích thước.** Từ chối message > 4 MiB; text tối đa ngang `MAX_EDITABLE_BYTES`.
6. **Không tin dữ liệu từ JS.** Validate enum language id, độ dài, version, URL scheme.

### 8.2. Kotlin → JS

Đối tượng toàn cục: `window.OpenCodeEditor`.

| Hàm | Tham số | Mục đích |
| --- | --- | --- |
| `init(payload)` | `EditorInit` | Gửi protocol, settings, theme, ngôn ngữ mặc định |
| `loadDocument(payload)` | `DocumentLoad` | Nạp tài liệu mới hoặc reload sau conflict |
| `unloadDocument()` | — | Xoá buffer khi đóng editor |
| `setReadOnly(payload)` | `{readOnly, reason}` | Bật/tắt chỉ đọc |
| `setTheme(payload)` | `ThemePayload` | Đổi palette |
| `setSettings(payload)` | `EditorSettings` | Font, wrap, tab, line numbers, fold… |
| `setLanguage(payload)` | `{id}` | Người dùng chọn ngôn ngữ thủ công |
| `requestSnapshot(payload)` | `{reason, requestId}` | Yêu cầu JS gửi text + version |
| `confirmSaved(payload)` | `{version, hash}` | Báo lưu thành công, cập nhật `savedDoc` |
| `saveFailed(payload)` | `{version, reason}` | Báo lỗi/conflict, giữ dirty |
| `find(payload)` | `{query, caseSensitive, regexp, wholeWord}` | Bắt đầu tìm |
| `findNext(payload)` | `{forward}` | Match tiếp theo |
| `clearFind()` | — | Xoá highlight tìm kiếm |
| `undo()`, `redo()` | — | Lịch sử CodeMirror |
| `gotoLine(payload)` | `{line}` | Nhảy tới dòng |
| `focus()` | — | Đưa focus vào editor |
| `restoreView(payload)` | `{selection, scroll}` | Khôi phục sau recreation |
| `ping()` | — | Kiểm tra sống |

### 8.3. JS → Kotlin

Đối tượng: `window.OpenCodeEditorBridge` (tên có prefix để tránh trùng với web app).

| Hàm | Payload | Khi nào |
| --- | --- | --- |
| `onReady(protocol, userAgent, capabilities)` | JSON | Trang load xong, CodeMirror tạo xong |
| `onDirtyChanged(dirty, length)` | JSON | Khi trạng thái dirty đổi |
| `onCursorChanged(line, column, selectedChars)` | JSON | Debounce ~50 ms |
| `onHistoryChanged(canUndo, canRedo)` | JSON | Sau mỗi transaction |
| `onSearchResult(count, index)` | JSON | Sau find/next/prev |
| `onSnapshot(payload)` | `EditorSnapshot` | Trả lời `requestSnapshot` |
| `onError(code, message)` | JSON | Lỗi JS/CSS/khởi tạo |
| `onLink(url, kind)` | JSON | Người dùng bấm link trong Markdown |
| `onLanguageResolved(id, source)` | JSON | Registry đã chọn ngôn ngữ |
| `onFocusChanged(focused)` | JSON | Để native ẩn/hiện bàn phím |
| `onMetrics(payload)` | JSON | Debug: thời gian mở, số dòng, bộ nhớ ước tính |

### 8.4. Kiểu dữ liệu

```ts
interface EditorInit {
  protocol: number;
  settings: EditorSettings;
  theme: ThemePayload;
  language: string;
}

interface DocumentLoad {
  protocol: number;
  path: string;
  name: string;
  text: string;            // luôn dùng "\n", không BOM
  version: number;
  baseHash: string;
  language: string;
  readOnly: boolean;
  readOnlyReason?: string;
  selection?: { anchor: number; head: number };
  scroll?: { top: number; left: number };
}

interface EditorSnapshot {
  reason: "save" | "close" | "recovery" | "background";
  requestId: number;       // khớp với requestSnapshot gần nhất
  text: string;            // "\n", không BOM
  version: number;
  baseHash: string;
  selection: { anchor: number; head: number };
  scroll: { top: number; left: number };
  dirty: boolean;
}
```

`EditorSettings`:

```json
{
  "fontSize": 14,
  "lineHeight": 1.45,
  "fontFamily": "monospace",
  "tabSize": 4,
  "softTab": true,
  "wordWrap": false,
  "lineNumbers": true,
  "relativeLineNumbers": false,
  "highlightActiveLine": true,
  "bracketMatching": true,
  "autoCloseBrackets": true,
  "autoIndent": true,
  "foldGutter": true,
  "indentGuides": true,
  "rainbowBrackets": false,
  "autocomplete": "local",
  "showWhitespace": false
}
```

### 8.5. Quy ước newline và encoding

- `TextFileIO.load` trả `text` có thể chứa `\r\n` hoặc `\r`.
- Trước khi gửi sang JS, Kotlin chuẩn hoá: `text.replace("\r\n", "\n").replace('\r', '\n')`.
- JS luôn làm việc với `\n`; vị trí offset là offset trong chuỗi này.
- Khi nhận snapshot, Kotlin gọi `TextFileIO.save(document, snapshot.text)`; hàm này tự
  `normalizeNewlines` về `document.newline`, giữ BOM và charset.
- Không gửi BOM sang JS. Không gửi ký tự `\uFEFF` ở đầu tài liệu.
- UTF-16: Kotlin encode lại đúng charset; JS không cần biết.
- File read-only vì encoding lạ: vẫn có thể gửi text để xem/tô màu, nhưng `readOnly = true`
  và không cho save.

### 8.6. Luồng lưu chi tiết

```text
1. Native Save
2. Kotlin: requestId++; renderer.requestSnapshot("save", requestId)
3. JS: chụp snapshotDoc = state.doc; gửi OpenCodeEditorBridge.onSnapshot({
        reason: "save",
        requestId: currentRequestId,
        text: snapshotDoc.toString(),
        version: currentVersion,
        baseHash: savedHash,
        selection, scroll,
        dirty: !snapshotDoc.eq(savedDoc)
   })
4. Kotlin (bridge thread → UI/IO):
   - nếu requestId != currentRequestId → bỏ qua snapshot cũ
   - TextFileIO.save(document, text, force=false)
5a. Ok(newHash):
   - document = document.copy(hash = newHash)
   - renderer.confirmSaved(version, newHash)
   - JS: savedHash = newHash
         nếu currentVersion == version:
             savedDoc = snapshotDoc; dirty = false
         ngược lại:               // người dùng đã gõ tiếp trong lúc ghi
             savedDoc = snapshotDoc; dirty = !state.doc.eq(savedDoc)
   - auto-commit nếu bật
5b. Conflict:
   - renderer.saveFailed(version, "conflict")
   - hiện dialog Tải lại / Ghi đè / Huỷ
   - Ghi đè: TextFileIO.save(..., force = true)
   - Tải lại: TextFileIO.load(file) → renderer.loadDocument(...)
5c. Failure:
   - renderer.saveFailed(version, message)
   - snackbar; giữ dirty
```

### 8.7. Threading và hiệu năng

- `@JavascriptInterface` được gọi trên thread riêng của WebView; mọi thao tác UI phải
  `runOnUiThread`.
- I/O (`TextFileIO`, hash) chạy trên `Executors.newSingleThreadExecutor()` như hiện tại.
- `onCursorChanged` debounce 50–100 ms ở phía JS để không spam bridge.
- `onDirtyChanged` chỉ gọi khi giá trị đổi, không gọi mỗi transaction.
- `evaluateJavascript` phải gọi trên main thread.
- Khi Activity `onStop`, gọi `renderer.snapshot("background")` nếu dirty để ghi recovery; không
  chặn UI quá 100 ms.

### 8.8. Bảo mật bridge

- Chỉ add interface cho WebView của editor, không add cho WebView OpenCode.
- Tên interface không đoán được? Không cần bảo mật bằng obscurity, nhưng dùng prefix
  `OpenCodeEditorBridge` và chỉ expose method cần thiết.
- Không expose `File`, `Context`, `WebView`, hay bất kỳ object nào có thể leo thang.
- Validate `url` trong `onLink`: chỉ `https:`, `http:`, `mailto:`; mở bằng `Intent` sau khi
  người dùng xác nhận nếu là link ngoài.
- Từ chối `javascript:`, `file:`, `content:`, `intent:`.
- Giới hạn `text.length <= 4 * 1024 * 1024` (lớn hơn `MAX_EDITABLE_BYTES` một chút để an toàn).
- Không log nội dung tệp; log tối đa độ dài và tên tệp.
- `onRenderProcessGone` → đóng WebView, fallback native, không crash app.

---

## 9. Nhận diện ngôn ngữ

### 9.1. Registry phía Kotlin

Tạo `EditorLanguageRegistry.kt` với dữ liệu tĩnh:

```kotlin
data class EditorLanguage(
    val id: String,             // "kotlin", "javascript", "plaintext"
    val displayName: String,    // "Kotlin"
    val chunk: String?,         // "chunks/legacy-clike.js", null = plaintext
    val extensions: Set<String>,
    val filenames: List<Regex>,
    val aliases: Set<String>,
)
```

Thứ tự ưu tiên khi resolve:

1. Tên tệp chính xác (không phân biệt hoa thường): `Dockerfile`, `Makefile`, `Gemfile`,
   `CMakeLists.txt`, `.gitignore`, `.editorconfig`, `.env`.
2. Regex tên tệp: `^Dockerfile\..*$`, `^.*\.d\.ts$`.
3. Extension dài nhất: `.d.ts` trước `.ts`; `.blade.php` trước `.php`.
4. Shebang ở dòng đầu (đọc tối đa 256 byte): `#!/usr/bin/env python3`, `#!/bin/bash`,
   `#!/usr/bin/env node`.
5. Alias người dùng chọn (lưu theo path).
6. Fallback `plaintext`.

Registry là **nguồn duy nhất** cho cả Kotlin và JS: Kotlin resolve rồi gửi `language.id`;
JS chỉ map id → hàm `import()` chunk. Không để JS tự đoán lại, tránh hai bên lệch nhau.

### 9.2. Bảng ngôn ngữ MVP

| Nhóm | Ngôn ngữ | Nguồn CodeMirror |
| --- | --- | --- |
| Bắt buộc | Plain text | không cần |
| Bắt buộc | Kotlin | `@codemirror/legacy-modes/mode/clike` (`kotlin`) |
| Bắt buộc | Java | `@codemirror/lang-java` |
| Bắt buộc | XML | `@codemirror/lang-xml` |
| Bắt buộc | JSON | `@codemirror/lang-json` (JSONC dùng legacy hoặc plain text) |
| Bắt buộc | YAML | `@codemirror/lang-yaml` |
| Bắt buộc | Markdown | `@codemirror/lang-markdown` |
| Bắt buộc | JavaScript / TypeScript / JSX / TSX | `@codemirror/lang-javascript` |
| Bắt buộc | HTML | `@codemirror/lang-html` |
| Bắt buộc | CSS / SCSS / LESS | `@codemirror/lang-css`, `@codemirror/lang-sass`, `@codemirror/lang-less` |
| Bắt buộc | Shell (bash/sh/zsh) | `@codemirror/legacy-modes/mode/shell` |
| Bắt buộc | Python | `@codemirror/lang-python` |
| Bắt buộc | Gradle (Groovy/Kotlin DSL) | legacy groovy + Kotlin |
| Bắt buộc | Properties / INI / .gitconfig | legacy properties |
| Bắt buộc | TOML | legacy toml |
| Bắt buộc | Dockerfile | legacy dockerfile |
| Bắt buộc | Diff | legacy diff |
| Nên có | C / C++ | `@codemirror/lang-cpp` |
| Nên có | Go | `@codemirror/lang-go` |
| Nên có | Rust | `@codemirror/lang-rust` |
| Nên có | SQL | `@codemirror/lang-sql` |
| Tuỳ chọn | PHP | `@codemirror/lang-php` |

Các ngôn ngữ khác có thể thêm sau bằng một chunk mới + một dòng trong registry, không cần
đụng Kotlin UI.

### 9.3. Trường hợp đặc biệt

- **Kotlin**: dùng legacy mode trước. Legacy mode là stream tokenizer, không có parser tree đầy
  đủ, nên fold/bracket có thể kém chính xác hơn Lezer. Có thể thay bằng grammar Lezer tự build
  hoặc grammar từ cộng đồng ở phase sau; registry chỉ cần đổi `chunk`.
- **Gradle Kotlin DSL (`build.gradle.kts`)**: resolve thành `kotlin`; Gradle Groovy
  (`build.gradle`) resolve thành `groovy`.
- **`.gitignore`, `.env`, `.editorconfig`**: plain text hoặc properties; ưu tiên tên chính xác.
- **Tệp không có extension**: thử shebang; nếu không, plain text.
- **Tệp rất lớn**: nếu > 512 KB hoặc > 20.000 dòng, tự động tắt highlight/fold/indent guides
  (gửi `language = "plaintext"` tạm thời) để giữ tốc độ; vẫn cho edit.
- **Người dùng chọn sai ngôn ngữ**: lưu override theo path trong `OpenCodeSettings`; có action
  "Chọn ngôn ngữ" trong menu overflow.

---

## 10. Tính năng editor

### 10.1. MVP (Phase 2)

| Tính năng | Cách làm |
| --- | --- |
| Syntax highlighting | `@codemirror/language` + Lezer/legacy grammar, `HighlightStyle` từ palette |
| Line numbers | `lineNumbers()` của `@codemirror/view` |
| Active line + gutter | `highlightActiveLine()`, `highlightActiveLineGutter()` |
| Bracket matching | `bracketMatching()` |
| Auto-close + auto-indent | `closeBrackets()`, `indentOnInput()` |
| Undo/redo | `history()` + keymap; toolbar gọi `undo()`/`redo()` |
| Search / replace | `@codemirror/search`; native search bar điều khiển |
| Status line/column | `onCursorChanged` + selection |
| Read-only | `EditorState.readOnly` + `EditorView.editable` + input guard |
| Dirty tracking | so `state.doc` với `savedDoc` |
| Save/conflict | giữ nguyên `TextFileIO` |
| Theme light/dark/dynamic | JSON từ `OpenCodeWebTheme` |
| Word wrap | `EditorView.lineWrapping` bật/tắt bằng `Compartment` |

### 10.2. Phase 3

| Tính năng | Cách làm |
| --- | --- |
| Fold | `foldGutter()` + `codeFolding()`; Kotlin legacy dùng indentation folding |
| Multi-cursor | `EditorState.allowMultipleSelections`, `rectangularSelection`, `crosshairCursor` |
| Selection matches | `highlightSelectionMatches()` |
| Bracket/indent guides | port bản nhẹ từ Acode hoặc tự viết |
| Rainbow brackets | port `rainbowBrackets.ts` bản nhẹ, tắt mặc định |
| Local word completion | `@codemirror/autocomplete` với `completeAnyWord` |
| Language completion | `languageData.autocomplete` từ grammar |
| Go to line | dialog native → `gotoLine()` |
| Toggle comment | `toggleComment()` của `@codemirror/commands` |
| Indent/outdent | `indentMore`/`indentLess` |
| Duplicate/move line | command keymap |
| Recent files | `OpenCodeSettings` + danh sách trong Files/editor |
| Tab/session | `EditorSession` giữ nhiều buffer; xem 13.2 |

### 10.3. Phase 4 (tuỳ chọn)

- Markdown preview (xem 13.3).
- Format on save / format command với Prettier? **Không** mang Prettier vào APK ngay; nếu cần,
  chỉ format cho JSON/XML bằng parser có sẵn, hoặc gọi binary local nếu người dùng cài.
- Lint gutter: chỉ khi có nguồn lỗi (ví dụ JSON parse error) — có thể dùng `linter()` với
  parser của CodeMirror để báo lỗi cú pháp, không cần LSP.
- LSP: để ngỏ, không hứa trong tài liệu này.

### 10.4. Không làm để tránh phình

- Emmet.
- Color preview.
- Sticky scroll.
- Canvas/WebGL renderer.
- Theme marketplace.
- Plugin system.
- Autosave mặc định (có thể là setting explicit, nhưng không bật mặc định).
- Telemetry.

---

## 11. Vòng đời, xoay màn hình, process death

### 11.1. Xoay màn hình

- `AndroidManifest.xml` khai báo `configChanges` gồm `orientation|screenSize|screenLayout|
  smallestScreenSize|keyboardHidden|navigation`, nên Activity **không recreate** khi xoay.
- WebView là view của Activity, không bị detach; buffer và undo history giữ nguyên.
- Chỉ cần `onConfigurationChanged` cập nhật theme/setting nếu palette đổi (thường không đổi khi
  xoay, trừ khi hệ thống đổi dark mode).
- Bàn phím: thêm `android:windowSoftInputMode="adjustResize"` cho Activity để WebView co lại
  đúng khi IME mở. Phải test lại OpenCode Web UI vì setting này ảnh hưởng toàn Activity.

### 11.2. Đổi dark/light hoặc dynamic color

Manifest hiện không khai báo `uiMode`, nên đổi dark/light sẽ recreate Activity.

Đề xuất:

1. Trong `onSaveInstanceState`/controller, lưu snapshot nếu dirty (text + selection + scroll +
   version + baseHash).
2. Sau recreate, `EditorScreen` được tạo lại; nếu có snapshot cho cùng path:
   - `TextFileIO.load` lại metadata/hash.
   - Nếu hash trên đĩa vẫn bằng `baseHash` của snapshot → dùng snapshot text (không mất thay
     đổi), gửi `loadDocument` với text đó.
   - Nếu hash khác → hiện dialog "Tệp đã thay đổi trên đĩa" trước khi khôi phục.
3. Nếu không dirty, chỉ load lại từ đĩa và khôi phục selection/scroll nếu nội dung không đổi.

### 11.3. Process death

- Android có thể giết tiến trình khi ở background. `EditText` hiện tại cũng mất buffer nếu
  không lưu; đây không phải regression, nhưng có thể cải thiện.
- Đề xuất recovery nhẹ:
  - Khi `onStop` và dirty, ghi JSON `{path, baseHash, text, selection, scroll, savedAt}` vào
    `cacheDir/editor-recovery/`.
  - Khi mở lại tệp, nếu recovery tồn tại, `baseHash` khớp hash hiện tại, và `savedAt` mới hơn
    lần sửa cuối → hỏi "Khôi phục thay đổi chưa lưu?".
  - Xoá recovery sau khi lưu thành công hoặc người dùng chọn bỏ.
  - Giới hạn: chỉ một recovery cho mỗi path, tổng dung lượng ≤ 8 MB; xoá cũ nhất.
- Không mã hoá; thư mục cache là app-private. Không backup (app đã `allowBackup=false`).

### 11.4. Đóng editor

- Nếu dirty: dialog Lưu / Không lưu / Huỷ (giữ nguyên hiện tại).
- Nếu chọn Lưu: `save(after = { close() })`.
- `close()` gọi `renderer.dispose()` chỉ khi rời hẳn editor (không phải khi chỉ ẩn panel để mở
  Git changes?).
- Nếu giữ WebView sống khi ẩn panel, lần mở sau nhanh hơn nhưng tốn bộ nhớ. Đề xuất:
  - Giữ WebView khi người dùng mở/đóng nhanh trong cùng Activity.
  - `dispose()` trong `onDestroy` và khi người dùng bấm back về Files (giải phóng bộ nhớ).
  - Có thể thêm timeout 5 phút không dùng thì dispose; không cần ở MVP.

---

## 12. Fallback và giới hạn

### 12.1. Khi nào dùng native

| Điều kiện | Hành vi |
| --- | --- |
| `OpenCodeSettings.editorRenderer == "native"` | Dùng `EditText` |
| WebView không có hoặc `WebViewCompat.getCurrentWebViewPackage()` là null | Native |
| Trang không gọi `onReady` trong 3 giây | Native + snackbar "Đã chuyển sang trình sửa đơn giản" |
| `onError("unsupported")` từ capability probe | Native |
| `onRenderProcessGone` | Native, tạo lại WebView ở lần mở sau |
| Người dùng bật "Chế độ đơn giản"/trợ năng | Native |
| Tệp > 2 MiB / nhị phân | Placeholder read-only (không renderer) |
| Tệp > 512 KB hoặc > 20.000 dòng | CodeMirror nhưng tắt highlight/fold/guides; hoặc native nếu chậm |

### 12.2. Capability probe

Tách một `probe.js` rất nhỏ, chỉ dùng cú pháp cơ bản (không `eval`, không
`new Function` vì CSP `script-src 'self'` sẽ chặn):

```html
<script src="./probe.js"></script>
<script type="module">
  import "./editor.js";
</script>
```

`probe.js` kiểm tra các API tối thiểu:

```js
(function () {
  var ok = !!(window.Promise && window.Map && window.Set &&
              window.WeakMap && window.Symbol && window.globalThis &&
              window.ResizeObserver && window.Intl && window.CSS);
  window.__ocEditorSupported = ok;
  if (!ok && window.OpenCodeEditorBridge) {
    window.OpenCodeEditorBridge.onError("unsupported", "WebView quá cũ");
  }
})();
```

Trong `editor.js`, nếu `window.__ocEditorSupported !== true` thì dừng ngay, không khởi tạo
CodeMirror. Nếu `import()` hoặc khởi tạo ném lỗi, `window.onerror`/`unhandledrejection` gọi
`onError("init", message)`. Native có timeout 3 giây để fallback nếu không nhận `onReady`.

### 12.3. Tệp lớn

- `TextFileIO.MAX_EDITABLE_BYTES = 2 MiB` giữ nguyên cho edit.
- Với tệp 512 KB–2 MiB: vẫn mở CodeMirror nhưng:
  - `language = "plaintext"` nếu > 512 KB.
  - Tắt `foldGutter`, `indentGuides`, `highlightSelectionMatches`.
  - Tắt `highlightActiveLine` nếu > 1 MiB.
- Với tệp > 2 MiB: giữ placeholder read-only + nút "Mở bằng ứng dụng khác".
- Tương lai: có thể thêm chế độ "xem tệp lớn" chỉ đọc, chia đoạn; không làm bây giờ.

### 12.4. Trợ năng

CodeMirror trong WebView thường hoạt động với TalkBack ở mức cơ bản nhưng không bằng
`EditText`:

- Ô soạn thảo là `contenteditable`, TalkBack đọc được dòng nhưng điều hướng theo ký tự có thể
  khó.
- Selection/caret có thể không được thông báo.

Đề xuất:

- Thêm setting "Trình sửa đơn giản (trợ năng)" trong Cài đặt → Trình sửa.
- Nếu `AccessibilityManager.isTouchExplorationEnabled` và người dùng chưa từng chọn, hiện
  banner một lần: "Bạn đang dùng TalkBack. Chuyển sang trình sửa đơn giản?" với hai nút.
- Không tự động chuyển mà không hỏi, tránh làm người dùng mất tính năng.
- Đảm bảo toolbar native vẫn là nguồn điều khiển chính (Save, Search, Undo, Redo) để TalkBack
  dùng được dù đang ở WebView.
- Thêm `contentDescription` cho WebView: "Vùng soạn thảo mã, tệp X".
- Kiểm tra font scale 200%: CodeMirror dùng `fontSize` từ setting, nên phải nhân với
  `configuration.fontScale` hoặc dùng đơn vị `rem` với root font size do Kotlin đặt.

---

## 13. Mở rộng cấp tệp

### 13.1. Tệp gần đây

- Lưu tối đa 20 path trong `OpenCodeSettings` dưới dạng JSON array, mới nhất trước.
- Chỉ lưu path còn tồn tại; dọn khi mở Files/Settings.
- Hiển thị:
  - Một section "Gần đây" ở đầu Files khi thư mục hiện tại là home? Hoặc trong editor overflow.
  - Trong editor: menu overflow → "Tệp gần đây" mở bottom sheet/danh sách.
- Không lưu nội dung, chỉ path + thời gian.

### 13.2. Tab / phiên nhiều tệp

Đây là thay đổi lớn, chỉ làm sau khi MVP ổn định.

Mô hình đề xuất:

```kotlin
class EditorSession {
    data class Tab(
        val path: String,
        val document: TextDocument,
        val text: String,          // buffer hiện tại (có thể dirty)
        val savedText: String,     // text tại lần load/save
        val selection: Selection,
        val scroll: Scroll,
        val languageId: String,
    )

    val tabs: List<Tab>
    var activeIndex: Int
}
```

- Khi chuyển tab: `renderer.snapshot("tab-switch")` cho tab cũ, `renderer.loadDocument` cho tab
  mới.
- Giới hạn 8 tab; tab dirty hiện dấu chấm.
- Đóng tab dirty → dialog như đóng editor.
- Khôi phục phiên sau process death: chỉ khôi phục path + selection/scroll, không khôi phục
  text dirty trừ khi có recovery.
- UI: dải tab ngang dưới toolbar, hoặc dropdown trong toolbar nếu hẹp. Ưu tiên dropdown để
  không chiếm chiều cao trên điện thoại.

### 13.3. Xem trước Markdown

- Acode dùng `markdown-it` + `DOMPurify` (xem `package.json` và `src/pages/markdownPreview`).
- Đề xuất Phase 4:
  - Thêm chunk `markdown-preview.js` dùng `markdown-it` + `DOMPurify` (hoặc một renderer nhỏ
    hơn) chạy trong cùng WebView editor, chế độ `preview`.
  - Hoặc một WebView riêng chỉ để preview, không có bridge quyền ghi.
- Bảo mật:
  - Sanitize HTML bằng DOMPurify.
  - CSP `default-src 'none'; img-src 'self' data: file:; style-src 'self' 'unsafe-inline'`.
  - Không cho script trong Markdown chạy.
  - Link ngoài mở qua native với xác nhận.
  - Ảnh local: nếu muốn hiển thị, cần `allowFileAccess` giới hạn; đây là rủi ro. Có thể chỉ
    hiển thị ảnh `data:` và ảnh trong cùng thư mục nếu dùng custom loader. Phase 4 có thể chỉ
    render text/code/link, chưa hỗ trợ ảnh local.
- Toggle Preview/Edit trên toolbar; giữ vị trí scroll tương đối nếu có thể.

### 13.4. Màu nhận diện ngôn ngữ trong Files (tuỳ chọn)

Ngoài syntax highlighting, có thể tô màu icon/tên tệp theo ngôn ngữ trong `FilesScreen`:

- Thêm `EditorLanguageRegistry.colorFor(language)` trả một màu từ palette (primary, tertiary,
  secondary…), không hard-code hex.
- Row tệp hiện dùng icon trong tonal container; có thể đổi tint theo nhóm ngôn ngữ.
- Không bắt buộc cho MVP; nếu làm, phải giữ độ tương phản và không phá Material You.

### 13.5. Tích hợp Git

- Giữ nguyên: sau khi lưu, nếu trong repo và setting auto-commit bật → commit local.
- Snackbar "Đã lưu và commit trên máy" + action "Thay đổi" mở `GitChangesScreen`.
- Không tự push.
- Có thể hiển thị chip "trong repo" ở status bar như hiện tại.
- Không thêm staging/commit UI vào editor; đã có màn hình riêng.

---

## 14. Build, CI và giấy phép

### 14.1. Thay đổi Gradle

- Không thêm dependency Java/Kotlin mới (đã có `androidx.webkit`).
- Asset nằm trong `app/src/main/assets/editor/`, Gradle tự đóng gói.
- Nếu cần giảm dung lượng, bật `aaptOptions.ignoreAssetsPattern` cho file nguồn
  (`tools/editor/node_modules`) — nhưng các file đó không nằm trong `assets`, nên không cần.
- `build.gradle.kts` có thể thêm task kiểm tra sự tồn tại của `editor.js` và `index.html` để
  fail sớm nếu quên commit asset:

```kotlin
val checkEditorAssets = tasks.register("checkEditorAssets") {
    doLast {
        val dir = layout.projectDirectory.dir("src/main/assets/editor").asFile
        check(dir.resolve("index.html").isFile) { "Thiếu editor/index.html" }
        check(dir.resolve("editor.js").isFile) { "Thiếu editor/editor.js" }
    }
}
tasks.named("preBuild") { dependsOn(checkEditorAssets) }
```

### 14.2. CI

Workflow `Debug build` hiện chạy `:app:testDebugUnitTest` rồi `:app:assembleDebug`. Không cần
đổi để build được. Nên thêm (phase 0/1):

- Job `editor-bundle` (optional, `workflow_dispatch` hoặc path filter `tools/editor/**`):
  `npm ci && npm run build && git diff --exit-code -- app/src/main/assets/editor`.
- Nếu không muốn thêm Node vào CI chính, để job riêng và không chặn build Android.

### 14.3. Giấy phép

- CodeMirror 6, Lezer: MIT.
- `@codemirror/language-data` và các grammar: MIT (một số grammar có thể có license khác; kiểm
  tra từng package).
- Nếu port `indentGuides.ts`, `rainbowBrackets.ts` từ Acode: Acode là MIT; giữ header ghi
  nguồn và bản `LICENSE` của Acode trong `licenses/`.
- Không dùng theme/asset của Acode nếu không có license rõ ràng; chỉ dùng CodeMirror theme
  hoặc tự viết `HighlightStyle`.

### 14.4. Phiên bản

- Pin chính xác trong `tools/editor/package.json`, ví dụ:

```json
{
  "dependencies": {
    "@codemirror/state": "6.7.6",
    "@codemirror/view": "6.43.14",
    "@codemirror/language": "6.13.1",
    "@codemirror/commands": "6.11.0",
    "@codemirror/search": "6.7.1",
    "@codemirror/autocomplete": "6.20.3",
    "@codemirror/lang-javascript": "6.2.5",
    "@codemirror/lang-html": "6.4.12",
    "@codemirror/lang-css": "6.3.1",
    "@codemirror/lang-sass": "6.0.2",
    "@codemirror/lang-less": "6.0.2",
    "@codemirror/lang-json": "6.0.2",
    "@codemirror/lang-markdown": "6.5.2",
    "@codemirror/lang-xml": "6.1.0",
    "@codemirror/lang-yaml": "6.1.3",
    "@codemirror/lang-java": "6.0.2",
    "@codemirror/lang-python": "6.2.1",
    "@codemirror/lang-cpp": "6.0.3",
    "@codemirror/lang-go": "6.0.1",
    "@codemirror/lang-rust": "6.0.2",
    "@codemirror/lang-sql": "6.10.0",
    "@codemirror/legacy-modes": "6.5.3",
    "@lezer/highlight": "1.2.5"
  }
}
```

- Không dùng `^` để tránh bundle thay đổi ngoài ý muốn khi build lại.
- Ghi version trong `tools/editor/README.md` và trong commit message khi nâng.

---

## 15. Kiểm thử và tiêu chí chất lượng

### 15.1. Unit test Kotlin (chạy được trên CI)

- `EditorLanguageRegistryTest`:
  - `.kt` → Kotlin, `.kts` → Kotlin, `build.gradle` → Groovy, `build.gradle.kts` → Kotlin.
  - `Dockerfile`, `Makefile`, `.gitignore`, `.editorconfig`, `.env`.
  - `.d.ts` thắng `.ts`.
  - Shebang `#!/usr/bin/env python3` → Python.
  - Không có extension → plaintext.
- `EditorThemeBuilderTest`:
  - JSON có đủ key `colors`, `syntax`, `markdown`.
  - Không có giá trị rỗng; dark/light khác nhau.
- `EditorBridgeProtocolTest`:
  - Serialize/deserialize `DocumentLoad`, `EditorSnapshot`.
  - Từ chối version sai, text quá lớn, URL `javascript:`.
- `TextFileIOTest` (nếu có thể tách khỏi Android):
  - UTF-8/BOM/UTF-16/newline/atomic/hash giữ nguyên như hiện tại.
  - `normalizeNewlines` trước khi ghi.
- Không cần test WebView trong unit test; dùng instrumentation hoặc thủ công.

### 15.2. Kiểm thử trên thiết bị

| Nhóm | Ca kiểm thử |
| --- | --- |
| Ngôn ngữ | `.kt`, `.java`, `.xml`, `.json`, `.yaml`, `.md`, `.js`, `.ts`, `.html`, `.css`, `.py`, `.sh`, `.gradle`, `.properties`, `.toml`, `Dockerfile`, `Makefile`, tệp không extension |
| Encoding | UTF-8, UTF-8 BOM, UTF-16LE/BE BOM, Windows-1252 (read-only) |
| Newline | LF, CRLF, CR, hỗn hợp |
| Kích thước | 0 B, 1 KB, 100 KB, 1.9 MB, 2.1 MB, tệp nhị phân |
| Quyền | Tệp chỉ đọc, tệp trong thư mục chỉ đọc |
| Bàn phím | Telex, VNI, tiếng Nhật, emoji, bàn phím cứng, Enter/Tab/Backspace |
| IME | `adjustResize`, không che dòng đang sửa, không nhảy scroll |
| Theme | Light, dark, dynamic color, đổi wallpaper, font scale 100/150/200% |
| Trợ năng | TalkBack, chuyển chế độ đơn giản, toolbar đọc được |
| Xoay | Xoay khi dirty, xoay khi search mở, xoay khi bàn phím mở |
| Vòng đời | Background/foreground, process death, mở app ngoài rồi quay lại |
| Lưu | Lưu thường, lưu khi conflict, ghi đè, tải lại, auto-commit |
| Hiệu năng | Mở 100 KB < 300 ms; gõ không giật; cuộn 10.000 dòng mượt |
| Bảo mật | Không request mạng; link ngoài mở qua xác nhận; `javascript:` bị chặn |
| Fallback | WebView cũ/không có; renderer crash; setting native; tệp lớn |

### 15.3. Ngân sách hiệu năng

| Chỉ số | Ngân sách |
| --- | --- |
| Thời gian mở tệp 100 KB | ≤ 300 ms (không tính I/O chậm) |
| Thời gian gõ → hiển thị | ≤ 16 ms mỗi ký tự ở tệp 100 KB |
| Bộ nhớ tăng thêm | ≤ 80 MB khi editor mở |
| APK tăng | ≤ 3 MB trước nén / ≤ 1 MB trong APK |
| Bridge message | ≤ 4 MiB; text chỉ gửi khi load/save/recovery |
| Thời gian lưu 1 MB | ≤ 500 ms trên thiết bị trung bình |

### 15.4. Tiêu chí nghiệm thu MVP

1. Mở `.kt` trong repo → thấy màu cú pháp, số dòng, active line, bracket matching.
2. Sửa, undo/redo, search next/prev, lưu; nội dung đúng, encoding/BOM/newline giữ nguyên.
3. Sửa tệp bằng app ngoài rồi quay lại → conflict dialog hoạt động.
4. Xoay màn hình khi dirty → không mất buffer, không mất vị trí con trỏ.
5. Bật chế độ đơn giản → quay về `EditText` với đầy đủ chức năng cũ.
6. WebView lỗi/không hỗ trợ → tự fallback, có thông báo.
7. Tệp > 2 MiB/nhị phân → placeholder read-only như hiện tại.
8. Không có request mạng từ trang editor.
9. Build CI xanh; APK tăng trong ngân sách.
10. Không regression OpenCode WebView (mở/đóng editor không reload web UI).

---

## 16. Lộ trình triển khai

### Phase 0 — Nền móng, không đổi hành vi người dùng

- Tách `OpenCodeWebTheme.tokens(context)` và `EditorThemeBuilder`.
- Thêm `EditorLanguageRegistry` + unit test.
- Thêm `EditorRenderer` interface; bọc code `EditText` hiện tại thành
  `NativeEditorRenderer` (refactor cơ học).
- Thêm feature flag `editorRenderer` (`auto|native|web`), mặc định `auto` nhưng Phase 0 vẫn
  dùng native.
- Tạo `tools/editor` + bundle skeleton (chưa nối UI).
- Thêm `checkEditorAssets`.

**Nghiệm thu:** build xanh; hành vi editor y hệt; unit test registry/theme chạy.

### Phase 1 — CodeMirror chỉ đọc

- Trang `index.html` + CodeMirror 6 + theme + language detection.
- `CodeMirrorEditorRenderer` load tài liệu read-only, chưa cho sửa.
- Nút ẩn trong menu overflow: "Xem trước bằng trình sửa mới" (debug), hoặc bật qua setting.
- Đo thời gian mở, dung lượng, lỗi WebView.
- Fallback native nếu lỗi.

**Nghiệm thu:** mở được các ngôn ngữ MVP, màu đúng palette, không crash; chưa dùng làm mặc
định.

### Phase 2 — Editing MVP, mặc định cho tệp nhỏ

- Bật edit, save, conflict, search, undo/redo, status, dirty.
- Native search bar điều khiển CodeMirror.
- `editorRenderer=auto` dùng CodeMirror cho tệp ≤ 512 KB và không phải read-only.
- Giữ native cho tệp lớn/không hỗ trợ và cho setting "đơn giản".
- Thêm recovery cơ bản nếu kịp.

**Nghiệm thu:** toàn bộ mục 15.4; kiểm tra IME/rotation trên thiết bị thật.

### Phase 3 — Tính năng code editor

- Fold, multi-cursor, selection matches, indent guides, rainbow brackets (tắt mặc định).
- Autocomplete local word + language.
- Go to line, toggle comment, indent/outdent.
- Tệp gần đây, chọn ngôn ngữ thủ công.
- Cân nhắc tab/session.

**Nghiệm thu:** các thao tác quen thuộc hoạt động; không tăng thời gian mở quá 20%.

### Phase 4 — Mở rộng

- Markdown preview.
- Lint cú pháp nhẹ (JSON/XML) nếu cần.
- Tab/session hoàn chỉnh (nếu chưa làm ở Phase 3).
- Màu nhận diện ngôn ngữ trong Files (tuỳ chọn).
- Đánh giá LSP/format như một quyết định riêng.

**Nghiệm thu:** tính năng mới không làm chậm MVP; tài liệu cập nhật.

### 16.1. Rollback

- Mọi phase đều có thể quay lại bằng `OpenCodeSettings.editorRenderer = "native"`.
- Không xoá `NativeEditorRenderer` và layout cũ cho tới khi Phase 3 ổn định trên thiết bị.
- Asset CodeMirror có thể bị bỏ khỏi APK nếu tắt hoàn toàn, nhưng giữ lại để bật lại nhanh.

---

## 17. Rủi ro và câu hỏi cần chốt

### 17.1. Rủi ro

| Rủi ro | Mức | Giảm thiểu |
| --- | --- | --- |
| WebView trên API 26 quá cũ | Trung bình | Capability probe + fallback native + test WebView tối thiểu |
| IME/contenteditable lỗi với Telex/VNI | Trung bình–cao | Test sớm ở Phase 1; fallback native; báo lỗi qua bridge |
| TalkBack kém hơn `EditText` | Cao với người dùng trợ năng | Chế độ đơn giản + banner; giữ toolbar native |
| Hai WebView tốn bộ nhớ | Trung bình | Disposal khi đóng; đo memory; có thể dùng chung process |
| Bridge đồng bộ text sai | Cao | Chỉ gửi text khi save/load; version + hash; unit test protocol |
| Mất dữ liệu khi process death | Trung bình | Recovery cache + dialog khôi phục |
| Bundle trôi phiên bản | Trung bình | Pin version + CI verify + hash |
| APK tăng quá ngân sách | Trung bình | Cắt ngôn ngữ ít dùng; lazy chunk |
| Theme lệch native/web | Thấp | Một nguồn `OpenCodeWebTheme.tokens` |
| CSP chặn CodeMirror | Thấp | Test sớm; chỉ `style-src 'unsafe-inline'` |
| Link/asset local bị lộ | Thấp | WebViewAssetLoader + chặn điều hướng |
| CodeMirror đổi API | Thấp | Pin version; không auto-update |
| Không có môi trường build local | Cao (quy trình) | Chỉ push khi CI; test trên thiết bị thật |

### 17.3. Trạng thái thực hiện (đánh dấu 2026-11-05)

| Hạng mục | Trạng thái | Ghi chú |
| --- | --- | --- |
| Phase 0: `OpenCodeWebTheme.tokens()` / theme builder | ✅ Đã làm | `Tokens`, `syntax/markdown/editor` maps, `--syntax-function`. |
| Phase 0: `EditorLanguageRegistry` | ✅ Đã làm | Filename/regex/extension/shebang, override qua settings; chưa có unit test chạy. |
| Phase 0: `EditorRenderer` + native wrapper | ✅ Đã làm | `NativeEditorRenderer` giữ fallback EditText. |
| Phase 0: `tools/editor` + bundle skeleton | ✅ Đã làm | esbuild + code splitting + asset copy + SHA256. |
| Phase 0: `checkEditorAssets` | ✅ Đã làm | Wired vào `preBuild`/asset merge. |
| Phase 1: trang CodeMirror + theme + load | ✅ Đã làm (chưa QA thiết bị) | Local asset, `WebViewAssetLoader`, bridge. |
| Phase 2: edit/save/conflict/search/undo/status | ⚠️ Một phần, đã đấu nối cốt lõi | `EditorScreen` dùng renderer snapshot + `TextFileIO`; search/goto/language/recent/simple mode có trong UI; chưa verify IME/rotation/thiết bị thật. |
| Phase 2: fallback/readiness | ⚠️ Một phần | Fallback khi renderer error/no WebView/simple mode; chưa có timeout readiness riêng cho thiết bị chậm. |
| Phase 3: fold/multi-cursor/autocomplete/search nâng cao | ⚠️ Nền tảng có, chưa hoàn thiện UX | CodeMirror cung cấp nhiều tính năng; chưa productize đầy đủ (rainbow/indent guides toggles, tab/session đầy đủ). |
| Phase 4: Markdown preview/LSP/format | ⏳ Chưa làm | Ngoài phạm vi triển khai hiện tại. |
| Recovery snapshot bền vững | ⏳ Chưa làm | Chưa lưu snapshot ra đĩa/khôi phục sau process death. |
| Unit tests (registry/theme/protocol/TextFileIO) | ⏳ Chưa làm/chạy | Dự án chưa có hạ tầng test; chưa verify compile/test. |
| Build Android/CI xanh | ⏳ Chưa xác minh | Môi trường hiện tại không có `JAVA_HOME`/SDK để chạy Gradle. |
| QA thiết bị (IME/encoding/newline/xoay/vòng đời/fallback) | ⏳ Chưa xác minh | Cần thiết bị/WebView thật. |
| APK size/perf budget | ⏳ Chưa đo | Cần build release/benchmark thiết bị. |

### 17.2. Câu hỏi cần chốt (đã chọn tạm trong triển khai)

1. **Renderer mặc định:** **Đã chọn tạm (code):** `auto` ưu tiên CodeMirror cho tệp thường ≤ 1.5 MB bridge budget; `native`/`web`/simple mode có trong settings/menu. Chưa có rollout opt-in theo phase trên production.
2. **Giữ editor native:** **Đã chọn:** giữ `NativeEditorRenderer` làm fallback kỹ thuật + chế độ đơn giản (menu/setting), không xoá.
3. **Bộ ngôn ngữ:** **Đã chọn tạm:** triển khai registry/bundle cho các ngôn ngữ thực dụng trong repo/app (Kotlin, Java, XML, JSON/JSONC, YAML, Markdown, JS/TS/JSX/TSX, HTML, CSS/SCSS/Less, Shell, Python, Groovy/Gradle, properties/INI/env, TOML, Dockerfile, diff, C/C++, Go, Rust, SQL, PHP, C#/Scala/Dart/Objective-C qua loader/registry tương ứng) — một số dùng Lezer, một số dùng legacy stream parser; cần QA độ chính xác tô màu.
   Properties, TOML, Dockerfile, Diff, C/C++, Go, Rust, SQL).
4. **Tab/session:** có nằm trong phạm vi bản này không? Đề xuất: Phase 3, sau khi MVP ổn định.
5. **Markdown preview:** có cần ảnh local không? Nếu có, phải thiết kế loader an toàn; nếu
   không, chỉ render text/code/link.
6. **Lint/format:** chỉ lint cú pháp nhẹ hay chờ LSP? Đề xuất: lint nhẹ ở Phase 4, LSP tách
   tài liệu riêng.
7. **Autosave:** có thêm setting không? Đề xuất: không bật mặc định; chỉ explicit save.
8. **Màu icon tệp trong Files:** có làm không? Đề xuất: tuỳ chọn, sau MVP.
9. **Ngưỡng lớn:** giữ 512 KB cho highlight hay nâng lên 1 MB? Đề xuất: 512 KB, đo lại trên
   thiết bị.
10. **WebView dùng chung hay riêng:** Đề xuất riêng để cô lập bảo mật và tránh reload OpenCode.

---

## 18. Tiêu chí nghiệm thu tổng

- [ ] Mở tệp text trong app thấy màu cú pháp đúng cho ít nhất 15 ngôn ngữ/định dạng phổ biến.
- [ ] Có số dòng, active line, bracket matching, auto-indent, auto-close, undo/redo, search/
      replace, fold, multi-cursor (MVP + Phase 3).
- [ ] Lưu giữ nguyên encoding, BOM, kiểu newline; ghi atomic; phát hiện conflict bằng SHA-256.
- [ ] Không gửi toàn bộ text qua bridge mỗi lần gõ; snapshot chỉ khi load/save/recovery.
- [ ] Theme lấy từ `OpenCodeWebTheme.tokens`; light/dark/dynamic đúng, không hard-code.
- [ ] Tệp > 2 MiB, nhị phân, encoding lạ, không có quyền → read-only như hiện tại.
- [ ] WebView lỗi/cũ → fallback native; có setting "chế độ đơn giản" cho trợ năng.
- [ ] Xoay màn hình, IME mở, background/foreground, process death không mất buffer (hoặc có
      recovery).
- [ ] Trang editor không request mạng; link ngoài mở qua xác nhận; CSP chặt.
- [ ] APK tăng ≤ 3 MB trước nén / ≤ 1 MB trong APK; thời gian mở tệp 100 KB ≤ 300 ms.
- [ ] Build CI xanh; unit test registry/theme/protocol chạy; không regression OpenCode WebView.

---

## Phụ lục A — Hợp đồng bridge (tóm tắt)

```text
Kotlin → JS  (window.OpenCodeEditor)
  init(EditorInit)
  loadDocument(DocumentLoad)
  unloadDocument()
  setReadOnly({readOnly, reason})
  setTheme(ThemePayload)
  setSettings(EditorSettings)
  setLanguage({id})
  requestSnapshot({reason})
  confirmSaved({version, hash})
  saveFailed({version, reason})
  find({query, caseSensitive, regexp, wholeWord})
  findNext({forward})
  clearFind()
  undo()
  redo()
  gotoLine({line})
  focus()
  restoreView({selection, scroll})
  ping()

JS → Kotlin  (window.OpenCodeEditorBridge)
  onReady(protocol, userAgent, capabilities)
  onDirtyChanged(dirty, length)
  onCursorChanged(line, column, selectedChars)
  onHistoryChanged(canUndo, canRedo)
  onSearchResult(count, index)
  onSnapshot(EditorSnapshot)
  onError(code, message)
  onLink(url, kind)
  onLanguageResolved(id, source)
  onFocusChanged(focused)
  onMetrics(payload)
```

## Phụ lục B — Map token màu

| Token | Dùng cho |
| --- | --- |
| `--syntax-comment` | comment, meta |
| `--syntax-keyword` | keyword, control, modifier, self |
| `--syntax-string` | string, regexp, character |
| `--syntax-primitive` | number, bool, null, atom |
| `--syntax-property` | propertyName, attributeName, label |
| `--syntax-type` | typeName, className, namespace, tagName |
| `--syntax-variable` | variableName, definition/local |
| `--syntax-function` (mới) | function, macroName |
| `--syntax-constant` | constant, enum member |
| `--syntax-operator` | operator |
| `--syntax-punctuation` | punctuation, bracket, separator |
| `--syntax-critical` | invalid, error |
| `--markdown-*` | heading, strong, emph, link, code, quote, list, rule, image |

## Phụ lục C — Tệp dự kiến thêm/sửa

**Thêm**

- `docs/EDITOR_CODE_HIGHLIGHTING_PROPOSAL.md` (tài liệu này)
- `app/src/main/assets/editor/index.html`
- `app/src/main/assets/editor/editor.css`
- `app/src/main/assets/editor/editor.js`
- `app/src/main/assets/editor/chunks/*.js`
- `app/src/main/assets/editor/licenses/*`
- `app/src/main/kotlin/com/opencode/android/EditorRenderer.kt`
- `app/src/main/kotlin/com/opencode/android/NativeEditorRenderer.kt`
- `app/src/main/kotlin/com/opencode/android/CodeMirrorEditorRenderer.kt`
- `app/src/main/kotlin/com/opencode/android/EditorBridge.kt`
- `app/src/main/kotlin/com/opencode/android/EditorLanguageRegistry.kt`
- `app/src/main/kotlin/com/opencode/android/EditorThemeBuilder.kt`
- `app/src/main/kotlin/com/opencode/android/EditorSession.kt` (Phase 3)
- `app/src/main/kotlin/com/opencode/android/EditorRecovery.kt` (Phase 2/3)
- `tools/editor/package.json`
- `tools/editor/package-lock.json`
- `tools/editor/build.mjs`
- `tools/editor/src/*.ts`
- `tools/editor/README.md`

**Sửa**

- `app/src/main/kotlin/com/opencode/android/EditorScreen.kt` — dùng `EditorRenderer`, bỏ logic
  `EditText` trực tiếp.
- `app/src/main/kotlin/com/opencode/android/TextFile.kt` — chỉ thêm hàm hỗ trợ chuẩn hoá
  newline cho editor nếu cần; không đổi hành vi.
- `app/src/main/kotlin/com/opencode/android/OpenCodeWebTheme.kt` — tách `tokens(context)`,
  thêm `--syntax-function`.
- `app/src/main/kotlin/com/opencode/android/OpenCodeSettings.kt` — thêm key editor/recent/
  renderer.
- `app/src/main/res/layout/panel_editor.xml` — thêm container cho WebView, giữ layout native.
- `app/src/main/res/menu/menu_editor.xml` — thêm "Chọn ngôn ngữ", "Đi tới dòng", "Tệp gần
  đây", "Chế độ đơn giản".
- `app/src/main/res/values/strings.xml` — chuỗi mới.
- `app/src/main/AndroidManifest.xml` — `windowSoftInputMode="adjustResize"`.
- `app/build.gradle.kts` — task `checkEditorAssets` (không bắt buộc).
- `.github/workflows/nightly.yml` — job verify bundle (tuỳ chọn).
- `docs/GITHUB_FILES_EDITOR_PROPOSAL.md` — thêm liên kết tới tài liệu này.
- `docs/UI_REDESIGN_PROPOSAL.md` — thêm liên kết tới tài liệu này.

## Phụ lục D — Nguồn Acode đã đọc

| Nội dung | Đường dẫn |
| --- | --- |
| Tổng quan, phiên bản | `https://github.com/Acode-Foundation/Acode` |
| Package/phiên bản CodeMirror | `https://github.com/Acode-Foundation/Acode/blob/main/package.json` |
| Extension nền | `https://github.com/Acode-Foundation/Acode/blob/main/src/cm/baseExtensions.ts` |
| Lắp extension editor | `https://github.com/Acode-Foundation/Acode/blob/main/src/cm/mainEditorExtensions.ts` |
| Tạo EditorView (DOM/canvas) | `https://github.com/Acode-Foundation/Acode/blob/main/src/cm/createEditorView.ts` |
| Registry mode | `https://github.com/Acode-Foundation/Acode/blob/main/src/cm/modelist.ts` |
| Đăng ký ngôn ngữ | `https://github.com/Acode-Foundation/Acode/blob/main/src/cm/supportedModes.ts` |
| Tag highlight mở rộng | `https://github.com/Acode-Foundation/Acode/blob/main/src/cm/highlightTags.ts` |
| Theme dựng sẵn | `https://github.com/Acode-Foundation/Acode/tree/main/src/cm/themes` |
| Cài đặt editor | `https://github.com/Acode-Foundation/Acode/blob/main/src/settings/editorSettings.js` |
| Read-only guard | `https://github.com/Acode-Foundation/Acode/blob/main/src/cm/editorReadOnly.ts` |
| Indent guides | `https://github.com/Acode-Foundation/Acode/blob/main/src/cm/indentGuides.ts` |
| Rainbow brackets | `https://github.com/Acode-Foundation/Acode/blob/main/src/cm/rainbowBrackets.ts` |
| Search highlighter | `https://github.com/Acode-Foundation/Acode/blob/main/src/cm/searchMatchHighlighter.ts` |
| LSP client | `https://github.com/Acode-Foundation/Acode/tree/main/src/cm/lsp` |
| Markdown preview page | `https://github.com/Acode-Foundation/Acode/tree/main/src/pages/markdownPreview` |

## Phụ lục E — Ghi chú về quyết định

- **Không dùng Ace.** Acode đã chuyển sang CodeMirror 6; CodeMirror 6 có Lezer, tree-based
  parsing, extension composition và bundle nhỏ hơn so với việc nhúng Ace + mode cũ.
- **Không tự viết tokenizer.** Mỗi ngôn ngữ là một parser; tự viết sẽ sai và không bao giờ
  theo kịp.
- **Không dùng CDN.** Offline-first và bảo mật.
- **Không đổi `TextFileIO`.** Đây là ranh giới an toàn dữ liệu; mọi thay đổi chỉ ở lớp renderer.
- **Không hứa LSP.** LSP cần runtime riêng; để tài liệu riêng nếu sau này làm.
- **Fallback là một tính năng, không phải code chết.** Nó bảo vệ người dùng WebView cũ, người
  dùng TalkBack và các tệp lớn.

---

*Hết tài liệu. Khi người dùng chốt các câu hỏi ở mục 17.2, bước tiếp theo là Phase 0: tách
theme token, thêm registry và feature flag, chưa thay đổi giao diện editor.*
