# Clean-Room Behavioural Specification

**Subject:** an Android utility that switches the device's active audio output between the
earpiece, the loudspeaker, Bluetooth and a wired headset, and keeps that choice applied.

**Status:** this document is a *behavioural* specification written from observation of a
third-party application bundle. It contains no source code, no decompiled code and no
implementation detail from the original. The implementation in this repository is written
from this document alone.

---

## 1. How this specification was derived

| Source | What it gave |
| --- | --- |
| `Earpiece_Mode_Audio_Switch_1.79.apks` (base split, 6.3 MB of a 6.55 MB bundle) | Package identity, SDK range, the full component graph, permissions, declared features, bundled SDK inventory |
| The application's public store listing and review corpus | The user-facing feature set, the vocabulary the app uses for its own actions, and the reported failure modes |
| Platform documentation for `android.media` | The behaviour a route switch must produce on each API level |

### What could not be observed, and is therefore *specified* rather than *copied*

The supplied bundle is truncated: it ends mid-way through the second DEX file. Consequently
the resource table (`resources.arsc`), all `res/` payloads, and the string pool of
`classes2.dex` (where the application's own classes live) were not recoverable. The original
UI copy, layout, colours, animation timings and internal class design are therefore
**unknown**, and this specification deliberately does not attempt to reproduce them.

What *was* fully recovered, and is authoritative here:

* Package identity `com.sparkapps.mobileearphone.yp`, versionName `1.79`, versionCode `79`,
  minSdk 24, targetSdk 36.
* Every declared component (activities, services, receivers, providers, the Quick Settings
  tile is *absent* in the original — see §6).
* Every declared permission and `<uses-feature>`.
* The bundled SDK inventory (see §7), which is the basis of the debloat claim.

Where the original's exact behaviour could not be observed, this document states the
requirement the rebuild must meet and marks the reasoning as **[derived]**.

---

## 2. Scope

### In scope

1. Selecting the active audio output route: **earpiece**, **speaker**, **Bluetooth**,
   **wired headset**.
2. Keeping the selected route applied while media from *other* applications plays — the
   utility does not play the user's media, it owns the route.
3. An on-device verification sound ("test tone") that proves where audio is currently going.
4. A foreground service that holds the route and survives the app being backgrounded.
5. A Quick Settings tile and a notification with route actions.
6. Restoring the last route after reboot, if the user asked for it.
7. A Material You (Material 3, dynamic colour) user interface.

### Out of scope (present in the original, deliberately dropped)

* The bundled offline music player and album/artist browsing.
* The Wi-Fi audio/video host-and-join feature (profile creation, password protection,
  connected-user management).
* Subscriptions, in-app purchases, advertising, analytics, crash reporting, push messaging.
* Any network access whatsoever. The rebuild declares **no `INTERNET` permission**.

---

## 3. Vocabulary

| Term | Meaning |
| --- | --- |
| **Route** | One of `EARPIECE`, `SPEAKER`, `BLUETOOTH`, `WIRED`. |
| **Requested route** | What the user last asked for. Persisted. |
| **Active route** | What the platform is actually using right now. Read back from the system, never assumed. |
| **Downgrade** | The requested route is unavailable, so a substitute is used and the substitution is surfaced to the user. |
| **Capability** | Whether the hardware for a route is present and usable right now. |

---

## 4. Functional requirements

### R1 — Route application

**R1.1** The utility shall apply a route only when the user asks for it, or when a route it
previously applied must be re-asserted (R4, R6). It shall not change the route on launch.

**R1.2** Applying `EARPIECE` shall make the built-in earpiece the active output for media
playback from any application. The platform only permits this while the audio mode is a
communication mode; the implementation shall therefore enter a communication audio mode for
as long as the earpiece route is held, and shall restore the previous mode when the route is
released. **[derived]**

**R1.3** Applying `SPEAKER` shall make the loudspeaker the active output and shall not leave
the device in a communication mode.

**R1.4** Applying `BLUETOOTH` shall select a connected Bluetooth output device if one exists.
When several are connected, a device intended for voice (SCO) is preferred over one intended
for media (A2DP) *only* if the audio mode is a communication mode; otherwise A2DP is
preferred. **[derived]**

**R1.5** Applying `WIRED` shall select a connected wired headset or headphones.

**R1.6** After applying, the utility shall read the active route back from the platform and
report it. If the platform did not honour the request, the utility shall report failure — it
shall never display a route it has not verified.

**R1.7** Applying a route shall be idempotent: applying the already-active route shall
succeed without audible glitching.

### R2 — Capability detection

**R2.1** Capabilities shall be derived from the platform's enumeration of output devices, not
from a static hardware database.

**R2.2** A route whose hardware is absent shall be shown as unavailable and shall not be
selectable.

**R2.3** Capabilities shall be re-evaluated whenever output devices are added or removed
(headset plugged, Bluetooth connected or disconnected), and the UI shall reflect the change
while it is visible.

### R3 — Downgrade

**R3.1** If the requested route becomes unavailable, the utility shall fall back in this
order, taking the first available:

| Requested | Fallback order |
| --- | --- |
| `EARPIECE` | `SPEAKER` → `WIRED` → `BLUETOOTH` |
| `SPEAKER` | `EARPIECE` → `WIRED` → `BLUETOOTH` |
| `BLUETOOTH` | `WIRED` → `SPEAKER` → `EARPIECE` |
| `WIRED` | `BLUETOOTH` → `SPEAKER` → `EARPIECE` |

**R3.2** A downgrade shall always be surfaced: the active route is shown as active, the
requested route is shown as unavailable, and a single-line explanation names both.

**R3.3** The requested route is *not* overwritten by a downgrade. When the hardware returns,
the requested route is restored.

### R4 — Persistence and re-assertion

**R4.1** The requested route and the user's preferences shall survive process death.

**R4.2** While the service is running, the utility shall re-assert the requested route when
the set of output devices changes and when audio becomes noisy (headphones unplugged), so
that a route the user chose is not silently lost.

**R4.3** On boot, if the user enabled it, the utility shall restore the last requested route.

### R5 — Foreground service

**R5.1** The route shall be held by a foreground service with an ongoing notification, so
that it survives the activity being backgrounded or dismissed.

**R5.2** The notification shall show the active route, offer one action to stop the service,
and open the app when tapped.

**R5.3** Starting the service while it is already running shall update the route, not create
a second instance.

**R5.4** Stopping the service shall restore the platform audio mode that was in effect before
the service first applied a route.

### R6 — Quick Settings tile

**R6.1** A tile shall be present, labelled with the utility's short name, showing whether a
route is currently held.

**R6.2** Tapping the tile when no route is held shall apply the last requested route; tapping
it when a route is held shall release it. Long-pressing shall open the app.

**R6.3** The tile state shall be refreshed whenever the service starts or stops.

### R7 — Test tone

**R7.1** The utility shall be able to play a short, recognisable verification sound through
the currently active route without needing a media file, so that the user can confirm where
audio is going.

**R7.2** The tone shall be synthesised on device; no audio asset shall be bundled.

**R7.3** Playing the tone shall not disturb the applied route, and the tone shall stop on
request and when the app leaves the foreground.

### R8 — User interface

**R8.1** Material 3 with dynamic colour where the platform provides a system palette
(Android 12+), and a fixed fallback palette below that. Light and dark themes both follow the
system setting.

**R8.2** One screen listing the four routes as selectable cards. Each card shows the route
name, its availability, and — for the active route — an explicit "active" indication.

**R8.3** The screen shows whether the route is being held by the service, with a single
control to hold or release it.

**R8.4** The screen offers the test tone and the boot-restore preference.

**R8.5** Every string is a translatable resource. No text is hardcoded.

**R8.6** The UI shall never block on audio work: route changes are applied off the main
thread and reported back to the UI as state.

---

## 5. Non-functional requirements

**N1** No network permission, no third-party runtime SDK, no advertising or analytics code.
**N2** No reflection into hidden platform APIs unless the public API genuinely cannot do the
job on that API level, and never without a version guard and a caught failure. Prefer the
public device-selection API on Android 12+.
**N3** Route application must complete within 250 ms on a warm process; the UI must show the
result within one frame of the platform confirming it.
**N4** The app must not crash or leak when permissions are denied, when no Bluetooth device
exists, or when the platform rejects a route change.
**N5** The service must release audio resources (audio focus, communication mode, device
callbacks) on stop, on task removal and on service destruction.

---

## 6. Deliberate deviations from the original

These are corrections, not omissions. Each was chosen from an observed weakness.

1. **A Quick Settings tile is added.** The original declares no `TileService`; the store
   listing and reviews describe a slow, notification-heavy path to switching. A tile makes
   the primary action one gesture. **[derived]**
2. **Active route is verified, not assumed.** Reviews report "switching is inconsistent and
   sometimes fails" while the UI claimed success. R1.6 makes the displayed state a
   read-back from the platform.
3. **Downgrades are explicit.** R3.2 removes the silent failure mode where a request was
   accepted and nothing changed.
4. **No `INTERNET` permission.** The original declares `INTERNET`, `ACCESS_NETWORK_STATE`,
   `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `READ_PHONE_STATE`, `AD_ID` and
   `com.android.vending.BILLING` for features this rebuild does not have.
5. **No splash screen.** The original gates launch behind a splash plus ad preload. This
   rebuild draws its first frame immediately. **[derived]**

---

## 7. Debloat record

Declared by the original and absent here:

| Removed | Evidence in the original |
| --- | --- |
| Google Mobile Ads + AdMob mediation | `com.google.android.gms.ads.*`, `AdActivity`, `APPLICATION_ID` meta-data |
| Firebase Analytics / Messaging / Installations / DataTransport | `FirebaseInitProvider`, `FirebaseMessagingService`, `AppMeasurement*`, `TransportBackendDiscovery` |
| Google Play Billing 9.1.0 | `com.android.vending.BILLING`, `ProxyBillingActivity`, `SubscriptionActivity` |
| WorkManager (+ 6 constraint receivers) | `androidx.work.*` services and receivers |
| Room, Picasso, Glide+OkHttp, TedPermission, AppThemeHelper | providers, `GlideModule` meta-data, `TedPermissionActivity` |
| Usage-stats provider | `bot.box.appusage.provider.AppUsageProvider` |
| Offline music player + Wi-Fi sharing | `NewMusicPlayer.*`, `sharevideooraudio.*` activities and `PlayerService` |

Permissions retained by the rebuild, each justified:

| Permission | Why |
| --- | --- |
| `MODIFY_AUDIO_SETTINGS` | Required to change audio mode and speakerphone state. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The route is held by a foreground service. |
| `POST_NOTIFICATIONS` | The foreground notification. |
| `BLUETOOTH_CONNECT` (S+) | Enumerating and selecting Bluetooth output devices. |
| `RECEIVE_BOOT_COMPLETED` | Optional route restore on boot (R4.3). |

---

## 8. Assumptions to validate on hardware

1. Some OEM builds ignore a route request while another app holds exclusive audio focus. R1.6
   surfaces this as a failure rather than a silent no-op; the correct long-term behaviour
   (retry, or ask the user to pause the other app) is not specified here.
2. Earpiece output for *media* is a platform-sanctioned side effect of communication mode on
   most devices, but a minority of OEM builds route communication-mode media to the
   loudspeaker regardless. Where that happens, R1.6 reports failure.
3. Volume: the earpiece is physically quieter than the loudspeaker. This rebuild does not
   raise volume above the user's setting, and does not request `MODIFY_AUDIO_SETTINGS`
   privileges beyond mode and route selection.
4. **System-wide media routing has no public API.** `AudioManager.setCommunicationDevice`
   selects the device for the calling app's *communication* audio only; it returns `true`
   without moving media played by other applications. The only lever an ordinary app has is
   the communication audio mode plus the speakerphone flag, which is deprecated from API 34
   and honoured by OEM audio policy on most, but not all, devices. R1.2 therefore requires
   **both** mechanisms to be applied together, and R1.6 requires the read-back so that a
   device which ignores the mode reports failure instead of a false success.
