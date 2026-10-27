# Testing ArriveLog on a device

Everything detection does is written to **Settings → Debug log** (and its CSV export). Check it after every test: each signal shows its true event time, the processing delay, and why it was accepted or ignored.

## Setup for testing

1. Install from GitHub Releases / Obtainium (signed) or `./gradlew installDebug`.
2. Finish the setup wizard. On **Test now** all blocking items must be green.
3. To test outside your real hours, make a temporary custom profile covering *now* (Schedule → Add custom profile) and assign it to today. Change it back afterwards.

## Geofence with mock locations

1. Developer options → **Select mock location app** → a mock app (e.g. Lockito or "Mock Locations").
2. Start the mock route *outside* the fence (> radius + 200 m), wait a minute, then move it inside the fence and keep it there for at least 3 minutes (2 min loitering + responsiveness).
3. Expected in the Debug log: `GEOFENCE_ENTER ✓ AWAITING_DWELL`, then `GEOFENCE_DWELL ✓ ARRIVAL_RECORDED` with the ENTER time, then `GEOFENCE_ARMED:PRESENCE` (or `GEOFENCE_REMOVED` with exit tracking off).
4. Move outside for 12 minutes → `GEOFENCE_EXIT ✓ EXIT_PENDING`; the exit is committed at the next wake/sync (open the app, or tap **Re-arm** in the Debug log).

Mock locations do not always reach Play services' geofencing on Samsung; if nothing arrives, test once on the real commute.

## Wi-Fi

With USB debugging (`adb` from your PC):

```bash
adb shell svc wifi disable     # drop Wi-Fi
adb shell svc wifi enable      # reconnect → WifiReceiver fires within seconds
adb shell cmd wifi status      # what the phone is connected to
```

Expected: `WIFI_CONNECTED ✓ ARRIVAL_RECORDED` inside the window, or `WIFI_CONNECTED ✗ SSID_NOT_OFFICE` for other networks.
`SSID_UNKNOWN` means the name was hidden from the app: check "Allow all the time" location and that Location is on.

## Wakes, boot, time zone

```bash
adb shell dumpsys alarm | grep -A3 com.arrivelog          # the single pending wake
adb shell dumpsys activity service WorkManager | grep arrivelog
adb reboot                                                 # Debug log should show WAKE:BOOT:FORCE + GEOFENCE_ARMED
adb shell cmd alarm set-timezone Asia/Kolkata              # WAKE:TIMEZONE_CHANGED:FORCE
adb shell cmd alarm set-timezone Asia/Dubai
```

Doze (the 06:15 arm must still fire):

```bash
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce
```

## Exit reminder, Stats, Health (Phase 5)

1. Detection mode → turn on **Remind me to add the exit time**. Use a temporary profile whose expected end is about 2 h before now, with an arrival logged today and no exit. Open the app (or wait for the wake): *Still at work?* appears once. **Left now** logs the exit (source manual) and the notification goes away; it does not come back the same day.
2. **Undo** on an arrival while still at the office: the arrival disappears and is *not* logged again within a minute. Debug log shows `GEOFENCE_ARMED:ARRIVAL` with no new `GEOFENCE_DWELL ✓ ARRIVAL_RECORDED`.
3. **Stats**: switch Week / Month and step back. Numbers should match the Home day log (late days, hours of days with an exit). Edit a day and come back: the stats update.
4. **Health**: turn Location off → "Location turned on" goes red with Fix; turn it back on and return → green. Set battery to Optimised → red; Fix opens the battery list. **Re-arm now** writes `WAKE:HEALTH:FORCE` to the Debug log.

## Export, widget, tile, backup (Phase 6)

1. **Export**: Settings → Export & backup → *This month* → XLSX → share to Drive or email; open it in Excel / Sheets: one row per day, hours as numbers. Repeat with CSV.
2. **Widget**: long-press the home screen → Widgets → ArriveLog → *Today's arrival*. It shows today's arrival; edit today's time in the app and the widget follows within a few seconds.
3. **Tile**: pull down Quick Settings → edit (pencil) → drag *Log arrival* in. On a day with no arrival, tap it: the arrival notification appears and the tile shows "Arrived HH:mm". Tapping again changes nothing.
4. **Backup**: *Save backup* to Downloads. Edit a day, then *Restore* that file: the confirm dialog shows the counts, and the day is back to its saved value. A random JSON file gives "not an ArriveLog backup" and changes nothing.
5. **Battery numbers**: Debug log → *Registered today* should roughly match the time between `GEOFENCE_ARMED` and `GEOFENCE_REMOVED` lines.

## Battery

Full audit and the one-week measurement: [`BATTERY.md`](BATTERY.md).

```bash
adb shell dumpsys batterystats --reset
# ... one normal working day ...
adb shell dumpsys batterystats --charged com.arrivelog
adb bugreport bugreport.zip     # load into Battery Historian for the timeline
```

The Debug log's "Wake-ups today" should be about 3 (arm, missed-entry check or disarm, plus one per real Wi-Fi/geofence event) on a working day and 0–1 on a weekend.
