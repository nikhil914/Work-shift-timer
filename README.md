# ArriveLog

Automatic workplace arrival logging (geofence + Wi-Fi), restricted to the working schedule.
Built in phases. Done: **Phase 1** (data layer, decision engine, tests), **Phase 2** (setup wizard, permissions), **Phase 3** (background detection) and **Phase 4** (Home, day log, edit sheet, notifications).
Distribution: **Google Play**.

## Decisions (26 Oct 2026)

1. **Early arrivals are logged at their true time.** Auto arrivals are accepted from arm time (window start − 15 min) to window end, so being inside before 06:30 logs e.g. 06:22. If you were already inside when detection started, the arrival is stamped at arm time and flagged *estimated*. Evidence from before arm time is never auto-logged; it is offered in the missed-entry notification.
2. **Exits always use the geofence**, also in Wi-Fi-only mode (after arrival only; the arrival itself still needs Wi-Fi in that mode).
3. **Google Play release**: no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; see "Play requirements" below.

## Build

- Android Studio (current stable), JDK 17+. Gradle 9.8.2 wrapper, AGP 9.4.1, Kotlin 2.4.21, KSP 2.3.12.
- Copy `local.properties.example` → `local.properties` and set `MAPS_API_KEY` (only needed from Phase 2; an empty key still builds).
- `./gradlew :app:assembleDebug`
- Tests: `./gradlew :domain:test :app:testDebugUnitTest`
- SVN: `svn propset svn:ignore -F .gitignore .` covers the same build/IDE folders as `.gitignore`.

## Modules

| Module | What | Android deps |
|---|---|---|
| `:domain` | Models, `ArrivalDecisionEngine`, `ListenerPlanner`, `ScheduleResolver`/`DayTimeline`, `WifiMatcher`, use cases, repository interfaces | **None** (plain Kotlin/JVM module — the engine cannot import `android.*`) |
| `:app` | Room, typed DataStore, repository impls, Hilt, Compose UI | Yes |

Key files:

- `domain/.../engine/ArrivalDecisionEngine.kt` — every decision. Pure function of (signal, day context, injected `Clock`) → outcomes + new per-day state.
- `domain/.../engine/ListenerPlanner.kt` — what may be registered right now (enforces the section 5b budget).
- `domain/.../usecase/DetectionUseCases.kt` — `ProcessSignalUseCase` (the single entry point background receivers will call in Phase 3), `SettleDayUseCase`, `CheckMissedEntryUseCase`.
- `app/.../data/db/Daos.kt` — the SQL re-enforces the arrival/exit rules independently of the engine.
- `app/.../ui/home/` — Home screen, day log and edit sheet (Phase 4).
- `app/.../notifications/` — arrival and missed-entry notifications and their actions.
- `app/.../ui/shell/` — Phase 1 dev shell, now Settings → Simulator (dev).

## Phase 1: what exists

- **Data model (Room v1, schema exported to `app/schemas/`)**: `work_day` (PK = date → idempotent), `event_log`, `workplace`, `detection_state`. No destructive migration fallback.
- **Settings**: typed DataStore (`settings.json`) with versioned DTOs; unknown/invalid values fall back to defaults instead of crashing; corrupt file → defaults.
- **Engine rules** (all unit-tested):
  - Weekend / weekday OFF / holiday / leave / user-marked day → ignored. Manual "Log now" works any time, any day.
  - Arrival time = true event time (geofence ENTER time confirmed by DWELL; Wi-Fi callback time). ENTER alone never counts. DWELL with no ENTER seen → back-calculated (−2 min) and flagged `estimated`.
  - The arrival *time* must fall between arm time and window end (see Decisions). Older evidence (e.g. a drive-by whose EXIT was lost) never blocks a real arrival.
  - OR / AND / Wi-Fi only / Geofence only. AND uses the earlier signal if both are within 60 min and in-window, otherwise the moment both became true.
  - Earliest arrival wins; later signals never overwrite. A late-delivered signal proving an *earlier* in-window arrival may move an untouched auto arrival earlier — never a manual or edited one.
  - Exit: 10-min debounce in event time, re-entry cancels, last exit wins, re-entry after a committed exit reopens the day, out-of-order EXITs are discarded, a geofence EXIT while still on office Wi-Fi is treated as GPS jitter.
  - Missed entry: checked at window end + 10 min grace; offers any presence detected earlier as a one-tap suggestion.
  - Time zones: days and windows are evaluated in the current device zone (`DeviceZoneClock` re-reads it every call); each arrival stores its zone. DST gaps/overlaps tested with Europe/London.
- **Concurrency**: decide + write + state + raw log happen in one Room transaction (tested with 20 simultaneous geofence/Wi-Fi signals → exactly one arrival).

Tests (Phase 1): 90 domain unit tests + 19 Robolectric/JVM tests in `:app` (DAOs, settings serializer, engine-to-Room pipeline). Lint: clean except the intentional `OldTargetApi` (see below). The home-card ViewModel test from section 9 (`HomeViewModelTest`) came with Phase 4.

Battery cost of Phase 1: zero. Nothing runs in the background; no permissions are requested yet.

## Spec deviations and platform constraints

1. **targetSdk 36, compileSdk 37** (spec said 35 "latest stable"). Latest stable is API 37 (Android 17). Google Play has required API 36 for new apps and updates since 31 Aug 2026, and One UI 8 is Android 16 (API 36). Targeting 37 would opt into Android 17 behaviour changes that can't be tested on One UI 7/8.
2. **Geofencing foreground-service removal date**: Google's Play Console help dates it 26 Aug 2026, not 28 Oct 2026. No impact — the app never uses a foreground service.
3. **Radius 150–500 m** (spec section 1 said 100–500; section 5b sets a 150 m minimum). 5b wins; stored values are clamped.
4. **Wi-Fi PendingIntent callback**: `registerNetworkCallback(request, PendingIntent)` is delivered when a matching network becomes *available*; it has no "lost" delivery, and `FLAG_INCLUDE_LOCATION_INFO` applies only to `NetworkCallback` objects. Plan for Phase 3: read the SSID in the receiver via `WifiManager.getConnectionInfo()` (works with background location granted), treat every "available" as a new connection, and detect exits through geofence EXIT. Wi-Fi-lost is only seen while the process is alive (best effort). Will be verified on the S23 in Phase 3.
5. **Late delivery vs. window**: "outside the window never creates an arrival" is applied to the arrival *time*, not the processing time, so an ENTER at 10:58 confirmed at 11:03 still counts (window end + 10 min grace).
6. **"Never blank"**: the app can't force a choice if both missed-entry notifications are ignored. Unresolved past working days are flagged "needs a time or status" on Home, with a banner counting them.
7. **Job budget**: arm, missed-entry check (which also disarms when nothing was detected) and disarm = 3 per working day; 2 when exit tracking is off. The re-notify (only on missed days) and the opt-in exit reminder add one each. Phase 3 will also propose `AlarmManager.setAndAllowWhileIdle` (inexact, no exact-alarm permission) for the arm trigger, because WorkManager can defer a 06:15 job in Doze until the phone is picked up.
8. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** is removed (Play restricts it). Phase 2 uses the battery-optimisation settings list intent plus Samsung's "Unrestricted" and "Never sleeping apps" instructions instead.
9. **"Fully offline"**: the background is offline. The setup map needs network for tiles and search while it is on screen, so `INTERNET` is declared. Phase 2 will use the platform `Geocoder` for search (no Places API billing) unless you prefer Places.

Additions to the spec's data model (for debugging and correctness): `RawEvent.receivedAt` (shows delivery delay) and `bssid`, a `SYSTEM` event type (arm/disarm/checks, counted as wake-ups), `WorkDay.zoneId`, separate `arrivalEdited`/`exitEdited` (`editedManually` is derived), `originalExitAt`, `arrivalWorkplaceId`, and the `detection_state` table (per-day engine memory that must survive process death).

## Phase 2: setup wizard

First launch runs six steps; each can be reopened later from **Settings** (top-right on the home screen):

1. **Workplaces** (up to 3): Google map with address search (platform `Geocoder`, no Places billing), *Use my current location*, draggable pin or tap-to-place, 150–500 m radius with circle preview. Without a Maps key the editor falls back to current-location / typed coordinates.
2. **Office Wi-Fi** per workplace: connected network + cached scan results (a fresh scan only on tap), manual entry, *also match BSSID* (BSSIDs captured when you tick a network), *count as present when in range*.
3. **Detection**: the four modes, exit logging, exit reminder, faster detection.
4. **Schedule**: Mon–Sun → profile A / B / custom / Off, profile editor with validation, holidays and leave (date ranges).
5. **Permissions**: one card per permission, explanation shown before each system prompt (doubles as Google Play's prominent disclosure for background location), *Open settings* after a refusal, battery-optimisation list plus Samsung *Unrestricted* / *Never sleeping apps* steps.
6. **Test now**: readiness checklist, one location fix with distance to each fence, connected Wi-Fi and whether it matches, and whether an arrival *would* be logged in the current mode.

Precise location is requested the first time it is needed (map or Wi-Fi step), with the same explanation, rather than only at step 5: Android won't show Wi-Fi names without it.
Nothing runs in the background yet; that is Phase 3.

## Phase 3: background detection

`background/DetectionCoordinator` is the single entry point, serialised by one mutex:

- **Signals**: `GeofenceReceiver` (Play services geofence PendingIntent) and `WifiReceiver` (`ConnectivityManager.registerNetworkCallback(request, PendingIntent)`, delivered even when the app is not running) hand off with `goAsync()`. Geofence time = trigger location's fix time; Wi-Fi time = callback time. EXIT also reads the current Wi-Fi (office Wi-Fi still connected = GPS jitter, ignored). With "count as present when in range" and Battery Saver off, cached scan results are read while already awake for a geofence event.
- **Sync** (every wake): settle pending exits (today and yesterday), run the missed-entry check once when due (posts the notification, see Phase 4), reconcile registrations with `ListenerPlanner` (re-register only when the geofence signature or Wi-Fi flag differs, or on force), schedule the single next wake, daily housekeeping (event log kept 90 days).
- **Wakes**: one `AlarmManager.setAndAllowWhileIdle` alarm (inexact, Doze-allowed, no exact-alarm permission) at arm / missed-entry check / disarm / next working day, from `WakePlanner`. Plus a once-a-day WorkManager watchdog.
- **Forced re-registration** on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, time / time-zone change, settings edits, the watchdog, and after `GEOFENCE_NOT_AVAILABLE` (Location toggled off).
- **Battery guards**: fences expire one hour after their planned removal even if the disarm never runs; arrival with exit tracking off removes everything; days off register nothing.
- **Settings → Debug log**: arming status, next wake, wake-ups today, last error, every raw event, CSV export, *Re-arm*.

Device testing (mock locations, `adb` Wi-Fi toggles, Doze, reboot, battery stats): [`docs/TESTING.md`](docs/TESTING.md).

Known limitation: Android doesn't deliver "Location turned back on" to a stopped app, so after Location is switched off and on, fences return at the next wake, app open, or watchdog.

### Google Maps API key

Create an *Android* key for *Maps SDK for Android* in Google Cloud Console and restrict it to package `com.arrivelog` with these SHA-1 fingerprints:

- release (CI): `B3:CD:C8:1C:E7:9A:6E:1F:CE:2F:D1:DA:F7:A2:AB:53:B9:9B:85:51`
- debug: `./gradlew signingReport` on your PC

Add it as `MAPS_API_KEY` in `local.properties` and as the `MAPS_API_KEY` repository secret.

## Releases and signing

Every build on GitHub Actions (`.github/workflows/android.yml`) runs the tests, then builds:

- a debug APK (always),
- a **signed release APK + AAB** when the signing secrets are present,
- a **GitHub Release** for every signed build from `main`, tagged `v0.1.<run>` with matching `versionName` (APK + AAB). Works with Obtainium: add `https://github.com/nikhil914/Work-shift-timer`.
- a release for a tag `vX.Y.Z`, or from Actions → *Android build* → **Run workflow** with a version such as `0.2.0`. Bump `VERSION_PREFIX` in the workflow for a new minor line.

`versionCode` = 100 + the workflow run number, so it only goes up and every CI build installs over the previous one.
One keystore signs every release; the workflow fails if an APK is ever signed with a different certificate.

Release certificate SHA-256: `26:90:6D:C7:E9:CD:41:B0:75:38:EB:41:58:B1:90:51:50:B5:AD:40:28:81:19:15:91:B6:6C:BE:DF:BF:8B:AB`

Repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ARRIVELOG_KEYSTORE_BASE64` | base64 of `arrivelog-release.jks` |
| `ARRIVELOG_KEYSTORE_PASSWORD` | keystore password |
| `ARRIVELOG_KEY_ALIAS` | `arrivelog` |
| `ARRIVELOG_KEY_PASSWORD` | same as the keystore password (PKCS12) |
| `MAPS_API_KEY` | optional, from Phase 2 |

Local signed build: add `ARRIVELOG_KEYSTORE_FILE`, `ARRIVELOG_KEYSTORE_PASSWORD`, `ARRIVELOG_KEY_ALIAS`, `ARRIVELOG_KEY_PASSWORD` to `local.properties` and run `./gradlew :app:assembleRelease`.

**Keep two offline backups of the keystore and password.** If it is lost, no future version can update an installed copy.
For Google Play, enrol in Play App Signing and choose **"Use my own key"** (upload this key via the PEPK tool) so the Play build and the GitHub APKs share one certificate and update each other; with a Google-generated key they would not.

## Play requirements to plan for

- **Background location** needs the Play Console permissions declaration, a short video of the feature, and an in-app prominent disclosure before the system prompt (the Phase 2 explanation screens are written for this). Approval is Google's call; the core-feature case is strong but not guaranteed.
- **Privacy policy URL** and a **Data safety** form (location and Wi-Fi names processed on device only, never shared or uploaded).
- **targetSdk 36** today; expect the next yearly bump to 37 around Aug 2027.
- **Release**: Android App Bundle, Play App Signing, Maps key restricted to the package and the Play signing SHA-1 (Phase 6).
- **applicationId** is `com.arrivelog` for now and cannot change after the first upload, so pick the final one before publishing.

## Phase 4: Home, day log, edit sheet, notifications

- **Home** (opens after setup): today's card shows the arrival time large, a chip (*On time* / *Late by 12 min* / *Not yet* / *Not detected* / *Day off*, *WFH*, *Weekend* …), the source (📍 geofence, 📶 Wi-Fi, ✋ manual), a live *At work for* counter while you have no exit (it only ticks while the screen is visible), and the exit time. *Log arrival now* appears while nothing is logged.
- **Day log**: every day from the first recorded one, newest first, grouped by Monday weeks with the week's total. Row: date, arrival – exit, duration, source, ✎ if edited. Off days are greyed; past working days with neither an arrival nor a status show *Needs a time or status*.
- **Edit sheet** (tap a row or the card): arrival, exit, status (Working / Day off / WFH / Leave), note. Editing an auto-detected time keeps the detected time (*Detected 08:07 by Wi-Fi, edited by you*) and stops later auto signals from changing it. Clearing the arrival clears the exit and lets detection log again.
- **Notifications**
  - *Arrived 08:07 — logged* (geofence / Wi-Fi / by hand; *estimated* when you were already inside at arm time). **Edit time** opens the sheet on the arrival picker. **Undo** clears it and forgets the evidence, and the fences are re-armed *without* the "already inside" trigger so the same presence is not logged again a second later; a fresh entry or Wi-Fi connection logs normally.
  - *No arrival detected today* (high priority) at window end + 10 min. **Log now** (only on the day itself), **Pick time** (preselects presence seen before the window, if any), **Day off / WFH / Leave**. Repeated once 60 min later if still unresolved (one extra wake, only on such days). Logging an arrival or marking the day cancels it.
  - Without the notification permission both are skipped; Home still flags the day.

Not in Phase 4: the exit reminder (*Still at work?*), stats and the health screen come in Phase 5.

## Testing Phase 1 on the phone

These steps now live under **Settings → Simulator (dev)**.

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (or Run from Studio).
The shell drives the real engine and database with simulated signals at a time you type (today only). The first simulated signal creates a "Demo office" (SSID `ArriveLog-Demo`).

1. Launch. Today's card shows the right profile (Mon–Thu: 06:30–11:00, expected 08:00; Fri: 06:30–10:00; Sat/Sun: "Not a working day").
2. Time `08:07` → **Office Wi-Fi connected** → "Arrived 08:07 · Wi-Fi", log shows `ARRIVAL_RECORDED`.
3. Time `08:30` → Wi-Fi again → `ALREADY_ARRIVED`, arrival unchanged.
4. Time `08:03` → **Geofence ENTER** (`ALREADY_ARRIVED`), time `08:05` → **Geofence DWELL** → `ARRIVAL_MOVED_EARLIER`, arrival now 08:03.
5. Pick a time after 08:30 and at least 10 min before now → **Geofence EXIT** → "Exit pending". **Settle pending exit** → exit recorded at that time. Then Wi-Fi at a later time → `EXIT_REOPENED`, exit back to "—".
6. Switch mode to **Geofence AND Wi-Fi**, then force-stop the app (or reboot) and reopen: the mode and today's data persist.
7. (Mon–Thu) **Reset today**, time `06:20` → Wi-Fi → logged 06:20 (early arrival). **Reset today** again, time `11:30` → Wi-Fi → `AFTER_WINDOW`. After 11:10 real time, **Missed-entry check** → `NOTIFY@11:30` (the detected time is offered as a suggestion).
8. Rotate / switch dark mode: no crash, dynamic colours follow the wallpaper.

## Later phases (not built yet)

5 exit/stats/health · 6 export/widget/QS tile/backup + battery audit.
