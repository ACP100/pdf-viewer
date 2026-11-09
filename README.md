# PDF Viewer for Android

A native Android PDF reader and editor (Kotlin + Jetpack Compose) modelled on the feature set of
mainstream PDF readers: view, search, annotate, fill & sign, organize pages and a set of PDF tools.

- **Minimum Android version:** 8.0 (API 26). Target: Android 15 (API 35).
- **Rendering:** PDFium (via `io.legere:pdfiumandroid`).
- **Editing:** PdfBox-Android (`com.tom-roush:pdfbox-android`).
- Fully offline. No account, no ads, no tracking.

## Download

Get the latest APK from [Releases](https://github.com/ACP100/pdf-viewer/releases):

| File | For |
|---|---|
| `PDF-Viewer-<version>-arm64-v8a.apk` | Most phones (recommended) |
| `PDF-Viewer-<version>-armeabi-v7a.apk` | Older 32-bit phones |
| `PDF-Viewer-<version>-universal.apk` | Any device (larger) |
| `…-x86_64.apk`, `…-x86.apk` | Emulators, Chromebooks |

Allow "Install unknown apps" for your browser or file manager when Android asks.

### Size
| | |
|---|---|
| Download (per-device APK) | ~10.5–12 MB |
| Universal APK | ~25 MB |
| Memory while reading | ~80 MB measured with several pages rendered (page images are cached and freed as needed) |

About half of the app is the PDFium rendering engine. The rest is the PDF editing library (with the
CJK character maps and fonts it needs) and the app itself.

## Features

### Reading
- Open PDFs from the file picker, from other apps ("Open with", "Share to"), from the device file list or from recents
- Continuous vertical scroll or single-page horizontal swipe
- Pinch zoom (0.5×–10×), double-tap zoom, fling, sharp re-rendering when zoomed in
- Page indicator, draggable fast-scroll thumb, "Go to page"
- Page thumbnails grid
- Table of contents (PDF outline) and your own page bookmarks (rename/delete)
- Text search with match count, previous/next, match case, whole words
- Long-press text selection with drag handles: copy, share, highlight, underline, strikethrough, comment, read aloud, find in document, web search, select page
- Tappable internal links (jump to page) and web links (with confirmation)
- Night mode (inverted), sepia mode, keep-screen-on, full-screen reading (tap to hide bars)
- Reading mode: reflowed text with adjustable font size and themes
- Read aloud (text-to-speech) that follows page by page, with speed control
- Password-protected PDFs
- Document properties (title, author, dates, page size, …) with editing
- Remembers the last page of every document
- Share and print

### Comment & annotate
- Highlight, underline, strikethrough and squiggly (drag over text, or from a text selection)
- Sticky notes, text boxes, freehand drawing (colour, width, opacity), rectangles, ovals, lines, arrows
- Add images
- Eraser, undo/redo, select/move/resize/recolour/delete what you added
- Comments list: jump to, edit or delete comments (including ones already in the file)
- Everything is saved as standard PDF annotations (with appearance streams) so other PDF apps show them

### Fill & Sign
- Fill interactive forms: tap fields on the page or use the form panel (text, checkboxes, radio buttons, dropdowns/lists); flatten forms
- Add text, today's date, check marks, crosses, dots, circles and lines anywhere
- Draw or type a signature and initials (saved for reuse) and place them; signature fields are filled automatically

### Organize pages
- Drag to reorder, rotate, delete, duplicate, insert blank pages, insert pages from another PDF, extract selected pages

### Tools (home screen)
Merge PDFs · Split (page ranges, every N pages, one file per page) · Compress · Protect with password (with print/copy/edit permissions) · Remove password · Images to PDF · Scan to PDF with the camera · Export pages as PNG/JPEG · Export text · Add watermark · Add page numbers

### Home
Recent files with thumbnails and reading progress, starred files, a device-wide PDF list (optional "All files access"), search, light/dark theme.

## Not included
These need server-side services, proprietary engines or much larger subsystems and are not part of this app:
OCR of scanned pages, editing the existing text/images of a PDF, export to Word/Excel/PowerPoint,
certificate-based digital signatures, cloud storage/sync and shared reviews, AI assistants.

## Build

Requirements: JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-<abi>-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-<abi>-release.apk (minified)
./gradlew testDebugUnitTest    # Robolectric tests for all PDF editing operations
```

Builds produce one APK per CPU type plus `app-universal-*.apk`. Set the version with
`-PappVersionName=1.2.3` (the version code is derived from it, or pass `-PappVersionCode`).

Release builds are signed with your key when these environment variables are set:
`SIGNING_KEYSTORE_FILE`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Otherwise they fall back to the debug key, which is only suitable for personal installs.

## Releases

GitHub Actions (`.github/workflows/release.yml`):

- **Every push to `main` and every pull request:** runs the tests and builds the APKs, attached to
  the workflow run as a downloadable artifact.
- **Publish a release:** push a version tag, or use *Actions → Build & Release → Run workflow* and
  enter a version.

  ```bash
  git tag v1.0.1
  git push origin v1.0.1
  ```

  The workflow builds and signs the APKs and creates the GitHub Release `v1.0.1` with them attached.

### One-time setup: your signing key

Android only installs an update if it is signed with the same key as the installed app. Create a
key once and store it as repository secrets, so every release is signed the same way:

```bash
keytool -genkeypair -v -keystore release.jks -alias pdfviewer -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks > release.jks.b64     # macOS: base64 -i release.jks -o release.jks.b64
```

In the repository: *Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `release.jks.b64` |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `pdfviewer` |
| `KEY_PASSWORD` | the key password (same as the keystore password unless you set another) |

Keep `release.jks` and its passwords somewhere safe and never commit them. Without the secrets the
workflow still works, but each build is signed with a throwaway key, so users would have to
uninstall before installing a newer release.

## Project layout

```
app/src/main/java/com/pdfviewer/app/
  pdf/PdfEngine.kt        PDFium wrapper (rendering, text, search, links, outline) on one thread
  pdf/PdfEditor.kt        PdfBox operations: write annotations, forms, organize, merge, split,
                          compress, encrypt, watermark, page numbers, images → PDF
  view/PdfView.kt         Custom page view: layout, zoom/scroll, tiles, selection, drawing tools
  annot/                  Annotation models and on-screen painter
  ui/viewer/              Viewer screen, toolbars, sheets, dialogs, read-aloud, reflow, print
  ui/organize/            Organize pages screen
  ui/home/                Home (recent / files / starred / tools) and the tool flows
  data/                   Recents, bookmarks, settings, file helpers
```

Edits are kept in memory (with undo/redo) until you save; saving writes them into the PDF with
PdfBox and reloads the document. Files opened read-only are saved with "Save a copy".
