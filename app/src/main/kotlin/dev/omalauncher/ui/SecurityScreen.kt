package dev.omalauncher.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.openUrl
import dev.omalauncher.security.Finding
import dev.omalauncher.security.MitmSelfTest
import dev.omalauncher.security.NetworkFacts
import dev.omalauncher.security.SecurityCatalog
import dev.omalauncher.security.SecurityCategory
import dev.omalauncher.security.SecurityHit
import dev.omalauncher.security.Severity
import dev.omalauncher.security.ToolSource
import java.security.KeyStore

/**
 * The "Security Tools" panel, opened from the home menu and from settings. It's a searchable database of
 * defensive network, wifi, bluetooth and privacy utilities — the ones you've installed float to the top — plus a
 * built-in man-in-the-middle self-test that inspects how this device is connected right now. Everything here is
 * about keeping *your* traffic safe on the move; nothing probes anyone else.
 */
@Composable
fun SecurityScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var installedOnly by rememberSaveable { mutableStateOf(false) }

    // The launcher's catalog of launchable apps is the source of truth for "installed"; refresh it on open so an
    // app sideloaded since last time shows up marked.
    androidx.compose.runtime.LaunchedEffect(Unit) { runtime.refreshApps() }
    val installedPkgs = remember(runtime.androidApps.apps.size) { runtime.androidApps.apps.map { it.component.packageName }.toSet() }

    val hits = SecurityCatalog.search(query, installedPkgs).let { if (installedOnly) it.filter { h -> h.installed } else it }
    val browsing = query.isBlank() && !installedOnly

    fun launch(pkg: String) {
        val app = runtime.androidApps.apps.firstOrNull { it.component.packageName == pkg } ?: return
        runtime.androidApps.open(app)
        onDismiss()
    }

    OverlayPanel("security tools", onDismiss, contentWidth = ReadingWidth) {
        BasicText(
            "A searchable kit for staying safe on untrusted networks. Tools you've installed are highlighted; the " +
                "self-test looks for anything intercepting this connection.",
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        SelfTestCard()
        Spacer(Modifier.height(10.dp))
        SearchField(
            query = query,
            onChange = { query = it },
            onGo = {},
            placeholder = "search tools, e.g. wifi, vpn, bluetooth",
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val n = SecurityCatalog.installed(installedPkgs).size
            BracketAction(if (installedOnly) "show all" else "installed only ($n)", Palette.accent) { installedOnly = !installedOnly }
        }
        Spacer(Modifier.height(4.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hits.isEmpty()) {
                item {
                    BasicText(
                        if (installedOnly) "none of the catalog's tools are installed yet — switch off the filter to browse them." else "nothing matches “$query”.",
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                        modifier = Modifier.padding(8.dp),
                    )
                }
            } else if (browsing) {
                // Grouped by category with a heading, the way someone browses "what do I have for wifi?".
                for (category in SecurityCatalog.categories) {
                    val group = hits.filter { it.tool.category == category }
                    if (group.isEmpty()) continue
                    item(key = "head-${category.name}") { CategoryHeading(category) }
                    items(group, key = { it.tool.name }) { ToolCard(it, ::launch, context) }
                }
            } else {
                // A query or the installed filter: one ranked list, installed first.
                items(hits, key = { it.tool.name }) { ToolCard(it, ::launch, context) }
            }
        }
    }
}

@Composable
private fun CategoryHeading(category: SecurityCategory) {
    Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
        BasicText(category.label, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
        BasicText(category.blurb, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
}

@Composable
private fun ToolCard(
    hit: SecurityHit,
    launch: (String) -> Unit,
    context: Context,
) {
    val tool = hit.tool
    Column(Modifier.card()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 8.dp)) {
                BasicText(
                    tool.name,
                    style = Mono.copy(color = if (hit.installed) Palette.green else Palette.foreground, fontSize = TextSize.Item),
                )
                BasicText(tool.summary, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        Spacer(Modifier.height(6.dp))
        // Pulled left by the brackets' own padding so the first [ lines up with the text above it.
        Row(Modifier.offset(x = (-8).dp)) {
            when {
                hit.installed -> {
                    BracketAction("open", Palette.green) { tool.pkg?.let(launch) }
                    BracketAction("info", Palette.accent) { context.openUrl(tool.homepage) }
                }
                tool.pkg != null -> {
                    BracketAction("install", Palette.accent) { context.openUrl(storeUrl(tool.source, tool.pkg)) }
                    BracketAction("info", Palette.muted) { context.openUrl(tool.homepage) }
                }
                else -> {
                    BracketAction("open", Palette.accent) { context.openUrl(tool.homepage) }
                }
            }
        }
    }
}

/** The best store or project link for a tool by its [source] and [pkg]. */
private fun storeUrl(
    source: ToolSource,
    pkg: String,
): String =
    when (source) {
        ToolSource.FDroid -> "https://f-droid.org/packages/$pkg/"
        ToolSource.PlayStore -> "https://play.google.com/store/apps/details?id=$pkg"
        ToolSource.Web -> "https://play.google.com/store/apps/details?id=$pkg"
    }

/** The man-in-the-middle self-test: gathers facts from Android, runs the pure checker and lists what it found. */
@Composable
private fun SelfTestCard() {
    val context = LocalContext.current
    var findings by remember { mutableStateOf<List<Finding>?>(null) }
    Column(Modifier.card()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 8.dp)) {
                BasicText("man-in-the-middle self-test", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                BasicText("checks this device's connection for interception; nothing leaves the phone", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.offset(x = (-8).dp)) {
            BracketAction("run self-test", Palette.accent) { findings = MitmSelfTest.run(gatherFacts(context)) }
            findings?.let { BracketAction("clear", Palette.muted) { findings = null } }
        }
        findings?.let { list ->
            Spacer(Modifier.height(6.dp))
            for (f in list) {
                Spacer(Modifier.height(6.dp))
                BasicText("${severityMark(f.severity)} ${f.title}", style = Mono.copy(color = severityColor(f.severity), fontSize = TextSize.Body))
                BasicText(f.detail, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
    }
}

private fun severityMark(severity: Severity): String =
    when (severity) {
        Severity.Alert -> "!!"
        Severity.Warn -> "!"
        Severity.Info -> "·"
        Severity.Ok -> "✓"
    }

private fun severityColor(severity: Severity): Color =
    when (severity) {
        Severity.Alert, Severity.Warn -> Palette.red
        Severity.Info -> Palette.accent
        Severity.Ok -> Palette.green
    }

/** Read the device's current connection into [NetworkFacts]; best-effort, never throwing on a locked-down OEM. */
private fun gatherFacts(context: Context): NetworkFacts {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val network = cm?.activeNetwork
    val caps = network?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
    val link = network?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }
    val proxy = cm?.let { runCatching { it.defaultProxy }.getOrNull() }

    val proxyHost = proxy?.host?.takeIf { it.isNotBlank() } ?: System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() }
    val proxyPort = proxy?.port?.takeIf { it > 0 } ?: System.getProperty("http.proxyPort")?.toIntOrNull()?.takeIf { it > 0 }

    val userAddedCas =
        runCatching {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            java.util.Collections.list(store.aliases()).count { it.startsWith("user:") }
        }.getOrDefault(0)

    return NetworkFacts(
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        userAddedCaCount = userAddedCas,
        vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
        captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        privateDnsActive = link?.isPrivateDnsActive == true,
        onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
    )
}
