package dev.omalauncher.security

/**
 * A local, defensive "man-in-the-middle" self-test: it looks at how *this* device is connected right now and
 * reports anything that could let someone read or alter your traffic. It never touches anyone else's device and
 * sends nothing off the phone — the UI gathers the facts below from Android and this code turns them into plain
 * findings. Pure logic, so it's unit-tested like the catalog.
 */
data class NetworkFacts(
    /** A system HTTP proxy, if one is set (host only; a corporate or an interception proxy both show up here). */
    val proxyHost: String?,
    /** Its port, when known. */
    val proxyPort: Int?,
    /**
     * How many *user-added* certificate authorities are in the system trust store. Each one can mint a trusted
     * certificate for any website, so a non-zero count is the single biggest sign your https can be intercepted.
     */
    val userAddedCaCount: Int,
    /** A VPN tunnel is up. Good when it's yours; this test can't tell whose it is, so it only notes it. */
    val vpnActive: Boolean,
    /** The current network sits behind a captive portal (the "sign in to wifi" pages). */
    val captivePortal: Boolean,
    /** Encrypted DNS (Android's Private DNS) is in effect, so lookups can't be silently rewritten. */
    val privateDnsActive: Boolean,
    /** Connected over wifi (as opposed to cellular), i.e. sharing a link with whoever else is on it. */
    val onWifi: Boolean,
)

/** How much a finding should worry you, worst first. */
enum class Severity {
    Alert,
    Warn,
    Info,
    Ok,
}

data class Finding(
    val severity: Severity,
    val title: String,
    val detail: String,
)

object MitmSelfTest {
    /**
     * Turn [facts] into findings, worst first. When nothing stands out, a single [Severity.Ok] "all clear" is
     * returned rather than an empty list, so the panel always has something to show.
     */
    fun run(facts: NetworkFacts): List<Finding> {
        val findings = mutableListOf<Finding>()

        if (facts.userAddedCaCount > 0) {
            val n = facts.userAddedCaCount
            findings +=
                Finding(
                    Severity.Alert,
                    if (n == 1) "1 extra root certificate is trusted" else "$n extra root certificates are trusted",
                    "A user-added certificate authority can sign for any website, which is exactly how https gets " +
                        "intercepted. If you didn't add it yourself for a tool you trust, remove it in Settings → " +
                        "Security → Encryption & credentials → Trusted credentials → User.",
                )
        }

        if (facts.proxyHost != null) {
            val where = facts.proxyHost + (facts.proxyPort?.let { ":$it" } ?: "")
            findings +=
                Finding(
                    Severity.Warn,
                    "A network proxy is set ($where)",
                    "All web traffic is routed through this proxy. That's normal on some managed or corporate " +
                        "networks, but an attacker's proxy looks the same — make sure you set it.",
                )
        }

        if (facts.captivePortal) {
            findings +=
                Finding(
                    Severity.Warn,
                    "Behind a captive portal",
                    "This network intercepts connections until you sign in. Don't enter real passwords or payment " +
                        "details on the portal page, and treat the network as untrusted afterwards.",
                )
        }

        if (facts.onWifi && !facts.vpnActive) {
            findings +=
                Finding(
                    Severity.Warn,
                    "On wifi without a VPN",
                    "On a network you don't control, anyone else on it can try to watch or redirect your traffic. " +
                        "Turn on a VPN (see privacy & vpn) so the local network sees only ciphertext.",
                )
        }

        if (facts.vpnActive) {
            findings +=
                Finding(
                    Severity.Info,
                    "A VPN tunnel is active",
                    "Your traffic is tunnelled. This test can't confirm it's your VPN — make sure it's the one you " +
                        "started, not one an app or a profile added.",
                )
        }

        findings +=
            if (facts.privateDnsActive) {
                Finding(
                    Severity.Ok,
                    "Encrypted DNS is on",
                    "Private DNS is active, so name lookups can't be quietly rewritten to send you to the wrong server.",
                )
            } else {
                Finding(
                    Severity.Info,
                    "DNS is not encrypted",
                    "Turn on Private DNS (Settings → Network → Private DNS) or use Intra so the network can't tamper " +
                        "with where your lookups go.",
                )
            }

        if (findings.none { it.severity == Severity.Alert || it.severity == Severity.Warn }) {
            findings.add(
                0,
                Finding(
                    Severity.Ok,
                    "Nothing suspicious right now",
                    "No extra root certificates, no proxy and no captive portal on this connection. Re-run the test " +
                        "whenever you join a new network.",
                ),
            )
        }

        return findings.sortedBy { it.severity.ordinal }
    }

    /** The worst severity in a run — the panel colours its header with it. */
    fun verdict(findings: List<Finding>): Severity = findings.minByOrNull { it.severity.ordinal }?.severity ?: Severity.Ok
}
