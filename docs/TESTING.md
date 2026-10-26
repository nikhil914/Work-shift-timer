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

## Notifications and Home (Phase 4)

Use a temporary custom profile covering *now* (see Setup) so the window opens and closes while you watch.

1. Arrival: trigger Wi-Fi (`adb shell svc wifi disable` / `enable` at the office) → *Arrived HH:mm — logged*. Tap **Edit time**: the sheet opens on the arrival picker. Change it; Home shows ✎ and the sheet shows *Detected … edited by you*.
2. Undo: on a fresh arrival notification tap **Undo**. Home goes back to *Not yet*; the Debug log shows `UNDO_ARRIVAL` and `GEOFENCE_ARMED:ARRIVAL` but **no** immediate new `ARRIVAL_RECORDED`. Reconnecting Wi-Fi afterwards logs a new arrival.
3. Missed entry: stay away (or switch Wi-Fi off) until window end + 10 min → *No arrival detected today*. Try each action: **Log now** logs the current time; **Pick time** opens the picker; **Day off / WFH / Leave** opens the sheet on the status chips. Ignore it once and it repeats ~60 min later (`MISSED_ENTRY_RENOTIFY`).
4. Never blank: leave a working day unresolved; the next day Home shows it as *Needs a time or status* and the banner counts it.
5. Live counter: with an arrival and no exit, *At work for* updates within 30 s while Home is open.

```bash
adb shell dumpsys notification --noredact | grep -A3 "pkg=com.arrivelog"   # posted notifications
```

## Battery

```bash
adb shell dumpsys batterystats --reset
# ... one normal working day ...
adb shell dumpsys batterystats --charged com.arrivelog
adb bugreport bugreport.zip     # load into Battery Historian for the timeline
```

The Debug log's "Wake-ups today" should be about 3 (arm, missed-entry check or disarm, plus one per real Wi-Fi/geofence event) on a working day and 0–1 on a weekend.
