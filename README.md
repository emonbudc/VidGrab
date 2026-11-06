# VidGrab

**Search, watch and download videos and music from YouTube and 1,800+ sites. Open source, no ads, no account.**

VidGrab is a GPL-3.0 Android app built on [QuickTube](https://github.com/devgalib/QuickTube) by devgalib. The full feature set is preserved; games/promotions are removed.

[**Download the latest APK**](https://github.com/emonbudc/VidGrab/releases/latest) · [Website](https://udcemon.me/VidGrab/) · [Report a bug](https://github.com/emonbudc/VidGrab/issues)

> Only download content you own or have permission to save, and respect each site's terms of service.
> VidGrab doesn't bypass DRM and isn't affiliated with YouTube, Google or any other site.

## Features

- **Download tab**: search, "download copied link", quick sites, YouTube trending, music feeds, platform how-to guides.
- **Search**: live suggestions, recent and hot searches, All / Videos / Playlist / Channels / Music filters, web searches.
- **Video pages**: inline 720p player, format sheet, background audio, about sheet, comments, up next, channels and playlists.
- **Batch download**: download a whole playlist/channel, or select videos and queue them at one quality.
- **Download sheet**: audio (M4A/Opus) and video 144p–4K, plus more formats; video-only streams merged with audio on-device.
- **Share to download**: "Download with VidGrab" in every app's share sheet, with the format picker over the source app.
- **Browser**: built-in WebView that detects MP4, HLS (including AES-128), iframe and shadow-DOM players; site list, history, saved pages.
- **Download engine**: 4 MB ranged pieces over up to 8 connections, resumable chunks, video+audio in parallel, re-extraction on expired links, persistent queue, Wi-Fi/mobile limits, speed limit, notifications.
- **Play tab**: downloaded list with filters, search, multi-select, vault, play as audio, convert to audio, share, rename, delete, resume position.
- **Player**: speed, previous/next, seek, double-tap ±10s, fullscreen, queue, listen mode, shuffle/repeat, mini player, lock-screen controls, picture-in-picture.
- **Gallery**: photos by month, albums, zoomable viewer, photo editor, video/audio player, audio extraction.
- **Settings**: download path, task limits, mobile data, notifications, appearance (system/light/dark, 7 accents, AMOLED, Material You), player and privacy options.
- **Tools**: recycle bin, deleted download history, WhatsApp status saver, PIN vault, storage clean, large-file clean, app uninstaller.
- **Extraction**: NewPipe Extractor for YouTube/SoundCloud/Bandcamp, page reader for other sites, and bundled yt-dlp with daily extractor updates.
- **Updates**: in-app GitHub release checks, SHA-256 and signing-key verification, one-tap install.

## Build

```sh
./gradlew :app:installDebug
./gradlew testDebugUnitTest
```

Release build for device testing (R8 on, debug key, installs next to the debug app):

```sh
./gradlew :app:installQa
```

CI builds and publishes signed APKs from `main` through `.github/workflows/build.yml`.

## Credits

- [QuickTube](https://github.com/devgalib/QuickTube) by devgalib (GPL-3.0): the app this is based on.
- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0): YouTube support.
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense) via [youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL-3.0): site support.
- AndroidX (Compose, Room, Navigation, DataStore), libVLC (LGPL-2.1), Koin, Coil, OkHttp (Apache-2.0).
- Material Symbols (Apache-2.0), Simple Icons (CC0), Plus Jakarta Sans (OFL-1.1).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
