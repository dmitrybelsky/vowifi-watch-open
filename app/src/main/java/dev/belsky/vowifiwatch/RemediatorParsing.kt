package dev.belsky.vowifiwatch

internal object PackageListParser {
    fun hasMilletPackage(value: String, packageName: String): Boolean =
        value.split(',').any { it.trim() == packageName }

    fun withMilletPackage(value: String, packageName: String): String = when {
        hasMilletPackage(value, packageName) -> value
        value.isEmpty() -> packageName
        else -> "$value, $packageName"
    }

    fun hasNightPackage(value: String, packageName: String): Boolean =
        value.split('_').any { it == packageName }

    fun withNightPackage(value: String, packageName: String): String = when {
        hasNightPackage(value, packageName) -> value
        value.isEmpty() || value == "null" -> packageName
        else -> "${value}_$packageName"
    }
}

internal object VoWiFiSettingParser {
    private val parcel = Regex(
        """Result:\s*Parcel\(\s*[0-9a-fA-F]{8}\s+([0-9a-fA-F]{8})"""
    )

    fun enabled(output: String): Boolean? {
        val match = parcel.find(output) ?: return null
        return when (match.groupValues[1].toIntOrNull(16)) {
            0 -> false
            1 -> true
            else -> null
        }
    }
}

internal object RadioLogParser {
    private const val BOOT_GRACE_MS = 5_000L
    private val imsRegistered = Regex(
        """(?m)^\s*(\d+)\.(\d{3})\s+\d+\s+\d+\s+\w\s+.*\bisImsRegistered\s*=\s*(true|false)\s*$"""
    )

    fun latestImsRegistered(output: String, bootAt: Long, now: Long): Boolean? {
        var latestAt = Long.MIN_VALUE
        var registered: Boolean? = null
        for (match in imsRegistered.findAll(output)) {
            val seconds = match.groupValues[1].toLongOrNull() ?: continue
            val millis = match.groupValues[2].toLongOrNull() ?: continue
            val at = seconds * 1_000L + millis
            if (at >= latestAt) {
                latestAt = at
                registered = match.groupValues[3] == "true"
            }
        }
        if (registered == null || latestAt < bootAt - BOOT_GRACE_MS || latestAt > now + 10_000L) {
            return null
        }
        return registered
    }
}
