# Battery audit (spec section 5b)

Target: **under 1% battery per day** with battery set to *Unrestricted* on a Samsung Galaxy (One UI 7/8).

This page has two parts: how the design meets each 5b rule (checked in code), and the measured numbers, which need a one-week run on the phone and are filled in after that run.

## Design check, rule by rule

| 5b rule | How ArriveLog meets it | Where |
|---|---|---|
| Event-driven only: no location updates, GPS polling, wake locks, foreground service, polling loops, network | Geofences (Play services) and the Wi-Fi `PendingIntent` callback deliver events to receivers; nothing requests location updates. No `WAKE_LOCK`, no `FOREGROUND_SERVICE` in the manifest. The only network use is map tiles and search on the setup screen while it is open. | `AndroidManifest.xml`, `background/Receivers.kt` |
| Geofence responsiveness 180 s (60 s opt-in), radius ≥ 150 m, ≤ 3 fences | `ListenerPlanner` sets responsiveness from `fasterDetection`; workplaces are capped at 3 and radius at 150–500 m. | `ListenerPlanner.kt`, `Workplace.kt` |
| Arm only when needed | Fences go up 15 min before the entry window, come down at arrival (exit tracking off), at expected end + 3 h (exit tracking on), or at window end with nothing detected. Days off register nothing. Fences also carry an expiry one hour after their planned removal, so a missed disarm cannot leave them up. | `ListenerPlanner.kt`, `Registrars.kt` |
| Wi-Fi without scanning | `startScan()` is never called. Connected SSID comes from the `ConnectivityManager` callback; cached scan results are read only while already awake for a geofence event, and never in Battery Saver. | `WifiSource.kt`, `DetectionCoordinator.kt` |
| Cheapest signal first, then stand down | In OR mode, the first arrival removes the Wi-Fi callback and switches fences to presence-only (or removes them if exit tracking is off). | `ListenerPlanner.kt` |
| Optional GPS confirmation is one balanced-power fix with a 30 s timeout | Off by default; skipped in Battery Saver. | `LocationSource.kt` |
| At most 3 scheduled wakes per working day, inexact, no exact-alarm permission | `WakePlanner` keeps a single next wake: arm, missed-entry check, disarm. Extras only when they apply: one re-notify on a missed day, one settle-exit after leaving, and the opt-in exit reminder. `setAndAllowWhileIdle` (inexact). One daily watchdog job (WorkManager). | `WakePlanner.kt`, `Receivers.kt` |
| Widget updates only on change | `updatePeriodMillis = 0`. `WidgetUpdater` redraws only when today's arrival, exit, status or chip would change, from inside a sync that is already running, so the widget adds no wake-ups. | `widget/TodayWidget.kt` |
| Live counter ticks only while visible | Home's one-minute ticker is collected with `WhileSubscribed(0)` and `collectAsStateWithLifecycle`. | `HomeViewModel.kt` |
| Quick Settings tile | Bound by the system only while the panel is open (`ACTIVE_TILE = false`); no background work of its own. | `tile/LogArrivalTileService.kt` |
| Battery Saver | Fences stay; GPS confirmation and in-range Wi-Fi reads are skipped. | `DetectionCoordinator.kt` |
| Measure it | Settings → Debug log shows *Wake-ups today*, *Registered today* (hours of geofences and Wi-Fi callback, rebuilt from the event log) and *Listeners active now*. | `ui/debug/DebugLogScreen.kt` |

### Expected daily cost (estimate, to be confirmed by the measurement below)

- **Working day, exit tracking on:** about 3 scheduled wakes plus one per real event (arrival, leaving). Geofences are registered for roughly the entry window plus the working day (≈ 12–13 h). Geofencing is batched by Play services with the location the phone already computes, so its cost is shared with other apps and stays small at 180 s responsiveness.
- **Working day, exit tracking off:** after the arrival nothing is registered at all.
- **Weekend / day off / leave:** no listeners, 0–1 wakes.

## Samsung (One UI)

*Unrestricted* battery is not enough on its own. One UI also has **Sleeping apps** and **Deep sleeping apps** lists (Settings → Device care → Battery → Background usage limits). An app in those lists does not get its alarms or geofence broadcasts until it is opened. ArriveLog cannot read those lists, so:

- the setup wizard's permission step and the Health screen show the Samsung steps (add ArriveLog to **Never sleeping apps**);
- the daily watchdog job and every app open re-register anything that should be up (`WAKE:WATCHDOG:FORCE`, `WAKE:APP_OPEN` in the Debug log), so a period of being put to sleep heals by itself;
- the Health screen turns red when what is registered differs from what should be.

## How to measure (one week)

1. Charge to 100%, then reset the stats:
   ```bash
   adb shell dumpsys batterystats --reset
   ```
2. Use the phone normally for 7 days (unplug each morning). Each evening note *Wake-ups today* and *Registered today* from Settings → Debug log.
3. At the end of the week:
   ```bash
   adb shell dumpsys batterystats --charged com.arrivelog > arrivelog-stats.txt
   adb bugreport bugreport.zip    # open in Battery Historian for the per-app timeline
   ```
   In `arrivelog-stats.txt`, look at the *Estimated power use* line for the app's UID, and at *Wakeup alarms* and *Jobs*.
4. Settings → Battery → (app) on the phone also shows the app's share since the last full charge.

## Measured results

Not measured yet. These need the one-week run above on the S23 Ultra and are filled in afterwards.

| Day | Type | Wake-ups | Geofences registered | Wi-Fi callback registered | App share of battery |
|---|---|---|---|---|---|
| | | | | | |

Pass condition: the app's share stays under 1% of a full charge on every day.
