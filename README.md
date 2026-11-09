# PDF Viewer for Android

A native Android PDF reader and editor (Kotlin + Jetpack Compose) modelled on the feature set of
mainstream PDF readers: view, search, annotate, fill & sign, organize pages and a set of PDF tools.

- **Minimum Android version:** 8.0 (API 26). Target: Android 15 (API 35).
- **Rendering:** PDFium (via `io.legere:pdfiumandroid`).
- **Editing:** PdfBox-Android (`com.tom-roush:pdfbox-android`).
- Fully offline. No account, no ads, no tracking.

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
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk (minified)
./gradlew testDebugUnitTest    # Robolectric tests for all PDF editing operations
```

The release build is signed with the debug key so it can be installed directly. Before publishing
to a store, create your own keystore and set a `signingConfig` in `app/build.gradle.kts`.

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
