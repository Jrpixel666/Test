package dev.omalauncher.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityCatalogTest {
    @Test fun everyCategoryHasAtLeastOneTool() {
        for (category in SecurityCatalog.categories) {
            assertTrue("$category has no tools", SecurityCatalog.tools.any { it.category == category })
        }
        assertEquals(SecurityCategory.entries.size, SecurityCatalog.categories.size)
    }

    @Test fun packagesAreUniqueSoInstalledDetectionIsUnambiguous() {
        val pkgs = SecurityCatalog.tools.mapNotNull { it.pkg }
        assertEquals(pkgs.size, pkgs.toSet().size)
    }

    @Test fun blankQueryReturnsEverything() {
        assertEquals(SecurityCatalog.tools.size, SecurityCatalog.search("", emptySet()).size)
    }

    @Test fun searchMatchesNameSummaryAndCategory() {
        assertTrue(SecurityCatalog.search("bluetooth", emptySet()).all { it.tool.category == SecurityCategory.Bluetooth })
        // "vpn" lives in the privacy category's summaries, not any name.
        assertTrue(SecurityCatalog.search("vpn", emptySet()).isNotEmpty())
        // Case and surrounding whitespace don't matter.
        assertEquals(SecurityCatalog.search("WIFI", emptySet()).size, SecurityCatalog.search("  wifi ", emptySet()).size)
    }

    @Test fun unmatchedQueryIsEmpty() {
        assertTrue(SecurityCatalog.search("definitely-not-a-tool", emptySet()).isEmpty())
    }

    @Test fun installedToolsFloatToTheTopAndAreMarked() {
        val pkg = SecurityCatalog.tools.first { it.pkg != null }.pkg!!
        val hits = SecurityCatalog.search("", setOf(pkg))
        assertTrue("installed tool should be first", hits.first().installed)
        assertEquals(1, hits.count { it.installed })
        assertEquals(pkg, hits.first().tool.pkg)
    }

    @Test fun referenceEntriesWithoutAPackageNeverReadAsInstalled() {
        // A web/Termux entry has no package, so no installed set can mark it.
        val webTool = SecurityCatalog.tools.first { it.pkg == null }
        val hit = SecurityCatalog.search(webTool.name, emptySet()).first { it.tool == webTool }
        assertFalse(hit.installed)
    }

    @Test fun installedViewReturnsOnlyWhatIsPresent() {
        assertTrue(SecurityCatalog.installed(emptySet()).isEmpty())
        val pkg = SecurityCatalog.tools.first { it.pkg != null }.pkg!!
        val installed = SecurityCatalog.installed(setOf(pkg, "com.example.not.in.catalog"))
        assertEquals(1, installed.size)
        assertTrue(installed.all { it.installed })
    }
}
