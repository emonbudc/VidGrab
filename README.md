# VidGrab

[![Build](https://github.com/emonbudc/VidGrab/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/emonbudc/VidGrab/actions/workflows/build.yml)

**Search, watch and download videos and music from YouTube and 1,800+ sites. Open source, no ads, no account.**

VidGrab is a GPL-3.0 Android app built on [QuickTube](https://github.com/devgalib/QuickTube) by devgalib. The full feature set is preserved; games and promotions are removed.

[**Download the latest APK**](https://github.com/emonbudc/VidGrab/releases/latest) · [Website](https://udcemon.me/VidGrab/) · [Report a bug](https://github.com/emonbudc/VidGrab/issues) · [Privacy](PRIVACY.md)

> Only download content you own or have permission to save, and respect each site's terms of service.
> VidGrab doesn't bypass DRM and isn't affiliated with YouTube, Google or any other site.

## Highlights

### Home feeds that learn what you watch

**For you**, **Music**, **News**, **Movies** and **Natok** each build their own feed from the same signals:

- Recently searched topics become search seeds. On a themed tab, searches that do not match the tab are narrowed with its subject instead of leaking into it.
- Recently opened YouTube videos become related-video seeds when their titles fit the tab.
- Results are ranked by seed strength, result position, view count, upload freshness and channel variety.
- Shorts, duplicates and videos you already opened are filtered out.
- Every tab refreshes after a new search or a newly opened video.
- With no history at all, **For you** falls back to the built-in mixed discover feed.

The feeds are paged, deduplicated and capped per channel so one creator cannot take over a list.

### Playback built for real use

- Inline and full-screen video with quality selection, speed control, sleep timer and autoplay.
- Resume from where you stopped, with playback history capped at 300 videos.
- Swipe for brightness, volume and seeking; double-tap to skip; hold for temporary speed.
- Queue, shuffle, repeat, previous/next and an up-next countdown.
- Mini player above the bottom navigation with play/pause, picture-in-picture and close.
- Picture-in-picture from the player, the mini player and the gallery player, with rewind, play/pause and forward controls in the window.
- Background play keeps the sound going after leaving the app. When enabled, it takes priority over picture-in-picture.
- Lock-screen and notification controls, audio-focus handling, headphone unplug pause and a foreground playback service.
- Local downloads, online streams and gallery videos share the same player engine.

### Downloads

- Search or paste a link, then pick video 144p–4K or audio in M4A/Opus.
- Video-only streams are merged with the best audio on-device.
- Whole playlists and channels can be queued in one go.
- Ranged downloads with up to 8 connections, resumable chunks, parallel video/audio tracks and automatic re-extraction when a link expires.
- Persistent queue with Wi-Fi/mobile limits, speed limit, pause/resume/cancel and progress notifications.
- "Download with VidGrab" appears in every app's share sheet with the format picker on top of the source app.

### Search and browsing

- Live search suggestions, recent searches and hot topics.
- Filters for videos, playlists, channels and music.
- Built-in browser with site list, history, bookmarks and player detection for MP4, HLS (including AES-128), iframes and shadow-DOM videos.
- Native pages for YouTube, SoundCloud, Bandcamp and media.ccc.de; the rest go through the page reader or bundled yt-dlp.

### Library, gallery and tools

- Downloaded library with filters, search, multi-select, rename, share, delete and vault.
- Play downloads as audio or convert video to audio.
- Gallery with photos by month, albums, zoomable viewer, photo editor and video/audio playback.
- Recycle bin, deleted-download history, WhatsApp status saver and PIN vault.
- Appearance options: system/light/dark, AMOLED black, Material You and eight accent palettes.

## Requirements

- Android 7.0 (API 24) or newer. Picture-in-picture requires Android 8.0 (API 26).
- `arm64-v8a` or `armeabi-v7a`; release builds ship per-ABI APKs plus a universal APK.

## Build

```sh
./gradlew :app:installDebug
./gradlew testDebugUnitTest
```

Release-equivalent build for device testing (R8 on, debug key, installs next to the debug app):

```sh
./gradlew :app:installQa
```

CI builds tests and signed release APKs from `main` through `.github/workflows/build.yml`. Release signing uses these repository secrets:

- `KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Without them the app still builds; the release APK is simply unsigned for distribution.

## Project layout

```text
app/                 application shell, navigation, player activities, sharing
core/                model, data, database, datastore, extraction, downloads, playback, UI
feature/             home, search, video, player, library, gallery, browser, settings, tools
build-logic/         Gradle convention plugins
docs/                GitHub Pages site
```

## Privacy

VidGrab has no accounts and no servers of its own. Downloads, history, vault contents and settings stay on the device. Official release builds may include Firebase Crashlytics and anonymous analytics when a Firebase configuration is supplied at build time; both can be disabled in **Settings → Privacy**. URLs, titles, search terms and file names are never sent. See [PRIVACY.md](PRIVACY.md) for details.

## Contributing

Issues and pull requests are welcome. Keep changes focused, follow the existing module boundaries and run `./gradlew testDebugUnitTest` before opening a pull request. New features belong in their `feature/` module; shared contracts belong in `core/`.

## Credits

- [QuickTube](https://github.com/devgalib/QuickTube) by devgalib (GPL-3.0): the app this is based on.
- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0): YouTube, SoundCloud, Bandcamp and media.ccc.de support.
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense) via [youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL-3.0): additional site support.
- AndroidX (Compose, Room, Navigation, DataStore), libVLC (LGPL-2.1), Koin, Coil and OkHttp (Apache-2.0).
- Material Symbols (Apache-2.0), Simple Icons (CC0), Plus Jakarta Sans (OFL-1.1).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
