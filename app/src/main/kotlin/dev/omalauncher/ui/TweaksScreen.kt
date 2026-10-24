package dev.omalauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.JobState
import dev.omalauncher.linux.Package
import dev.omalauncher.linux.THEME_PACKS
import dev.omalauncher.linux.TweakValues
import kotlinx.coroutines.launch

/**
 * Settings › tweaks: GTK, icon and cursor themes from the Debian install, Hyprland's gaps, borders and
 * effects, and theme packs to install from Debian's archive. Apps started from then on pick them up.
 */
@Composable
fun TweaksScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val tweaks = runtime.tweaks
    val apt = runtime.apt
    val scope = rememberCoroutineScope()
    // Rescanned after a theme pack installs or goes.
    LaunchedEffect(apt.revision.intValue) { tweaks.scan() }
    val v = tweaks.values
    val themes = tweaks.installed

    fun set(new: TweakValues) = scope.launch { tweaks.set(new) }

    OverlayPanel("tweaks", onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            BasicText(
                "From your Debian ARM install: the themes it has, and how Hyprland looks. Linux apps opened from now on use them.",
                Modifier.padding(bottom = 8.dp),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )

            SectionLabel("appearance")
            Picker("gtk theme", v.gtkTheme, themes.gtk) { set(v.copy(gtkTheme = it)) }
            Picker("icons", v.iconTheme, themes.icons) { set(v.copy(iconTheme = it)) }
            Picker("cursor", v.cursorTheme, themes.cursors) { set(v.copy(cursorTheme = it)) }
            Number("cursor size", v.cursorSize, 16..64, step = 8) { set(v.copy(cursorSize = it)) }
            Toggle("prefer dark", "dark variants of GTK themes and apps", v.preferDark) { set(v.copy(preferDark = it)) }

            SectionLabel("hyprland")
            Number("gaps inside", v.gapsIn, 0..30) { set(v.copy(gapsIn = it)) }
            Number("gaps outside", v.gapsOut, 0..40) { set(v.copy(gapsOut = it)) }
            Number("border", v.borderSize, 0..10) { set(v.copy(borderSize = it)) }
            Number("rounding", v.rounding, 0..24, step = 2) { set(v.copy(rounding = it)) }
            Toggle("animations", "window open, close and workspace animations", v.animations) { set(v.copy(animations = it)) }
            Toggle("blur", "blurs what's behind see-through windows; costs CPU without a GPU", v.blur) { set(v.copy(blur = it)) }
            BasicText(
                "Written to ~/.config/hypr/tweaks.conf. Hyprland reloads it as it changes; hyprland.conf is yours to edit.",
                Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
            BracketAction("open hyprland", Palette.accent) { runtime.launch(dev.omalauncher.linux.HYPRLAND_COMMAND) }

            SectionLabel("system")
            Info("firmware and kernel modules", "disabled · dpkg never unpacks them, apt never installs kernels or firmware")
            Info("display", "the launcher is the Wayland compositor; no VNC server is installed, and apt holds them back")
            Info("root", "none needed · Debian runs under proot with a fake root")

            SectionLabel("theme packs")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var installedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
                LaunchedEffect(apt.revision.intValue) { installedNames = apt.installed().mapTo(HashSet()) { it.name } }
                for (pack in THEME_PACKS) {
                    val state = apt.states[pack.pkg] ?: JobState.Idle
                    Column(Modifier.card(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                BasicText(pack.title, style = Mono.copy(fontSize = TextSize.Item))
                                BasicText("${pack.pkg} · ${pack.description}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 2)
                            }
                            when {
                                state is JobState.Working -> BasicText("${state.action}…", style = Mono.copy(color = Palette.accent))
                                pack.pkg in installedNames -> BasicText("installed", Modifier.padding(horizontal = 8.dp), style = Mono.copy(color = Palette.muted))
                                else ->
                                    BracketAction("install", Palette.accent) {
                                        scope.launch { apt.install(Package(pack.pkg, "", pack.description, "debian", installed = false)) }
                                    }
                            }
                        }
                        JobLog(state)
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

/** A theme setting: the picked one, or the launcher's; tapping lists the installed ones to pick from. */
@Composable
private fun Picker(
    label: String,
    value: String?,
    options: List<String>,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 8.dp).card(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, Modifier.width(120.dp), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(
                value ?: "follow launcher theme",
                Modifier.weight(1f),
                style = Mono.copy(color = if (value == null) Palette.muted else Palette.accent),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(if (open) "▴" else "▾", style = Mono.copy(color = Palette.muted))
        }
        if (open) {
            Column(Modifier.padding(top = 6.dp)) {
                val all = listOf<String?>(null) + options
                if (options.isEmpty()) {
                    BasicText("none installed · get some under theme packs", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                }
                for (option in all) {
                    BasicText(
                        (if (option == value) "● " else "○ ") + (option ?: "follow launcher theme"),
                        Modifier.fillMaxWidth().clickable {
                            open = false
                            onPick(option)
                        }.padding(vertical = 6.dp),
                        style = Mono.copy(color = if (option == value) Palette.accent else Palette.foreground),
                    )
                }
            }
        }
    }
}

@Composable
private fun Number(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.padding(bottom = 8.dp).card(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
        BracketAction("−", Palette.accent) { onChange((value - step).coerceIn(range)) }
        BasicText(value.toString(), Modifier.width(40.dp), style = Mono.copy(color = Palette.accent))
        BracketAction("+", Palette.accent) { onChange((value + step).coerceIn(range)) }
    }
}

@Composable
private fun Toggle(
    label: String,
    description: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.padding(bottom = 8.dp).card(10.dp, onClick = { onChange(!on) }), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = Mono.copy(fontSize = TextSize.Item))
            BasicText(description, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        BasicText(if (on) "[on]" else "[off]", style = Mono.copy(color = if (on) Palette.accent else Palette.muted))
    }
}

@Composable
private fun Info(
    label: String,
    detail: String,
) {
    Column(Modifier.padding(bottom = 8.dp).card(10.dp)) {
        BasicText(label, style = Mono.copy(fontSize = TextSize.Item))
        BasicText(detail, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
}
