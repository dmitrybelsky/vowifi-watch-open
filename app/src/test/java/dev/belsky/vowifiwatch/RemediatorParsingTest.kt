package dev.belsky.vowifiwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemediatorParsingTest {
    private val packageName = "dev.example.watch"

    @Test
    fun `millet membership trims comma separated entries`() {
        assertTrue(PackageListParser.hasMilletPackage("one.pkg, dev.example.watch ,two.pkg", packageName))
        assertFalse(PackageListParser.hasMilletPackage("one.pkg, dev.example.watch2", packageName))
        assertFalse(PackageListParser.hasMilletPackage("null", packageName))
    }

    @Test
    fun `millet append preserves existing entries and avoids duplicates`() {
        assertEquals("dev.example.watch", PackageListParser.withMilletPackage("", packageName))
        assertEquals("one.pkg, dev.example.watch", PackageListParser.withMilletPackage("one.pkg", packageName))
        assertEquals(
            "one.pkg, dev.example.watch",
            PackageListParser.withMilletPackage("one.pkg, dev.example.watch", packageName)
        )
    }

    @Test
    fun `night membership requires an exact underscore token`() {
        assertTrue(PackageListParser.hasNightPackage("one.pkg_dev.example.watch_two.pkg", packageName))
        assertFalse(PackageListParser.hasNightPackage("one.pkg_dev.example.watch2", packageName))
        assertFalse(PackageListParser.hasNightPackage("one.pkg,dev.example.watch", packageName))
    }

    @Test
    fun `night append handles null empty and populated lists`() {
        assertEquals("dev.example.watch", PackageListParser.withNightPackage("null", packageName))
        assertEquals("dev.example.watch", PackageListParser.withNightPackage("", packageName))
        assertEquals("one.pkg_dev.example.watch", PackageListParser.withNightPackage("one.pkg", packageName))
        assertEquals(
            "one.pkg_dev.example.watch",
            PackageListParser.withNightPackage("one.pkg_dev.example.watch", packageName)
        )
    }

    @Test
    fun `vowifi parcel parser reads enabled disabled and malformed values`() {
        assertEquals(true, VoWiFiSettingParser.enabled("Result: Parcel(00000000 00000001   '........')"))
        assertEquals(false, VoWiFiSettingParser.enabled("Result: Parcel(00000000 00000000   '........')"))
        assertNull(VoWiFiSettingParser.enabled("Result: Parcel(00000000 00000002   '........')"))
        assertNull(VoWiFiSettingParser.enabled("not a parcel"))
    }

    @Test
    fun `radio parser returns the newest registration state`() {
        val output = listOf(
            radioLine("1699999980.000", true),
            radioLine("1699999990.000", false)
        ).joinToString("\n")

        assertFalse(RadioLogParser.latestImsRegistered(output, BOOT_AT, NOW)!!)
    }

    @Test
    fun `radio parser accepts the latest state at boot grace boundary`() {
        val output = radioLine("1699999935.000", true)

        assertTrue(RadioLogParser.latestImsRegistered(output, BOOT_AT, NOW)!!)
    }

    @Test
    fun `radio parser rejects entries older than boot grace`() {
        val output = radioLine("1699999934.999", true)

        assertNull(RadioLogParser.latestImsRegistered(output, BOOT_AT, NOW))
    }

    @Test
    fun `radio parser rejects future entries and missing states`() {
        assertNull(RadioLogParser.latestImsRegistered(radioLine("1700000010.001", true), BOOT_AT, NOW))
        assertNull(RadioLogParser.latestImsRegistered("no IMS state", BOOT_AT, NOW))
    }

    @Test
    fun `radio parser ignores malformed numeric timestamps`() {
        val malformed = "not-a-time.000 5469 5469 D ImsM: isImsRegistered = true"

        assertNull(RadioLogParser.latestImsRegistered(malformed, BOOT_AT, NOW))
    }

    private fun radioLine(timestamp: String, registered: Boolean): String =
        "$timestamp 5469 5469 D ImsM: onRegistrationChanged: isImsRegistered = $registered"

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val BOOT_AT = NOW - 60_000L
    }
}
