package dev.omalauncher.linux

import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File

/** Exec of the drawer's Hyprland entry. */
const val HYPRLAND_COMMAND = "hyprland-session"

/**
 * Hyprland inside the launcher. The launcher is the Wayland compositor that Linux windows open on (no
 * VNC, no X server); Hyprland starts as one of its clients, with its Wayland backend, and tiles the apps
 * started inside it in that window. Its config is the user's; settings › tweaks writes only tweaks.conf.
 */
object Hyprland {
    /** Where settings › tweaks writes gaps, borders and the rest; hyprland.conf sources it. */
    const val TWEAKS_CONF = "/root/.config/hypr/tweaks.conf"

    /**
     * Nested Hyprland. Aquamarine picks its Wayland backend because WAYLAND_DISPLAY names the launcher's
     * socket; Hyprland makes its own socket for the apps inside it. Without a GPU render node it can't start,
     * and says why in /tmp/hyprland.log.
     */
    private val SESSION =
        """
        #!/bin/sh
        # Written by the launcher on each start; edit ~/.config/hypr/hyprland.conf instead.
        export XDG_CURRENT_DESKTOP=Hyprland XDG_SESSION_DESKTOP=Hyprland
        export AQ_NO_ATOMIC=1 WLR_NO_HARDWARE_CURSORS=1 HYPRLAND_NO_SD_NOTIFY=1 HYPRLAND_NO_RT=1
        mkdir -p "${'$'}XDG_RUNTIME_DIR"
        bin=${'$'}(command -v Hyprland || command -v hyprland)
        if [ -z "${'$'}bin" ]; then
          echo "Hyprland isn't installed: settings › packages › hyprland" >&2
          exit 127
        fi
        "${'$'}bin" "${'$'}@" 2>&1 | tee /tmp/hyprland.log
        """.trimIndent() + "\n"

    private val CONFIG =
        """
        # Hyprland in the Debian ARM launcher. Written once; it's yours to edit.
        # Settings › tweaks writes tweaks.conf, sourced at the end.

        monitor = , preferred, auto, 1

        ${'$'}terminal = foot
        ${'$'}menu = wofi --show drun

        exec-once = waybar
        exec-once = mako

        env = XCURSOR_SIZE, 24

        input {
            kb_layout = us
            follow_mouse = 1
            touchpad {
                natural_scroll = true
            }
        }

        general {
            layout = dwindle
            resize_on_border = true
        }

        dwindle {
            pseudotile = true
            preserve_split = true
        }

        misc {
            disable_hyprland_logo = true
            disable_splash_rendering = true
        }

        ${'$'}mod = SUPER
        bind = ${'$'}mod, Return, exec, ${'$'}terminal
        bind = ${'$'}mod, T, exec, ${'$'}terminal
        bind = ${'$'}mod, D, exec, ${'$'}menu
        bind = ${'$'}mod, Q, killactive,
        bind = ${'$'}mod, W, killactive,
        bind = ${'$'}mod, F, fullscreen,
        bind = ${'$'}mod, V, togglefloating,
        bind = ${'$'}mod SHIFT, E, exit,
        bind = ${'$'}mod, left, movefocus, l
        bind = ${'$'}mod, right, movefocus, r
        bind = ${'$'}mod, up, movefocus, u
        bind = ${'$'}mod, down, movefocus, d
        bind = ${'$'}mod, 1, workspace, 1
        bind = ${'$'}mod, 2, workspace, 2
        bind = ${'$'}mod, 3, workspace, 3
        bind = ${'$'}mod, 4, workspace, 4
        bind = ${'$'}mod SHIFT, 1, movetoworkspace, 1
        bind = ${'$'}mod SHIFT, 2, movetoworkspace, 2
        bind = ${'$'}mod SHIFT, 3, movetoworkspace, 3
        bind = ${'$'}mod SHIFT, 4, movetoworkspace, 4
        bindm = ${'$'}mod, mouse:272, movewindow
        bindm = ${'$'}mod, mouse:273, resizewindow

        source = ~/.config/hypr/tweaks.conf
        """.trimIndent() + "\n"

    /** The session script (always current) and the config (the user's once edited). */
    fun managed(proot: Proot): List<Managed> =
        listOf(
            Managed.text(File(proot.rootfs, "usr/local/bin/$HYPRLAND_COMMAND"), Policy.Owned, executable = true) { SESSION },
            Managed.text(File(proot.home, ".config/hypr/hyprland.conf"), Policy.Seeded) { CONFIG },
        )
}
