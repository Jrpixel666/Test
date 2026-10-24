# Debian ARM Launcher

An Android home screen that can run **Debian 13 (trixie) for arm64** next to your phone apps,
**under proot, without root**. The launcher itself is the Wayland compositor: every Linux window
opens as its own Android task. There's no VNC anywhere; Owl widgets and Hyprland draw through the launcher.

Built on [Omalauncher](https://github.com/SimonSchubert/omalauncher) (MIT), with its Linux side
moved from Arch Linux ARM to Debian ARM, and with its widget language
[Owl](https://github.com/SimonSchubert/omalauncher/tree/main/owl) built in (`owl/`).

## What it does

- **First start, and every start until Debian is installed:** choose **launcher only** or
  **launcher and Debian ARM**. Picking the launcher only asks again the next time it starts.
- **Debian under proot:** the rootfs is the official arm64 `debian:trixie` image (exported by
  `.github/workflows/rootfs.yml` to the `rootfs-debian-trixie` release, checked by SHA-256 on download).
- **Firmware and kernel modules are disabled:** they're skipped when unpacking, dpkg has `path-exclude`
  rules for `/lib/firmware`, `/usr/lib/firmware`, `/lib/modules`, `/usr/lib/modules` and `/boot`, and apt
  pins `linux-image-*`, `linux-modules-*`, `firmware-*` and the microcode packages to priority -1.
- **No VNC:** apt pins `tigervnc-*`, `tightvncserver`, `x11vnc`, `wayvnc` and the rest to -1, so they never install.
- **Owl:** the home screen's widgets, apps and panels are Owl programs (`app/src/main/assets/widgets`);
  `owl-check` and `owl-send` are in the guest's PATH.
- **Hyprland:** Debian 13 has no Hyprland, so setup installs it from Debian testing (forky), pinned so
  nothing else moves to testing, with `waybar`, `wofi` and `mako` from trixie, plus a **Hyprland** entry in the
  app drawer that starts it nested in the launcher (`/usr/local/bin/hyprland-session`, config in
  `~/.config/hypr/hyprland.conf`).
- **Settings › tweaks:** reads the GTK, icon and cursor themes your Debian install has, sets Hyprland's gaps,
  border, rounding, animations and blur (`~/.config/hypr/tweaks.conf`), and installs theme packs (Arc,
  Numix, Orchis, Papirus, Breeze, DMZ) with apt.
- **Themes:** the start screen and Settings › style pick one of Omarchy's color themes; only that theme's
  colors and wallpaper download (a few MB). Omarchy's full theme pack (~70 MB) is never downloaded.
- **Settings › packages:** search, install and remove Debian packages with apt.

Needs an arm64 phone with Android 10 or newer.

## Known limits

- **Hyprland needs a GPU render node.** Unrooted Android apps can't open `/dev/dri`, so on most phones
  Hyprland won't start inside proot; the reason is in `/tmp/hyprland.log`. Every app still opens in the
  launcher's own compositor.
- **No GPU acceleration yet.** Upstream's Mali and Adreno drivers are Arch builds, so Linux apps render
  in software (llvmpipe and lavapipe).

## Installing

Download `debian-arm-launcher-<version>.apk` from the
[latest build](https://github.com/Jrpixel666/Test/releases/tag/launcher-latest) and open it on the phone.
Builds are signed with a public test key (`signing/public-test.jks`), so a newer build installs over an
older one. It is **not** a secret key: use your own (`keystore.properties`) for anything you distribute.

## Building

```sh
./scripts/fetch-proot.sh          # Termux's proot -> app/src/main/jniLibs (needs the NDK and bsdtar)
./gradlew :app:assembleRelease
```

CI (`.github/workflows/build.yml`) does the same on every push, checks every Debian package name the
installer uses against trixie, runs the tests and uploads the APKs.
