# Cyfer Streaming for Android

Native Android port of [Cyfer Streaming](https://github.com/FortunasXP/Cyfer-Streaming) — a private streaming front‑end that pulls torrent + Stremio addon sources, resolves them through Real‑Debrid / TorBox or the on‑device libtorrent engine, and plays via libmpv.

Built with Jetpack Compose + Material 3, targeting parity with the Electron desktop app on phone, tablet, and Android TV.

---

## Features

- **Sources** — TMDb-driven browse / search / discover, with provider + resolution filters in the picker.
- **Torrent providers** — all major public trackers, plus the anime tier (Nyaa, ToonsHub, TokyoTosho, Bangumi .moe, Erai-Raws, AniDex).
- **Stremio addons** — full URL install flow, retry + progressive load.
- **Debrid services** — Real‑Debrid, TorBox.
- **On‑device torrent engine** — libtorrent4j with fastresume persistence, piece prioritisation for streaming, foreground service.
- **Player** — libmpv-android with gestures (vol/brightness/seek), PiP, immersive mode, playback speed, aspect, external + addon subtitles, subtitle styling (scale / position / backdrop).
- **Downloads** — pin any source for offline. App Store-style progress ring on every row, "Available offline" / "Checking…" pills, offline-first Downloads screen.
- **Anime** — Kitsu home + AniList progress push, multi-title search (canonical / English / romaji).
- **Calendar** — upcoming-episodes feed across watchlist; periodic episode-air notifications via WorkManager.
- **Sync** — Trakt device-code OAuth + scrobble, AniList token paste + progress push.
- **Continue Watching + History** — local progress, plus a Continue Watching surface accessible from Home.
- **Hardware acceleration setting**, dark theme, 5-tab slim nav (Home / Browse / Search / My List / Settings).

## Install

Grab the APK from [the latest release](https://github.com/FortunasXP/cyfer-streaming-android/releases/latest) and side-load it. Per-architecture builds:

| ABI | When to use |
| --- | --- |
| `app-arm64-v8a-debug.apk` | Modern phones / tablets (most common). |
| `app-armeabi-v7a-debug.apk` | Older 32-bit devices. |
| `app-x86_64-debug.apk` | Emulators / Chromebooks. |
| `app-universal-debug.apk` | Catch-all if you're unsure. |

Enable "Install unknown apps" for your browser / file manager before opening the APK.

## Build from source

Requires Android Studio Koala+ (AGP 8.x) and an Android SDK with platform 34+ installed.

```powershell
cd android
gradle :app:assembleDebug          # Debug APKs in app/build/outputs/apk/debug/
gradle :app:installDebug           # Install on the connected device / emulator
```

To produce a release-signed build, copy `app/keystore.properties.example` to `app/keystore.properties`, point it at your keystore, then run `gradle :app:assembleRelease`.

## Project layout

```
android/
  app/
    src/main/java/app/cyfer/streaming/android/
      data/        — Repositories (TMDb, Kitsu, Trakt, AniList, settings, library, downloads)
      data/torrent/      — Provider implementations + libtorrent4j engine + NanoHTTPD streaming server
      navigation/        — CyferApp host, bottom nav, screen routing
      player/            — MpvPlayer wrapper + libmpv lifecycle
      ui/                — All Compose screens
    src/main/res/        — Drawables, themes, manifest
  proguard-rules.pro
  build.gradle           — App module config (libs, signing, splits)
build.gradle             — Root project + plugin versions
```

## Related

- **Desktop app** — [Cyfer-Streaming](https://github.com/FortunasXP/Cyfer-Streaming) (Electron + Next.js, the original).

## License

Personal project — no public license. Source available for inspection.
