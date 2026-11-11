# FPS Setter

Pick a refresh rate and the app sets your phone's screen to it, and keep RAM
clear while you play. Uses Shizuku (no root needed).

## Build

1. Open this folder in Android Studio (Koala or newer) and let it sync.
2. Run it on your phone, or use Build > Build APK(s).

## Use

1. Install Shizuku and start it (wireless debugging or root).
2. Open FPS Setter and tap **Grant**.
3. Tap one of the rates your screen supports (or type one) and press **Apply**.
4. **Reset to default** undoes everything.

## What it does

| Option | Command run through Shizuku |
| --- | --- |
| Always | `settings put system peak_refresh_rate <fps>` |
| Lock it | `settings put system min_refresh_rate <fps>` |
| Force display mode (Android 13+) | `cmd display set-user-preferred-display-mode <w> <h> <fps>` |
| Brand keys, Xiaomi/Redmi/POCO | `user_refresh_rate`, `miui_refresh_rate` |
| Brand keys, Samsung | `refresh_rate_mode` |
| Remove game FPS cap (Android 15+) | `setprop debug.graphics.game_default_frame_rate.disabled true` |
| Game package filled in | `cmd game mode performance <pkg>` and `cmd game set --mode performance --fps disable <pkg>` |

## RAM booster

- **Boost now** clears memory once. **Start auto boost** keeps doing it on a
  timer (every 10 to 600 seconds) from a background service with a
  notification and a Stop button.
- Normal mode runs `am kill-all`, which closes apps that are only cached in
  the background.
- **Aggressive** also runs `am force-stop` on every running app you installed,
  except the app on screen, your keyboard, your launcher, Shizuku, this app,
  the game package, and anything in the keep list.

## Get the APK

Every push builds the app with GitHub Actions. Open the **Actions** tab, pick
the latest "Build APK" run and download **FpsSetter-debug-apk**.

## Limits

- It cannot go above what the screen hardware supports. A 60 Hz panel stays 60.
- It removes software caps on the screen. A game with its own internal FPS cap
  keeps that cap.
- The game FPS cap switch is cleared by a reboot. Press Apply again after restarting.
- Aggressive boosting stops closed apps from sending notifications until you
  open them again, and Android may restart some of them by itself.
- Some brands reset these settings on reboot or when battery saver turns on.
  Just press Apply again.
