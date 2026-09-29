# CineJoy TV

A lightweight Android / Fire TV wrapper for https://cinejoy.pk/ with built-in ad and tracker blocking.

## Features

- Full-screen WebView with JavaScript, cookies, localStorage and IndexedDB enabled.
- HTML5 fullscreen video, with DRM (protected media) allowed.
- Fire TV remote support:
  - **D-pad** moves a red highlight between clickable items (movie cards, buttons, menus, search box), like a TV app. **Select** opens the highlighted item. When nothing is left in that direction, the page scrolls so more content can load.
  - Menu > **Navigation: Pointer** switches to an on-screen pointer instead, for controls the highlight can't reach, such as buttons inside an embedded video player.
  - In fullscreen video: **Select** / **Play-Pause** toggles playback, **Left/Right** and **Rewind/Fast-forward** seek 10 s.
  - **Back** exits fullscreen, then goes back in history. Press it twice on the first page to exit.
  - **Menu (≡)** opens options: Home, Reload, navigation mode (focus highlight or pointer), ad blocker on/off, desktop site, update filter lists, clear cache, exit.
- Login and session are kept (cookies are flushed to disk), and the app reopens the last CineJoy page you had open.
- Ad and tracker blocking:
  - Uses uBlock Origin filters, EasyList, EasyPrivacy and Peter Lowe's list. They're downloaded on first run and refreshed every 3 days. A built-in list covers the time before the first download.
  - Blocks requests to ad and tracker domains, while honouring the lists' `@@` exception rules. Requests to `cinejoy.pk` itself are never blocked.
  - Popups are blocked. Only user-clicked links to CineJoy open, and they open in the same view. Embedded third-party players can't call `window.open`.
  - Redirects that take the whole page away from CineJoy (to ad domains, other sites, or `intent://` / app-store links) are cancelled. Google, Facebook and Apple sign-in are allowed.
  - Adds light cosmetic hiding for common ad containers.

## Build

The project has no Gradle wrapper, and the SDK isn't needed on your PC.

**Option A: GitHub Actions (no local tools)**
1. Push this folder to a GitHub repository, on the `main` branch.
2. The **Build APK** workflow builds a signed APK and publishes it as a release.
3. The APK is then at `https://github.com/<user>/<repo>/releases/latest/download/cinejoy-tv.apk`.

**Option B: Android Studio**
Open the folder in Android Studio, then choose Build > Build APK(s). If Android Studio asks, let it create the Gradle wrapper.

## Install on Fire TV (no laptop)

1. On the Fire TV, go to Settings > My Fire TV > Developer Options. Enable **Install unknown apps** for **Downloader**. (If Developer Options is hidden, go to Settings > My Fire TV > About and click the device name 7 times.)
2. Install **Downloader** (by AFTVnews) from the Amazon Appstore.
3. In Downloader, enter the release APK URL and install it.
4. Open **CineJoy TV** from Your Apps.

## Notes

- If a video won't play, open Menu and turn the ad blocker off to check whether blocking is the cause.
- To allow another sign-in or video domain for top-level navigation, add it to `NAV_ALLOWLIST` in `MainActivity.kt`.
