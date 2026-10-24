package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebianTest {
    @Test fun readsAptsUpgradableLines() {
        assertEquals("bash 5.2.37-1 → 5.2.37-2", upgradable("bash/stable 5.2.37-2 arm64 [upgradable from: 5.2.37-1]"))
        assertEquals("libc6 2.41-12 → 2.41-12+deb13u1", upgradable("libc6/stable-security 2.41-12+deb13u1 arm64 [upgradable from: 2.41-12]"))
        assertNull(upgradable("Listing..."))
        assertNull(upgradable(""))
    }

    @Test fun mapsArchNamesWidgetsDeclare() {
        assertEquals(listOf("procps", "jq"), Apt.required(mapOf("pacman" to listOf("procps-ng", "jq"))))
        // An apt list wins: it names Debian's packages already.
        assertEquals(listOf("ncdu"), Apt.required(mapOf("apt" to listOf("ncdu"), "pacman" to listOf("dua-cli"))))
        assertEquals(emptyList<String>(), Apt.required(emptyMap()))
    }

    @Test fun installsQuotedWithoutRecommends() {
        val script = aptInstall(listOf("foot", "it's"))
        assertTrue(script.startsWith("apt-get update && apt-get install -y --no-install-recommends 'foot' 'it'\\''s' && "))
        val each = aptInstallEach(listOf("hyprland", "waybar"))
        assertTrue(each.contains("for p in 'hyprland' 'waybar'; do"))
    }

    @Test fun tweaksWriteGtkAndHyprland() {
        val v = TweakValues(gtkTheme = "Arc-Dark", iconTheme = "Papirus-Dark", cursorTheme = "Breeze_Light", cursorSize = 32, gapsIn = 3, blur = true)
        val ini = Tweaks.gtkSettings(v, mapOf("gtk-font-name" to "Inter 11", "gtk-theme-name" to "Adwaita"))
        assertTrue(ini.startsWith("[Settings]\n"))
        assertTrue("gtk-font-name=Inter 11\n" in ini)
        assertTrue("gtk-theme-name=Arc-Dark\n" in ini)
        assertTrue("gtk-icon-theme-name=Papirus-Dark\n" in ini)
        assertTrue("gtk-cursor-theme-size=32\n" in ini)
        // A theme already dark isn't asked for its dark variant again.
        assertEquals(listOf("GTK_THEME=Arc-Dark", "XCURSOR_THEME=Breeze_Light", "XCURSOR_SIZE=32"), Tweaks.env(v))
        assertEquals("GTK_THEME=Arc:dark", Tweaks.env(v.copy(gtkTheme = "Arc")).first())
        val conf = Tweaks.hyprlandConf(v)
        assertTrue("gaps_in = 3" in conf)
        assertTrue("enabled = true" in conf)
        assertTrue("env = XCURSOR_THEME, Breeze_Light" in conf)
    }
}
