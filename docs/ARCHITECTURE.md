# Architecture

Earpiece is a single-module Android app that moves audio output between the
earpiece, loudspeaker, a Bluetooth headset and wired headphones, then shows the
user what the platform actually did. It supports Android 8.0 through Android 16
(`minSdk` 26, `targetSdk`/`compileSdk` 36) and depends on nothing beyond
AndroidX, Compose and JUnit.

One constraint shapes everything below: routing audio is an imprecise request,
not a command. The platform may refuse, substitute a device, or revoke one
mid-flight, so the app is built around *verification* — a request reaches the UI
only once read back from `AudioManager`.

## 1. Layer map

Sources live under `app/src/main/java/dev/creepypasta/earpiece/`.

| Package | Files | Responsibility |
| --- | --- | --- |
| `model` | `AudioRoute.kt`, `RouteCapabilities.kt`, `RouteResolution.kt`, `RouteResolver.kt` | Domain types and fallback rules. No `android.*` imports. |
| `state` | `RouteState.kt` | Process-wide `StateFlow` bus: requested, active, service running, capabilities. |
| `audio` | `AudioRouteManager.kt`, `TestTonePlayer.kt` | The only code that talks to `AudioManager` and `AudioTrack`. |
| `data` | `RoutePreferences.kt` | `SharedPreferences`: last requested route, restore-on-boot flag. |
| `service` | `AudioRouteService.kt`, `RouteNotifications.kt`, `BootReceiver.kt` | Foreground service holding the route, its notification, boot restore. |
| `tile` | `RouteTileService.kt` | Quick Settings tile; a thin front-end onto the service. |
| `ui` | `MainActivity.kt`, `MainScreen.kt`, `MainViewModel.kt`, `ui/theme/` | Compose Material 3 UI and the view model joining state to actions. |

`AudioRoute` is the vocabulary the whole app speaks: four entries, each with a
stable string `key` used for persistence and tile state. `RouteCapabilities` is
a snapshot of the hardware plus the `sdkInt` it was taken at. `RouteResolver`
turns a request and a snapshot into a `RouteResolution` — requested, effective,
`downgraded` — by walking a fixed fallback chain per route.

**Two boundary rules.**

1. Only `audio` and `service` touch platform audio APIs. `AudioRouteManager` is
   the single point of contact with `AudioManager`, `AudioDeviceInfo` and
   `AudioDeviceCallback`; `TestTonePlayer` owns the `AudioTrack` behind the test
   tone. Nothing in `ui`, `tile`, `data`, `state` or `model` may import
   `android.media.*`. The service holds the manager and forwards calls to it, so
   there is one live platform conversation per process.
2. `model` stays free of `android.*` so it can be unit-tested on a plain JVM.
   `RouteResolver` holds the interesting decisions — what is reachable, what to
   fall back to, whether to report a downgrade — and Android-free means all of
   it is covered by fast local tests rather than instrumented ones.

Dependency direction is one-way: `ui` → `state`/`data`/`service` → `audio` →
`model`. `model` depends on nothing.

## 2. Applying a route

1. The user taps a route card in `MainScreen`; the tap reaches
   `MainViewModel.select(route)`.
2. The view model writes `RoutePreferences.requestedRoute` so the choice
   survives process death, and publishes it via `RouteState.publishRequested`.
3. It calls `AudioRouteService.apply(context, route)`, which starts the service
   if needed or sends it a new intent, with the route in `EXTRA_ROUTE`.
4. `AudioRouteService` takes a fresh `AudioRouteManager.capabilities()` snapshot,
   publishes it, and calls `AudioRouteManager.apply(route)`.
5. `AudioRouteManager` resolves the request with `RouteResolver.resolve` against
   that snapshot, then applies the effective route using the mechanism for the
   API level (section 3).
6. It reads the route back with `currentRoute()` — `getCommunicationDevice` on
   31+, and on older releases `isSpeakerphoneOn`, `isBluetoothScoOn` and the
   connected device list.
7. It publishes the verified result via `RouteState.publishActive`. If the
   verified route differs from the request the UI is told it was downgraded; if
   the platform refused outright, `apply` returns null and the service publishes
   a failure instead.
8. `MainScreen` renders from `RouteState.active`. The highlighted card is always
   the route the platform confirmed.

The invariant to protect in review: **the UI never displays a route the platform
has not confirmed.** `RouteState.requested` exists only to show what the user
asked for mid-switch, and to mark a downgrade. It never paints the active state.

The same sequence runs from the tile (`RouteTileService` →
`AudioRouteService.cycle`) and from the notification's stop action — which is why
the flow is owned by the service, not the view model.

## 3. API-level matrix

| Concern | Android 8.0–11 (26..30) | Android 12–16 (31..36) |
| --- | --- | --- |
| Route selection | `setSpeakerphoneOn`, `startBluetoothSco`/`stopBluetoothSco`, `setBluetoothScoOn`, audio mode changes | `setCommunicationDevice(AudioDeviceInfo)` for all four routes; legacy calls unused |
| Reading back | `isSpeakerphoneOn` + `isBluetoothScoOn` + `getDevices` | `getCommunicationDevice()`, falling back to `getDevices` when it returns null |
| Bluetooth | No runtime permission; the device appears or it does not | `BLUETOOTH_CONNECT` runtime permission; denial surfaces as `msg_bluetooth_denied` |
| Notifications | Posts with no runtime permission | From 33, `POST_NOTIFICATIONS` runtime permission; the service still runs, the notification may not show |
| Foreground service | Plain `startForeground`, no declared type | From 34, must declare `foregroundServiceType="mediaPlayback"` and hold `FOREGROUND_SERVICE_MEDIA_PLAYBACK` |
| Dynamic colour | Static Material 3 palette only | From 31, `dynamicLightColorScheme`/`dynamicDarkColorScheme` in `EarpieceTheme` |

Every branch is guarded by a `Build.VERSION.SDK_INT` check, and every platform
audio call is wrapped so `SecurityException` (permission revoked between check
and call) and `RuntimeException` (vendor `AudioManager` throwing on unsupported
modes) degrade to a null result rather than a crash.

## 4. Threading

Route application runs on `Dispatchers.Default`, never on the main thread.
`AudioManager` calls are binder round-trips into `audioserver`; on a device with
a slow or wedged audio HAL, `setCommunicationDevice` or `startBluetoothSco` can
block for hundreds of milliseconds, and SCO bring-up is worse. On the main thread
that would drop frames during the very transition the user is watching.
`RouteResolver` is trivial arithmetic but runs on the same worker for
consistency.

Main-thread requirements are strict:

- `AudioDeviceCallback` is registered with a `Handler` on the main looper, so
  `observeDevices` delivers capability updates there; its callback does nothing
  but publish to `RouteState`.
- Tile state updates are main-thread only.
- Notification updates are posted from the service's main-thread context.

`RouteState` bridges the two: workers publish, the main thread collects. Being a
`StateFlow`, a slow collector cannot back-pressure the audio path — it misses
intermediate values, which is correct for a state bus.

## 5. Lifecycle and resource ownership

`AudioRouteService` is the owner. It creates `AudioRouteManager` on start and is
responsible for everything the manager acquires:

- The `AudioDeviceCallback` is registered by `AudioRouteManager.observeDevices`
  when the service starts and removed by `stopObserving` when it stops.
  Registering it anywhere else would leak a callback per configuration change.
- The becoming-noisy receiver (`ACTION_AUDIO_BECOMING_NOISY`) is registered by
  the service while it runs, so unplugging headphones stops the test tone and
  re-resolves the route. It is unregistered in `onDestroy`.
- Scopes: the service uses a scope cancelled in `onDestroy`; the view model uses
  `viewModelScope`, cancelled in `onCleared()`, which also stops the test tone
  and drops its manager reference. `AudioRouteManager` creates no scope — it is a
  passive object driven by its callers.
- `release()` runs from `ACTION_RELEASE` and `onDestroy`. It restores the audio
  mode and speakerphone state saved on the first `apply()`, so withdrawing the
  app's influence returns the device to whatever the system had chosen.
  `TestTonePlayer.release()` stops and discards the `AudioTrack`.

`BootReceiver` does one thing: if `restoreOnBoot` is set, it starts the service
with the persisted route. It holds no state and is not exported.

## 6. What this app deliberately does not have

- **No `INTERNET` permission.** There is nothing to fetch. Withholding it is an
  auditable guarantee that nothing can be exfiltrated.
- **No analytics.** The app's state is four routes and a service flag; no
  product question telemetry would answer justifies a tracking SDK.
- **No ads.** A utility living in a foreground service and a Quick Settings tile
  cannot host advertising without degrading the interaction it exists for.
- **No billing.** No paid tier, so no Play Billing library and no entitlement
  checks in the routing path.
- **No music player.** Earpiece routes; it does not play. Playback would mean a
  media session, a queue and a much larger foreground-service surface. The test
  tone is a diagnostic, not a player.
- **No third-party SDK.** Every dependency in `app/build.gradle.kts` is AndroidX
  or JUnit, which keeps the APK small, the build reproducible, and the privacy
  claims above verifiable by reading one file.

## 7. Testing strategy

`RouteResolver` is tested on the plain JVM with JUnit 4: it is the only place
with branching worth testing, and it has no Android types in its signature. The
cases that matter:

- every route with full capabilities — effective equals requested, not
  downgraded;
- each fallback chain in order: earpiece → speaker, wired, Bluetooth; speaker →
  earpiece, wired, Bluetooth; Bluetooth → wired, speaker, earpiece; wired →
  Bluetooth, speaker, earpiece;
- nothing in the chain available, which must give speaker with
  `downgraded = true`;
- earpiece rejected when `sdkInt` is below 26 even if its capability flag is set;
- `RouteCapabilities.UNKNOWN` (speaker only), which resolves every request to
  speaker;
- `AudioRoute.fromKey` with a known key, an unknown key and null.

What cannot be unit-tested is the platform conversation: whether
`setCommunicationDevice` actually moves audio, whether SCO connects to a given
headset, whether the read-back agrees with the request on a specific vendor
build. Those need real hardware and are verified manually across Android
versions and at least one Bluetooth headset.

CI runs on a plain JVM: compile the debug variant, run `testDebugUnitTest`, run
`lint`. No emulator, no instrumented tests. Lint catches what unit tests cannot —
a missing `SDK_INT` guard or an undeclared foreground service type fails the
build rather than a test.
