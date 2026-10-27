package dev.omalauncher.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MitmSelfTestTest {
    private val clean =
        NetworkFacts(
            proxyHost = null,
            proxyPort = null,
            userAddedCaCount = 0,
            vpnActive = false,
            captivePortal = false,
            privateDnsActive = true,
            onWifi = false,
        )

    @Test fun aCleanConnectionIsAllClear() {
        val findings = MitmSelfTest.run(clean)
        assertEquals(Severity.Ok, MitmSelfTest.verdict(findings))
        assertTrue(findings.any { it.title.contains("Nothing suspicious") })
    }

    @Test fun userAddedCertificatesAreTheTopAlert() {
        val findings = MitmSelfTest.run(clean.copy(userAddedCaCount = 2))
        assertEquals(Severity.Alert, MitmSelfTest.verdict(findings))
        assertEquals(Severity.Alert, findings.first().severity)
        assertTrue(findings.first().title.contains("2 extra root certificates"))
    }

    @Test fun oneCertificateReadsAsSingular() {
        val findings = MitmSelfTest.run(clean.copy(userAddedCaCount = 1))
        assertTrue(findings.first().title.contains("1 extra root certificate is"))
    }

    @Test fun aProxyIsWarnedWithItsAddress() {
        val findings = MitmSelfTest.run(clean.copy(proxyHost = "10.0.0.1", proxyPort = 8080))
        val proxy = findings.first { it.title.startsWith("A network proxy") }
        assertEquals(Severity.Warn, proxy.severity)
        assertTrue(proxy.title.contains("10.0.0.1:8080"))
    }

    @Test fun openWifiWithoutVpnIsWarned() {
        val findings = MitmSelfTest.run(clean.copy(onWifi = true, vpnActive = false))
        assertTrue(findings.any { it.severity == Severity.Warn && it.title.contains("without a VPN") })
    }

    @Test fun vpnSuppressesTheWifiWarningAndIsNoted() {
        val findings = MitmSelfTest.run(clean.copy(onWifi = true, vpnActive = true))
        assertTrue(findings.none { it.title.contains("without a VPN") })
        assertTrue(findings.any { it.title.contains("VPN tunnel is active") })
    }

    @Test fun captivePortalIsWarned() {
        val findings = MitmSelfTest.run(clean.copy(captivePortal = true))
        assertTrue(findings.any { it.severity == Severity.Warn && it.title.contains("captive portal") })
    }

    @Test fun unencryptedDnsIsSurfacedAsInfo() {
        val findings = MitmSelfTest.run(clean.copy(privateDnsActive = false))
        assertTrue(findings.any { it.severity == Severity.Info && it.title.contains("DNS is not encrypted") })
    }

    @Test fun findingsComeBackWorstFirst() {
        val findings = MitmSelfTest.run(clean.copy(userAddedCaCount = 1, proxyHost = "p", onWifi = true, privateDnsActive = false))
        val order = findings.map { it.severity.ordinal }
        assertEquals(order.sorted(), order)
    }
}
