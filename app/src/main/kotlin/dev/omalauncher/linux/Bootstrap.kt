package dev.omalauncher.linux

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.omalauncher.CountingInputStream
import dev.omalauncher.LauncherData
import dev.omalauncher.hex
import dev.omalauncher.openHttp
import dev.omalauncher.upgrade.Provisioner
import dev.omalauncher.upgrade.StepJournal
import dev.omalauncher.upgrade.writeAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

private const val TAG = "bootstrap"
/**
 * Debian 13 (trixie) for arm64: the official debian:trixie image's filesystem, exported by this
 * repo's CI (.github/workflows/rootfs.yml) and attached to a release, with its SHA-256 beside it.
 */
private const val ROOTFS_URL = "https://github.com/Jrpixel666/Test/releases/download/rootfs-debian-trixie/debian-trixie-arm64.tar.gz"
private const val ROOTFS_SHA256_URL = "$ROOTFS_URL.sha256"
private const val TARBALL = "debian-trixie-arm64.tar.gz"

/** Attempts per network step before setup (or an update) reports a failure. */
private const val NETWORK_TRIES = 3

/** How long `echo | cat` may take in the guest before setup calls the phone's kernel broken; it takes well under a second. */
private const val FORK_CHECK_SECONDS = 30L

/** Room for the download (~50 MB). */
private const val TARBALL_SPACE = 100_000_000L

/** Room for the unpacked system and what setup installs into it, Hyprland and the themes too (~2.2 GB with an agent), with some to spare. */
private const val SYSTEM_SPACE = 2_600_000_000L

/** A failure that trying again can't fix, like a full disk: a network step stops at once rather than downloading again. */
private class NoRetry(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private fun Throwable.outOfSpace() =
    generateSequence(this) { it.cause }.any {
        (it is ErrnoException && it.errno == OsConstants.ENOSPC) || it.message?.contains("ENOSPC") == true
    }

private fun gigabytes(bytes: Long) = "%.1f GB".format(bytes / 1e9)

/**
 * Omarchy's classic terminal apps, by their Debian names. The Nerd Font glyphs
 * come from the launcher's own symbols font. Changing this list needs a new id
 * for the packages step.
 */
private val PACKAGES =
    listOf(
        "foot", "fonts-dejavu-core", "btop", "fastfetch", "fonts-jetbrains-mono",
        "neovim", "eza", "bat", "fd-find", "ripgrep", "fzf", "zoxide", "tmux", "jq", "tealdeer",
        "man-db", "less", "inxi", "whois", "git", "bash-completion", "pulseaudio", "pulseaudio-utils", "micro",
        "libxkbcommon-tools", "fontconfig", "curl", "ca-certificates", "procps", "gawk", "findutils", "xdg-utils",
        "unzip", "xz-utils", "mesa-vulkan-drivers", "libgl1-mesa-dri", "libegl1", "libgles2",
    )

/**
 * What goes with Hyprland, plus GTK, icon and cursor themes the tweaks screen offers, all from trixie.
 * Installed one by one if the batch fails, so a name a mirror lacks costs only that package.
 */
private val DESKTOP_PACKAGES =
    listOf(
        "xwayland", "waybar", "wofi", "mako-notifier",
        "gnome-themes-extra", "arc-theme", "numix-gtk-theme",
        "adwaita-icon-theme", "papirus-icon-theme", "breeze-cursor-theme", "dmz-cursor-theme",
        "libgtk-3-bin", "gsettings-desktop-schemas", "dconf-cli",
    )

/**
 * Hyprland isn't in Debian 13; it comes from testing (forky), which apt otherwise never picks
 * ([TESTING_PIN]). apt brings along the newer libraries it needs from there.
 */
private val HYPRLAND_PACKAGES = listOf("hyprland")

private const val TESTING = "forky"

/** Debian names the binaries of these differently; the launcher and Omarchy's configs call them by the usual names. */
private val ALIASES = mapOf("bat" to "/usr/bin/batcat", "fd" to "/usr/bin/fdfind")

/** Omarchy's Winamp-style player; not in Debian, but upstream ships an arm64 build. */
private const val CLIAMP_VERSION = "v2.3.0"
private const val CLIAMP_URL = "https://github.com/bjarneo/cliamp/releases/download/$CLIAMP_VERSION/cliamp-linux-arm64"
private const val CLIAMP_SHA256 = "827b4aa879feecab33ea621dc1460c60ffa765c210b5046d679ce5a4073d2b32"

/**
 * Debian's CDN; plain http, since the image has no CA certificates until the first install. apt checks
 * signatures itself, with [keyring] (the archive keyring's path moved between releases).
 */
private fun sources(keyring: String?): String {
    val signedBy = keyring?.let { "Signed-By: $it\n" }.orEmpty()
    return "Types: deb\nURIs: http://deb.debian.org/debian\nSuites: trixie trixie-updates\n" +
        "Components: main contrib non-free non-free-firmware\n$signedBy\n" +
        "Types: deb\nURIs: http://deb.debian.org/debian-security\nSuites: trixie-security\n" +
        "Components: main contrib non-free non-free-firmware\n$signedBy\n" +
        "Types: deb\nURIs: http://deb.debian.org/debian\nSuites: $TESTING\n" +
        "Components: main\n$signedBy"
}

private val KEYRINGS = listOf("/usr/share/keyrings/debian-archive-keyring.pgp", "/usr/share/keyrings/debian-archive-keyring.gpg")

/**
 * apt under proot: no privilege drop to _apt for downloads (proot's fake root can't), no
 * recommends (they pull in half a desktop), patient retries for phone connections.
 */
private val APT_CONF =
    """
    APT::Sandbox::User "root";
    APT::Install-Recommends "false";
    APT::Install-Suggests "false";
    Acquire::Retries "5";
    Acquire::http::Timeout "60";
    Dpkg::Use-Pty "0";
    Dpkg::Options { "--force-confdef"; "--force-confold"; };
    """.trimIndent() + "\n"

/**
 * Firmware and kernel modules are useless under proot, which runs on Android's kernel:
 * dpkg never unpacks them, whatever package ships them.
 */
private val DPKG_NO_FIRMWARE =
    """
    path-exclude=/lib/firmware/*
    path-exclude=/usr/lib/firmware/*
    path-exclude=/lib/modules/*
    path-exclude=/usr/lib/modules/*
    path-exclude=/boot/*
    """.trimIndent() + "\n"

/**
 * Packages apt may never install: kernels and firmware (see [DPKG_NO_FIRMWARE]), and VNC servers.
 * The launcher is the display: Linux windows are Wayland clients of it, so nothing goes over VNC.
 */
private val APT_PINS =
    """
    Package: linux-image-* linux-headers-* linux-modules-* linux-firmware* firmware-* *-firmware intel-microcode amd64-microcode
    Pin: release *
    Pin-Priority: -1

    Package: tigervnc-* tightvncserver x11vnc* wayvnc novnc vnc4server turbovnc* realvnc-*
    Pin: release *
    Pin-Priority: -1
    """.trimIndent() + "\n"

/**
 * Debian testing only where asked for (`-t forky`, for Hyprland), and for what that keeps current: below
 * trixie's 500, so upgrades never move the rest of the system to testing.
 */
private val TESTING_PIN =
    """
    Package: *
    Pin: release n=$TESTING
    Pin-Priority: 100
    """.trimIndent() + "\n"

/** Services don't start from package scripts: there's no init under proot. */
private const val POLICY_RC = "#!/bin/sh\nexit 101\n"

/** What the bootstrap installs; the packages screen doesn't offer to remove these. */
val CORE_PACKAGES = PACKAGES.toSet()

/** The phases of a fresh install, in order, as the setup screen lists them. */
enum class Stage(
    val label: String,
) {
    Download("download debian arm"),
    Extract("unpack the system"),
    Configure("configure apt"),
    Packages("install omarchy's apps"),
    Desktop("install hyprland and themes"),
    Agents("install coding agents"),
}

/** Where the optional Linux system stands. The launcher works in every state; Linux features only when [Installed]. */
sealed interface LinuxState {
    data object Checking : LinuxState

    /** Never installed, or removed: nothing of it is on disk. */
    data object NotInstalled : LinuxState

    data class Installing(
        val stage: Stage,
        val step: String,
        val progress: Float?,
        val log: List<String>,
    ) : LinuxState

    /** An install, or an update that must finish before Linux runs, stopped; [Bootstrap.run] retries it. */
    data class Failed(
        val stage: Stage,
        val message: String,
        val log: List<String>,
    ) : LinuxState

    data object Installed : LinuxState

    data object Removing : LinuxState
}

/**
 * One idempotent change to the rootfs. Once it succeeds its [id] is recorded
 * and it doesn't run again; to bring a changed step to existing installs,
 * give it a new id. [network] steps of an update run in the background, so
 * an offline phone still reaches the home screen. [isDone], when set, reads
 * the step's state from disk instead of the journal.
 */
private class Step(
    val id: String,
    val stage: Stage,
    val label: String,
    val network: Boolean,
    /** A failure doesn't stop a fresh install: the system works without it, and it's retried later. */
    val optional: Boolean = false,
    val isDone: (() -> Boolean)? = null,
    val run: suspend () -> Unit,
)

/**
 * Sets up the Debian ARM rootfs and keeps it current. A fresh install
 * runs every [steps] entry behind the setup screen; after an app update only
 * the new ones run, see [Step].
 */
class Bootstrap(
    private val context: Context,
    private val proot: Proot,
    private val agents: AgentStore,
    private val apt: Apt,
    /** The guest's files; see [Provisioner]. */
    private val provisioner: Provisioner,
) {
    private val _state = MutableStateFlow<LinuxState>(LinuxState.Checking)
    val state = _state.asStateFlow()

    val isInstalled get() = _state.value == LinuxState.Installed

    /** An install was started (and may still need to finish), or one finished. */
    val wanted get() = journal.setupComplete || choiceFile.exists()

    /** Update steps running after the home screen is up; Idle when there are none. */
    private val _updates = MutableStateFlow<JobState>(JobState.Idle)
    val updates = _updates.asStateFlow()

    private val log = OutputTail(200)
    private val journal = StepJournal(File(proot.filesDir, "rootfs-steps.json"))

    /** The agents picked when installing Linux, one bin per line; its existence means an install was started. */
    private val choiceFile get() = File(proot.filesDir, "setup-agents")

    /** The coding agents setup installs, in the order they were listed. */
    val chosenAgents: List<Agent>
        get() = runCatching { choiceFile.readLines() }.getOrDefault(emptyList()).mapNotNull { bin -> AGENTS.find { it.bin == bin } }

    fun choose(picked: List<Agent>) {
        proot.filesDir.mkdirs()
        choiceFile.writeText(picked.joinToString("") { it.bin + "\n" })
    }

    private var stage = Stage.Download
    private var step = ""

    private val steps =
        listOf(
            Step("rootfs", Stage.Download, "Downloading Debian ARM", network = true) { installRootfs() },
            Step("etc-1", Stage.Configure, "Configuring the system", network = false) { configure() },
            // Only before the first setup: a phone where Linux already runs can fork.
            Step("forks-1", Stage.Configure, "Checking that programs can start", network = false, isDone = { journal.setupComplete }) {
                checkForks()
            },
            // The image has none, but a kernel or firmware that got in some other way only takes up room.
            Step("no-kernel-1", Stage.Configure, "Removing kernel and firmware packages", network = false) {
                guest(
                    "p=\$(dpkg-query -W -f '\${Package}\\n' 'linux-image-*' 'linux-modules-*' 'firmware-*' 2>/dev/null); " +
                        "[ -z \"\$p\" ] || apt-get purge -y \$p; true",
                )
            },
            Step("packages-1", Stage.Packages, "Installing packages", network = true) { guest(aptInstall(PACKAGES, refresh = true)) },
            Step("aliases-1", Stage.Packages, "Linking bat and fd", network = false) { linkAliases() },
            Step("keymap-1", Stage.Packages, "Compiling keymap", network = false, isDone = { proot.keymap.exists() }) { compileKeymap() },
            Step("cliamp-$CLIAMP_VERSION", Stage.Packages, "Installing cliamp", network = true, optional = true) { installCliamp() },
            Step("desktop-1", Stage.Desktop, "Installing themes and Hyprland's tools", network = true, optional = true) { guest(aptInstallEach(DESKTOP_PACKAGES)) },
            Step("hyprland-1", Stage.Desktop, "Installing Hyprland", network = true, optional = true) { guest(aptInstall(HYPRLAND_PACKAGES, release = TESTING)) },
        )

    private fun Step.done() = isDone?.invoke() ?: (id in journal)

    /**
     * Runs the step; one that needs the network gets a few tries, since phone connections drop.
     * Retrying is cheap: apt keeps what it already downloaded, and downloads resume.
     */
    private suspend fun Step.attempt() {
        repeat(if (network) NETWORK_TRIES - 1 else 0) { n ->
            try {
                return run()
            } catch (e: Exception) {
                if (e is NoRetry || e is CancellationException) throw e
                log.add("${e.message}; trying again (${n + 2} of $NETWORK_TRIES)")
                kotlinx.coroutines.delay(5_000L * (n + 1))
            }
        }
        run()
    }

    /**
     * Brings Linux to [LinuxState.Installed] if it's wanted: updates an
     * installed system, or continues an install that was started (also
     * after the app was killed during it). Otherwise reports NotInstalled.
     */
    suspend fun run() =
        withContext(Dispatchers.IO) {
            try {
                when {
                    journal.setupComplete -> update()
                    !choiceFile.exists() -> {
                        _state.value = LinuxState.NotInstalled
                        // Left by a removal that didn't finish (the app was killed, or a file resisted).
                        if (proot.rootfs.exists()) runCatching { LauncherData.deleteTree(proot.rootfs) }.onFailure { Log.e(TAG, "leftover rootfs", it) }
                        return@withContext
                    }
                    else -> setup()
                }
                _state.value = LinuxState.Installed
            } catch (e: Throwable) {
                // A cancelled install is about to be removed; it didn't fail.
                if (cancelled) {
                    Log.i(TAG, "setup cancelled")
                    return@withContext
                }
                // Errors too (out of memory): otherwise the screen shows the step running forever.
                Log.e(TAG, "setup failed", e)
                _state.value = LinuxState.Failed(stage, e.message ?: e.toString(), log.toList())
            }
        }

    @Volatile private var cancelled = false

    @Volatile private var connection: HttpURLConnection? = null

    /** Whether the install can still be cancelled: until setup completes. An update of an installed system can't. */
    val cancellable get() = !journal.setupComplete && !cancelled

    /**
     * Stops an install that hasn't completed: the download and the unpack stop at their next block. The caller kills
     * guest commands, waits for [run] to return, then calls [remove]. False when there's nothing to cancel.
     */
    fun cancel(): Boolean {
        if (!cancellable) return false
        cancelled = true
        connection?.disconnect()
        return true
    }

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("cancelled")
    }

    /** Starts installing Linux with [picked] coding agents; see [run]. */
    suspend fun install(picked: List<Agent>) {
        choose(picked)
        run()
    }

    /**
     * Deletes the rootfs and what we recorded about it. Callers stop guest
     * processes first. Launcher data (themes, widgets) lives outside it and stays.
     */
    suspend fun remove() =
        withContext(Dispatchers.IO) {
            _state.value = LinuxState.Removing
            backlog = emptyList()
            apt.lock.withLock {
                try {
                    LauncherData.deleteTree(proot.rootfs)
                } catch (e: Exception) {
                    // Whatever is left can't run; a later install unpacks over a fresh directory anyway.
                    Log.e(TAG, "rootfs not fully deleted", e)
                }
                journal.reset()
                provisioner.forgetAll()
                choiceFile.delete()
                proot.keymap.delete()
                context.cacheDir.listFiles { f -> f.name.startsWith(TARBALL) }?.forEach { it.delete() }
                log.clear()
                cancelled = false
            }
            _state.value = LinuxState.NotInstalled
        }

    /**
     * A fresh install: every step, in order. A failure stops here and a
     * retry picks up at the failed step. Agents are extras: the system
     * works without them, and settings can retry.
     */
    private suspend fun setup() {
        for (step in steps) {
            if (step.done()) continue
            checkCancelled()
            report(step.stage, step.label)
            try {
                step.attempt()
                journal.mark(step.id)
            } catch (e: Exception) {
                if (!step.optional || e is CancellationException) throw e
                log.add("${step.label} failed: ${e.message}; retry in settings")
            }
        }
        installAgents()
        checkCancelled()
        journal.completeSetup()
    }

    /**
     * After an app update: offline steps run before the home screen, so it
     * never shows a half-configured system; from the first step that needs
     * the network on, the rest continue in the background (see [updates]).
     */
    private suspend fun update() {
        val pending = steps.filterNot { it.done() }
        val blocking = pending.takeWhile { !it.network }
        for (step in blocking) {
            report(step.stage, step.label)
            step.run()
            journal.mark(step.id)
        }
        backlog = pending.drop(blocking.size)
    }

    private var backlog: List<Step> = emptyList()

    private val backlogRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Runs update steps that need the network; call once the home screen is up. Safe to call again to retry. */
    suspend fun runBacklog() {
        if (!backlogRunning.compareAndSet(false, true)) return
        try {
            runSteps()
        } finally {
            backlogRunning.set(false)
        }
    }

    private suspend fun runSteps() =
        withContext(Dispatchers.IO) {
            val todo = backlog.filterNot { it.done() }
            if (todo.isEmpty()) return@withContext
            for (step in todo) {
                _updates.value = JobState.Working(step.label, emptyList())
                try {
                    // Package steps run apt; nothing else may hold its lock meanwhile.
                    apt.lock.withLock { step.attempt() }
                    journal.mark(step.id)
                } catch (e: Throwable) {
                    Log.w(TAG, "update step ${step.id} failed", e)
                    _updates.value = JobState.Failed(log.toList().takeLast(12) + (e.message ?: e.toString()))
                    return@withContext
                }
            }
            _updates.value = JobState.Idle
        }

    /**
     * Installs the agents picked on the welcome screen. A failed agent doesn't
     * fail setup: it can be installed again from settings.
     */
    private suspend fun installAgents() {
        val chosen = chosenAgents
        for (agent in chosen) {
            if (agents.isInstalled(agent)) continue
            checkCancelled()
            try {
                report(Stage.Agents, "Installing ${agent.name}")
                guest(agents.installScript(agent))
                agents.onInstalled(agent)
            } catch (e: IllegalStateException) {
                log.add("${agent.name} didn't install; try again in settings › coding agents")
            }
        }
        chosen.firstOrNull { agents.isInstalled(it) }?.let(agents::choose)
    }

    private fun report(
        stage: Stage,
        step: String,
        progress: Float? = null,
    ) {
        this.stage = stage
        this.step = step
        if (journal.setupComplete && _state.value == LinuxState.Installed) return
        _state.value = LinuxState.Installing(stage, step, progress, log.toList())
    }

    /** Always a fresh unpack: this step only runs until one completed, so a rootfs here is a half-unpacked one. */
    private fun installRootfs() {
        // Unpacking replaces the rootfs: the user's home, packages and agents. Only ever before a setup finished.
        check(!journal.setupComplete) { "refusing to replace a set-up system" }
        // A half-unpacked rootfs from an earlier try only takes up room the download needs.
        LauncherData.deleteTree(proot.rootfs)
        val tarball =
            try {
                obtainTarball()
            } catch (e: Exception) {
                throw if (e.outOfSpace()) outOfSpace(e) else e
            }
        try {
            extract(tarball)
        } catch (e: Exception) {
            if (e is NoRetry || e is CancellationException || e.outOfSpace()) {
                // The download is fine and kept for the next try; what was unpacked gives its room back.
                runCatching { LauncherData.deleteTree(proot.rootfs) }
                throw if (e is NoRetry || e is CancellationException) e else outOfSpace(e)
            }
            // A truncated or bogus download (a captive portal's page) would fail every retry otherwise.
            if (tarball.parentFile == context.cacheDir) tarball.delete()
            throw e
        }
        tarball.delete() // the download is no longer needed
    }

    /** Stops before writing when the app's storage has less than [bytes] free: a full phone fails at once, not after the download. */
    private fun requireSpace(bytes: Long) {
        val free = proot.filesDir.usableSpace
        if (free < bytes) {
            throw NoRetry("not enough space: Linux needs ${gigabytes(bytes)} free, the phone has ${gigabytes(free)}; free some up, then try again")
        }
    }

    private fun outOfSpace(e: Exception) =
        NoRetry("the phone ran out of space; Linux needs about ${gigabytes(TARBALL_SPACE + SYSTEM_SPACE)} free; free some up, then try again", e)

    /**
     * Prefers a tarball pushed via adb to the app's external files dir; downloads otherwise.
     * An interrupted download resumes where it stopped, as long as the file on the server is
     * still the same one (If-Range): "latest" moves, and two versions mustn't be spliced.
     */
    private fun obtainTarball(): File {
        val external = context.getExternalFilesDir(null)?.let { File(it, TARBALL) }
        if (external != null && external.exists()) return external
        val target = File(context.cacheDir, TARBALL)
        if (target.exists()) return target
        val partial = File(context.cacheDir, "$TARBALL.part")
        val validator = File(context.cacheDir, "$TARBALL.version")
        requireSpace(TARBALL_SPACE - (if (partial.exists()) partial.length() else 0L) + SYSTEM_SPACE)
        report(Stage.Download, "Downloading Debian ARM", 0f)
        val conn = openHttp(ROOTFS_URL)
        connection = conn
        try {
            checkCancelled()
            download(conn, partial, validator)
        } finally {
            connection = null
            conn.disconnect()
        }
        checkCancelled()
        verify(partial)
        // Only a complete download is kept for a retry.
        check(partial.renameTo(target)) { "can't move the download into place" }
        validator.delete()
        return target
    }

    /** Into [partial], continuing it if the server still has the version [validator] names. Throws if it ends short. */
    private fun download(
        conn: HttpURLConnection,
        partial: File,
        validator: File,
    ) {
        val have = if (partial.exists() && validator.exists()) partial.length() else 0L
        if (have > 0) {
            conn.setRequestProperty("Range", "bytes=$have-")
            conn.setRequestProperty("If-Range", validator.readText())
        }
        val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (conn.responseCode !in 200..299) error("download failed: HTTP ${conn.responseCode}")
        val start = if (resumed) have else 0L
        val total = conn.contentLengthLong.let { if (it > 0) it + start else -1L }
        (conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified"))?.let(validator::writeText) ?: validator.delete()
        conn.inputStream.use { input ->
            java.io.FileOutputStream(partial, resumed).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = start
                var lastReport = 0L
                while (true) {
                    checkCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 4 shl 20) {
                        lastReport = done
                        report(
                            Stage.Download,
                            if (total > 0) "${done shr 20} of ${total shr 20} MB" else "${done shr 20} MB",
                            if (total > 0) done.toFloat() / total else null,
                        )
                    }
                }
            }
        }
        check(total <= 0 || partial.length() == total) { "the download was cut short; retry to continue it" }
    }

    private fun extract(tarball: File) {
        report(Stage.Extract, "Starting", 0f)
        val root = proot.rootfs
        LauncherData.deleteTree(root)
        requireSpace(SYSTEM_SPACE)
        root.mkdirs()
        // Nothing we recorded about the old rootfs holds for this one.
        journal.reset()
        provisioner.forgetAll()
        val total = tarball.length()
        val counter = CountingInputStream(tarball.inputStream())
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(counter, 1 shl 16), 1 shl 16)).use { tar ->
            var count = 0
            while (true) {
                checkCancelled()
                val entry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || skipEntry(name)) continue
                val file = File(root, name)
                file.parentFile?.mkdirs()
                when {
                    entry.isDirectory -> file.mkdirs()
                    entry.isSymbolicLink -> {
                        file.delete()
                        Os.symlink(entry.linkName, file.path)
                    }
                    // Hard links aren't allowed in app storage; copy the target instead.
                    entry.isLink -> {
                        val target = File(root, entry.linkName.removePrefix("./"))
                        if (target.exists()) target.copyTo(file, overwrite = true)
                    }
                    entry.isFile -> file.outputStream().use { tar.copyTo(it) }
                    else -> continue // device nodes, fifos
                }
                if (!entry.isSymbolicLink) runCatching { Os.chmod(file.path, entry.mode and 0x1ff or 0x180) }
                if (++count % 500 == 0) {
                    report(Stage.Extract, "$count files", counter.count.toFloat() / total)
                }
            }
        }
    }

    private fun skipEntry(name: String) =
        name == "boot" ||
            name.startsWith("boot/") ||
            name.startsWith("usr/lib/firmware/") ||
            name.startsWith("usr/lib/modules/") ||
            name.startsWith("dev/")

    /**
     * Compares [file] with the checksum CI published next to the rootfs. A mismatch (a captive
     * portal's page, a rebuilt image spliced onto an old partial download) deletes it for a fresh try.
     */
    private fun verify(file: File) {
        val conn = openHttp(ROOTFS_SHA256_URL, connectTimeout = 15_000, readTimeout = 20_000)
        val expected =
            try {
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for the rootfs checksum")
                conn.inputStream.bufferedReader().readText().trim().substringBefore(' ').lowercase()
            } finally {
                conn.disconnect()
            }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                checkCancelled()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        if (digest.digest().hex() != expected) {
            file.delete()
            File(context.cacheDir, "$TARBALL.version").delete()
            error("the Debian download doesn't match its checksum; retry to download it again")
        }
    }

    private fun configure() {
        val root = proot.rootfs
        File(root, "etc/resolv.conf").apply {
            delete()
            writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/hostname").writeText("debian-arm\n")
        File(root, "etc/default/locale").apply {
            parentFile?.mkdirs()
            writeText("LANG=C.UTF-8\n")
        }
        // The image's own list, replaced by one that also has contrib and non-free (fonts, some themes).
        File(root, "etc/apt/sources.list").delete()
        File(root, "etc/apt/sources.list.d").apply { mkdirs() }.listFiles()?.forEach { it.delete() }
        val keyring = KEYRINGS.firstOrNull { File(root, it.removePrefix("/")).isFile }
        File(root, "etc/apt/sources.list.d/debian.sources").writeText(sources(keyring))
        File(root, "etc/apt/apt.conf.d/99proot").apply {
            parentFile?.mkdirs()
            writeText(APT_CONF)
        }
        // The docker image drops man pages and caches; the launcher wants man pages, apt's lists are kept.
        File(root, "etc/dpkg/dpkg.cfg.d/excludes").delete()
        File(root, "etc/apt/apt.conf.d/docker-clean").delete()
        File(root, "etc/apt/apt.conf.d/docker-gzip-indexes").delete()
        File(root, "etc/apt/apt.conf.d/docker-no-languages").delete()
        File(root, "etc/dpkg/dpkg.cfg.d/01-proot-no-firmware").apply {
            parentFile?.mkdirs()
            writeText(DPKG_NO_FIRMWARE)
        }
        File(root, "etc/apt/preferences.d/proot").apply {
            parentFile?.mkdirs()
            writeText(APT_PINS)
        }
        File(root, "etc/apt/preferences.d/testing").writeText(TESTING_PIN)
        File(root, "usr/sbin/policy-rc.d").apply {
            writeText(POLICY_RC)
            setExecutable(true, false)
        }
    }

    /** /usr/local/bin/bat and fd, for the names Debian gives them. */
    private fun linkAliases() {
        for ((name, target) in ALIASES) {
            val link = File(proot.rootfs, "usr/local/bin/$name")
            link.parentFile?.mkdirs()
            link.delete()
            Os.symlink(target, link.path)
        }
    }

    private fun compileKeymap() {
        guest(
            "xkbcli compile-keymap --layout us > /tmp/keymap.xkb || " +
                "/usr/lib/xkbcommon/xkbcli-compile-keymap --layout us > /tmp/keymap.xkb",
        )
        // Clients mmap the keymap and parse it as a C string.
        writeAtomically(proot.keymap, proot.hostFile("/tmp/keymap.xkb").readBytes() + 0)
    }

    /**
     * Streamed to disk and hashed on the way: the binary is ~36 MB, and holding it while a buffer
     * doubles needs more heap than small phones give an app (48 MB on a 2 GB Android 10 device).
     */
    private fun installCliamp() {
        val target = File(proot.rootfs, "usr/local/bin/cliamp")
        val tmp = File(target.parentFile, ".cliamp.download")
        val digest = MessageDigest.getInstance("SHA-256")
        // Runs under apt's lock, so a stalled download must time out rather than hold it.
        val conn = openHttp(CLIAMP_URL, connectTimeout = 10_000, readTimeout = 20_000)
        try {
            conn.useCaches = false
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $CLIAMP_URL")
            target.parentFile?.mkdirs()
            DigestInputStream(conn.inputStream, digest).use { input ->
                tmp.outputStream().use { out ->
                    input.copyTo(out, 1 shl 16)
                    out.fd.sync()
                }
            }
        } finally {
            conn.disconnect()
        }
        if (digest.digest().hex() != CLIAMP_SHA256) {
            tmp.delete()
            error("cliamp download doesn't match $CLIAMP_VERSION")
        }
        tmp.setExecutable(true, false)
        // One rename, so a running cliamp is never replaced by half a file.
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("can't replace $target")
        }
    }

    /**
     * Runs a pipeline, the smallest guest command that forks. Some custom ROM kernels (4.14, with Android's cgroup v2
     * freezer backported but not its follow-up, upstream 05b289263772) make every fork of a traced process restart
     * forever (omalauncher#105), and the next step, apt, would hang without a word. Such a phone gets an error naming the cause.
     */
    private fun checkForks() {
        val process = proot.process("echo forks | cat").start()
        val output = StringBuffer()
        val reader = thread(isDaemon = true) { runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } } }
        if (process.waitFor(FORK_CHECK_SECONDS, TimeUnit.SECONDS)) {
            reader.join(1_000)
            output.lines().forEach(log::add)
            check(process.exitValue() == 0 && "forks" in output) { "Linux can't run commands here (exit ${process.exitValue()})" }
            return
        }
        process.destroyForcibly()
        // The guest shell is proot's tracee and outlives a killed proot.
        proot.killAll()
        log.add("a guest command that starts another one didn't finish in $FORK_CHECK_SECONDS s (kernel ${System.getProperty("os.version")})")
        error(
            "this phone's kernel stops Linux programs from starting other programs, so setup can't go on. " +
                "custom ROMs with a 4.14 kernel need kernel commit 05b289263772 from their maintainer",
        )
    }

    private fun guest(script: String) {
        log.add("$ $script")
        val code = proot.run(script) { line -> if (log.add(line)) report(stage, step) }
        check(code == 0) { "`${script.take(60)}` failed (exit $code)" }
    }
}
