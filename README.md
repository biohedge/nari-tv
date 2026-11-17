# Nari TV Router 0.2: Game refresh & USB auto-recovery (EXPERIMENTAL)

Project built from Nari TV Router 0.1 for Android TV 14 and Razer Nari Ultimate.

## Why this version exists

The device really produces sound over `card=N;device=1` (Game) after Nari TV Router has added Game and removed Chat. However, after disconnect/reconnect Android may show Game as the selected Audio Policy output without reopening the physical HAL output, so sound may be missing until the output is toggled.

## New controls

- **4 · Обновить Game (при пропаже звука)** — performs an explicit transition `Game UNAVAILABLE -> 850ms -> Game AVAILABLE -> Chat UNAVAILABLE`. This is **not the same** as just announcing that Game is available. It should ask Android Audio Policy/AudioFlinger to recreate the USB output. It hasn't been confirmed to repair playback on this particular TCL firmware yet. Expect a brief audio interruption.
- **Автовосстановление USB** — starts a persistent daemon-mode Shizuku UserService (UID shell 2000), monitoring the selected ALSA card about once per second. It schedules a refresh after detecting disappearance/reappearance of the PCM device nodes or Android re-registering Chat. It never periodically bounces the output while playback is otherwise working.
- **Посмотреть состояние автоматики** — displays the last action or error.
- **Вернуть Chat** — also disables auto-recovery so it cannot unexpectedly switch back to Game.

### Limitations

1. If **only the headset power** toggles while the USB dongle remains connected, Android may generate no USB/ALSA events. No reliable event-based auto-recovery can be promised; use the one-click **Обновить Game** button. The dongle may still be attached to the USB bus the entire time.
2. The monitor watches the **configured ALSA card** (4 in the original user's log). A new card number after USB re-enumeration requires opening the app and updating the card value and enabling auto-repair again. This avoids accidentally touching another USB sound card.
3. Shizuku is still required and must be restarted after reboot if running through ADB without root.
4. The `setWiredDeviceConnectionState` call tells AudioService about availability. It cannot prove that a hardware stream reopened successfully. Actual sound still needs testing.
5. Installing the debug APK over the previous version preserves local settings. Both versions share the `dev.naritv` package and should be signed by the same debug key. If installing from a different GitHub Actions environment changed the signing key, Android might reject an update; in that case you may need to uninstall the old build (losing app prefs), then install this one.

## Build using GitHub Actions

Replace project files, commit/push the repo, and run **Actions → Build experimental Nari TV APK**.
The workflow installs Android API 35 / Build Tools 35 and runs `gradle :app:assembleDebug`. The APK is under the **nari-tv-router-debug-apk** artifact.

Install using `adb install -r app-debug.apk` if signed by the same debug key, otherwise uninstall the old package first.

## Test sequence

1. Enable Shizuku, then start Nari TV Router. Confirm correct ALSA card. Keep the headphone volume low for test.
2. With current working Game route, turn off/on or unplug/replug the USB dongle until the sound becomes stuck.
3. Press **Обновить Game** once and test if sound comes back **without manually switching TV audio outputs**.
4. If yes, enable **Автовосстановление USB**, close the application, then disconnect/reconnect the USB dongle. Check the actual sound and app's **Посмотреть состояние автоматики**.
5. If physical sound remains absent, restore Chat and provide the app auto-status + `adb logcat -d -s NariTV:*` for further debug.

## Changes

- Shizuku UserService switched to `daemon(true)` and version 2, to survive closing the UI.
- Shizuku UserService polls ALSA presence and uses `AudioSystem.getDeviceConnectionState()` when supported.
- Manual Game reinitialization intentionally disconnects and reconnects **Game**, unlike the old 'switch' function that simply re-announces the currently available output.
- No modifications to `/system`, `/vendor`, or root permissions.

Source: `app/src/main/java/dev/naritv/RouteService.java`, `MainActivity.java`, `IRouteService.aidl`.
