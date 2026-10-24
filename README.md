# Brave TV (`dev.billy.bravetv`)

A lightweight, zero-dependency Android TV app (~25 KB APK) that wraps **YouTube TV (`https://www.youtube.com/tv`)** and a full **10-foot Web Browser** with **Brave-style Ad & Tracker Shields**.

## Features

- **Native YouTube TV Leanback Experience**:
  - Spoofs a Smart TV User-Agent (`Tizen / SMART-TV`) so YouTube serves the full D-pad-controlled TV app (`https://www.youtube.com/tv`).
  - Full remote control navigation (`Up`, `Down`, `Left`, `Right`, `Select`, `Back`) with zero mouse cursor needed.
  - Supports QR code phone sign-in (`yt.be/activate`).
  - Suppresses Android `WebView`'s default grey video poster flash (`getDefaultVideoPoster()` returns a `1×1` transparent pixel).
- **Brave-Style Ad & Sponsor Shield (`BRAVE_YT_SHIELD_JS` + Network Filter)**:
  - **JSON API Interceptor**: Hooks `JSON.parse` and `Response.prototype.json` to strip `adPlacements`, `playerAds`, `adSlots`, and `adBreakHeartbeatParams` from YouTube player payloads before playback.
  - **Network Request Blocker**: Blocks requests to `doubleclick.net`, `googlesyndication.com`, `googleadservices.com`, `/pagead/`, `/api/stats/ads`, and `get_midroll_info` at the `WebViewClient.shouldInterceptRequest` layer.
  - **Fallback Ad Skipper**: Automatically mutes and fast-forwards any residual `.ad-showing video` streams and clicks skip buttons every 250ms.
- **Built-In Desktop Web Browser Mode**:
  - Press **Menu** (or **long-press Back**) on your TV remote to toggle the Brave TV top bar, switch between **YouTube TV** and **Web Search**, or toggle the D-pad virtual pointer.
  - Automatically computes the exact `<meta name="viewport">` scale (`tvCssWidth / 1440`) for `1920×1080` `@ 2.0x` Android TV surfaces so desktop websites never overflow horizontally.

## Project Structure

```text
brave-tv/
├── AndroidManifest.xml                  # Android TV LEANBACK_LAUNCHER manifest
├── build.sh                             # Zero-Gradle SDK build script (~1.5s build)
├── res/values/strings.xml               # App label
└── src/dev/billy/bravetv/
    └── MainActivity.java                # Complete app, ad-shield, and TV key handling
```

## Build & Install on Android TV

```bash
# Build signed APK to dist/brave-tv.apk
./build.sh

# Install onto Android TV over ADB
adb connect <TV_IP>:5555
adb install -r dist/brave-tv.apk
adb shell am start -n dev.billy.bravetv/.MainActivity
```
