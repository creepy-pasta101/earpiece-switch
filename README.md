# Earpiece

A debloated, clean-room rebuild of the "earpiece mode / audio output switcher" utility: hold a
chosen audio output — **earpiece**, **speaker**, **Bluetooth** or **wired headset** — for every app
on the device.

No ads, no analytics, no billing, no bundled music player, and **no `INTERNET` permission at all**.

- **Material You** UI (Material 3 with dynamic colour on Android 12+)
- **Quick Settings tile** — one tap to hold the last route, one tap to release it
- **Foreground service** that keeps the route applied while you use other apps
- **Synthesised test tone** so you can hear where audio is actually going
- **Verified state** — the UI shows the route read back from the platform, never a hopeful guess

## Why a rebuild

The original app ships a full offline music player, a Wi-Fi audio/video sharing feature, Google
Mobile Ads, Firebase Analytics and Messaging, Play Billing 9.1, WorkManager, Room, Picasso, Glide
and an app-usage provider — for a utility whose actual job is one `AudioManager` call. This project
keeps the job and drops the rest.

The behavioural contract this implementation was written against is in
[`docs/CLEAN_ROOM_SPEC.md`](docs/CLEAN_ROOM_SPEC.md); the layer map and API-level matrix are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

This is a **clean-room** implementation: the specification was derived from observed behaviour
(the published APK's component graph and permission set, plus its public feature description), and
the code was written from that specification. No decompiled code, assets or UI copy from the
original were used. The supplied bundle was in fact truncated — the resource table and the
application's own DEX string pool were unrecoverable — so the original's layouts, colours and
wording are not reproduced anywhere.

## Requirements

- Android 8.0 (API 26) or newer, targetSdk 36
- A device with an earpiece (all phones have one; tablets and some media players do not)

## Build

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # pure-JVM tests for the route resolver
./gradlew assembleRelease        # minified, unsigned
```

Requires JDK 17 and the Android SDK with platform 36. CI does exactly this on every push and pull
request and publishes the APKs as build artifacts (`.github/workflows/android.yml`).

## Permissions, and what each is for

| Permission | Why |
| --- | --- |
| `MODIFY_AUDIO_SETTINGS` | Change the audio mode and speakerphone state. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The route is held by a foreground service. |
| `POST_NOTIFICATIONS` | The ongoing route notification. |
| `BLUETOOTH_CONNECT` | Enumerate and select Bluetooth outputs (Android 12+). |
| `RECEIVE_BOOT_COMPLETED` | Optional: restore the last route after a reboot. |

Nothing else. There is no network permission, so the app cannot phone home even if it wanted to.

## How routing works

The earpiece route needs the platform in a communication audio mode — that is the only lever an
ordinary app has for getting media into the ear speaker — so the app enters that mode and sets the
speakerphone flag, and restores the previous mode when the route is released. On Android 12+ it
*additionally* uses the public device-selection API (`AudioManager.setCommunicationDevice`) to pin
the communication device and to read the active route back.

Both mechanisms are applied on every switch, deliberately. `setCommunicationDevice` alone is not
enough: it selects the device for *this app's* communication audio and returns `true` even when the
media you are listening to in another app does not move at all.

After every switch the app reads the route back from the platform and retries once if the first
read disagrees, because audio policy can lag a transition. If the route still did not take, the app
says so instead of pretending it worked. When the hardware for a route disappears (headphones
unplugged, Bluetooth dropped) the app falls back in a fixed order and tells you which route it
actually used. A watchdog re-asserts the held route every few seconds, and stands down during a
phone call.

## Known platform limits

Android has no public API that forces *system-wide* media routing. Everything this app does is a
sanctioned side effect of the communication audio mode, which is how this whole category of app
works — and it means two things you should know:

- **It works on most devices, not all.** Some OEM audio policies ignore the mode for media, and the
  speakerphone flag is deprecated from API 34. When the platform refuses, the app now tells you
  instead of silently doing nothing.
- **Nothing is forced during a call.** The watchdog stands down while the phone is in a call, so
  the app will not hijack your call audio.

The route you asked for and the route the platform confirmed are both shown: a card is only marked
active once the platform has been read back and agrees.

## Layout

```
app/src/main/java/dev/creepypasta/earpiece/
├── model/      Android-free domain: routes, capabilities, resolver (unit-tested)
├── state/      Process-wide StateFlow bus shared by UI, service and tile
├── audio/      AudioRouteManager — the only class that talks to AudioManager
├── data/       SharedPreferences storage
├── service/    Foreground service, notification, boot receiver
├── tile/       Quick Settings tile
└── ui/         Compose Material 3 screen, view model, theme
```

## Licence

Apache 2.0 — see [LICENSE](LICENSE).
