package dev.omalauncher.security

/**
 * The "Security Tools" feature's data model and search, kept free of Android types so it's covered by plain
 * JVM unit tests like the rest of the launcher's logic. The UI (ui/SecurityScreen) reads the device's installed
 * packages and hands them here as a set of package names.
 *
 * Everything in the catalog is a *defensive* utility: it helps you see and harden your own device and the
 * networks it joins while travelling. There is nothing here for attacking other people's devices or networks —
 * the "mitm self-test" category is about detecting whether *you* are being intercepted, not intercepting anyone.
 */
enum class SecurityCategory(
    /** Lower-case heading shown in the panel and matched by search. */
    val label: String,
    /** One line under the heading. */
    val blurb: String,
) {
    Network("network", "map and probe the networks you're on"),
    Wifi("wifi", "see the airwaves around you and what you're joining"),
    Bluetooth("bluetooth", "scan and inspect nearby bluetooth and ble devices"),
    Mitm("mitm self-test", "catch anything sitting between you and the internet"),
    Privacy("privacy & vpn", "encrypt your traffic and your dns on untrusted networks"),
}

/** Where [SecurityTool.install] should send someone who wants the tool. */
enum class ToolSource {
    /** Google Play; [SecurityTool.pkg] is the listing id. */
    PlayStore,

    /** F-Droid; opening its listing falls back to the project page when F-Droid isn't installed. */
    FDroid,

    /** No app to install — a Termux command, a web page, or a desktop tool. [SecurityTool.homepage] is the link. */
    Web,
}

/**
 * One entry in the database. [pkg] is the Android package name when the tool is an app that can be launched or
 * installed, which is also how [SecurityCatalog.search] decides it's "installed". Tools with no [pkg] (a Termux
 * command, a web test) are reference entries: they show, and open their [homepage], but never read as installed.
 */
data class SecurityTool(
    val name: String,
    val category: SecurityCategory,
    val summary: String,
    val homepage: String,
    val pkg: String? = null,
    val source: ToolSource = ToolSource.PlayStore,
) {
    /** The lower-cased text search matches against. */
    val haystack: String = "$name ${category.label} $summary ${pkg ?: ""}".lowercase()
}

/** A catalog entry paired with whether its package is present on this device. */
data class SecurityHit(
    val tool: SecurityTool,
    val installed: Boolean,
)

object SecurityCatalog {
    /**
     * The curated database. Package names are the real listing ids so "installed" detection and the install
     * links work; keep them in sync with the store when they change. New tools go under the category a traveller
     * would look in, not where the vendor files them.
     */
    val tools: List<SecurityTool> =
        listOf(
            // --- network --------------------------------------------------------------------------------------
            SecurityTool(
                "PCAPdroid",
                SecurityCategory.Network,
                "capture and inspect your own device's traffic with no root; the backbone of the self-test",
                "https://github.com/emanuele-f/PCAPdroid",
                "com.emanuelef.remote_capture",
                ToolSource.FDroid,
            ),
            SecurityTool(
                "Fing · Network Scanner",
                SecurityCategory.Network,
                "list every device on the wifi you joined and flag ones that shouldn't be there",
                "https://www.fing.com",
                "com.overlook.android.fing",
            ),
            SecurityTool(
                "PingTools Network Utilities",
                SecurityCategory.Network,
                "ping, traceroute, dns lookup, port scan and whois from your phone",
                "https://pingtools.org",
                "ua.com.streamsoft.pingtools",
            ),
            SecurityTool(
                "Network Analyzer",
                SecurityCategory.Network,
                "lan discovery, reachability and open-port checks on the subnet you're sharing",
                "https://techet.net/netanalyzer/",
                "net.techet.netanalyzerlite.an",
            ),
            SecurityTool(
                "nmap (in Termux)",
                SecurityCategory.Network,
                "the classic port scanner; `pkg install nmap` inside Termux, scan only networks you own",
                "https://nmap.org",
                source = ToolSource.Web,
            ),
            // --- wifi ------------------------------------------------------------------------------------------
            SecurityTool(
                "WiFiAnalyzer (open-source)",
                SecurityCategory.Wifi,
                "channel map, signal strength and nearby access points; no ads, no tracking",
                "https://github.com/VREMSoftwareDevelopment/WiFiAnalyzer",
                "com.vrem.wifianalyzer",
                ToolSource.FDroid,
            ),
            SecurityTool(
                "Network Signal Info",
                SecurityCategory.Wifi,
                "live wifi and cellular signal detail to spot a weak or spoofed tower or ap",
                "https://www.kaibits-software.com/product_netsignalinfo.htm",
                "com.Elecont.WiFiSignal",
            ),
            SecurityTool(
                "WiGLE WiFi Wardriving",
                SecurityCategory.Wifi,
                "log and identify access points around you; useful for recognising a rogue twin of a known ssid",
                "https://wigle.net",
                "net.wigle.wigleandroid",
            ),
            // --- bluetooth -------------------------------------------------------------------------------------
            SecurityTool(
                "nRF Connect for Mobile",
                SecurityCategory.Bluetooth,
                "nordic's scanner: discover ble devices, read their services and see who's advertising near you",
                "https://www.nordicsemi.com/Products/Development-tools/nRF-Connect-for-mobile",
                "no.nordicsemi.android.mcp",
            ),
            SecurityTool(
                "BLE Scanner",
                SecurityCategory.Bluetooth,
                "quick scan of bluetooth low-energy devices in range and their signal strength",
                "https://www.bluepixeltech.com",
                "com.macdom.ble.blescanner",
            ),
            // --- mitm self-test --------------------------------------------------------------------------------
            SecurityTool(
                "This launcher's self-test",
                SecurityCategory.Mitm,
                "built in: checks for proxies, extra root certificates, captive portals and open wifi right now",
                "https://github.com/SimonSchubert/omalauncher",
                source = ToolSource.Web,
            ),
            SecurityTool(
                "Intra (secure DNS)",
                SecurityCategory.Mitm,
                "by jigsaw: forces dns-over-https so a network can't quietly redirect your lookups",
                "https://getintra.org",
                "app.intra",
            ),
            SecurityTool(
                "badssl.com",
                SecurityCategory.Mitm,
                "web page of broken-tls test cases; if any 'bad' page loads, something is weakening your https",
                "https://badssl.com",
                source = ToolSource.Web,
            ),
            // --- privacy & vpn ---------------------------------------------------------------------------------
            SecurityTool(
                "RethinkDNS + Firewall",
                SecurityCategory.Privacy,
                "on-device firewall, dns and wireguard; block apps from phoning home on hostile networks",
                "https://rethinkdns.com",
                "com.celzero.bravedns",
                ToolSource.FDroid,
            ),
            SecurityTool(
                "Orbot (Tor)",
                SecurityCategory.Privacy,
                "route traffic through tor when you can't trust the local network at all",
                "https://orbot.app",
                "org.torproject.android",
                ToolSource.FDroid,
            ),
            SecurityTool(
                "WireGuard",
                SecurityCategory.Privacy,
                "fast, modern vpn tunnel back to a server you control, so the local wifi sees only ciphertext",
                "https://www.wireguard.com",
                "com.wireguard.android",
                ToolSource.FDroid,
            ),
            SecurityTool(
                "Aegis Authenticator",
                SecurityCategory.Privacy,
                "offline, encrypted 2fa codes; keeps logins safe even if a network is compromised",
                "https://getaegis.app",
                "com.beemdevelopment.aegis",
                ToolSource.FDroid,
            ),
        )

    /** Every category that has at least one tool, in enum order, so the panel can lay out sections. */
    val categories: List<SecurityCategory> = SecurityCategory.entries.filter { cat -> tools.any { it.category == cat } }

    /**
     * The database filtered by [query] and annotated with what's [installed] on the device.
     *
     * A blank query returns everything. Otherwise a tool matches when the trimmed, lower-cased query is a
     * substring of its name, summary, category label or package. Results come back installed-first (so the tools
     * you actually carry float to the top), then in category order, then by name — a stable order the UI and the
     * tests can both rely on.
     */
    fun search(
        query: String,
        installed: Set<String>,
    ): List<SecurityHit> {
        val q = query.trim().lowercase()
        return tools
            .asSequence()
            .filter { q.isEmpty() || it.haystack.contains(q) }
            .map { SecurityHit(it, it.pkg != null && it.pkg in installed) }
            .sortedWith(
                compareByDescending<SecurityHit> { it.installed }
                    .thenBy { it.tool.category.ordinal }
                    .thenBy { it.tool.name.lowercase() },
            )
            .toList()
    }

    /** Just the tools present on the device — the "tools I installed" view behind the panel's filter. */
    fun installed(installed: Set<String>): List<SecurityHit> =
        search("", installed).filter { it.installed }
}
