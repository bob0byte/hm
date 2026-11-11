# FPS Setter

A game booster for Android that works through Shizuku (no root needed).

## What it does

- **Auto game mode:** pick your games once. When one opens, the app applies
  your tweaks, clears RAM on a timer and shows an FPS counter. When you leave
  the game it puts everything back.
- **Input lag reducer:** holds the screen at its fastest refresh rate and
  stops the system from dropping it, lifts Android's frame limit for games
  (15+), puts the game in performance mode, turns battery saver off, optional
  do-not-disturb, optional lower render resolution.
- **Wi-Fi guard:** takes other installed apps offline while you play, using
  Android's built-in per-app firewall, and restores them when you leave.
- **In-game counter:** FPS, ping, free RAM and battery temperature.
- **RAM cleaner:** normal mode clears cached background apps; aggressive mode
  also force-closes running apps you installed.

## What is never closed

- Any system app (only apps you installed yourself can be closed).
- The app or apps on screen, this app, Shizuku, Google Play services, Play Store.
- Your keyboard, launcher, phone app, SMS app, assistant and autofill service.
- Apps with accessibility or notification access, your always-on VPN,
  device admin apps, your live wallpaper, and apps with an active media
  session (music, calls).
- Your chosen games and everything in the "never close" list.

## Get the APK

Every push builds the app with GitHub Actions. Open the **Actions** tab, pick
the latest "Build APK" run and download **FpsSetter-debug-apk**.

## Limits

- No outside app can make a game draw frames faster than the game chooses to.
  This app removes the limits Android adds on top.
- The screen can't show more frames than its hardware supports.
- The game FPS cap switch is cleared by a reboot; it is re-applied whenever a
  game opens.
- Aggressive RAM cleaning stops closed apps from sending notifications until
  you open them again.
