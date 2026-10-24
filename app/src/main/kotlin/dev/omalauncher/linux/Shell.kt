package dev.omalauncher.linux

import dev.omalauncher.widgets.lang.shQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [s] as one single-quoted shell word; the owl language's `sh()` quotes the same way. */
fun shellQuote(s: String) = shQuote(s)

/**
 * Installs [packages] with apt after refreshing its lists, without recommends, then drops the
 * downloaded .debs: storage on phones is tight. [refresh] is kept for callers that ask for it;
 * apt always reads fresh lists here, since a stale list names versions the mirror no longer has.
 * [release] installs from that suite (`-t`), like testing for what stable lacks.
 */
@Suppress("UNUSED_PARAMETER")
fun aptInstall(
    packages: Collection<String>,
    refresh: Boolean = false,
    release: String? = null,
) = "apt-get update && apt-get install -y --no-install-recommends ${release?.let { "-t ${shellQuote(it)} " }.orEmpty()}" +
    "${packages.joinToString(" ") { shellQuote(it) }} && $CLEAN_APT_CACHE"

/**
 * Installs what it can of [packages]: all at once, or, when one of them is missing from the
 * mirror (a name Debian doesn't carry), one by one, skipping the ones that fail.
 */
fun aptInstallEach(packages: Collection<String>): String {
    val names = packages.joinToString(" ") { shellQuote(it) }
    return "apt-get update && { apt-get install -y --no-install-recommends $names || " +
        "for p in $names; do apt-get install -y --no-install-recommends \"\$p\" || echo \"skipped \$p\"; done; } && $CLEAN_APT_CACHE"
}

/** Downloaded packages; apt keeps them until cleaned. */
const val CLEAN_APT_CACHE = "apt-get clean"

/** Progress of a guest job started from the UI: installing an agent, a package, a widget's packages. */
sealed interface JobState {
    data object Idle : JobState

    data class Working(
        val action: String,
        val log: List<String>,
    ) : JobState

    data class Failed(
        val log: List<String>,
    ) : JobState
}

/** The last [max] lines of a command's output. Progress bars redraw with carriage returns; only their latest state is kept. */
class OutputTail(
    private val max: Int = 12,
) {
    private val lines = ArrayDeque<String>()

    /** Adds [raw]; false if it was blank after dropping redrawn progress. */
    fun add(raw: String): Boolean {
        val text = raw.substringAfterLast('\r').trim()
        if (text.isEmpty()) return false
        lines.addLast(text)
        while (lines.size > max) lines.removeFirst()
        return true
    }

    fun toList() = lines.toList()

    fun clear() = lines.clear()

    override fun toString() = lines.joinToString("\n")
}

/**
 * Runs [script] in the guest to completion, blocking, feeding each output
 * line to [onLine]. Returns the exit code; a command that can't start
 * throws.
 */
fun Proot.run(
    script: String,
    onLine: (String) -> Unit = {},
): Int {
    val process = process(script).start()
    process.inputStream.bufferedReader().forEachLine(onLine)
    return process.waitFor()
}

/** Like [run], reporting the tail of the output to [report] as it comes. Returns success and the tail. */
fun Proot.runJob(
    script: String,
    tail: OutputTail = OutputTail(),
    report: (List<String>) -> Unit = {},
): Pair<Boolean, OutputTail> {
    val ok =
        runCatching { run(script) { if (tail.add(it)) report(tail.toList()) } == 0 }
            .getOrElse {
                tail.add(it.message ?: it.toString())
                false
            }
    return ok to tail
}

/**
 * Runs [script] as a job the UI shows: [publish] gets Working with the output
 * tail as it comes, then Idle or Failed. With [lock], it waits for it first
 * and shows "waiting" meanwhile. Returns success and the tail.
 */
suspend fun Proot.trackedJob(
    action: String,
    script: String,
    lock: Mutex? = null,
    publish: (JobState) -> Unit,
): Pair<Boolean, OutputTail> {
    if (lock != null) publish(JobState.Working("waiting", emptyList()))
    val job: suspend () -> Pair<Boolean, OutputTail> = {
        withContext(Dispatchers.IO) {
            publish(JobState.Working(action, emptyList()))
            runJob(script) { publish(JobState.Working(action, it)) }.also { (ok, tail) ->
                publish(if (ok) JobState.Idle else JobState.Failed(tail.toList()))
            }
        }
    }
    return if (lock != null) lock.withLock { job() } else job()
}
