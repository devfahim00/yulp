# Yulp

Modern, lightweight Android browser. Package: `com.devfahim00.yulp`

Kotlin + WebView + Material 3 (no Compose, small APK). minSdk 29.

## Features
Multi-tab, incognito tabs, smart URL/search bar, progress bar, bookmarks, history, find in page,
desktop site mode, web dark mode, long-press link/image actions, fullscreen video, file upload,
external app links (intent://, mailto:, tel:), share, clear browsing data, light/dark theme,
can be set as default browser.

### v1.1
- **Ad blocker** (own page): enable/disable switch, total/today stats, blocked-request history,
  built-in host blocklist of ad/tracker networks. Ads are blocked in incognito too, but nothing
  is recorded there.
- **Tab grid page**: tabs open in a full-screen 2-column thumbnail grid (no more popup dialog),
  per-tab close, new tab / new incognito / close all.
- **Incognito indicator** shown beside the reload button while an incognito tab is active.
- **Dedicated pages** for bookmarks, history and downloads.
- **Built-in multi-thread download engine**: IDM-style HTTP Range splitting (1–16 threads,
  selectable per download and as default), runs in a foreground service so downloads continue
  while the app is in the background, pause/resume/cancel/restart, progress survives process
  death, live speed display, files saved to the public Downloads folder (MediaStore).

## Build
Open in Android Studio, or push to GitHub: the `Build APK` workflow produces a release APK artifact.
