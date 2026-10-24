package dev.omalauncher.linux

import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import dev.omalauncher.upgrade.Managed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

data class Package(
    val name: String,
    val version: String,
    val description: String,
    /** Where it comes from (the Debian archive); null for the installed list. */
    val repo: String?,
    val installed: Boolean,
)

/** Installs and removes guest packages from the settings screen, one apt at a time. */
class Apt(
    private val proot: Proot,
) {
    /** apt and dpkg lock their databases; everything that runs them takes this first. */
    val lock = Mutex()

    /** Per-package install/remove progress. */
    val states = mutableStateMapOf<String, JobState>()

    /** Bumped after installs and removals so lists reload. */
    val revision = mutableIntStateOf(0)

    /**
     * Not offered for removal: what the launcher relies on (foot, fonts, Omarchy's apps), and apt
     * and dpkg themselves. Essential packages aren't listed at all; apt refuses the rest itself.
     */
    val core = CORE_PACKAGES + setOf("apt", "dpkg", "debian-archive-keyring", "bash", "coreutils")

    private val status = File(proot.rootfs, "var/lib/dpkg/status")
    private val extendedStates = File(proot.rootfs, "var/lib/apt/extended_states")

    /** `apt-cache search` over the package lists, best name matches first. */
    suspend fun search(query: String): List<Package> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val installed = withContext(Dispatchers.IO) { readStatus() }.filter { it.installed }.mapTo(HashSet()) { it.name }
        val lower = q.lowercase()
        return output("apt-cache search -- ${shellQuote(q)}")
            .lines()
            // "name - description"
            .mapNotNull { line ->
                val name = line.substringBefore(" - ", "").trim()
                if (name.isEmpty() || ' ' in name) return@mapNotNull null
                Package(name, "", line.substringAfter(" - ").trim(), "debian", name in installed)
            }.sortedWith(
                compareBy<Package>(
                    { it.name != lower },
                    { !it.name.startsWith(lower) },
                    { lower !in it.name },
                    { it.name },
                ),
            ).take(MAX_RESULTS)
    }

    /**
     * What the user installed (what `apt-mark showmanual` lists, minus the base system), read
     * straight from dpkg's database: much faster than starting proot.
     */
    suspend fun installed(): List<Package> =
        withContext(Dispatchers.IO) {
            val auto = autoInstalled()
            readStatus()
                .filter { it.installed && !it.base && it.name !in auto }
                .map { Package(it.name, it.version, it.description, null, installed = true) }
                .sortedBy { it.name }
        }

    /** A package's stanza in /var/lib/dpkg/status, the fields we show. */
    private class Entry(
        val name: String,
        val version: String,
        val description: String,
        val installed: Boolean,
        /** Essential, required or important: the base system every Debian has. */
        val base: Boolean,
    )

    private fun readStatus(): List<Entry> {
        val text = runCatching { status.readText() }.getOrElse { return emptyList() }
        return text.split("\n\n").mapNotNull { stanza ->
            val fields = fields(stanza)
            val name = fields["Package"] ?: return@mapNotNull null
            Entry(
                name = name,
                version = fields["Version"].orEmpty(),
                description = fields["Description"].orEmpty(),
                installed = fields["Status"]?.endsWith(" installed") == true,
                base = fields["Essential"] == "yes" || fields["Priority"] in setOf("required", "important"),
            )
        }
    }

    /** Packages apt pulled in as dependencies, from extended_states' `Auto-Installed: 1`. */
    private fun autoInstalled(): Set<String> {
        val text = runCatching { extendedStates.readText() }.getOrElse { return emptySet() }
        return text.split("\n\n").mapNotNullTo(HashSet()) { stanza ->
            val fields = fields(stanza)
            fields["Package"]?.takeIf { fields["Auto-Installed"] == "1" }
        }
    }

    /** A deb822 stanza's single-line fields (a description's first line). */
    private fun fields(stanza: String): Map<String, String> =
        stanza
            .lines()
            .filter { it.isNotEmpty() && !it[0].isWhitespace() && ':' in it }
            .associate { it.substringBefore(':') to it.substringAfter(':').trim() }

    suspend fun install(pkg: Package) = run(pkg.name, "installing", aptInstall(listOf(pkg.name)))

    /** Nothing of apt's own is provided into the guest; kept so Runtime lists every provider alike. */
    fun managed(): List<Managed> = emptyList()

    /** Removes the package, its configuration, and the dependencies nothing else needs. */
    suspend fun remove(name: String) = run(name, "removing", "apt-get purge -y --autoremove ${shellQuote(name)} && $CLEAN_APT_CACHE")

    /** Kept between searches: starting proot costs more than the search. */
    private val shell = GuestShell(proot)

    private suspend fun output(script: String): String =
        runCatching { shell.run(script, timeoutSeconds = 30).out }.getOrElse {
            Log.w(TAG, "apt query failed", it)
            ""
        }

    private suspend fun run(
        name: String,
        action: String,
        script: String,
    ): Boolean {
        val (ok, _) = proot.trackedJob(action, script, lock) { if (it == JobState.Idle) states.remove(name) else states[name] = it }
        Log.i(TAG, "$action $name: ${if (ok) "ok" else "failed"}")
        revision.intValue++
        return ok
    }

    companion object {
        private const val TAG = "apt"

        /** Short queries match thousands of packages. */
        private const val MAX_RESULTS = 100

        /**
         * Arch names widgets from the store declare in `requires { pacman: [...] }`, for the few Debian
         * calls something else; the rest are the same on both.
         */
        private val FROM_PACMAN =
            mapOf(
                "procps-ng" to "procps",
                "github-cli" to "gh",
                "dua-cli" to "ncdu",
                "fd" to "fd-find",
                "python" to "python3",
                "python-pip" to "python3-pip",
                "nodejs" to "nodejs",
                "openssh" to "openssh-client",
                "bind" to "bind9-dnsutils",
                "inetutils" to "inetutils-tools",
                "iproute2" to "iproute2",
                "noto-fonts-emoji" to "fonts-noto-color-emoji",
                "ttf-jetbrains-mono" to "fonts-jetbrains-mono",
                "ttf-dejavu" to "fonts-dejavu-core",
                "xkbcommon" to "libxkbcommon-tools",
                "libnotify" to "libnotify-bin",
            )

        /** The Debian packages a widget needs: its `apt` list, else its `pacman` one in Debian's names. */
        fun required(requires: Map<String, List<String>>): List<String> =
            requires["apt"] ?: requires["pacman"].orEmpty().map { FROM_PACMAN[it] ?: it }
    }
}
