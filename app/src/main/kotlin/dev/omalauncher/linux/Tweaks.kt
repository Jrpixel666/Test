package dev.omalauncher.linux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.upgrade.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/** What settings › tweaks sets; null themes follow the launcher's theme. */
data class TweakValues(
    val gtkTheme: String? = null,
    val iconTheme: String? = null,
    val cursorTheme: String? = null,
    val cursorSize: Int = 24,
    val preferDark: Boolean = true,
    val gapsIn: Int = 5,
    val gapsOut: Int = 10,
    val borderSize: Int = 2,
    val rounding: Int = 8,
    val animations: Boolean = true,
    val blur: Boolean = false,
)

/** The themes the Debian install has, read from its share directories (and the user's own). */
data class InstalledThemes(
    val gtk: List<String> = emptyList(),
    val icons: List<String> = emptyList(),
    val cursors: List<String> = emptyList(),
)

/**
 * A package of themes the tweaks screen offers to install; [pkg] is its Debian name.
 */
data class ThemePack(
    val pkg: String,
    val title: String,
    val description: String,
)

/** GTK, icon and cursor themes from Debian's archive; the tweaks screen installs them with [Apt]. */
val THEME_PACKS =
    listOf(
        ThemePack("gnome-themes-extra", "Adwaita, HighContrast", "GNOME's GTK themes, dark ones too"),
        ThemePack("arc-theme", "Arc", "flat GTK theme with dark and darker variants"),
        ThemePack("numix-gtk-theme", "Numix", "modern flat GTK theme"),
        ThemePack("orchis-gtk-theme", "Orchis", "rounded Material-like GTK theme"),
        ThemePack("papirus-icon-theme", "Papirus", "icon theme, light and dark"),
        ThemePack("adwaita-icon-theme", "Adwaita icons", "GNOME's icons and cursor"),
        ThemePack("breeze-icon-theme", "Breeze icons", "KDE's icon theme"),
        ThemePack("breeze-cursor-theme", "Breeze cursor", "KDE's cursor theme"),
        ThemePack("dmz-cursor-theme", "DMZ cursors", "white and black cursor themes"),
    )

/**
 * Settings › tweaks: themes and Hyprland's look, taken from what the Debian install has. Saved in the
 * launcher's files; [apply] writes them into the guest (GTK's settings.ini, the default cursor,
 * Hyprland's tweaks.conf) and into the environment of every Linux app started from then on.
 */
class Tweaks(
    private val proot: Proot,
) {
    private val file = File(proot.filesDir, "tweaks.properties")

    var values by mutableStateOf(load())
        private set

    var installed by mutableStateOf(InstalledThemes())
        private set

    /** Lists the themes the guest has now; after a theme pack installs too. */
    suspend fun scan() {
        installed = withContext(Dispatchers.IO) { scanThemes() }
    }

    /** Saves [new] and writes it into the guest. */
    suspend fun set(new: TweakValues) {
        values = new
        withContext(Dispatchers.IO) {
            save(new)
            apply()
        }
    }

    /** Writes the saved tweaks into the guest; Runtime calls it once Linux runs. Blocking. */
    fun apply() {
        val v = values
        proot.tweakEnv = env(v)
        if (!proot.rootfs.isDirectory) return
        // Hyprland's config sources this file, so it's always there.
        writeAtomically(proot.hostFile(Hyprland.TWEAKS_CONF).also { it.parentFile?.mkdirs() }, hyprlandConf(v).toByteArray())
        // Left alone while the launcher's theme picks GTK's: those settings.ini are the launcher's to write.
        if (v.gtkTheme != null || v.iconTheme != null || v.cursorTheme != null) {
            for (gtk in listOf("gtk-3.0", "gtk-4.0")) {
                val ini = proot.hostFile("${Proot.GUEST_HOME}/.config/$gtk/settings.ini")
                ini.parentFile?.mkdirs()
                writeAtomically(ini, gtkSettings(v, readIni(ini)).toByteArray())
            }
        }
        // Apps that don't read GTK's settings take the cursor from ~/.icons/default, as X and Wayland apps alike do.
        v.cursorTheme?.let { cursor ->
            val index = proot.hostFile("${Proot.GUEST_HOME}/.icons/default/index.theme")
            index.parentFile?.mkdirs()
            writeAtomically(index, "[Icon Theme]\nName=Default\nInherits=$cursor\n".toByteArray())
        }
    }

    private fun scanThemes(): InstalledThemes {
        val home = Proot.GUEST_HOME
        val themeDirs = listOf("/usr/share/themes", "$home/.themes", "$home/.local/share/themes").map(proot::hostFile)
        val iconDirs = listOf("/usr/share/icons", "$home/.icons", "$home/.local/share/icons").map(proot::hostFile)
        val gtk =
            themeDirs.flatMap { dir -> dir.listFiles().orEmpty().filter { File(it, "gtk-3.0").isDirectory || File(it, "gtk-4.0").isDirectory } }
                .map { it.name }
        val iconThemes = iconDirs.flatMap { it.listFiles().orEmpty().toList() }.filter { it.name != "default" }
        val icons = iconThemes.filter { File(it, "index.theme").isFile && hasIcons(it) }.map { it.name }
        val cursors = iconThemes.filter { File(it, "cursors").isDirectory }.map { it.name }
        // Adwaita is built into GTK itself; it's there without a directory.
        return InstalledThemes(
            gtk = (gtk + listOf("Adwaita", "Adwaita-dark")).distinct().sortedBy { it.lowercase() },
            icons = icons.distinct().sortedBy { it.lowercase() },
            cursors = cursors.distinct().sortedBy { it.lowercase() },
        )
    }

    /** A theme with icons in it, not just cursors (like the cursor-only packages). */
    private fun hasIcons(theme: File) = theme.listFiles().orEmpty().any { it.isDirectory && it.name != "cursors" }

    private fun load(): TweakValues {
        val p = Properties()
        runCatching { file.inputStream().use(p::load) }
        val d = TweakValues()
        fun int(key: String, default: Int) = p.getProperty(key)?.toIntOrNull() ?: default
        fun bool(key: String, default: Boolean) = p.getProperty(key)?.toBooleanStrictOrNull() ?: default
        return TweakValues(
            gtkTheme = p.getProperty("gtk.theme")?.takeIf { it.isNotEmpty() },
            iconTheme = p.getProperty("gtk.icons")?.takeIf { it.isNotEmpty() },
            cursorTheme = p.getProperty("cursor.theme")?.takeIf { it.isNotEmpty() },
            cursorSize = int("cursor.size", d.cursorSize),
            preferDark = bool("gtk.dark", d.preferDark),
            gapsIn = int("hypr.gaps-in", d.gapsIn),
            gapsOut = int("hypr.gaps-out", d.gapsOut),
            borderSize = int("hypr.border", d.borderSize),
            rounding = int("hypr.rounding", d.rounding),
            animations = bool("hypr.animations", d.animations),
            blur = bool("hypr.blur", d.blur),
        )
    }

    private fun save(v: TweakValues) {
        val p = Properties()
        v.gtkTheme?.let { p.setProperty("gtk.theme", it) }
        v.iconTheme?.let { p.setProperty("gtk.icons", it) }
        v.cursorTheme?.let { p.setProperty("cursor.theme", it) }
        p.setProperty("cursor.size", v.cursorSize.toString())
        p.setProperty("gtk.dark", v.preferDark.toString())
        p.setProperty("hypr.gaps-in", v.gapsIn.toString())
        p.setProperty("hypr.gaps-out", v.gapsOut.toString())
        p.setProperty("hypr.border", v.borderSize.toString())
        p.setProperty("hypr.rounding", v.rounding.toString())
        p.setProperty("hypr.animations", v.animations.toString())
        p.setProperty("hypr.blur", v.blur.toString())
        val out = java.io.ByteArrayOutputStream()
        p.store(out, "settings › tweaks")
        writeAtomically(file, out.toByteArray())
    }

    /** The other keys of an existing settings.ini, which the launcher's theme or the user wrote. */
    private fun readIni(file: File): Map<String, String> =
        runCatching { file.readLines() }.getOrDefault(emptyList())
            .filter { '=' in it && !it.startsWith("[") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    companion object {
        /** GTK_THEME wins over settings.ini, which GTK on Wayland without a settings daemon may not read. */
        fun env(v: TweakValues): List<String> =
            listOfNotNull(
                v.gtkTheme?.let { "GTK_THEME=$it" + if (v.preferDark && !it.endsWith("-dark", ignoreCase = true)) ":dark" else "" },
                v.cursorTheme?.let { "XCURSOR_THEME=$it" },
                "XCURSOR_SIZE=${v.cursorSize}",
            )

        fun gtkSettings(
            v: TweakValues,
            existing: Map<String, String>,
        ): String {
            val keys = LinkedHashMap(existing)
            keys["gtk-application-prefer-dark-theme"] = if (v.preferDark) "1" else "0"
            v.gtkTheme?.let { keys["gtk-theme-name"] = it }
            v.iconTheme?.let { keys["gtk-icon-theme-name"] = it }
            v.cursorTheme?.let { keys["gtk-cursor-theme-name"] = it }
            keys["gtk-cursor-theme-size"] = v.cursorSize.toString()
            return "[Settings]\n" + keys.entries.joinToString("") { "${it.key}=${it.value}\n" }
        }

        fun hyprlandConf(v: TweakValues): String =
            """
            # Written by settings › tweaks in the launcher; changes here are replaced. Edit hyprland.conf instead.
            general {
                gaps_in = ${v.gapsIn}
                gaps_out = ${v.gapsOut}
                border_size = ${v.borderSize}
            }
            decoration {
                rounding = ${v.rounding}
                blur {
                    enabled = ${v.blur}
                }
            }
            animations {
                enabled = ${v.animations}
            }
            ${v.cursorTheme?.let { "env = XCURSOR_THEME, $it" } ?: ""}
            env = XCURSOR_SIZE, ${v.cursorSize}
            """.trimIndent() + "\n"
    }
}
