# Black Screen / ブラック画面

**Black out the screen; video keeps playing.**
画面を真っ黒に、動画はそのまま。

A tiny Android app that lays a pure-black, touch-blocking cover over whatever is on screen.
YouTube (or any video, podcast or music app) keeps playing underneath, the screen is
near-off (black pixels draw almost no power on OLED phones), and accidental touches in
your pocket do nothing. A modern take on the old "Black Screen of Death" idea.

Part of the free family apps at <https://gutsmcd-cmd.github.io/free-apps/>.
Free forever · no ads · no login · no tracking · **no internet permission at all**.
English and 日本語 (switch inside the app).

## Install (sideload)

1. On your Android phone (Android 8.0 or newer), download `Black-Screen.apk`
   from the Black Screen card on <https://gutsmcd-cmd.github.io/free-apps/>.
2. Tap the downloaded file. If asked, allow your browser or Files app to
   "Install unknown apps", then tap **Install**.
3. Open **Black Screen** and allow the two permissions it asks for:
   - **Display over other apps** – needed to draw the black cover over other apps.
   - **Notifications** (Android 13+) – shows the Unlock / Black out buttons.
   - **If the switch is greyed out / "restricted setting"** (Android 13+, because the app was
     not installed from the Play Store): open **App info** (the app shows a button for it) →
     tap **⋮** (top right) → **Allow restricted settings** → confirm with your PIN or
     fingerprint → go back and tap **Allow** again. On some phones that menu item only
     appears after you have been blocked once. One-time only.
4. Tap **Add the Quick Settings tile** (Android 13+), or add it by hand:
   pull down Quick Settings → pencil/edit → drag in "Black Screen".

## How to use

- **Start:** play your video, pull down Quick Settings and tap the **Black Screen** tile.
  Or tap **Black out** in the notification, or use the in-app
  "Black out in 3 seconds" button and switch back to your video.
- **Unlock:** tap the screen **4 times quickly, anywhere**. Faint dots under the reminder
  light up as each tap counts. Or pull down the notification shade and tap **Unlock**
  (or tap the tile again).
- Touch the black screen once and a faint reminder appears for a couple of seconds.

### Why "4 quick taps"?
Old apps used a double-tap or a long-press, which a pocket easily triggers. Fabric pressing
on glass mostly makes long presses, drags and several contacts at once; those reset the count.
4 short single-finger taps unlock it: each press under 0.4 s, pauses under 0.6 s, all within
about 2.5 s, anywhere on the screen.

### Pocket sensor (off by default)
When switched on in Settings: while the cover is up and the proximity sensor is covered
(pocket, face down), the app asks Android to switch the display fully off. The video keeps
playing. **Caveat:** while the display is off, Android delivers no touches at all, and on some
phones (especially with under-display sensors) the sensor reads "near" even when uncovered.
Then the 4 taps do nothing until you press the power button. That is why it is off by default
since 1.0.2.

## Honest limits

- Volume and power buttons still work (apps can't block them without Accessibility access,
  which this app deliberately does not ask for).
- On some phones, swipes from the very edge (Back, Home, notification shade) can still get
  through, because those system gestures sit above any app's overlay.
- Pressing power turns the screen off normally, which may pause some videos. Use the
  cover instead.
- The status/navigation bar area is covered as far as Android allows; some phones keep a thin
  gesture handle visible.

## How it works

- `BlackoutController` adds a `TYPE_APPLICATION_OVERLAY` window (full screen, cutout area
  included, `FLAG_KEEP_SCREEN_ON`, lowest window brightness, raised slightly while the hint shows)
  that swallows every touch.
- `BlackoutService` is a `specialUse` foreground service that keeps the app alive while the
  cover is up and shows the Unlock / Black out notification.
- `BlackoutTileService` is the Quick Settings tile. The tile and notification start an
  invisible `StartBlackoutActivity` so the shade closes first, then the cover appears.
- `UnlockDetector` is plain Kotlin with unit tests.

## Build from source

Requirements: JDK 17+ and the Android SDK (platform 37).

```sh
# gradle-wrapper.jar (binary) and the gradlew scripts are not committed.
# Regenerate them once with a locally installed Gradle 9.x:
gradle wrapper --gradle-version 9.8.1
./gradlew testDebugUnitTest lintRelease assembleRelease
```

Release signing reads `keystore.properties` (not in this repo):

```
storeFile=keystore/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file `assembleRelease` produces an unsigned APK; `assembleDebug` always works.

## Changelog

- **1.0.2** – Fix: 4-tap unlock did nothing until the power button was pressed. Cause: the
  Pocket sensor (then on by default) held a proximity wake lock that switched the display off,
  and an off display receives no touches. Pocket sensor is now off by default (with a warning),
  taps count anywhere on screen with more relaxed timing, and faint dots show each tap.
- **1.0.1** – First-run screen explains Android 13+ "restricted settings" (greyed-out
  "Display over other apps" switch for sideloaded apps) with an **Open App info** button.
- **1.0.0** – First release.

## License

MIT, see [LICENSE](LICENSE).
