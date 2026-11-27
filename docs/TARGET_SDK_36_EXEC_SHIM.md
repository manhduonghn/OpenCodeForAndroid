# targetSdk 36 và cơ chế chạy file trong prefix

## Vấn đề

Từ API 29, Android áp dụng W^X: `execve()` một file thuộc `app_data_file`
(bao gồm mọi file trong `/data/data/com.opencode.android/files/...`) bị từ chối.
App trước đây giữ `targetSdk = 28` để tránh giới hạn này. Nâng lên 36 cần một
cơ chế khác, nhưng vẫn giữ nguyên mô hình prefix Termux (`pkg`, không root,
không PRoot, không viết lại apt/dpkg).

## Giải pháp

Ba loại file được tách riêng:

| Loại | Vị trí | Cách chạy |
| --- | --- | --- |
| ELF bionic động, script shebang | prefix (`app_data_file`) | `/system/bin/linker64 <file> ...` |
| ELF glibc (OpenCode CLI) | prefix, nhưng loader nằm trong APK | `<nativeLibraryDir>/liboc_ld.so --library-path ... --preload liboc-pathmap.so <binary> ...` |
| helper tĩnh / entrypoint (`oc-gopatch`, `opencode-pty`, `oc-run`, cache `rg`) | `nativeLibraryDir` (`apk_data_file`) | `execve()` trực tiếp |

`nativeLibraryDir` do hệ thống giải nén từ APK, nhãn `apk_data_file`, nên
vẫn được `execve()` dưới targetSdk 36. Vì vậy `app/build.gradle.kts` dùng
`packagingOptions.jniLibs.useLegacyPackaging = true` (AGP 8.1+ chấp nhận
thuộc tính này trong build script, không cho phép ghi
`android:extractNativeLibs` trong `AndroidManifest.xml`).

### Bionic `LD_PRELOAD` shim

`native/oc-exec/oc-exec.c` builds `liboc_exec.so` bằng NDK clang. Thư viện
được preload vào **mọi tiến trình bionic** của runtime (`sh`, `bash`, `pkg`,
terminal):

- `execve`/`execv*`/`execl*`/`fexecve`/`posix_spawn*` được chặn;
- nếu file nằm trong prefix/home/app data → đọc ELF/shebang và gọi
  `/system/bin/linker64` với `argv[0]` tên linker và đường dẫn file ở `argv[1]`
  (linker **mmap** file, W^X cho phép); nếu chỉ đưa file ở `argv[0]` thì linker
  hiểu nhầm đối số đầu tiên là file cần nạp — lỗi này đã được kiểm chứng bằng
  `exec -a` trên máy;
- nếu file là ELF glibc → gọi loader trong `nativeLibraryDir`, xoá
  `LD_PRELOAD`/`LD_LIBRARY_PATH` khỏi env con và truyền
  `liboc-pathmap.so` qua `--preload`;
- nếu file đã nằm trong `nativeLibraryDir` hoặc ở `/system`, `/apex` → chạy
  bình thường, nhưng shim được chèn lại vào env để các con sau vẫn được route;
- `OC_EXEC_DISABLE=1` tắt toàn bộ routing (dùng để debug).

Shim **không bao giờ** được nạp vào tiến trình glibc: `liboc-pathmap.so` vẫn
là preload duy nhất của glibc. Hai thư viện này không trộn lẫn.

### Vì sao không chạy glibc qua `/system/bin/linker64`

`ld-linux-aarch64.so.1` không có `PT_PHDR`, Android linker báo
`Could not find a PHDR: broken executable?`. Do đó loader được đóng gói thành
`liboc_ld.so` trong APK. `native/glibc-loader/build.sh` tải package glibc của
Termux, kiểm tra SHA-256 và thay chuỗi Termux prefix (31 byte) bằng
`/data/data/com.opencode.android` (cũng 31 byte) ngay lúc build; runtime libc
trong prefix vẫn do `ensureGlibcRelocated()` xử lý như cũ.

### Entrypoint và PTY

- `$PREFIX/bin/oc-run` là symlink tới `liboc_ocrun.so`: một PIE C nhỏ trong
  nativeLibraryDir, đặt `OC_PREFIX`, `OC_EXEC_SHIM`, `OC_GLIBC_LOADER` rồi gọi
  `liboc_gopatch.so`.
- Cache ripgrep (`$HOME/.opencode/bin/rg`) là symlink tới `liboc_rg.so`, wrapper
  gọi `$PREFIX/bin/rg` qua system linker.
- `opencode-pty` được đóng gói thành `liboc_pty.so`; khi khởi động nó tự re-exec
  một lần qua linker64 với `LD_PRELOAD=liboc_exec.so`, nên các shell con do
  terminal spawn vẫn có shim.
- `native/gopatch/run.go` cũng được sửa: ưu tiên `OC_GLIBC_LOADER` từ APK, route
  ELF bionic qua linker64 và dùng file ẩn danh (memfd) cho bản copy đã patch.

## Kiểm tra

Cần build CI (`native.yml`) rồi cài APK trên máy API 36 và kiểm tra:

```sh
pkg install jq
jq --version
opencode --version
terminal: echo $LD_PRELOAD
oc-run jq --version
```

CI có thêm `native/wrappers/run-test.sh` (build host của shim/hai wrapper và kiểm
tra đối số `oc-run`, fallback của `oc-rg`) cùng `go test` cho `findLoader`/
`stripBionicPreload`; bản thân APK vẫn cần cài trên API 36 để xác nhận W^X.

CI hiện tại chưa có thiết bị API 36; máy thử nghiệm đang ở API 35. Trạng thái
này được coi là **chưa xác nhận trên thiết bị**.
